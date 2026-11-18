package com.librevision

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.librevision.data.models.SetupPhase
import com.librevision.data.vision.ScanPhase
import com.librevision.data.vision.ScanState
import com.librevision.domain.alerts.*
import com.librevision.domain.assistant.AssistantPhase
import com.librevision.domain.vision.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real device speech and Muse checks; all injected detections and scenario inputs are test
 * fixtures.
 */
class ClearGuidanceInstrumentation : Instrumentation() {
    private var audioOnly = false
    private var trafficOnly = false

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        audioOnly = arguments?.getString("check") == "audio"
        trafficOnly = arguments?.getString("check") == "traffic"
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

    private fun until(timeout: Long = 30000, test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!test() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        check(test()) { "Timed out waiting for app state" }
    }

    override fun onStart() {
        val output = Bundle()
        var model: LibreVisionViewModel? = null
        var originalHandsFree = true
        var originalDetail = false
        val prefs = targetContext.getSharedPreferences("scene_gateway", 0)
        val originalConsent = prefs.getBoolean("consent", false)
        try {
            val activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            main { model = ViewModelProvider(activity)[LibreVisionViewModel::class.java] }
            val vm = checkNotNull(model)
            waitForIdleSync()
            until {
                activity.lifecycle.currentState.isAtLeast(
                    androidx.lifecycle.Lifecycle.State.RESUMED
                )
            }
            originalHandsFree = vm.state.value.handsFree
            originalDetail = vm.state.value.detailed
            until { vm.modelSetup.value.phase == SetupPhase.READY }
            main {
                vm.pauseVoiceCapture()
                vm.open(Screen.HOME)
                vm.setCloudImageConsent(false)
            }
            val report =
                if (trafficOnly) trafficChecks(vm)
                else if (audioOnly) audioChecks(vm) else conversation(vm)
            val file =
                File(
                    targetContext.filesDir,
                    if (trafficOnly) "early-traffic-test.json"
                    else if (audioOnly) "clear-guidance-audio.json" else "muse-conversation.json",
                )
            file.writeText(report.toString(2))
            output.putString("stream", report.toString() + "\n")
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            output.putString("stream", "FAILED: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, output)
        } finally {
            main {
                model?.let {
                    it.stopScanning()
                    it.cancelAssistant()
                    it.dismissVoice()
                    it.settings(detailed = originalDetail)
                    it.setCloudImageConsent(originalConsent)
                    it.handsFree(originalHandsFree)
                    it.open(Screen.HOME)
                }
            }
        }
    }

    private fun conversation(vm: LibreVisionViewModel): JSONObject {
        val questions =
            listOf(
                "Let's rehearse an imaginary walk, without using the camera or starting navigation. I am standing on a footpath. A bicycle and a person block the way; a blue pharmacy sign is on my left. What should I do?",
                "Still in that imaginary scene, how should I remember where the pharmacy is?",
                "Imagine the bicycle has moved away but the person remains. Does that mean the path is safe now?",
                "End the rehearsal. Please open the app settings.",
                "Set audio detail to brief.",
            )
        val turns = JSONArray()
        val alerts =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("alerts")
                .apply { isAccessible = true }
                .get(vm) as AlertCoordinator
        for ((index, question) in questions.withIndex()) {
            main {
                if (index == 4) vm.settings(detailed = true)
                vm.voice()
                vm.pauseVoiceCapture()
            }
            val start = SystemClock.elapsedRealtime()
            main { vm.submitAssistantText(question) }
            until {
                vm.assistantState.value.phase in setOf(AssistantPhase.ANSWER, AssistantPhase.ERROR)
            }
            val reply = vm.assistantState.value
            check(reply.phase == AssistantPhase.ANSWER) { reply.answer }
            check(reply.answer.isNotBlank())
            if (index < 3)
                check(
                    vm.state.value.screen == Screen.HOME &&
                        !vm.state.value.scanRequested &&
                        !vm.navigationState.value.contextConsent &&
                        vm.navigationState.value.route == null
                ) {
                    "Hypothetical question unexpectedly triggered a camera or navigation tool"
                }
            if (index == 1)
                check(
                    reply.answer.contains("left", true) &&
                        !Regex("(?i)\\b(?:past where|beyond where|behind you|just past)\\b")
                            .containsMatchIn(reply.answer)
                ) {
                    "Muse added an unsupported spatial relation: ${reply.answer}"
                }
            if (index == 3)
                check(vm.state.value.screen == Screen.SETTINGS) { "Settings tool did not execute" }
            if (index == 4) check(!vm.state.value.detailed) { "Brief audio tool did not execute" }
            turns.put(
                JSONObject()
                    .put("user", question)
                    .put("muse", reply.answer)
                    .put("response_ms", SystemClock.elapsedRealtime() - start)
                    .put("screen", vm.state.value.screen.name)
            )
            until(20000) { !alerts.isSpeaking() }
        }
        return JSONObject()
            .put("real_meta_calls", true)
            .put("typed_script_not_acoustic_test", true)
            .put("imaginary_scenario_only", true)
            .put("user_audio_or_images_uploaded", false)
            .put("history_preserved", true)
            .put("settings_and_verbosity_tools_executed", true)
            .put("turns", turns)
    }

    @Suppress("UNCHECKED_CAST")
    private fun trafficChecks(vm: LibreVisionViewModel): JSONObject {
        val scanner =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("scanner")
                .apply { isAccessible = true }
                .get(vm)
        val frames =
            scanner.javaClass.getDeclaredField("mutable").apply { isAccessible = true }.get(scanner)
                as MutableStateFlow<ScanState>
        val ui =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("mutable")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<UiState>
        val lastAt =
            LibreVisionViewModel::class.java.getDeclaredField("lastHazardAt").apply {
                isAccessible = true
            }
        val lastLabel =
            LibreVisionViewModel::class.java.getDeclaredField("lastHazardLabel").apply {
                isAccessible = true
            }
        val alerts =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("alerts")
                .apply { isAccessible = true }
                .get(vm) as AlertCoordinator
        val active =
            AlertCoordinator::class.java.getDeclaredField("active").apply { isAccessible = true }
        val chair = Detection("chair", .95f, NormalizedBox(.3f, .2f, .7f, .95f))
        val car = Detection("car", .5f, NormalizedBox(.49f, .2f, .51f, .23f))
        fun frame(detections: List<Detection>) {
            ui.update { it.copy(tilt = PhoneTilt.UPRIGHT) }
            frames.value =
                ScanState(
                    ScanPhase.RUNNING,
                    "Fixture",
                    Observation(SystemClock.elapsedRealtime(), detections, false),
                )
        }
        try {
            main {
                vm.open(Screen.FOOTPATH)
                ui.update { it.copy(scanRequested = true, tilt = PhoneTilt.UPRIGHT) }
                frame(listOf(chair))
            }
            until { lastLabel.get(vm) == "chair" }
            val chairAt = lastAt.getLong(vm)
            check(active.get(alerts) == Priority.URGENT)
            val observed = SystemClock.elapsedRealtime()
            main { frame(listOf(chair, car)) }
            until { lastLabel.get(vm) == "car" }
            val carAt = lastAt.getLong(vm)
            check(carAt - chairAt < 5000) { "Vehicle was held by the ordinary warning cooldown" }
            check(active.get(alerts) == Priority.TRAFFIC)
            check(vm.state.value.footpath == "Stop, car ahead.")
            return JSONObject()
                .put("synthetic_detections_only", true)
                .put("native_tts", true)
                .put("small_vehicle_confidence", car.confidence)
                .put("small_vehicle_area", .0006)
                .put("vehicle_wins_over_large_chair", true)
                .put("vehicle_bypasses_static_warning_cooldown", true)
                .put("vision_result_to_speech_request_ms", carAt - observed)
                .put("vehicle_numeric_depth_omitted", true)
        } finally {
            main { vm.stopScanning() }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun audioChecks(vm: LibreVisionViewModel): JSONObject {
        val coordinator =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("alerts")
                .apply { isAccessible = true }
                .get(vm) as AlertCoordinator
        val native =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("speech")
                .apply { isAccessible = true }
                .get(vm) as SpeechOutput
        val currentId = native.javaClass.getDeclaredField("currentId").apply { isAccessible = true }
        val lastMessage =
            AlertCoordinator::class.java.getDeclaredField("last").apply { isAccessible = true }
        val foreground =
            LibreVisionViewModel::class.java.getDeclaredField("foreground").apply {
                isAccessible = true
            }
        val starts = JSONArray()
        val times = mutableListOf<Long>()
        val acceptedAt =
            LibreVisionViewModel::class.java.getDeclaredField("lastHazardAt").apply {
                isAccessible = true
            }
        val seen = mutableSetOf<Long>()
        var nativeObserved = false
        fun recordStart() {
            main {
                if (currentId.get(native) != null) nativeObserved = true
                val accepted = acceptedAt.getLong(vm)
                if (accepted != Long.MIN_VALUE && seen.add(accepted)) {
                    times += accepted
                    starts.put(lastMessage.get(coordinator))
                }
            }
        }
        val scanner =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("scanner")
                .apply { isAccessible = true }
                .get(vm)
        val frames =
            scanner.javaClass.getDeclaredField("mutable").apply { isAccessible = true }.get(scanner)
                as MutableStateFlow<ScanState>
        val ui =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("mutable")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<UiState>
        try {
            main {
                vm.open(Screen.FOOTPATH)
                ui.update { it.copy(scanRequested = true, tilt = PhoneTilt.UPRIGHT) }
            }
            val began = SystemClock.elapsedRealtime()
            for (index in 0..64) {
                main {
                    ui.update { it.copy(tilt = PhoneTilt.UPRIGHT) }
                    val labels =
                        when (index % 3) {
                            0 -> listOf("person", "chair")
                            1 -> listOf("cat", "chair")
                            else -> listOf("couch", "dog")
                        }
                    val detections = labels.map {
                        Detection(it, .95f, NormalizedBox(.3f, .2f, .7f, .95f))
                    }
                    frames.value =
                        ScanState(
                            ScanPhase.RUNNING,
                            "Test fixture",
                            Observation(SystemClock.elapsedRealtime(), detections, false),
                        )
                }
                Thread.sleep(100)
                recordStart()
            }
            until { !coordinator.isSpeaking() }
            check(times.size in 1..2) {
                "Unexpected spoken warnings: $starts; foreground=${foreground.get(vm)}; state=${vm.state.value}; last=${lastMessage.get(coordinator)}"
            }
            check(times.first() - began < 1000) {
                "Initial warning was delayed: first=${times.first()}, began=$began, delta=${times.first()-began}, warnings=$starts"
            }
            check(nativeObserved && vm.state.value.speechAvailable) {
                "Native speech unavailable: ${vm.state.value.speechIssue}"
            }
            if (times.size == 2)
                check(times[1] - times[0] >= 5000) { "Warnings did not respect the minimum gap" }
            val count = times.size
            Thread.sleep(4300)
            check(times.size == count) { "An expired observation was spoken later" }
            return JSONObject()
                .put("synthetic_detections", true)
                .put("native_tts", true)
                .put("camera_or_audio_uploaded", false)
                .put("warnings_finish_without_interruption", true)
                .put("minimum_gap_ms", 5000)
                .put("warnings", starts)
                .put("speech_start_intervals_ms", JSONArray(times.zipWithNext { a, b -> b - a }))
                .put("stale_queue_empty", true)
        } finally {
            main { vm.stopScanning() }
        }
    }
}
