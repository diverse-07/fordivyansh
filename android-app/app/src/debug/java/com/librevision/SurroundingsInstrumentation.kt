package com.librevision

import android.app.*
import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.*
import androidx.lifecycle.ViewModelProvider
import com.librevision.data.models.SetupPhase
import com.librevision.data.remote.*
import com.librevision.data.scene.*
import com.librevision.domain.assistant.*
import com.librevision.domain.scene.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*

/** Real silent camera recording; remote benchmark uses only a labelled generated video fixture. */
class SurroundingsInstrumentation : Instrumentation() {
    private var remote = false
    private var flowOnly = false
    private var headless = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        remote = arguments?.getString("remote") == "true"
        flowOnly = arguments?.getString("flowOnly") == "true"
        headless = arguments?.getString("headless") == "true"
        start()
    }

    private fun main(block: () -> Unit) {
        var error: Throwable? = null
        runOnMainSync {
            try {
                block()
            } catch (e: Throwable) {
                error = e
            }
        }
        error?.let { throw it }
    }

    private fun until(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30000
        while (!check() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        check(check()) { "Timed out waiting for native state" }
    }

    private fun audioTracks(file: File): Int {
        val reader = MediaExtractor()
        try {
            reader.setDataSource(file.absolutePath)
            return (0 until reader.trackCount).count {
                reader.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") ==
                    true
            }
        } finally {
            reader.release()
        }
    }

    private fun checkAppFlow(vm: LibreVisionViewModel, session: SurroundingsSession) {
        val field =
            SurroundingsSession::class.java.getDeclaredField("muse").apply { isAccessible = true }
        val original = field.get(session)
        val consent = vm.state.value.audioFallback
        val requests = mutableListOf<AssistantRequest>()
        val controller =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("voiceCommands")
                .apply { isAccessible = true }
                .get(vm) as com.librevision.data.speech.VoiceCommandController
        val alerts =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("alerts")
                .apply { isAccessible = true }
                .get(vm) as com.librevision.domain.alerts.AlertCoordinator
        val fake = MuseAssistant { request ->
            requests += request
            delay(80)
            Result.success(AssistantReply("Test reply: a sign is visible in the recorded video."))
        }
        try {
            main {
                vm.audioFallback(false)
                vm.open(Screen.SCENE)
                field.set(session, fake)
                vm.describe()
                check(session.state.value.phase == SurroundingsPhase.CONSENT)
                vm.transcript("start recording")
            }
            until {
                session.state.value.phase in
                    setOf(SurroundingsPhase.CAPTURING, SurroundingsPhase.ERROR)
            }
            check(session.state.value.phase == SurroundingsPhase.CAPTURING)
            Thread.sleep(1800)
            main { vm.transcript("finish recording") }
            until {
                session.state.value.phase in setOf(SurroundingsPhase.READY, SurroundingsPhase.ERROR)
            }
            check(session.state.value.phase == SurroundingsPhase.READY) {
                session.state.value.instruction
            }
            until { controller.awaitingCommand() && !alerts.isSpeaking() }
            check(requests.single().surroundings?.views?.size == 12)
            main { vm.transcript("Which sign was visible?") }
            until { requests.size == 2 && session.state.value.phase == SurroundingsPhase.READY }
            check(requests.last().history.size == 2)
            check(File(targetContext.cacheDir, "scene-video").listFiles().orEmpty().isEmpty())
        } finally {
            main {
                vm.pauseVoiceCapture()
                vm.open(Screen.HOME)
                field.set(session, original)
                vm.audioFallback(consent)
            }
        }
    }

    override fun onStart() {
        val result = Bundle()
        val report = JSONObject().put("motion_tracking_required", false)
        var model: LibreVisionViewModel? = null
        var handsFree = true
        var code = Activity.RESULT_OK
        try {
            if (!headless) {
                val activity =
                    startActivitySync(
                        Intent(targetContext, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                        as MainActivity
                main {
                    activity.window.addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    )
                    model = ViewModelProvider(activity)[LibreVisionViewModel::class.java]
                }
                val vm = checkNotNull(model)
                until { vm.modelSetup.value.phase == SetupPhase.READY }
                handsFree = vm.state.value.handsFree
                main {
                    vm.pauseVoiceCapture()
                    vm.stopAudio()
                    vm.open(Screen.SCENE)
                }
                val recorder =
                    LibreVisionViewModel::class
                        .java
                        .getDeclaredField("sceneVideo")
                        .apply { isAccessible = true }
                        .get(vm) as SceneVideoRecorder
                val session =
                    LibreVisionViewModel::class
                        .java
                        .getDeclaredField("surroundingsSession")
                        .apply { isAccessible = true }
                        .get(vm) as SurroundingsSession
                val rows = JSONArray()
                for (length in if (flowOnly) emptyList() else listOf(8000L, 16000L)) {
                    main { session.preparing() }
                    val recording = runBlocking {
                        val pending = async(Dispatchers.Main) { recorder.record() }
                        withTimeout(20000) {
                            recorder.state.first { it.phase == VideoPhase.RECORDING }
                        }
                        delay(length)
                        withContext(Dispatchers.Main) { recorder.finish() }
                        pending.await()
                    }
                    main { session.extracting() }
                    try {
                        check(audioTracks(recording.file) == 0)
                        val rowsForClip = JSONArray()
                        for (setting in
                            listOf(
                                Triple(12, 512, false),
                                Triple(10, 512, false),
                                Triple(12, 384, false),
                                Triple(12, 512, true),
                            )) {
                            val frames = runBlocking {
                                VideoFrameExtractor()
                                    .extract(
                                        recording.file,
                                        recording.endedAtMs,
                                        setting.first,
                                        setting.second,
                                        setting.third,
                                    )
                            }
                            try {
                                check(frames.views.size == setting.first)
                                check(
                                    frames.views.zipWithNext().all { (a, b) ->
                                        a.offsetMs < b.offsetMs
                                    }
                                )
                                check(
                                    frames.views.all {
                                        maxOf(it.width, it.height) <= setting.second &&
                                            it.jpeg.size <= 48000
                                    }
                                )
                                rowsForClip.put(
                                    JSONObject()
                                        .put("frames", setting.first)
                                        .put("edge_px", setting.second)
                                        .put("two_candidates_per_chunk", setting.third)
                                        .put("extraction_ms", frames.extractionMs)
                                        .put("jpeg_bytes", frames.imageBytes)
                                        .put("video_ms", frames.durationMs)
                                )
                            } finally {
                                frames.views.forEach { it.jpeg.fill(0) }
                            }
                        }
                        rows.put(
                            JSONObject()
                                .put("requested_record_ms", length)
                                .put("video_bytes", recording.file.length())
                                .put("startup_ms", recording.startupMs)
                                .put("finalize_ms", recording.finalizeMs)
                                .put("audio_tracks", 0)
                                .put("variants", rowsForClip)
                        )
                    } finally {
                        recording.file.delete()
                    }
                }
                report.put("real_camera", rows).put("real_camera_frames_uploaded", false)
                main { session.preparing() }
                runBlocking {
                    val pending = async(Dispatchers.Main) { recorder.record() }
                    withTimeout(20000) { recorder.state.first { it.phase == VideoPhase.RECORDING } }
                    delay(700)
                    withContext(Dispatchers.Main) { recorder.cancel() }
                    try {
                        pending.await()
                        error("Cancelled recording returned a file")
                    } catch (expected: CancellationException) {}
                }
                Thread.sleep(300)
                check(File(targetContext.cacheDir, "scene-video").listFiles().orEmpty().isEmpty())
                report.put("cancel_deletes_video", true)
                checkAppFlow(vm, session)
                report.put("app_record_finish_description_followup", true)
            }
            val fixture = File(targetContext.cacheDir, "benchmark-video.mp4")
            if (remote) {
                check(fixture.exists()) { "Push the generated benchmark-video.mp4 fixture first." }
                val remoteRows = JSONArray()
                runBlocking {
                    for (count in listOf(10, 12)) {
                        val frames =
                            VideoFrameExtractor()
                                .extract(fixture, SystemClock.elapsedRealtime(), count)
                        try {
                            val context =
                                SurroundingsContext(frames.views, 0, true, frames.durationMs)
                            val start = SystemClock.elapsedRealtime()
                            val answer =
                                GatewayMuseAssistant(GatewayPreferences(targetContext))
                                    .ask(
                                        AssistantRequest(
                                            "Describe useful surroundings in this labelled synthetic test video. Be concise.",
                                            "{\"syntheticTest\":true}",
                                            surroundings = context,
                                        )
                                    )
                                    .getOrThrow()
                            check(!answer.text.isNullOrBlank() && answer.action == null)
                            remoteRows.put(
                                JSONObject()
                                    .put("frames", count)
                                    .put("extraction_ms", frames.extractionMs)
                                    .put("jpeg_bytes", frames.imageBytes)
                                    .put(
                                        "request_estimated_base64_bytes",
                                        (frames.imageBytes * 4L + 2) / 3,
                                    )
                                    .put("api_ms", SystemClock.elapsedRealtime() - start)
                                    .put("output_tokens", answer.outputTokens)
                                    .put("answer", answer.text)
                            )
                            if (count == 12) {
                                val followAt = SystemClock.elapsedRealtime()
                                val follow =
                                    GatewayMuseAssistant(GatewayPreferences(targetContext))
                                        .ask(
                                            AssistantRequest(
                                                "Which sign appeared last in the video?",
                                                "{\"syntheticTest\":true}",
                                                listOf(
                                                    AssistantTurn("user", "Describe this video"),
                                                    AssistantTurn("assistant", answer.text!!),
                                                ),
                                                surroundings = context.copy(initial = false),
                                            )
                                        )
                                        .getOrThrow()
                                report.put(
                                    "followup",
                                    JSONObject()
                                        .put("ms", SystemClock.elapsedRealtime() - followAt)
                                        .put("answer", follow.text)
                                        .put("output_tokens", follow.outputTokens),
                                )
                            }
                        } finally {
                            frames.views.forEach { it.jpeg.fill(0) }
                        }
                    }
                }
                fixture.delete()
                report.put("remote_generated_fixture_only", remoteRows)
            }
        } catch (e: Throwable) {
            code = Activity.RESULT_CANCELED
            report.put("failure", e.stackTraceToString())
        } finally {
            main {
                model?.let {
                    it.cancelSurroundingsCapture()
                    it.open(Screen.HOME)
                    it.handsFree(handsFree)
                }
            }
        }
        File(targetContext.filesDir, "video-benchmark.json").writeText(report.toString(2))
        result.putString("stream", report.toString() + "\n")
        finish(code, result)
    }
}
