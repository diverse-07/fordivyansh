package com.visionassist.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sin

/**
 * Harvard Mobilio Binaural Spatial Audio Sonar Engine
 * Emits continuous, gentle stereo audio pulses panning left/right
 * to guide the blind user along the center of the safe walking path.
 */
class SpatialSonarEngine : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor()
    private val isRunning = AtomicBoolean(false)
    private var audioTrack: AudioTrack? = null

    @Volatile var isEnabled: Boolean = true
    @Volatile var currentSteeringBias: Float = 0.0f // -1.0 (hard left) to +1.0 (hard right)
    @Volatile var pulseRateMs: Long = 700L          // Distance-based interval

    init {
        initAudioTrack()
    }

    private fun initAudioTrack() {
        val sampleRate = 22050
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    /**
     * Updates steering offset from the footpath detection pipeline.
     * @param offsetNorm Normalized lateral deviation: 0.0 is center, -0.5 is left, +0.5 is right.
     * @param distanceAheadMeters Estimated clear distance ahead before next obstacle/curve.
     */
    fun updateSteering(offsetNorm: Float, distanceAheadMeters: Float) {
        currentSteeringBias = offsetNorm.coerceIn(-1.0f, 1.0f)
        
        // Closer to turning point or narrower path -> faster beeps
        pulseRateMs = when {
            distanceAheadMeters < 1.5f -> 250L
            distanceAheadMeters < 3.0f -> 450L
            else -> 750L
        }
    }

    fun start() {
        if (isRunning.getAndSet(true)) return
        audioTrack?.play()
        executor.execute { loopSonar() }
    }

    fun pause() {
        isRunning.set(false)
        audioTrack?.pause()
        audioTrack?.flush()
    }

    private fun loopSonar() {
        val sampleRate = 22050
        val toneDurationMs = 60
        val numSamples = (sampleRate * (toneDurationMs / 1000.0)).toInt()
        val freq = 520.0 // Hz - Soft, pleasant navigation ping

        // Precompute raw sine wave PCM
        val monoWave = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            // Apply Hann window envelope to avoid clicks
            val envelope = 0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / numSamples))
            val sample = (sin(2.0 * Math.PI * freq * t) * envelope * 0.45 * Short.MAX_VALUE).toInt()
            monoWave[i] = sample.toShort()
        }

        val stereoBuffer = ShortArray(numSamples * 2)

        while (isRunning.get()) {
            if (!isEnabled) {
                SystemClock.sleep(200)
                continue
            }

            // Calculate binaural panning based on current steering bias
            // bias < 0 (veer left): Left ear louder
            // bias > 0 (veer right): Right ear louder
            val leftGain = ((1.0f - currentSteeringBias) / 2.0f).coerceIn(0.15f, 1.0f)
            val rightGain = ((1.0f + currentSteeringBias) / 2.0f).coerceIn(0.15f, 1.0f)

            var bufIdx = 0
            for (i in 0 until numSamples) {
                stereoBuffer[bufIdx++] = (monoWave[i] * leftGain).toInt().toShort()
                stereoBuffer[bufIdx++] = (monoWave[i] * rightGain).toInt().toShort()
            }

            audioTrack?.write(stereoBuffer, 0, stereoBuffer.size)

            val sleepTime = (pulseRateMs - toneDurationMs).coerceAtLeast(50L)
            SystemClock.sleep(sleepTime)
        }
    }

    override fun close() {
        isRunning.set(false)
        audioTrack?.stop()
        audioTrack?.release()
        executor.shutdownNow()
    }
}
