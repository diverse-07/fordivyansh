package com.librevision.core.audio

import android.content.Context
import android.content.Intent
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.librevision.domain.alerts.NativeVoice
import com.librevision.domain.alerts.NativeVoicePolicy
import com.librevision.domain.alerts.SpeechOutput
import com.librevision.domain.alerts.SpeechEngineConnection
import java.util.Locale
import java.util.UUID

/**
 * Speaker output is independent of capture/VAD. During playback, command dispatch is restricted to
 * the explicit Muse wake phrase.
 */
class AndroidSpeechOutput(
    private val context: Context,
    private val finished: () -> Unit,
    private val unavailable: (String) -> Unit,
    private val playback: (Boolean) -> Unit = {},
    private val available: () -> Unit = {},
    private val onSpoken: (String) -> Unit = {},
    private val engineUnavailable: (Boolean) -> Unit = {},
) : SpeechOutput {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    private val focus =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(
                { change ->
                    if (
                        change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {
                        if (currentId != null) {
                            stop()
                            unavailable(
                                "Speech interrupted by another audio source. Replay when ready."
                            )
                            finished()
                        }
                    }
                },
                main,
            )
            .build()
    private var closed = false
    private var pending: String? = null
    private var currentId: String? = null
    private var locale = Locale.US
    private var timeout: Runnable? = null
    private var audible = true
    private val connection = SpeechEngineConnection<TextToSpeech>(
        installedEngines = {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(
                Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0,
            ).filter { it.serviceInfo?.enabled == true && it.serviceInfo?.applicationInfo?.enabled == true }
                .sortedByDescending { it.priority }.map { it.serviceInfo.packageName }
        },
        create = { name, initialized ->
            TextToSpeech(context.applicationContext, { status ->
                main.post { initialized(status == TextToSpeech.SUCCESS) }
            }, name)
        },
        prepare = ::prepareEngine,
        dispose = { it.shutdown() },
        scheduleTimeout = { expired ->
            val task = Runnable { expired() }
            main.postDelayed(task, 4000)
            val cancel: () -> Unit = { main.removeCallbacks(task) }
            cancel
        },
        connected = {
            engineUnavailable(false)
            val text = pending
            pending = null
            if (text != null && currentId != null && !closed) speak(text)
        },
        unavailable = {
            engineUnavailable(true)
            if (currentId != null) fail(currentId, ENGINE_UNAVAILABLE)
            else unavailable(ENGINE_UNAVAILABLE)
        },
    )

    init { connection.connect() }

    private fun prepareEngine(tts: TextToSpeech): Boolean {
        tts.setAudioAttributes(attributes)
        return tts.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String?) {
                    main.post { if (id == currentId && audible) available() }
                }

                override fun onDone(id: String?) {
                    main.post { complete(id) }
                }

                @Deprecated("Platform callback")
                override fun onError(id: String?) {
                    main.post {
                        fail(
                            id,
                            "Android voice playback failed. Check Text-to-speech settings or use Test speech.",
                        )
                    }
                }

                override fun onError(id: String?, code: Int) {
                    main.post {
                        fail(
                            id,
                            "Android voice playback failed (code $code). Check Text-to-speech settings.",
                        )
                    }
                }
            }
        ) == TextToSpeech.SUCCESS
    }

    fun reconnect(force: Boolean = false) {
        if (closed) return
        if (force || connection.state == SpeechEngineConnection.State.FAILED) {
            engineUnavailable(false)
            connection.connect(force)
        }
    }

    fun language(bangla: Boolean) {
        locale = if (bangla) Locale.forLanguageTag("bn-BD") else Locale.US
    }

    override fun speak(text: String, interrupt: Boolean) {
        if (closed) {
            unavailable("Speech output is closed.")
            finished()
            return
        }
        stop()
        val id = UUID.randomUUID().toString()
        currentId = id
        onSpoken(text)
        playback(true)
        audible = audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
        if (!audible) unavailable("Media volume is muted. Increase media volume and replay.")
        audio.requestAudioFocus(focus)
        if (connection.state == SpeechEngineConnection.State.FAILED) {
            fail(id, ENGINE_UNAVAILABLE)
            return
        }
        val tts = connection.readyEngine()
        if (tts == null) {
            pending = text
            return
        }
        // Engine startup has its own deadline; do not spend the playback deadline waiting to bind.
        val watchdog = Runnable {
            fail(id, "Speech playback timed out. Check your audio output and replay.")
        }
        timeout = watchdog
        main.postDelayed(watchdog,
            (text.split(Regex("\\s+")).size * 450L + 3000).coerceIn(12000, 30000))
        try {
            tts.setAudioAttributes(attributes)
            val current = tts.voice
            val default = tts.defaultVoice
            val voices =
                (listOfNotNull(current, default) + tts.voices.orEmpty()).distinctBy { it.name }
            fun installed(voice: android.speech.tts.Voice) =
                !voice.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
            var configured =
                current?.let { installed(it) && it.locale.language == locale.language } == true
            if (!configured) {
                // Let the user's engine choose its native voice; never replace it with recorded
                // speech.
                val result = tts.setLanguage(locale)
                configured =
                    result >= TextToSpeech.LANG_AVAILABLE &&
                        tts.voice?.let {
                            installed(it) && it.locale.language == locale.language
                        } == true
            }
            if (!configured) {
                val name =
                    NativeVoicePolicy.select(
                        voices.map {
                            NativeVoice(
                                it.name,
                                it.locale.language,
                                !it.isNetworkConnectionRequired,
                                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in
                                    it.features.orEmpty(),
                            )
                        },
                        locale.language,
                        current?.name,
                        default?.name,
                    )
                configured =
                    voices
                        .firstOrNull { it.name == name }
                        ?.let { tts.setVoice(it) == TextToSpeech.SUCCESS } == true
            }
            if (!configured) {
                fail(
                    id,
                    "No installed offline ${locale.displayLanguage} voice. Check Android Text-to-speech settings.",
                )
                return
            }
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.ERROR)
                fail(id, "Android speech request failed. Check Text-to-speech settings.")
        } catch (_: Exception) {
            fail(id, "Speech engine error. Check Android Text-to-speech settings.")
        }
    }

    private fun complete(id: String?) {
        if (id == null || id != currentId) return
        stop()
        finished()
    }

    private fun fail(id: String?, message: String) {
        if (id == null || id != currentId) return
        stop()
        unavailable(message)
        finished()
    }

    override fun stop() {
        currentId = null
        pending = null
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
        runCatching { connection.readyEngine()?.stop() }
        audio.abandonAudioFocusRequest(focus)
        playback(false)
    }

    override fun close() {
        closed = true
        stop()
        connection.close()
    }

    companion object {
        private const val ENGINE_UNAVAILABLE =
            "Android speech engine unavailable. Install or update Speech Recognition & Synthesis from Google, then return and tap Test speech."
    }
}
