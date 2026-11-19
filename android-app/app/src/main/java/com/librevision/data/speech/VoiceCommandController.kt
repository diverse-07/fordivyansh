package com.librevision.data.speech

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import com.librevision.data.models.AppModelManager
import com.librevision.domain.assistant.MuseVoiceSession
import com.librevision.domain.commands.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

enum class ListeningPhase {
    IDLE,
    PREPARING,
    LISTENING,
    PROCESSING,
    SUCCESS,
    FAILURE,
    STOPPING,
}

data class ListeningState(
    val phase: ListeningPhase = ListeningPhase.IDLE,
    val message: String = "Listening locally for Hey Vision.",
    val sessionActive: Boolean = false,
)

data class RecognizedCommand(
    val command: Command,
    val transcript: String,
    val session: Long,
    val wakeOnly: Boolean = false,
    val confidence: Float? = null,
    val pcm: ShortArray? = null,
    val activationId: Long? = null,
)

/** App-scoped through the ViewModel; reusable by every feature. No Android UI dependencies. */
class VoiceCommandController(
    private val context: Context,
    private val models: AppModelManager,
    private val router: VoiceCommandRouter = VoiceCommandRouter(),
    private val activation: MuseVoiceSession = MuseVoiceSession(),
    private val audioFallbackEnabled: () -> Boolean = { false },
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val mutable = MutableStateFlow(ListeningState())
    val state: StateFlow<ListeningState> = mutable
    private val events = Channel<RecognizedCommand>(4, onUndeliveredElement = { it.pcm?.fill(0) })
    private val generation = AtomicLong()
    val commands =
        events.receiveAsFlow().filter { event ->
            isCurrent(event.session).also { if (!it) event.pcm?.fill(0) }
        }

    fun isCurrent(session: Long) = session == generation.get() && !token.get()

    private fun send(event: RecognizedCommand) {
        if (events.trySend(event).isFailure) event.pcm?.fill(0)
    }

    @Volatile private var token = AtomicBoolean(true)
    @Volatile private var microphone: AudioRecord? = null
    private var closed = false
    private val transcripts = CommandTranscriptHandler(router)
    private val playbackGate = RecognitionPlaybackGate(SystemClock::elapsedRealtime)
    private val inputWindow = VoiceInputWindow(SystemClock::elapsedRealtime)

    fun openDictationWindow() = inputWindow.open()

    fun closeDictationWindow() = inputWindow.close()

    fun holdDictationWindow() = inputWindow.hold()

    fun awaitingCommand() = inputWindow.allowsDictation()

    fun authorized(id: Long?) = inputWindow.authorized(id)

    fun finishCommand(id: Long?) = inputWindow.finish(id)

    fun tick(handsFree: Boolean) {
        if (!handsFree && !inputWindow.hasSession() && !token.get()) stop()
    }

    fun suppressRecognitionForPlayback(active: Boolean) {
        playbackGate.playback(active)
    }

    fun rememberPlaybackText(text: String) {
        activation.rememberSpeech(text, SystemClock.elapsedRealtime())
    }

    private fun muted() = playbackGate.suppressRecognition()

    fun start() {
        if (closed || !token.get()) return
        val active = AtomicBoolean(false)
        token = active
        val session = generation.incrementAndGet()
        mutable.value =
            ListeningState(ListeningPhase.PREPARING, "Preparing English voice commands…", true)
        worker.execute {
            try {
                val files = models.readyFiles()
                val directory = files.english
                if (active.get()) return@execute
                fun deliver(result: DecodedSpeech, samples: ShortArray) {
                    if (active.get()) return
                    val wake = result.wakeDetected && AppSpeechPhrases.hasWake(result.text)
                    if (
                        wake &&
                            activation.echoesOwnSpeech(result.text, SystemClock.elapsedRealtime())
                    )
                        return
                    val bareWake =
                        AppSpeechPhrases.wakePhrases.any {
                            CommandInterpreter().normalize(result.text) == it
                        }
                    val authorization = inputWindow.authorize(result)
                    if (authorization == null) return
                    val outcome = transcripts.handle(result.text)
                    val command = outcome.command ?: Command.UNKNOWN
                    val pcm =
                        if (
                            !bareWake &&
                                audioFallbackEnabled() &&
                                SpeechFallbackPolicy.needsAudio(result, command)
                        )
                            samples.copyOf()
                        else null
                    report(
                        active,
                        if (outcome.command == null) ListeningPhase.LISTENING
                        else ListeningPhase.SUCCESS,
                        outcome.message,
                    )
                    send(
                        RecognizedCommand(
                            command,
                            result.text,
                            session,
                            confidence = result.confidence,
                            pcm = pcm,
                            activationId = authorization,
                        )
                    )
                }
                SpeechDecodeQueue(
                        factory = {
                            VoskUtteranceRecognizer(directory, inputWindow::allowsDictation)
                        },
                        onText = { text -> deliver(DecodedSpeech(text), ShortArray(0)) },
                        onWakeOnlyText = { text ->
                            if (
                                !active.get() &&
                                    AppSpeechPhrases.hasWake(text) &&
                                    !activation.echoesOwnSpeech(text, SystemClock.elapsedRealtime())
                            ) {
                                inputWindow.open()
                                val authorization = inputWindow.token()
                                send(
                                    RecognizedCommand(
                                        Command.UNKNOWN,
                                        AppSpeechPhrases.wakePhrases.first(),
                                        session,
                                        true,
                                        activationId = authorization,
                                    )
                                )
                            }
                        },
                        onUtteranceError = {
                            report(
                                active,
                                ListeningPhase.LISTENING,
                                "Could not understand that speech. Still listening; please repeat.",
                            )
                        },
                        onDecoded = ::deliver,
                        onDecodeFailure = { samples -> deliver(DecodedSpeech("", 0f), samples) },
                    )
                    .use { decoder ->
                        if (decoder.awaitReady { active.get() })
                            SileroVad(files.vad).use { vad ->
                                record(active, vad, decoder, session)
                            }
                    }
            } catch (error: Exception) {
                if (!active.get())
                    report(
                        active,
                        ListeningPhase.FAILURE,
                        "Voice unavailable: ${error.message}",
                        false,
                    )
            } catch (error: LinkageError) {
                if (!active.get())
                    report(
                        active,
                        ListeningPhase.FAILURE,
                        "Speech runtime unavailable: ${error.message}",
                        false,
                    )
            } finally {
                active.set(true)
                if (token === active)
                    mutable.value =
                        if (mutable.value.phase == ListeningPhase.FAILURE)
                            mutable.value.copy(sessionActive = false)
                        else ListeningState(ListeningPhase.IDLE, "Microphone off.")
            }
        }
    }

    @Suppress("MissingPermission")
    private fun record(
        active: AtomicBoolean,
        vad: SileroVad,
        decoder: SpeechDecodeQueue,
        session: Long,
    ) {
        val minimum =
            AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        check(minimum > 0) { "16 kHz microphone unavailable" }
        val mic =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum, 8192),
            )
        data class PcmFrame(val samples: ShortArray, val epoch: Long, val playing: Boolean)
        val queue = ArrayBlockingQueue<PcmFrame>(75)
        val echo = runCatching {
            if (AcousticEchoCanceler.isAvailable())
                AcousticEchoCanceler.create(mic.audioSessionId)?.apply { enabled = true }
            else null
        }
            .getOrNull()
        val captureFailed = AtomicBoolean(false)
        val captureStopped = AtomicBoolean(false)
        var capture: Thread? = null
        try {
            check(mic.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            microphone = mic
            if (active.get()) return
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            report(
                active,
                ListeningPhase.LISTENING,
                "Listening locally for Hey Vision.",
            )
            capture =
                Thread(
                        {
                            try {
                                val buffer = ShortArray(512)
                                var filled = 0
                                while (!active.get() && !captureStopped.get()) {
                                    val size = mic.read(buffer, filled, buffer.size - filled)
                                    if (active.get()) break
                                    check(size > 0) { "Microphone read failed" }
                                    filled += size
                                    if (filled < buffer.size) continue
                                    check(
                                        queue.offer(
                                            PcmFrame(
                                                buffer.copyOf(),
                                                playbackGate.version(),
                                                playbackGate.isPlaying(),
                                            ),
                                            200,
                                            TimeUnit.MILLISECONDS,
                                        )
                                    ) {
                                        "Speech decoder is behind"
                                    }
                                    filled = 0
                                }
                            } catch (_: Exception) {
                                if (!active.get() && !captureStopped.get()) captureFailed.set(true)
                            }
                        },
                        "netrasaathi-microphone",
                    )
                    .also { it.start() }
            val segmenter = SpeechSegmenter()
            var utteranceEpoch = playbackGate.version()
            var heardPlayback = false
            while (!active.get()) {
                check(!captureFailed.get()) {
                    "Microphone or speech queue failed; retry voice commands"
                }
                val frame = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                if (!segmenter.capturing) {
                    utteranceEpoch = frame.epoch
                    heardPlayback = frame.playing
                } else if (frame.playing || frame.epoch != utteranceEpoch) heardPlayback = true
                val segment =
                    segmenter.accept(frame.samples, vad.probability(frame.samples)) ?: continue
                val epoch = utteranceEpoch
                val guarded = heardPlayback
                val accepted =
                    decoder.offerWakeAware(
                        segment,
                        normalAllowed = { !guarded && !muted() && playbackGate.version() == epoch },
                        valid = { !active.get() },
                    )
                if (
                    !accepted &&
                        !active.get() &&
                        !guarded &&
                        !muted() &&
                        playbackGate.version() == epoch &&
                        audioFallbackEnabled()
                ) {
                    val authorization = inputWindow.claim()
                    if (authorization != null)
                        send(
                            RecognizedCommand(
                                Command.UNKNOWN,
                                "",
                                session,
                                confidence = 0f,
                                pcm = segment.copyOf(),
                                activationId = authorization,
                            )
                        )
                }
                segment.fill(0)
                report(
                    active,
                    ListeningPhase.LISTENING,
                    if (accepted) "Listening hands-free."
                    else "Processing speech. Please repeat shortly.",
                )
            }
        } finally {
            captureStopped.set(true)
            if (mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) runCatching { mic.stop() }
            capture?.join()
            microphone = null
            echo?.release()
            mic.release()
            queue.clear()
        }
    }

    private fun report(
        active: AtomicBoolean,
        phase: ListeningPhase,
        message: String,
        running: Boolean = true,
    ) {
        if (!active.get() && token === active)
            mutable.value = ListeningState(phase, message, running)
    }

    fun stop() {
        inputWindow.close()
        generation.incrementAndGet()
        token.set(true)
        if (microphone != null) {
            mutable.value =
                ListeningState(
                    ListeningPhase.STOPPING,
                    "Stopping microphone…",
                )
            runCatching { microphone?.stop() }
        } else mutable.value = ListeningState(ListeningPhase.IDLE, "Microphone off.")
    }

    override fun close() {
        stop()
        closed = true
        worker.shutdown()
        events.cancel()
    }
}
