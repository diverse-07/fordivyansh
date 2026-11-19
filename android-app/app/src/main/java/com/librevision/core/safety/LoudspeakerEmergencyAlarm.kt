package com.librevision.core.safety

import android.content.Context
import android.media.*
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sin

/** Local distress alarm. This class does not send a guardian notification. */
class LoudspeakerEmergencyAlarm(context: Context) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val main = Handler(Looper.getMainLooper())
    val isAlarmActive = AtomicBoolean(false)
    private val generation = AtomicLong()
    private val lock = Any()
    private var track: AudioTrack? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    private var closed = false
    private var oldMode: Int? = null
    private var oldAlarm: Int? = null
    private var oldMusic: Int? = null
    private var oldSpeaker = false
    private var oldDevice: AudioDeviceInfo? = null
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    init {
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.US
                tts?.setAudioAttributes(attributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = afterSpeech(id)
                    @Deprecated("Legacy TTS callback")
                    override fun onError(id: String?) = afterSpeech(id)
                    override fun onError(id: String?, code: Int) = afterSpeech(id)
                })
            }
        }
    }
    private fun current(ticket: Long) = !closed && isAlarmActive.get() && generation.get() == ticket
    private fun afterSpeech(id: String?) {
        val ticket = id?.removePrefix("alarm-")?.toLongOrNull() ?: return
        main.postDelayed({ if (current(ticket)) cycle(ticket) }, 600)
    }
    fun toggle(): Boolean {
        if (isAlarmActive.get()) { stop(); return false }
        start(); return isAlarmActive.get()
    }
    fun start() {
        if (closed || isAlarmActive.getAndSet(true)) return
        val ticket = generation.incrementAndGet()
        oldMode = runCatching { audio.mode }.getOrNull()
        oldAlarm = runCatching { audio.getStreamVolume(AudioManager.STREAM_ALARM) }.getOrNull()
        oldMusic = runCatching { audio.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        oldSpeaker = audio.isSpeakerphoneOn
        if (Build.VERSION.SDK_INT >= 31) oldDevice = audio.communicationDevice
        runCatching { audio.mode = AudioManager.MODE_IN_COMMUNICATION }
        runCatching { audio.isSpeakerphoneOn = true }
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audio.setCommunicationDevice(it) }
        }
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) }
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0,300,150,300,150,500,200),0)) }
        cycle(ticket)
    }
    private fun cycle(ticket: Long) {
        if (!current(ticket)) return
        Thread({
            var local: AudioTrack? = null
            try {
                val rate = 44100
                val count = rate * 5 / 2
                val samples = ShortArray(count) { i ->
                    val t = i.toDouble()/rate
                    (sin(2*Math.PI*(1250+400*sin(2*Math.PI*4*t))*t)*Short.MAX_VALUE).toInt().toShort()
                }
                if (!current(ticket)) return@Thread
                local = AudioTrack.Builder().setAudioAttributes(attributes)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(count*2).setTransferMode(AudioTrack.MODE_STREAM).build()
                synchronized(lock) {
                    if (!current(ticket)) return@synchronized
                    track = local
                    audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { local.preferredDevice = it }
                    local.play()
                    check(local.write(samples,0,samples.size) == samples.size)
                }
                val start = SystemClock.elapsedRealtime()
                while (current(ticket) && !AlarmCycle.complete(local.playbackHeadPosition.toLong(),count,
                        SystemClock.elapsedRealtime()-start)) SystemClock.sleep(20)
            } catch (_: Exception) {
                // Speech is still attempted if the tone route/runtime fails.
            } finally {
                synchronized(lock) {
                    runCatching { local?.stop() }; runCatching { local?.release() }
                    if (track === local) track = null
                }
            }
            main.post { if (current(ticket)) speak(ticket) }
        },"NetraSaathiAlarm").start()
    }
    private fun speak(ticket: Long) {
        if (!current(ticket)) return
        if (!ready) { main.postDelayed({ if (current(ticket)) cycle(ticket) },1000); return }
        val result = runCatching {
            tts?.speak("Help! I am blind! Please help me!", TextToSpeech.QUEUE_FLUSH,
                Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,1f) },"alarm-"+ticket)
        }.getOrNull()
        if (result == null || result == TextToSpeech.ERROR)
            main.postDelayed({ if (current(ticket)) cycle(ticket) },1000)
    }
    fun stop() {
        if (!isAlarmActive.getAndSet(false)) return
        generation.incrementAndGet()
        main.removeCallbacksAndMessages(null)
        synchronized(lock) { runCatching { track?.stop() } }
        runCatching { tts?.stop() }; runCatching { vibrator?.cancel() }
        runCatching { audio.isSpeakerphoneOn = oldSpeaker }
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            oldDevice?.let { audio.setCommunicationDevice(it) } ?: audio.clearCommunicationDevice()
        }
        oldMode?.let { runCatching { audio.mode = it } }
        oldAlarm?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM,it,0) } }
        oldMusic?.let { runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC,it,0) } }
    }
    override fun close() { stop(); closed = true; generation.incrementAndGet(); tts?.shutdown(); tts = null }
}
