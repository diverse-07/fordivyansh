package com.librevision.data.scene

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.librevision.domain.scene.VideoFramePlan
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class VideoPhase {
    IDLE,
    PREPARING,
    RECORDING,
    FINALIZING,
    ERROR,
}

data class VideoRecordingState(
    val phase: VideoPhase = VideoPhase.IDLE,
    val elapsedMs: Long = 0,
    val message: String = "",
)

data class RecordedSceneVideo(
    val file: File,
    val endedAtMs: Long,
    val startupMs: Long,
    val finalizeMs: Long,
)

/**
 * CameraX hardware recording with no audio track and no motion tracking. Main-thread camera owner.
 */
class SceneVideoRecorder(private val context: Context) : AutoCloseable {
    private val main = ContextCompat.getMainExecutor(context)
    private val folder =
        File(context.cacheDir, "scene-video").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
    private val mutable = MutableStateFlow(VideoRecordingState())
    val state = mutable.asStateFlow()
    private var view: PreviewView? = null
    private var owner: LifecycleOwner? = null
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var pending: CompletableDeferred<RecordedSceneVideo>? = null
    private var file: File? = null
    private var requestedAt = 0L
    private var startedAt = 0L
    private var stoppedAt = 0L
    private var generation = 0L
    private var durationLimitMs = VideoFramePlan.MAX_DURATION_MS

    fun attach(view: PreviewView, owner: LifecycleOwner) {
        this.view = view
        this.owner = owner
        if (pending != null) bind()
    }

    fun detach() {
        cancel()
        view = null
        owner = null
    }

    suspend fun record(durationMs: Long = VideoFramePlan.MAX_DURATION_MS): RecordedSceneVideo {
        check(pending == null) { "A video is already recording." }
        require(durationMs in 750..VideoFramePlan.MAX_DURATION_MS)
        durationLimitMs = durationMs
        generation++
        val result = CompletableDeferred<RecordedSceneVideo>()
        pending = result
        requestedAt = SystemClock.elapsedRealtime()
        startedAt = 0
        stoppedAt = 0
        mutable.value = VideoRecordingState(VideoPhase.PREPARING)
        if (view != null) bind()
        return try {
            withTimeout(45000) { result.await() }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("The camera did not finish recording. Try again.")
        } finally {
            if (pending === result) cancel()
        }
    }

    private fun bind() {
        val target = view ?: return
        val lifecycle = owner ?: return
        val ticket = generation
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (ticket != generation || pending == null || capture != null) return@addListener
                try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val p =
                        Preview.Builder().build().also {
                            it.surfaceProvider = target.surfaceProvider
                        }
                    val recorder =
                        Recorder.Builder()
                            .setQualitySelector(
                                QualitySelector.from(
                                    Quality.SD,
                                    FallbackStrategy.higherQualityOrLowerThan(Quality.SD),
                                )
                            )
                            .setTargetVideoEncodingBitRate(1_500_000)
                            .build()
                    val video = VideoCapture.withOutput(recorder)
                    preview = p
                    capture = video
                    cameraProvider.bindToLifecycle(
                        lifecycle,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        p,
                        video,
                    )
                    val output = File.createTempFile("surroundings-", ".mp4", folder)
                    file = output
                    val options =
                        FileOutputOptions.Builder(output)
                            .setDurationLimitMillis(durationLimitMs)
                            .setFileSizeLimit(10_000_000)
                            .build()
                    // Deliberately omit withAudioEnabled(): no microphone audio is recorded or
                    // uploaded.
                    recording =
                        recorder.prepareRecording(context, options).start(main) { event ->
                            if (ticket != generation) {
                                if (event is VideoRecordEvent.Finalize) output.delete()
                                return@start
                            }
                            when (event) {
                                is VideoRecordEvent.Start -> {
                                    startedAt = SystemClock.elapsedRealtime()
                                    mutable.value = VideoRecordingState(VideoPhase.RECORDING)
                                }
                                is VideoRecordEvent.Status ->
                                    if (mutable.value.phase == VideoPhase.RECORDING) mutable.value =
                                        VideoRecordingState(
                                            VideoPhase.RECORDING,
                                            event.recordingStats.recordedDurationNanos / 1_000_000,
                                        )
                                is VideoRecordEvent.Finalize -> {
                                    val result = pending
                                    pending = null
                                    recording = null
                                    file = null
                                    releaseCamera()
                                    val usable =
                                        !event.hasError() ||
                                            event.error in
                                                setOf(
                                                    VideoRecordEvent.Finalize
                                                        .ERROR_DURATION_LIMIT_REACHED,
                                                    VideoRecordEvent.Finalize
                                                        .ERROR_FILE_SIZE_LIMIT_REACHED,
                                                )
                                    if (usable && output.length() > 0) {
                                        val ended = SystemClock.elapsedRealtime()
                                        mutable.value = VideoRecordingState()
                                        val value =
                                            RecordedSceneVideo(
                                                output,
                                                ended,
                                                startedAt - requestedAt,
                                                if (stoppedAt > 0) ended - stoppedAt else 0,
                                            )
                                        if (result?.complete(value) != true) output.delete()
                                    } else {
                                        output.delete()
                                        val message =
                                            "Video could not be recorded. Check camera access and try again."
                                        mutable.value =
                                            VideoRecordingState(VideoPhase.ERROR, message = message)
                                        result?.completeExceptionally(
                                            IllegalStateException(message)
                                        )
                                    }
                                }
                            }
                        }
                } catch (e: Exception) {
                    fail(e)
                }
            },
            main,
        )
    }

    fun finish() {
        if (recording == null || mutable.value.phase == VideoPhase.FINALIZING) return
        stoppedAt = SystemClock.elapsedRealtime()
        mutable.update { it.copy(phase = VideoPhase.FINALIZING) }
        recording?.stop()
    }

    private fun fail(e: Exception) {
        val result = pending
        pending = null
        cancel()
        mutable.value =
            VideoRecordingState(VideoPhase.ERROR, message = e.message ?: "Camera unavailable.")
        result?.completeExceptionally(e)
    }

    fun cancel() {
        generation++
        recording?.close()
        recording = null
        pending?.cancel()
        pending = null
        file?.delete()
        file = null
        releaseCamera()
        mutable.value = VideoRecordingState()
    }

    private fun releaseCamera() {
        val cases = listOfNotNull(preview, capture)
        if (cases.isNotEmpty()) provider?.unbind(*cases.toTypedArray())
        preview = null
        capture = null
    }

    override fun close() = cancel()
}
