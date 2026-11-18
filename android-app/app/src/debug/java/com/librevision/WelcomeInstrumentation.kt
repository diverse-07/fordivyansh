package com.librevision

import android.app.*
import android.content.*
import android.graphics.Rect
import android.os.*
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelProvider
import com.librevision.core.design.*
import com.librevision.data.models.*
import com.librevision.domain.alerts.AlertCoordinator
import com.librevision.feature.welcome.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.*

/** Cached-install/tour checks, isolated fresh downloads, and accessible layout fixtures. */
class WelcomeInstrumentation : Instrumentation() {
    private var mode = "flow"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        mode = arguments?.getString("mode") ?: "flow"
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

    private fun until(timeout: Long = 40000, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!condition() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
        check(condition()) { "Native welcome state timed out" }
    }

    override fun onStart() {
        val output = Bundle()
        val report = JSONObject()
        var code = Activity.RESULT_OK
        val prefs = targetContext.getSharedPreferences("welcome", 0)
        val original = prefs.all
        var vm: LibreVisionViewModel? = null
        var oldAudio = false
        try {
            if (mode == "fresh") fresh(report)
            else {
                prefs.edit().clear().commit()
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
                    vm = ViewModelProvider(activity)[LibreVisionViewModel::class.java]
                }
                val model = checkNotNull(vm)
                oldAudio = model.state.value.audioFallback
                main { model.audioFallback(false) }
                if (mode == "ui") review(activity, model, report) else flow(model, report)
            }
        } catch (e: Throwable) {
            code = Activity.RESULT_CANCELED
            report.put("failure", e.stackTraceToString())
        } finally {
            main {
                vm?.let {
                    it.pauseTour()
                    it.stopAudio()
                    it.audioFallback(oldAudio)
                }
            }
            val edit = prefs.edit().clear()
            for ((key, value) in original) when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is String -> edit.putString(key, value)
                is Long -> edit.putLong(key, value)
            }
            edit.commit()
        }
        File(targetContext.filesDir, "welcome-$mode.json").writeText(report.toString(2))
        output.putString("stream", report.toString() + "\n")
        finish(code, output)
    }

    private fun flow(vm: LibreVisionViewModel, report: JSONObject) {
        check(vm.welcome.value.stage == WelcomeStage.WELCOME)
        check(!vm.listening.value.sessionActive)
        val progress = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val job = scope.launch { vm.modelSetup.collect { progress += it.percentage } }
        main { vm.beginWelcome() }
        until { vm.welcome.value.stage == WelcomeStage.TOUR_OFFER }
        check(
            vm.modelSetup.value.phase == SetupPhase.READY && vm.modelSetup.value.percentage == 100
        )
        check(vm.permissionBatchRequested() && !vm.claimWelcomePermissions())
        check(progress.zipWithNext().all { (a, b) -> b >= a })
        check(!vm.listening.value.sessionActive)
        report
            .put("cached_preparation_percentages", JSONArray(progress.toList()))
            .put("permission_request_once", true)
        if (mode == "setup") {
            main {
                vm.startTour()
                vm.nextTour()
                vm.finishWelcome()
                vm.transcript("Hey Vision")
            }
            check(vm.welcome.value.stage == WelcomeStage.COMPLETE)
            check(vm.state.value.voiceOpen) { "Wake was suppressed by the tour narration" }
            main { vm.dismissVoice() }
            report.put("wake_immediately_after_skipping_voice_lesson", true)
            job.cancel()
            scope.cancel()
            report.put("skip_tour_to_home", true)
            return
        }
        main { vm.startTour() }
        until { vm.welcome.value.step == 1 && vm.welcome.value.stage == WelcomeStage.TOUR }
        main { vm.pauseTour() }
        val paused = vm.welcome.value.step
        Thread.sleep(1000)
        check(vm.welcome.value.step == paused && vm.welcome.value.paused)
        main { vm.repeatTour() }
        val alerts =
            LibreVisionViewModel::class
                .java
                .getDeclaredField("alerts")
                .apply { isAccessible = true }
                .get(vm) as AlertCoordinator
        until { !alerts.isSpeaking() }
        check(vm.welcome.value.step == paused && vm.welcome.value.paused)
        main { vm.nextTour() }
        check(vm.welcome.value.step == 2 && vm.welcome.value.paused)
        main { vm.resumeTour() }
        var last = -1
        val end = SystemClock.elapsedRealtime() + 220000
        while (vm.welcome.value.stage == WelcomeStage.TOUR && SystemClock.elapsedRealtime() < end) {
            val state = vm.welcome.value
            check(!state.paused) { "Tour paused unexpectedly: ${vm.state.value.speechIssue}" }
            check(!vm.listening.value.sessionActive)
            check(!vm.state.value.scanRequested)
            if (state.step != last) {
                last = state.step
                sendStatus(
                    1,
                    Bundle().apply {
                        putString("stream", "Spoken lesson ${last+1}/${WelcomeFlow.lessons.size}\n")
                    },
                )
            }
            Thread.sleep(150)
        }
        check(vm.welcome.value.stage == WelcomeStage.COMPLETE)
        check(targetContext.getSharedPreferences("welcome", 0).getBoolean("completed", false))
        main { vm.startTour() }
        check(vm.welcome.value.step == 0 && vm.welcome.value.stage == WelcomeStage.TOUR)
        main { vm.finishWelcome() }
        check(
            vm.welcome.value.stage == WelcomeStage.COMPLETE && vm.state.value.screen == Screen.HOME
        )
        report
            .put("native_tts_auto_advancement", true)
            .put("pause_repeat_resume_next_skip_replay", true)
            .put("lessons", WelcomeFlow.lessons.size)
            .put("microphone_off_during_tour", true)
            .put("camera_and_network_features_not_started", true)
        job.cancel()
        scope.cancel()
    }

    private fun fresh(report: JSONObject) = runBlocking {
        val root =
            File(targetContext.cacheDir, "welcome-fresh-install").apply {
                deleteRecursively()
                mkdirs()
            }
        fun folder(name: String) = File(root, name).apply { mkdirs() }
        val isolated =
            object : ContextWrapper(targetContext) {
                override fun getFilesDir() = folder("files")

                override fun getNoBackupFilesDir() = folder("no-backup")

                override fun getCacheDir() = folder("cache")
            }
        val manager = AppModelManager(isolated)
        val values = mutableListOf<Int>()
        val start = SystemClock.elapsedRealtime()
        try {
            manager.prepare()
            withTimeout(600000) {
                manager.state.first { state ->
                    values += state.percentage
                    if (values.size == 1 || values.last() != values[values.lastIndex - 1])
                        sendStatus(
                            1,
                            Bundle().apply {
                                putString("stream", "Fresh setup ${state.percentage}%\n")
                            },
                        )
                    state.phase != SetupPhase.PREPARING
                }
            }
            check(manager.state.value.phase == SetupPhase.READY) { manager.state.value.message }
            check(values.zipWithNext().all { (a, b) -> b >= a } && values.last() == 100)
            check(
                !manager.state.value.message.contains("RF-DETR") &&
                    !manager.state.value.message.contains("Vosk")
            )
            val files = manager.readyFiles()
            check(
                files.vision.isDirectory &&
                    files.english.isDirectory &&
                    files.vad.isFile &&
                    files.depth.isFile
            )
            report
                .put("fresh_download_ms", SystemClock.elapsedRealtime() - start)
                .put("progress", JSONArray(values))
                .put(
                    "installed_bytes",
                    root.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                )
                .put("isolated_from_owner_models", true)
        } finally {
            manager.close()
            root.deleteRecursively()
        }
    }

    private fun find(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (node.text?.toString() == label || node.contentDescription?.toString() == label)
            return node
        for (i in 0 until node.childCount) node.getChild(i)?.let {
            find(it, label)?.let {
                return it
            }
        }
        return null
    }

    private fun review(activity: MainActivity, vm: LibreVisionViewModel, report: JSONObject) {
        main { vm.stopAudio() }
        waitForIdleSync()
        Thread.sleep(700)
        val actual = checkNotNull(uiAutomation.takeScreenshot())
        File(targetContext.filesDir, "welcome-native-root.png").outputStream().use {
            actual.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        actual.recycle()
        val cases =
            mutableListOf(
                "welcome" to WelcomeState(),
                "permissions" to WelcomeState(WelcomeStage.PERMISSIONS),
                "download-0" to WelcomeState(WelcomeStage.DOWNLOAD),
                "download-47" to WelcomeState(WelcomeStage.DOWNLOAD, percentage = 47),
                "download-99" to WelcomeState(WelcomeStage.DOWNLOAD, percentage = 99),
                "retry" to WelcomeState(WelcomeStage.DOWNLOAD, percentage = 32, failed = true),
                "tour-offer" to WelcomeState(WelcomeStage.TOUR_OFFER),
                "denied" to
                    WelcomeState(
                        WelcomeStage.TOUR_OFFER,
                        denied = listOf("Camera", "Microphone", "Location"),
                    ),
                "paused" to WelcomeState(WelcomeStage.TOUR, step = 2, paused = true),
            )
        WelcomeFlow.lessons.indices.forEach {
            cases += ("tour-$it" to WelcomeState(WelcomeStage.TOUR, step = it))
        }
        var selected by mutableStateOf(cases.first())
        var dark by mutableStateOf(false)
        var scale by mutableStateOf(1f)
        main {
            activity.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    LibreVisionTheme(false, dark) {
                        Surface(Modifier.fillMaxSize(), color = Background) {
                            key(selected.first, scale, dark) {
                                WelcomeScreen(
                                    selected.second,
                                    {},
                                    { false },
                                    {},
                                    {},
                                    {},
                                    {},
                                    {},
                                    {},
                                    {},
                                    {},
                                )
                            }
                        }
                    }
                }
            }
        }
        val folder = File(targetContext.filesDir, "welcome-ui").apply { mkdirs() }
        val launcher = targetContext.packageManager.getApplicationIcon(targetContext.packageName)
        check(launcher is android.graphics.drawable.AdaptiveIconDrawable)
        fun saveIcon(drawable: android.graphics.drawable.Drawable, name: String) {
            val bitmap =
                android.graphics.Bitmap.createBitmap(
                    512,
                    512,
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
            drawable.setBounds(0, 0, 512, 512)
            drawable.draw(android.graphics.Canvas(bitmap))
            File(folder, name).outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        saveIcon(launcher, "launcher.png")
        if (Build.VERSION.SDK_INT >= 33) {
            val mono = checkNotNull(launcher.monochrome)
            saveIcon(mono, "launcher-monochrome.png")
        }
        report.put("adaptive_and_themed_launcher", true)

        val failures = JSONArray()
        var count = 0
        for (font in listOf(1f, 1.6f, 2.4f)) for (night in listOf(false, true)) for (shot in
            cases) {
            main {
                scale = font
                dark = night
                selected = shot
                androidx.core.view
                    .WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .apply {
                        isAppearanceLightStatusBars = !night
                        isAppearanceLightNavigationBars = !night
                    }
            }
            waitForIdleSync()
            Thread.sleep(250)
            val name = "${if(night)"night" else "field"}-$font-${shot.first}"
            val bitmap = checkNotNull(uiAutomation.takeScreenshot())
            File(folder, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
            if (Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
            val root = uiAutomation.rootInActiveWindow
            val required =
                when (shot.second.stage) {
                    WelcomeStage.WELCOME -> listOf("Get started")
                    WelcomeStage.DOWNLOAD ->
                        if (shot.second.failed) listOf("Retry")
                        else listOf("${shot.second.percentage}%")
                    WelcomeStage.TOUR_OFFER -> listOf("Take the tour", "Skip")
                    WelcomeStage.TOUR ->
                        listOf(
                            if (shot.second.paused) "Resume" else "Pause",
                            if (shot.second.step == WelcomeFlow.lessons.lastIndex) "Finish"
                            else "Next",
                            "Repeat",
                            "Skip",
                        )
                    else -> emptyList()
                }
            val screen =
                Rect(
                    0,
                    0,
                    activity.resources.displayMetrics.widthPixels,
                    activity.resources.displayMetrics.heightPixels,
                )
            val missing = required.filter { label ->
                val node = root?.let { find(it, label) }
                val bounds = Rect()
                node?.getBoundsInScreen(bounds)
                node == null || !node.isVisibleToUser || !screen.contains(bounds) || bounds.isEmpty
            }
            if (missing.isNotEmpty())
                failures.put(JSONObject().put("shot", name).put("missing", JSONArray(missing)))
            count++
        }
        main {
            vm.beginWelcome()
            vm.claimWelcomePermissions()
            vm.welcomePermissionsFinished()
        }
        until { vm.welcome.value.stage == WelcomeStage.TOUR_OFFER }
        main {
            vm.finishWelcome()
            vm.stopAudio()
        }
        for (night in listOf(false, true)) {
            main {
                activity.setContent {
                    LibreVisionTheme(false, night) {
                        Surface(color = Background, modifier = Modifier.fillMaxSize()) {
                            val homeState by vm.state.collectAsState()
                            com.librevision.feature.home.HomeScreen(vm, homeState)
                        }
                    }
                }
                androidx.core.view
                    .WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .apply {
                        isAppearanceLightStatusBars = !night
                        isAppearanceLightNavigationBars = !night
                    }
            }
            waitForIdleSync()
            Thread.sleep(600)
            val home = checkNotNull(uiAutomation.takeScreenshot())
            File(folder, "home-brand-${if(night)"night" else "field"}.png").outputStream().use {
                home.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            home.recycle()
        }
        report.put("screenshots", count).put("failures", failures)
        check(failures.length() == 0) { "Welcome layout controls clipped" }
    }
}
