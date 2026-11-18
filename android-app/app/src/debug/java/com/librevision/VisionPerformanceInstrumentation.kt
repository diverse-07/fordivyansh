package com.librevision

import android.app.*
import android.graphics.*
import android.os.*
import com.librevision.data.models.*
import com.librevision.data.vision.*
import com.librevision.data.vision.depth.*
import com.librevision.data.vision.rfdetr.*
import java.util.concurrent.*
import org.json.*

/**
 * Synthetic input only. Both native models use the same executors and data shape as live scanning.
 */
class VisionPerformanceInstrumentation : Instrumentation() {
    private var preprocessOnly = false

    override fun onCreate(args: Bundle?) {
        super.onCreate(args)
        preprocessOnly = args?.getString("check") == "preprocess"
        start()
    }

    override fun onStart() {
        val output = Bundle()
        val detectorWorker = Executors.newSingleThreadExecutor()
        val depthWorker = Executors.newSingleThreadExecutor()
        val models = AppModelManager(targetContext)
        try {
            models.prepare()
            val until = SystemClock.elapsedRealtime() + 30000
            while (
                models.state.value.phase == SetupPhase.PREPARING &&
                    SystemClock.elapsedRealtime() < until
            ) Thread.sleep(50)
            val files = models.readyFiles()
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
            val pixels =
                IntArray(640 * 480) { i ->
                    Color.rgb((i % 640) * 255 / 640, (i / 640) * 255 / 480, 120)
                }
            bitmap.setPixels(pixels, 0, 640, 0, 0, 640, 480)
            if (preprocessOnly) {
                val report = JSONObject().put("synthetic_input_only", true)
                val samples = JSONArray()
                FramePreprocessor().use { prep ->
                    for (rotation in listOf(0, 90, 180, 270)) {
                        val crop = Rect(40, 30, 600, 450)
                        val previous = prep.fromBitmap(bitmap, crop, rotation).copyOf()
                        val upright = UprightFrame.fromBitmap(bitmap, crop, rotation)
                        // The shared source retains the original detector resampling path.
                        val shared = prep.fromBitmap(bitmap, crop, rotation)
                        val difference =
                            shared.indices.maxOf { k -> kotlin.math.abs(previous[k] - shared[k]) }
                        samples.put(
                            JSONObject()
                                .put("rotation", rotation)
                                .put("max_input_difference", difference)
                        )
                        upright.recycle()
                        check(difference < .001f) {
                            "Preprocessing changed at rotation $rotation: $difference"
                        }
                    }
                }
                bitmap.recycle()
                report.put("rotations", samples)
                output.putString("stream", report.toString() + "\n")
                finish(Activity.RESULT_OK, output)
                return
            }
            lateinit var detector: LiteRtVisionDetector
            lateinit var preprocess: FramePreprocessor
            detectorWorker
                .submit {
                    detector = LiteRtVisionDetector(files.vision) {}
                    preprocess = FramePreprocessor()
                }
                .get()
            val report =
                JSONObject()
                    .put("synthetic_input_only", true)
                    .put("input_width", 640)
                    .put("input_height", 480)
            val cases = JSONArray()
            var reference: FloatArray? = null
            for (threads in listOf(3, 2, 1, 3)) {
                lateinit var depth: MetricDepthEngine
                depthWorker
                    .submit {
                        depth = MetricDepthEngine(targetContext, files.depth, threads)
                        depth.infer(bitmap, 0)
                    }
                    .get()
                val pairs = JSONArray()
                repeat(5) {
                    val gate = CountDownLatch(1)
                    val d =
                        detectorWorker.submit(
                            Callable {
                                gate.await()
                                val start = System.nanoTime()
                                detector.detect(preprocess.fromBitmap(bitmap))
                                (System.nanoTime() - start) / 1e6
                            }
                        )
                    val z =
                        depthWorker.submit(
                            Callable {
                                gate.await()
                                depth.infer(bitmap, SystemClock.elapsedRealtime())
                            }
                        )
                    val start = System.nanoTime()
                    gate.countDown()
                    val detectionMs = d.get()
                    val result = z.get()
                    val previous = reference
                    if (previous == null) reference = result.values
                    val maxError =
                        previous?.indices?.maxOf { k ->
                            kotlin.math.abs(previous[k] - result.values[k])
                        } ?: 0f
                    pairs.put(
                        JSONObject()
                            .put("detection_ms", detectionMs)
                            .put("depth_ms", result.inferenceMs)
                            .put("pair_ms", (System.nanoTime() - start) / 1e6)
                            .put("depth_max_error_m", maxError)
                    )
                }
                cases.put(JSONObject().put("depth_threads", threads).put("samples", pairs))
                depthWorker.submit { depth.close() }.get()
            }
            detectorWorker
                .submit {
                    detector.close()
                    preprocess.close()
                }
                .get()
            bitmap.recycle()
            report.put("cases", cases)
            output.putString("stream", report.toString() + "\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", "FAILED: ${error.javaClass.simpleName}: ${error.message}\n")
            finish(Activity.RESULT_CANCELED, output)
        } finally {
            models.close()
            detectorWorker.shutdown()
            depthWorker.shutdown()
        }
    }
}
