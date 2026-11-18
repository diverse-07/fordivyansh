package com.librevision.data.vision.depth

import ai.onnxruntime.*
import android.content.Context
import android.graphics.Bitmap
import com.librevision.domain.vision.DepthFrame
import java.io.File
import java.nio.FloatBuffer
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * Only SpatialLab's Balanced 280 depth configuration is reused. No mapping/tracking/research code.
 */
class MetricDepthEngine(context: Context, model: File, threads: Int? = null) : AutoCloseable {
    private val metadata =
        context.assets.open("monocular-model.json").bufferedReader().use {
            JSONObject(it.readText())
        }
    val size = metadata.getInt("inputSize")
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val input = FloatArray(3 * size * size)
    private val source: Mat
    private val scaled: Mat
    private val rgba = ByteArray(size * size * 4)

    init {
        check(
            size == 280 &&
                metadata.getString("resizeMethod") == "OPENCV_CUBIC" &&
                metadata.getString("selectedRuntime") == "CPU"
        )
        check(OpenCVLoader.initLocal())
        Core.setNumThreads(1)
        source = Mat()
        scaled = Mat()
        session =
            try {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(threads ?: metadata.getInt("selectedThreads"))
                    options.setInterOpNumThreads(1)
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    options.addConfigEntry("session.intra_op.allow_spinning", "0")
                    environment.createSession(model.path, options)
                }
            } catch (error: Throwable) {
                source.release()
                scaled.release()
                throw error
            }
    }

    fun infer(upright: Bitmap, timestampMs: Long): DepthFrame {
        val start = System.nanoTime()
        val side = minOf(upright.width, upright.height)
        val square =
            Bitmap.createBitmap(
                upright,
                (upright.width - side) / 2,
                (upright.height - side) / 2,
                side,
                side,
            )
        try {
            Utils.bitmapToMat(square, source)
            Imgproc.resize(
                source,
                scaled,
                Size(size.toDouble(), size.toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_CUBIC,
            )
            scaled.get(0, 0, rgba)
            val n = size * size
            for (i in 0 until n) {
                val j = i * 4
                input[i] = ((rgba[j].toInt() and 255) / 255f - .485f) / .229f
                input[n + i] = ((rgba[j + 1].toInt() and 255) / 255f - .456f) / .224f
                input[2 * n + i] = ((rgba[j + 2].toInt() and 255) / 255f - .406f) / .225f
            }
            OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(input),
                    longArrayOf(1, 3, size.toLong(), size.toLong()),
                )
                .use { tensor ->
                    session.run(mapOf(metadata.getString("inputName") to tensor)).use { result ->
                        val buffer = (result[0] as OnnxTensor).floatBuffer
                        val values = FloatArray(buffer.remaining())
                        buffer.get(values)
                        check(values.size == n && values.all { it.isFinite() }) {
                            "Invalid depth output"
                        }
                        return DepthFrame(
                            size,
                            values,
                            upright.width,
                            upright.height,
                            timestampMs,
                            (System.nanoTime() - start) / 1e6,
                        )
                    }
                }
        } finally {
            if (square !== upright) square.recycle()
        }
    }

    override fun close() {
        session.close()
        source.release()
        scaled.release()
    }
}
