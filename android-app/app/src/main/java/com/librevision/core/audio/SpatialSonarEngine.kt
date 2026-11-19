package com.librevision.core.audio
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Sonifies a fresh obstacle's side, not a safe direction or calibrated urgency. */
class SpatialSonarEngine : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val generation = AtomicLong()
    @Volatile private var running = false
    @Volatile private var closed = false
    @Volatile private var targetAt = 0L
    @Volatile private var bias = 0f
    @Volatile var isEnabled = true
    fun updateSteering(offsetNorm: Float, distanceAheadMeters: Float?) {
        if (!offsetNorm.isFinite()) { clearTarget(); return }
        bias = offsetNorm.coerceIn(-1f, 1f)
        targetAt = SystemClock.elapsedRealtime()
    }
    fun clearTarget() { targetAt = 0L }
    @Synchronized fun start() {
        if (closed || running) return
        running = true
        val ticket = generation.incrementAndGet()
        executor.execute { loop(ticket) }
    }
    @Synchronized fun pause() {
        running = false; generation.incrementAndGet(); clearTarget()
    }
    private fun loop(ticket: Long) {
        var track: AudioTrack? = null
        try {
            val rate = 22050
            val n = rate * 60 / 1000
            track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(max(n * 4, AudioTrack.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            val mono = ShortArray(n) { i ->
                (sin(2 * PI * 520 * i / rate) * .5 * (1 - cos(2 * PI * i / n)) * .35 * Short.MAX_VALUE).toInt().toShort()
            }
            val buffer = ShortArray(n * 2)
            track.play()
            while (!closed && running && generation.get() == ticket) {
                if (!isEnabled || targetAt == 0L || SystemClock.elapsedRealtime() - targetAt !in 0..1500) {
                    SystemClock.sleep(30); continue
                }
                val left = ((1 - bias) / 2).coerceIn(.15f, 1f)
                val right = ((1 + bias) / 2).coerceIn(.15f, 1f)
                mono.indices.forEach { i ->
                    buffer[2*i] = (mono[i] * left).toInt().toShort()
                    buffer[2*i+1] = (mono[i] * right).toInt().toShort()
                }
                if (track.write(buffer, 0, buffer.size) < 0) break
                val until = SystemClock.elapsedRealtime() + 690
                while (generation.get() == ticket && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20)
            }
        } catch (_: Exception) {
            // Optional sonar failure cannot crash speech, haptics or scanning.
        } catch (_: LinkageError) {
        } finally {
            runCatching { track?.stop() }; runCatching { track?.release() }
            if (generation.get() == ticket) running = false
        }
    }
    override fun close() { closed = true; pause(); executor.shutdown() }
}
