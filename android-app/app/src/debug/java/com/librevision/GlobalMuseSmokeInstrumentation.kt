package com.librevision

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import com.librevision.domain.assistant.AssistantPhase
import org.json.JSONObject

/**
 * Injects decoded transcripts through the production router; does not claim acoustic recognition
 * coverage.
 */
class GlobalMuseSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = Bundle()
        var model: LibreVisionViewModel? = null
        var originalLarge = false
        val prefs = targetContext.getSharedPreferences("scene_gateway", 0)
        val originalConsent = prefs.getBoolean("consent", false)
        fun main(block: () -> Unit) {
            var failure: Throwable? = null
            runOnMainSync {
                try {
                    block()
                } catch (e: Throwable) {
                    failure = e
                }
            }
            failure?.let { throw it }
        }
        try {
            val activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            main {
                model = ViewModelProvider(activity)[LibreVisionViewModel::class.java]
                originalLarge = model!!.state.value.largeText
            }
            val vm = checkNotNull(model)
            val readyDeadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (
                vm.modelSetup.value.phase != com.librevision.data.models.SetupPhase.READY &&
                    android.os.SystemClock.elapsedRealtime() < readyDeadline
            ) Thread.sleep(100)
            check(vm.modelSetup.value.phase == com.librevision.data.models.SetupPhase.READY)
            waitForIdleSync()
            val report = JSONObject().put("decoded_transcripts_not_acoustic_test", true)
            for (screen in Screen.entries) {
                main {
                    vm.open(screen)
                    vm.transcript("Hey Vision")
                    check(vm.state.value.voiceOpen) {
                        "Wake ignored on $screen, lifecycle=${activity.lifecycle.currentState}"
                    }
                    vm.dismissVoice()
                }
            }
            report.put("wake_pages", Screen.entries.size)
            main {
                vm.open(Screen.HOME)
                vm.transcript("start scanning")
                check(vm.state.value.screen == Screen.FOOTPATH && vm.state.value.scanRequested)
                vm.transcript("stop scanning")
                check(!vm.state.value.scanRequested)
            }
            report.put("offline_scan_start_stop", true)
            prefs.edit().putBoolean("consent", false).commit()
            main {
                vm.open(Screen.HOME)
                vm.requestVisualQuestion("What is in front of me?")
                check(vm.navigationState.value.contextConsent)
                vm.dismissContextConsent()
            }
            report.put("global_image_consent_cancel_no_upload", true)
            main {
                vm.open(Screen.HOME)
                vm.transcript("Hey Vision, make the text larger")
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 26000
            while (
                android.os.SystemClock.elapsedRealtime() < deadline &&
                    vm.assistantState.value.phase == AssistantPhase.THINKING
            ) Thread.sleep(100)
            check(vm.assistantState.value.phase == AssistantPhase.ANSWER) {
                vm.assistantState.value.answer
            }
            check(vm.state.value.largeText) { "Muse did not execute text-size action" }
            report.put("real_muse_text_size_action", true)
            main {
                vm.open(Screen.TRAINING)
                vm.transcript("Could you set the text size to default")
            }
            val fallbackDeadline = android.os.SystemClock.elapsedRealtime() + 26000
            while (
                android.os.SystemClock.elapsedRealtime() < fallbackDeadline &&
                    vm.assistantState.value.phase == AssistantPhase.THINKING
            ) Thread.sleep(100)
            check(
                vm.assistantState.value.phase == AssistantPhase.ANSWER && !vm.state.value.largeText
            ) {
                "Natural fallback did not restore text size: ${vm.assistantState.value.answer}"
            }
            report.put("natural_fallback_without_wake_on_training", true)
            output.putString("stream", report.toString() + "\n")
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            output.putString("stream", "FAILED: ${e.javaClass.simpleName}: ${e.message}\n")
            finish(Activity.RESULT_CANCELED, output)
        } finally {
            prefs.edit().putBoolean("consent", originalConsent).commit()
            runOnMainSync {
                model?.let {
                    it.settings(large = originalLarge)
                    it.dismissVoice()
                    it.open(Screen.HOME)
                }
            }
        }
    }
}
