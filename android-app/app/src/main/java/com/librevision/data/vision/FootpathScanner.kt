package com.librevision.data.vision

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.librevision.data.models.AppModelManager
import com.librevision.data.vision.depth.DepthProcessor
import com.librevision.data.vision.rfdetr.FramePreprocessor
import com.librevision.domain.vision.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ScanPhase {
    IDLE,
    PREPARING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class ScanState(
    val phase: ScanPhase = ScanPhase.IDLE,
    val message: String = "Ready for on-device scanning.",
    val observation: Observation? = null,
    val modelMs: Double? = null,
    val pipelineMs: Double? = null,
)

/** Camera binding runs on main. GPU creation/inference/destruction share exactly one executor. */
class FootpathScanner(private val context: Context, private val models: AppModelManager) :
    AutoCloseable {
    private val depthProcessor = DepthProcessor(context, models)
    private val stillCapture = SelectedFrameCapture()

    suspend fun selectedFrame(fullFrame: Boolean = false): CameraStill {
        check(requested) { "Start camera scanning first" }
        return stillCapture.capture(fullFrame)
    }

    val depth = depthProcessor.state
    @Volatile var depthEnabled = true
    @Volatile var captureOnly = false
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val mutable = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = mutable
    private var token = AtomicBoolean(true)
    private var requested = false
    private var closed = false
    private var view: PreviewView? = null
    private var owner: LifecycleOwner? = null
    private var camera: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var boundCameraInfo: CameraInfo? = null
    private var engine: LiteRtVisionDetector? = null // Worker only.
    private var preprocessor: FramePreprocessor? = null // Worker only.

    fun attach(view: PreviewView, owner: LifecycleOwner) {
        this.view = view
        this.owner = owner
        if (requested) prepare()
    }

    fun detach() {
        stop()
        view = null
        owner = null
    }

    fun start() {
        if (closed || requested) return
        requested = true
        mutable.value = ScanState(ScanPhase.PREPARING, "Waiting for camera permission…")
        if (view != null) prepare()
    }

    private fun prepare() {
        if (closed || !requested || !token.get()) return
        val active = AtomicBoolean(false)
        token = active
        mutable.value =
            ScanState(
                ScanPhase.PREPARING,
                if (captureOnly) "Preparing camera…" else "Preparing RF-DETR Nano…",
            )
        worker.execute {
            try {
                val directory = models.readyFiles().vision
                if (active.get()) return@execute
                if (!captureOnly) {
                    engine =
                        LiteRtVisionDetector(directory) { message ->
                            post(active) { mutable.value = ScanState(ScanPhase.PREPARING, message) }
                        }
                    preprocessor = FramePreprocessor()
                }
                post(active) { bind(active) }
            } catch (error: Exception) {
                fail(active, "Scan unavailable: ${error.message}")
            } catch (error: LinkageError) {
                fail(active, "Footpath camera runtime unavailable: ${error.message}")
            }
        }
    }

    private fun bind(active: AtomicBoolean) {
        val target = view ?: return
        val lifecycle = owner ?: return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (active.get() || closed) return@addListener
                try {
                    val provider = future.get()
                    camera = provider
                    val rotation =
                        when (target.display?.rotation) {
                            Surface.ROTATION_90 -> Surface.ROTATION_90
                            Surface.ROTATION_180 -> Surface.ROTATION_180
                            Surface.ROTATION_270 -> Surface.ROTATION_270
                            else -> Surface.ROTATION_0
                        }
                    val p =
                        Preview.Builder().setTargetRotation(rotation).build().also {
                            it.surfaceProvider = target.surfaceProvider
                        }
                    val a =
                        ImageAnalysis.Builder()
                            .setTargetRotation(rotation)
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setResolutionStrategy(
                                        ResolutionStrategy(
                                            Size(640, 480),
                                            ResolutionStrategy
                                                .FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                        )
                                    )
                                    .build()
                            )
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                    preview = p
                    analysis = a
                    a.setAnalyzer(worker) { frame ->
                        val start = SystemClock.elapsedRealtime()
                        try {
                            val input =
                                try {
                                    if (active.get()) null
                                    else {
                                        val raw = frame.toBitmap()
                                        var rawTransferred = false
                                        try {
                                            // Preserve RF-DETR's original crop/rotation/resampling
                                            // exactly.
                                            val tensor =
                                                if (captureOnly) null
                                                else
                                                    preprocessor!!.fromBitmap(
                                                        raw,
                                                        frame.cropRect,
                                                        frame.imageInfo.rotationDegrees,
                                                    )
                                            val fullCapture = stillCapture.fullFrameRequested
                                            if (fullCapture) {
                                                val full =
                                                    UprightFrame.fromBitmap(
                                                        raw,
                                                        Rect(0, 0, raw.width, raw.height),
                                                        frame.imageInfo.rotationDegrees,
                                                    )
                                                try {
                                                    stillCapture.offer(
                                                        full,
                                                        start,
                                                        horizontalFov(
                                                            raw.width,
                                                            raw.height,
                                                            frame.imageInfo.rotationDegrees,
                                                        ),
                                                    )
                                                } finally {
                                                    if (full !== raw) full.recycle()
                                                }
                                            }
                                            if (
                                                (depthEnabled && !captureOnly) ||
                                                    (!fullCapture && stillCapture.requested)
                                            ) {
                                                val upright =
                                                    UprightFrame.fromBitmap(
                                                        raw,
                                                        frame.cropRect,
                                                        frame.imageInfo.rotationDegrees,
                                                    )
                                                var transferred = false
                                                try {
                                                    if (!fullCapture)
                                                        stillCapture.offer(upright, start)
                                                    if (depthEnabled && !captureOnly) {
                                                        depthProcessor.submit(upright, start)
                                                        transferred = true
                                                        rawTransferred = upright === raw
                                                    }
                                                } finally {
                                                    if (!transferred && upright !== raw)
                                                        upright.recycle()
                                                }
                                            }
                                            tensor
                                        } finally {
                                            if (!rawTransferred) raw.recycle()
                                        }
                                    }
                                } finally {
                                    frame.close()
                                } // Release CameraX before either model runs.
                            if (input != null && !active.get()) {
                                val detections = engine!!.detect(input)
                                val observation = Observation(start, detections, false)
                                val modelMs = engine!!.modelMs
                                val pipelineMs = (SystemClock.elapsedRealtime() - start).toDouble()
                                post(active) {
                                    mutable.value =
                                        ScanState(
                                            ScanPhase.RUNNING,
                                            "Scanning. Path safety unknown.",
                                            observation,
                                            modelMs,
                                            pipelineMs,
                                        )
                                }
                            }
                        } catch (error: Exception) {
                            fail(active, "Detection stopped: ${error.message}")
                        } catch (error: LinkageError) {
                            fail(active, "Footpath camera runtime stopped: ${error.message}")
                        }
                    }
                    val group = UseCaseGroup.Builder().addUseCase(p).addUseCase(a)
                    target.viewPort?.let { group.setViewPort(it) }
                    val bound =
                        provider.bindToLifecycle(
                            lifecycle,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            group.build(),
                        )
                    boundCameraInfo = bound.cameraInfo
                    mutable.value = ScanState(ScanPhase.RUNNING, "Scanning. Path safety unknown.")
                } catch (error: Exception) {
                    fail(active, "Camera unavailable: ${error.message}")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun horizontalFov(width: Int, height: Int, rotation: Int): Float? = runCatching {
        val info = Camera2CameraInfo.from(checkNotNull(boundCameraInfo))
        val sensor =
            checkNotNull(
                info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            )
        val focal =
            checkNotNull(
                info
                    .getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                    )
                    ?.maxOrNull()
            )
        val usedWidth = min(sensor.width, sensor.height * width / height)
        val usedHeight = min(sensor.height, sensor.width * height / width)
        val side = if (rotation % 180 == 90) usedHeight else usedWidth
        Math.toDegrees(2 * atan(side / (2.0 * focal))).toFloat()
    }
        .getOrNull()

    private fun post(active: AtomicBoolean, action: () -> Unit) {
        main.post { if (!closed && !active.get() && token === active) action() }
    }

    private fun fail(active: AtomicBoolean, message: String) {
        main.post { if (!closed && token === active && !active.get()) stop(message, true) }
    }

    fun stop(message: String = "Scanning stopped.", error: Boolean = false) {
        requested = false
        boundCameraInfo = null
        token.set(true)
        stillCapture.stop()
        depthProcessor.stop()
        analysis?.clearAnalyzer()
        analysis?.let { camera?.unbind(it) }
        preview?.let { camera?.unbind(it) }
        analysis = null
        preview = null
        if (closed) return
        val oldToken = token
        mutable.value = ScanState(if (error) ScanPhase.ERROR else ScanPhase.STOPPING, message)
        worker.execute {
            engine?.close()
            engine = null
            preprocessor?.close()
            preprocessor = null
            main.post {
                if (!closed && token === oldToken && !requested)
                    mutable.value =
                        ScanState(if (error) ScanPhase.ERROR else ScanPhase.IDLE, message)
            }
        }
    }

    override fun close() {
        stop()
        stillCapture.close()
        depthProcessor.close()
        closed = true
        view = null
        owner = null
        worker.shutdown()
    }
}
