package com.librevision.core.audio

import android.content.Context
import android.media.*
import android.os.*
import com.librevision.domain.alerts.FeedbackCue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Tiny in-memory chimes; never changes the user's speech engine or takes speech audio focus. */
class FeedbackCues(context: Context, private val playback: (Boolean) -> Unit) : AutoCloseable {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val samples = mutableMapOf<FeedbackCue, ShortArray>()
    private var track: AudioTrack? = null
    @Volatile var soundEnabled = true
    @Volatile var hapticsEnabled = true
    @Volatile private var closed = false
    private var last = 0L

    fun play(cue: FeedbackCue, withSound: Boolean = true, practice: Boolean = false) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (cue != FeedbackCue.OBSTACLE && now - last < 120) return
        last = now
        val ticket = generation.incrementAndGet()
        if (hapticsEnabled && cue.haptics.isNotEmpty() && vibrator?.hasVibrator() == true) {
            val times = cue.haptics.toLongArray()
            val amplitudes = IntArray(times.size) { if (it % 2 == 0) 0 else cue.amplitude }
            runCatching { vibrator.vibrate(VibrationEffect.createWaveform(times, amplitudes, -1)) }
        }
        worker.execute {
            if (closed || ticket != generation.get()) return@execute
            runCatching { track?.stop() }
            if ((!soundEnabled && !practice) || !withSound) {
                main.post { if (ticket == generation.get()) playback(false) }
                return@execute
            }
            runCatching {
                val pcm = samples.getOrPut(cue) { synthesize(cue) }
                val output =
                    track
                        ?: AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(16000)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build()
                            )
                            .setTransferMode(AudioTrack.MODE_STATIC)
                            .setBufferSizeInBytes(6400)
                            .build()
                            .also {
                                track = it
                                it.setVolume(.28f)
                            }
                val padded = ShortArray(3200)
                pcm.copyInto(padded)
                output.write(padded, 0, padded.size)
                output.setPlaybackHeadPosition(0)
                if (ticket != generation.get() || closed) return@runCatching
                // The short ready tones can coincide with the first words of a request.
                val guardsInput = cue != FeedbackCue.VOICE_TRIGGER && cue != FeedbackCue.LISTENING
                main.post { if (ticket == generation.get()) playback(guardsInput) }
                output.play()
                main.postDelayed(
                    {
                        if (ticket == generation.get()) {
                            playback(false)
                        }
                    },
                    (pcm.size * 1000L / 16000) + 30,
                )
            }
                .onFailure { main.post { if (ticket == generation.get()) playback(false) } }
        }
    }

    fun stop() {
        if (closed) return
        generation.incrementAndGet()
        vibrator?.cancel()
        main.post { playback(false) }
        worker.execute { runCatching { track?.stop() } }
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        worker.execute {
            track?.release()
            track = null
            samples.clear()
        }
        worker.shutdown()
    }

    companion object {
        fun synthesize(cue: FeedbackCue): ShortArray {
            val perNote = 16000 * cue.noteMs / 1000
            return ShortArray(perNote * cue.frequencies.size) { index ->
                val note = index / perNote
                val position = index % perNote
                val t = position / 16000.0
                val attack = (position / 80.0).coerceAtMost(1.0)
                val release = ((perNote - position) / 160.0).coerceAtMost(1.0)
                val decay = exp(-3.0 * position / perNote)
                val frequency = cue.frequencies[note].toDouble()
                val value = sin(2 * PI * frequency * t) + .16 * sin(2 * PI * frequency * 2 * t)
                (value * attack * release * decay * 7000 * cue.gain)
                    .roundToInt()
                    .coerceIn(-32767, 32767)
                    .toShort()
            }
        }
    }
}
