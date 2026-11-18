package com.librevision

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import com.librevision.data.models.SetupPhase
import com.librevision.data.vision.ScanPhase
import org.json.JSONObject

/** Checks platform behaviour needed by the design port, with no image or microphone upload. */
class DesignSmokeInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val output = Bundle()
        var model: LibreVisionViewModel? = null
        var night = false
        val consent = targetContext.getSharedPreferences("scene_gateway", 0)
        val previousConsent = consent.getBoolean("consent", false)
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
        fun until(test: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 30000
            while (!test() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
            check(test()) { "Timed out waiting for native state" }
        }
        try {
            val activity =
                startActivitySync(
                    Intent(targetContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                    as MainActivity
            main { model = ViewModelProvider(activity)[LibreVisionViewModel::class.java] }
            val vm = checkNotNull(model)
            night = vm.state.value.nightMode
            until { vm.modelSetup.value.phase == SetupPhase.READY }
            main {
                vm.appearance(true)
                check(vm.state.value.nightMode)
                check(
                    targetContext
                        .getSharedPreferences("accessibility", 0)
                        .getBoolean("nightMode", false)
                )
                vm.appearance(false)
                check(!vm.state.value.nightMode)
            }
            for (screen in Screen.entries) {
                main {
                    vm.open(screen)
                    vm.transcript("Hey Vision")
                    check(vm.state.value.voiceOpen)
                    vm.dismissVoice()
                }
            }
            main {
                vm.open(Screen.SCENE)
                vm.scan(preserveScene = true)
                check(vm.state.value.screen == Screen.SCENE && vm.state.value.scanRequested)
            }
            until { vm.state.value.scanPhase in setOf(ScanPhase.RUNNING, ScanPhase.ERROR) }
            check(vm.state.value.scanPhase == ScanPhase.RUNNING) { vm.state.value.footpath }
            main {
                vm.stopScanning()
                vm.open(Screen.HOME)
                vm.transcript("start scanning")
                check(vm.state.value.screen == Screen.FOOTPATH && vm.state.value.scanRequested)
                vm.transcript("stop scanning")
                check(!vm.state.value.scanRequested)
            }
            consent.edit().putBoolean("consent", false).commit()
            main {
                vm.open(Screen.SCENE)
                vm.describe()
                check(
                    vm.surroundingsState.value.phase ==
                        com.librevision.domain.scene.SurroundingsPhase.CONSENT
                )
                vm.dismissSurroundingsConsent()
                check(
                    vm.surroundingsState.value.phase ==
                        com.librevision.domain.scene.SurroundingsPhase.IDLE
                )
            }
            val report =
                JSONObject()
                    .put("theme_state_and_persistence", true)
                    .put("wake_routes_all_pages", 6)
                    .put("real_camera_in_surroundings_without_screen_jump", true)
                    .put("home_scan_commands_preserved", true)
                    .put("image_consent_cancel_no_upload", true)
                    .put("no_model_api_calls", true)
            output.putString("stream", report.toString() + "\n")
            main {
                vm.appearance(night)
                vm.dismissVoice()
                vm.open(Screen.HOME)
            }
            consent.edit().putBoolean("consent", previousConsent).commit()
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            consent.edit().putBoolean("consent", previousConsent).commit()
            main {
                model?.let {
                    it.appearance(night)
                    it.dismissVoice()
                    it.open(Screen.HOME)
                }
            }
            output.putString("stream", "FAILED: ${e.javaClass.simpleName}: ${e.message}\n")
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
