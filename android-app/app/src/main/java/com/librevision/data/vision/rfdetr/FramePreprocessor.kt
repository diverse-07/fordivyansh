package com.librevision.data.vision.rfdetr

import android.graphics.*
import androidx.camera.core.ImageProxy
import androidx.core.graphics.createBitmap
import java.io.Closeable

class FramePreprocessor : Closeable {
    private val resized = createBitmap(384, 384)
    private val canvas = Canvas(resized)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pixels = IntArray(384 * 384)
    private val chw = FloatArray(3 * 384 * 384)
    private val matrix = Matrix()
    private val source = FloatArray(6)
    private val destination = FloatArray(6)

    fun fromImage(image: ImageProxy): FloatArray {
        val bitmap = image.toBitmap()
        return try {
            fromBitmap(bitmap, image.cropRect, image.imageInfo.rotationDegrees)
        } finally {
            bitmap.recycle()
        }
    }

    fun fromBitmap(
        bitmap: Bitmap,
        crop: Rect = Rect(0, 0, bitmap.width, bitmap.height),
        rotation: Int = 0,
    ): FloatArray {
        require(crop.width() > 0 && crop.height() > 0)
        source[0] = crop.left.toFloat()
        source[1] = crop.top.toFloat()
        source[2] = crop.right.toFloat()
        source[3] = crop.top.toFloat()
        source[4] = crop.left.toFloat()
        source[5] = crop.bottom.toFloat()
        val corners =
            when (rotation) {
                0 -> floatArrayOf(0f, 0f, 384f, 0f, 0f, 384f)
                90 -> floatArrayOf(384f, 0f, 384f, 384f, 0f, 0f)
                180 -> floatArrayOf(384f, 384f, 0f, 384f, 384f, 0f)
                270 -> floatArrayOf(0f, 384f, 0f, 0f, 384f, 384f)
                else -> error("Unsupported camera rotation: $rotation")
            }
        corners.copyInto(destination)
        matrix.setPolyToPoly(source, 0, destination, 0, 3)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(bitmap, matrix, paint)
        resized.getPixels(pixels, 0, 384, 0, 0, 384, 384)
        val hw = pixels.size
        for (i in pixels.indices) {
            val value = pixels[i]
            chw[i] = (((value shr 16) and 255) / 255f - .485f) / .229f
            chw[hw + i] = (((value shr 8) and 255) / 255f - .456f) / .224f
            chw[2 * hw + i] = ((value and 255) / 255f - .406f) / .225f
        }
        return chw
    }

    override fun close() {
        resized.recycle()
    }
}
