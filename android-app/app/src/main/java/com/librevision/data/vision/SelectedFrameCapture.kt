package com.librevision.data.vision

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*

data class CameraStill(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val timestampMs: Long,
    val horizontalFovDegrees: Float? = null,
)

/** Captures one requested frame only; no continuous JPEG encoding, frame history or disk files. */
class SelectedFrameCapture : AutoCloseable {
    private data class Request(val fullFrame: Boolean, val result: CompletableDeferred<CameraStill>)

    private val pending = AtomicReference<Request?>()
    val fullFrameRequested: Boolean
        get() = pending.get()?.fullFrame == true

    private val encoder = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    val requested: Boolean
        get() = pending.get() != null

    suspend fun capture(fullFrame: Boolean = false): CameraStill {
        check(!closed) { "Camera capture is closed" }
        val result = CompletableDeferred<CameraStill>()
        val request = Request(fullFrame, result)
        check(pending.compareAndSet(null, request)) { "A frame request is already running" }
        return try {
            withTimeout(4000) { result.await() }
        } finally {
            pending.compareAndSet(request, null)
            result.cancel()
        }
    }

    fun offer(image: Bitmap, time: Long, horizontalFov: Float? = null) {
        val request = pending.getAndSet(null) ?: return
        if (!request.result.isActive || closed) return
        var copy: Bitmap? = null
        try {
            copy =
                if (request.fullFrame && maxOf(image.width, image.height) > 384) {
                    val ratio = 384f / maxOf(image.width, image.height)
                    Bitmap.createScaledBitmap(
                        image,
                        (image.width * ratio).toInt().coerceAtLeast(1),
                        (image.height * ratio).toInt().coerceAtLeast(1),
                        true,
                    )
                } else image.copy(Bitmap.Config.ARGB_8888, false)
            val owned = checkNotNull(copy)
            encoder.execute {
                try {
                    if (request.result.isActive && !closed)
                        ByteArrayOutputStream().use { bytes ->
                            for (quality in
                                if (request.fullFrame) listOf(70, 55, 40) else listOf(80)) {
                                bytes.reset()
                                check(owned.compress(Bitmap.CompressFormat.JPEG, quality, bytes))
                                if (!request.fullFrame || bytes.size() <= 32000) break
                            }
                            check(!request.fullFrame || bytes.size() <= 32000) {
                                "Capture could not be compressed within the scene budget."
                            }
                            request.result.complete(
                                CameraStill(
                                    bytes.toByteArray(),
                                    owned.width,
                                    owned.height,
                                    time,
                                    horizontalFov,
                                )
                            )
                        }
                } catch (error: Exception) {
                    request.result.completeExceptionally(error)
                } finally {
                    owned.recycle()
                }
            }
        } catch (error: Exception) {
            copy?.recycle()
            request.result.completeExceptionally(error)
        }
    }

    fun stop() {
        pending
            .getAndSet(null)
            ?.result
            ?.completeExceptionally(IllegalStateException("Camera is not scanning"))
    }

    override fun close() {
        closed = true
        stop()
        encoder.shutdown()
    }
}
