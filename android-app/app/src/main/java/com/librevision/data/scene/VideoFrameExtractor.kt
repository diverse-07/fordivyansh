package com.librevision.data.scene

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import com.librevision.domain.scene.*
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*

data class VideoFrames(
    val views: List<SurroundingsView>,
    val durationMs: Long,
    val extractionMs: Long,
    val videoBytes: Long,
) {
    val imageBytes
        get() = views.sumOf { it.jpeg.size }
}

/** The local silent video is deleted after selection. Only selected JPEGs leave this adapter. */
class VideoFrameExtractor {
    suspend fun extract(
        file: File,
        endedAtMs: Long,
        count: Int = 12,
        maxEdge: Int = 512,
        sharpest: Boolean = false,
    ): VideoFrames =
        withContext(Dispatchers.IO) {
            require(maxEdge in 384..512)
            val started = SystemClock.elapsedRealtime()
            val owned = mutableListOf<SurroundingsView>()
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val duration =
                    checkNotNull(
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        )
                        .toLong()
                val offsets = VideoFramePlan.offsets(duration, count)
                val width =
                    checkNotNull(
                            retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
                            )
                        )
                        .toInt()
                val height =
                    checkNotNull(
                            retriever.extractMetadata(
                                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
                            )
                        )
                        .toInt()
                val scale = maxEdge.toDouble() / maxOf(width, height)
                val scaledWidth = (width * scale.coerceAtMost(1.0)).toInt().coerceAtLeast(1)
                val scaledHeight = (height * scale.coerceAtMost(1.0)).toInt().coerceAtLeast(1)
                for (offset in offsets) {
                    ensureActive()
                    var best: Bitmap? = null
                    var bestOffset = offset
                    var score = -1.0
                    try {
                        val candidates =
                            if (sharpest)
                                listOf(
                                    offset - duration / (count * 6),
                                    offset + duration / (count * 6),
                                )
                            else listOf(offset)
                        for (candidate in candidates) {
                            ensureActive()
                            val bitmap =
                                checkNotNull(
                                    if (Build.VERSION.SDK_INT >= 27)
                                        retriever.getScaledFrameAtTime(
                                            candidate * 1000,
                                            MediaMetadataRetriever.OPTION_CLOSEST,
                                            scaledWidth,
                                            scaledHeight,
                                        )
                                    else
                                        retriever
                                            .getFrameAtTime(
                                                candidate * 1000,
                                                MediaMetadataRetriever.OPTION_CLOSEST,
                                            )
                                            ?.let { full ->
                                                val ratio =
                                                    maxEdge.toDouble() /
                                                        maxOf(full.width, full.height)
                                                if (ratio < 1)
                                                    Bitmap.createScaledBitmap(
                                                            full,
                                                            (full.width * ratio)
                                                                .toInt()
                                                                .coerceAtLeast(1),
                                                            (full.height * ratio)
                                                                .toInt()
                                                                .coerceAtLeast(1),
                                                            true,
                                                        )
                                                        .also { full.recycle() }
                                                else full
                                            }
                                ) {
                                    "A video frame could not be read. Try recording again."
                                }
                            val quality = if (sharpest) detail(bitmap) else 0.0
                            if (quality > score) {
                                best?.recycle()
                                best = bitmap
                                bestOffset = candidate
                                score = quality
                            } else bitmap.recycle()
                        }
                        val image = checkNotNull(best)
                        val jpeg =
                            ByteArrayOutputStream().use { output ->
                                for (quality in listOf(75, 60, 45)) {
                                    output.reset()
                                    check(
                                        image.compress(Bitmap.CompressFormat.JPEG, quality, output)
                                    )
                                    if (output.size() <= 48000) break
                                }
                                check(output.size() <= 48000) {
                                    "Image could not be compressed. Try recording again."
                                }
                                output.toByteArray()
                            }
                        owned +=
                            SurroundingsView(
                                jpeg,
                                image.width,
                                image.height,
                                bestOffset,
                                endedAtMs - (duration - bestOffset),
                            )
                    } finally {
                        best?.recycle()
                    }
                }
                ensureActive()
                VideoFrames(owned, duration, SystemClock.elapsedRealtime() - started, file.length())
            } catch (e: Throwable) {
                owned.forEach { it.jpeg.fill(0) }
                throw e
            } finally {
                retriever.release()
            }
        }

    private fun detail(bitmap: Bitmap): Double {
        val tiny = Bitmap.createScaledBitmap(bitmap, 64, 48, true)
        try {
            val pixels = IntArray(64 * 48)
            tiny.getPixels(pixels, 0, 64, 0, 0, 64, 48)
            fun luma(p: Int) = ((p shr 16 and 255) * 3 + (p shr 8 and 255) * 6 + (p and 255)) / 10
            var total = 0.0
            for (y in 1..46) for (x in 1..62) {
                val i = y * 64 + x
                total +=
                    kotlin.math.abs(
                        4 * luma(pixels[i]) -
                            luma(pixels[i - 1]) -
                            luma(pixels[i + 1]) -
                            luma(pixels[i - 64]) -
                            luma(pixels[i + 64])
                    )
            }
            return total
        } finally {
            if (tiny !== bitmap) tiny.recycle()
        }
    }
}
