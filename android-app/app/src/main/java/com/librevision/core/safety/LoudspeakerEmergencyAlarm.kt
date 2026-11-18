package com.librevision.core.safety

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/**
 * High-Decibel Emergency Siren & Distress Voice Broadcast.
 * 
 * CRITICAL BLIND SAFETY CAPABILITY:
 * Explicitly forces audio output through the device's PHYSICAL BUILT-IN LOUDSPEAKER
 * at MAXIMUM VOLUME, bypassing any connected Bluetooth TWS earbuds or wired headphones,
 * ensuring bystanders and nearby pedestrians on the street hear the distress call immediately.
 */
class LoudspeakerEmergencyAlarm(private val context: Context) : AutoCloseable {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    val isAlarmActive = AtomicBoolean(false)

    private var audioTrack: AudioTrack? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousAlarmVolume = 0
    private var previousMusicVolume = 0

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isTtsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (isAlarmActive.get()) {
                            mainHandler.postDelayed({ playSirenCycle() }, 400)
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (isAlarmActive.get()) {
                            mainHandler.postDelayed({ playSirenCycle() }, 1000)
                        }
                    }
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (isAlarmActive.get()) {
                            mainHandler.postDelayed({ playSirenCycle() }, 1000)
                        }
                    }
                })
            }
        }
    }

    /**
     * Toggles emergency alarm state.
     */
    fun toggle(): Boolean {
        return if (isAlarmActive.get()) {
            stop()
            false
        } else {
            start()
            true
        }
    }

    /**
     * Activates the emergency loudspeaker siren & blind distress announcement.
     */
    fun start() {
        if (isAlarmActive.getAndSet(true)) return

        Log.i(TAG, "EMERGENCY ALARM ACTIVATED: Routing to Physical Loudspeaker at MAX volume!")

        saveAndMaximizeAudio()
        startHapticAlert()
        playSirenCycle()
    }

    /**
     * Deactivates the emergency alarm and restores previous device audio parameters.
     */
    fun stop() {
        if (!isAlarmActive.getAndSet(false)) return

        Log.i(TAG, "Emergency alarm deactivated. Restoring audio levels.")
        mainHandler.removeCallbacksAndMessages(null)

        stopSiren()
        stopHapticAlert()

        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS: ${e.message}")
        }

        restoreAudioSettings()
    }

    private fun playSirenCycle() {
        if (!isAlarmActive.get()) return

        Thread({
            try {
                // Generate 2.5 seconds of high-pitched alternating two-tone siren
                val sampleRate = 44100
                val durationSeconds = 2.5
                val numSamples = (durationSeconds * sampleRate).toInt()
                val samples = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    val t = i.toDouble() / sampleRate
                    // Frequency oscillates rapidly between 850 Hz and 1650 Hz
                    val freq = 1250.0 + 400.0 * sin(2.0 * Math.PI * 4.0 * t)
                    val sample = (sin(2.0 * Math.PI * freq * t) * 32767.0).toInt().coerceIn(-32768, 32767)
                    samples[i] = sample.toShort()
                }

                val bufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(samples.size * 2)

                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()

                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                // Route directly to built-in physical speaker
                forceSpeakerRoute(track)

                track.play()
                track.write(samples, 0, samples.size)

                while (track.playState == AudioTrack.PLAYSTATE_PLAYING && isAlarmActive.get()) {
                    Thread.sleep(50)
                }

                runCatching {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
                    track.release()
                }
                if (audioTrack == track) audioTrack = null

                if (isAlarmActive.get()) {
                    mainHandler.post { speakDistressAnnouncement() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing siren tone: ${e.message}", e)
                if (isAlarmActive.get()) {
                    mainHandler.postDelayed({ speakDistressAnnouncement() }, 1000)
                }
            }
        }, "LoudspeakerSirenThread").start()
    }

    private fun speakDistressAnnouncement() {
        if (!isAlarmActive.get() || !isTtsReady) {
            if (isAlarmActive.get()) mainHandler.postDelayed({ playSirenCycle() }, 1000)
            return
        }

        val speechText = "HELP! I AM BLIND! PLEASE HELP ME! EMERGENCY AT THIS LOCATION!"
        val params = android.os.Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, params, "EmergencyUtterance")
    }

    private fun forceSpeakerRoute(track: AudioTrack) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val speakerDevice = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speakerDevice != null) {
                track.preferredDevice = speakerDevice
                Log.d(TAG, "AudioTrack preferredDevice set to BUILTIN_SPEAKER")
            }
        }
    }

    private fun saveAndMaximizeAudio() {
        try {
            previousAudioMode = audioManager.mode
            previousAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            previousMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

            // Override communication mode and force speakerphone ON
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true

            // Set built-in speaker device for communication on Android 12+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                }
            }

            // Maximize volume
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)

            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to maximize loudspeaker volume: ${e.message}")
        }
    }

    private fun restoreAudioSettings() {
        try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }

            audioManager.mode = previousAudioMode
            if (previousAlarmVolume > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, previousAlarmVolume, 0)
            }
            if (previousMusicVolume > 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousMusicVolume, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error restoring audio settings: ${e.message}")
        }
    }

    private fun startHapticAlert() {
        if (!vibrator.hasVibrator()) return
        val pattern = longArrayOf(0, 300, 150, 300, 150, 500, 200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0)) // Repeat from index 0
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, 0)
        }
    }

    private fun stopHapticAlert() {
        try {
            vibrator.cancel()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun stopSiren() {
        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                release()
            }
            audioTrack = null
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun close() {
        stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "NetraEmergencyAlarm"
    }
}
