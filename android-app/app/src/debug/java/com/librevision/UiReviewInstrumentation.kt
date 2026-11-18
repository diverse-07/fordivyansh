package com.librevision

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.librevision.core.design.*
import com.librevision.data.models.SetupPhase
import com.librevision.data.scene.SurroundingsSession
import com.librevision.domain.assistant.*
import com.librevision.domain.navigation.*
import com.librevision.domain.scene.*
import com.librevision.domain.vision.*
import com.librevision.feature.home.MuseVoicePanel
import com.librevision.feature.navigation.NavigationUiState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Deterministic visual fixtures: no real route, model API request, or microphone upload. */
class UiReviewInstrumentation : Instrumentation() {
    private var group = "all"
    private var scales = listOf(1f, 1.6f, 2.4f)

    private data class Panel(
        val status: String,
        val answer: String = "",
        val busy: Boolean = false,
        val failed: Boolean = false,
    )

    private data class Shot(
        val name: String,
        val ui: UiState = UiState(),
        val nav: NavigationUiState = NavigationUiState(),
        val guide: GuidanceState = GuidanceState(),
        val panel: Panel? = null,
        val required: List<String> = emptyList(),
        val scene: SurroundingsState = SurroundingsState(),
    )

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        group = arguments?.getString("group") ?: "all"
        arguments?.getString("scales")?.let { scales = it.split(',').map(String::toFloat) }
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

    private fun find(node: AccessibilityNodeInfo, label: String): AccessibilityNodeInfo? {
        if (
            node.text?.toString()?.replace(Regex("\\s+"), " ") == label ||
                node.contentDescription?.toString() == label
        )
            return node
        for (i in 0 until node.childCount) node.getChild(i)?.let {
            find(it, label)?.let { found ->
                return found
            }
        }
        return null
    }

    private fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.className?.toString()?.contains("EditText") != true)
            return node
        for (i in 0 until node.childCount) node.getChild(i)?.let {
            scrollable(it)?.let { found ->
                return found
            }
        }
        return null
    }

    private fun hasEdit(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString()?.contains("EditText") == true ||
            (0 until node.childCount).any { node.getChild(it)?.let(::hasEdit) == true }

    override fun onStart() {
        val output = Bundle()
        val rows = JSONArray()
        val failures = JSONArray()
        var code = Activity.RESULT_OK
        var model: LibreVisionViewModel? = null
        var originalHandsFree = true
        var originalAudio = false
        try {
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
            val end = SystemClock.elapsedRealtime() + 30000
            while (
                vm.modelSetup.value.phase != SetupPhase.READY && SystemClock.elapsedRealtime() < end
            ) Thread.sleep(100)
            check(vm.modelSetup.value.phase == SetupPhase.READY)
            originalHandsFree = vm.state.value.handsFree
            originalAudio = vm.state.value.audioFallback
            main {
                vm.pauseVoiceCapture()
                vm.audioFallback(false)
                vm.dismissVoice()
                vm.open(Screen.HOME)
            }
            val navField =
                LibreVisionViewModel::class.java.getDeclaredField("nav").apply {
                    isAccessible = true
                }
            @Suppress("UNCHECKED_CAST")
            val nav = navField.get(vm) as MutableStateFlow<NavigationUiState>
            val engine =
                LibreVisionViewModel::class
                    .java
                    .getDeclaredField("navigation")
                    .apply { isAccessible = true }
                    .get(vm)
            @Suppress("UNCHECKED_CAST")
            val guide =
                WalkingGuidanceEngine::class
                    .java
                    .getDeclaredField("mutable")
                    .apply { isAccessible = true }
                    .get(engine) as MutableStateFlow<GuidanceState>
            val session =
                LibreVisionViewModel::class
                    .java
                    .getDeclaredField("surroundingsSession")
                    .apply { isAccessible = true }
                    .get(vm) as SurroundingsSession
            @Suppress("UNCHECKED_CAST")
            val sceneState =
                SurroundingsSession::class
                    .java
                    .getDeclaredField("mutable")
                    .apply { isAccessible = true }
                    .get(session) as MutableStateFlow<SurroundingsState>
            var selected by mutableStateOf(Shot("home"))
            var night by mutableStateOf(false)
            var scale by mutableStateOf(1f)
            main {
                activity.setContent {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(density.density, scale)
                    ) {
                        LibreVisionTheme(false, night) {
                            Surface(color = Background, modifier = Modifier.fillMaxSize()) {
                                key(selected.name, scale, night) {
                                    LibreVisionApp(vm, selected.ui.copy(voiceOpen = false))
                                }
                                key(selected.name, scale, night, "voice-panel") {
                                    selected.panel?.let { panel ->
                                        MuseVoicePanel(
                                            panel.status,
                                            panel.answer,
                                            panel.busy,
                                            panel.status == "Listening",
                                            panel.failed,
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
            }
            val folder = File(targetContext.filesDir, "ui-review").apply { mkdirs() }
            for (font in scales) for (dark in listOf(false, true)) {
                for (shot in cases().filter { group == "all" || it.name.startsWith(group) }) {
                    main {
                        scale = font
                        night = dark
                        sceneState.value = shot.scene
                        selected = shot
                        nav.value = shot.nav
                        guide.value = shot.guide
                        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                            .apply {
                                isAppearanceLightStatusBars = !dark
                                isAppearanceLightNavigationBars = !dark
                            }
                    }
                    waitForIdleSync()
                    Thread.sleep(if (shot.scene.phase == SurroundingsPhase.CONSENT) 800 else 250)
                    val name =
                        "${if(dark)"night"else "field"}-${font.toString().replace('.','_')}-${shot.name}"
                    fun freshRoot(): AccessibilityNodeInfo? {
                        if (android.os.Build.VERSION.SDK_INT >= 34) uiAutomation.clearCache()
                        return uiAutomation.rootInActiveWindow
                    }
                    val screen =
                        Rect(
                            0,
                            0,
                            activity.resources.displayMetrics.widthPixels,
                            activity.resources.displayMetrics.heightPixels,
                        )
                    fun visible(root: AccessibilityNodeInfo?, label: String): Boolean {
                        val node = root?.let { find(it, label) } ?: return false
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        return node.isVisibleToUser &&
                            !bounds.isEmpty &&
                            screen.contains(bounds) &&
                            bounds.height() > 8
                    }
                    var root = freshRoot()
                    var attempt = 0
                    while (attempt++ < 12 && shot.required.any { !visible(root, it) }) {
                        Thread.sleep(100)
                        root = freshRoot()
                    }
                    val seen = shot.required.filter { visible(root, it) }.toMutableSet()
                    fun capture(suffix: String = "") {
                        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
                        File(folder, "$name$suffix.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                    capture()
                    val allowScroll =
                        shot.ui.screen in setOf(Screen.HOME, Screen.SETTINGS, Screen.TRAINING) ||
                            shot.nav.destinationDialog
                    if (allowScroll && seen.size < shot.required.size) {
                        repeat(6) { index ->
                            if (seen.size < shot.required.size) {
                                root
                                    ?.let { scrollable(it) }
                                    ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                Thread.sleep(250)
                                root = freshRoot()
                                seen += shot.required.filter { visible(root, it) }
                                capture("-scroll${index+1}")
                            }
                        }
                    }
                    val missing = JSONArray(shot.required.filter { it !in seen })
                    if (shot.panel != null && root != null && hasEdit(root))
                        missing.put("Unexpected manual text field")
                    if (missing.length() > 0)
                        failures.put(
                            JSONObject().put("screenshot", name).put("missing_or_clipped", missing)
                        )
                    rows.put(
                        JSONObject()
                            .put("name", name)
                            .put("font_scale", font)
                            .put("night", dark)
                            .put("required_controls", JSONArray(shot.required))
                            .put("missing_or_clipped", missing)
                    )
                }
                sendStatus(
                    1,
                    Bundle().apply {
                        putString(
                            "stream",
                            "Captured $group, font $font, ${if(dark)"night"else "field"}\n",
                        )
                    },
                )
            }
            val report =
                JSONObject()
                    .put("synthetic_ui_fixtures", true)
                    .put("screenshots", rows)
                    .put("failures", failures)
            File(folder, "report-$group.json").writeText(report.toString(2))
            output.putString(
                "stream",
                JSONObject()
                    .put("screenshots", rows.length())
                    .put("failures", failures)
                    .toString() + "\n",
            )
            if (failures.length() > 0) code = Activity.RESULT_CANCELED
        } catch (e: Throwable) {
            code = Activity.RESULT_CANCELED
            output.putString("stream", "FAILED: ${e.stackTraceToString()}\n")
        } finally {
            main {
                model?.let {
                    it.stopNavigation()
                    it.dismissVoice()
                    it.open(Screen.HOME)
                    it.audioFallback(originalAudio)
                    it.handsFree(originalHandsFree)
                }
            }
        }
        targetContext
            .getSharedPreferences("accessibility", 0)
            .edit()
            .putBoolean("handsFree", originalHandsFree)
            .commit()
        targetContext
            .getSharedPreferences("scene_gateway", 0)
            .edit()
            .putBoolean("audioConsent", originalAudio)
            .commit()
        finish(code, output)
    }

    private fun cases(): List<Shot> {
        val point = GeoPoint(23.7465, 90.3784)
        val destination =
            Destination(
                "ui-fixture",
                "Bangladesh Specialized Hospital and Diagnostic Centre, Mirpur Road, Dhaka, Bangladesh",
                GeoPoint(23.751, 90.381),
            )
        val route =
            WalkingRoute(
                destination,
                listOf("Turn right."),
                true,
                listOf(point, destination.point!!),
                listOf(RouteManeuver(1, "Turn right.", 10)),
                point,
                "UI fixture",
            )
        val navigation =
            NavigationUiState(
                selected = destination,
                route = route,
                location =
                    LocationFix(point.latitude, point.longitude, SystemClock.elapsedRealtime(), 5f),
            )
        val turn =
            GuidanceState(
                NavigationStatus.ACTIVE,
                "Turn right in 120 metres, about 185 steps.",
                displayText = "Right · 120 m · ≈185 steps\nTotal 1.8 km · ≈2,770 steps",
                totalMeters = 1800.0,
            )
        val long =
            "The pharmacy is on your left beside a blue sign. A parked motorcycle partly covers the entrance. The mapped route continues along this footpath; check the surface with your usual mobility aid. "
        val navKeys = listOf("Stop", "Search", "Repeat")
        return listOf(
            Shot(
                "home",
                required =
                    listOf(
                        "Navigate",
                        "Check Footpath",
                        "Describe Surroundings",
                        "Learn Audio Cues",
                        "Ask Muse. Voice commands",
                    ),
            ),
            Shot(
                "footpath-paused",
                UiState(screen = Screen.FOOTPATH),
                required = listOf("Start Scanning", "Ask Muse", "Repeat"),
            ),
            Shot(
                "footpath-unsafe",
                UiState(
                    screen = Screen.FOOTPATH,
                    indicator = FootpathIndicator.OBSTRUCTION,
                    footpath = "Stop, motorcycle about 123 steps away. Estimated.",
                ),
                required = listOf("Start Scanning", "Ask Muse", "Repeat"),
            ),
            Shot(
                "footpath-clear",
                UiState(
                    screen = Screen.FOOTPATH,
                    indicator = FootpathIndicator.NO_OBSTRUCTION,
                    footpath = "No obstruction reported in the centre.",
                ),
                required = listOf("Start Scanning", "Repeat"),
            ),
            Shot(
                "footpath-error",
                UiState(
                    screen = Screen.FOOTPATH,
                    footpath =
                        "Camera access is unavailable. Enable Camera in Android settings and try again.",
                ),
                required = listOf("Start Scanning", "Repeat"),
            ),
            Shot(
                "navigation-planning",
                UiState(screen = Screen.NAVIGATION),
                required =
                    listOf("Park", "Market", "Bus stop", "Pharmacy", "Go", "Search", "Repeat"),
            ),
            Shot(
                "navigation-turn",
                UiState(screen = Screen.NAVIGATION),
                navigation,
                turn,
                required = navKeys,
            ),
            Shot(
                "navigation-location-pending",
                UiState(screen = Screen.NAVIGATION),
                navigation,
                turn.copy(
                    status = NavigationStatus.WAITING_FOR_GPS,
                    message = "Route preview",
                    displayText = "Next: Turn right\nLocation updating · route preview",
                ),
                required = navKeys,
            ),
            Shot(
                "navigation-approximate",
                UiState(screen = Screen.NAVIGATION),
                navigation,
                turn.copy(
                    displayText =
                        "Upcoming: Right · location approximate\nTotal 1.8 km · ≈2,770 steps"
                ),
                required = navKeys,
            ),
            Shot(
                "navigation-off-route",
                UiState(screen = Screen.NAVIGATION),
                navigation,
                turn.copy(
                    status = NavigationStatus.OFF_ROUTE,
                    message = "Route deviation detected. Stop and recalculate the walking route.",
                    displayText = null,
                ),
                required = navKeys,
            ),
            Shot(
                "navigation-arrived",
                UiState(screen = Screen.NAVIGATION),
                navigation,
                turn.copy(
                    status = NavigationStatus.ARRIVED,
                    message =
                        "Near the mapped route end. The destination entrance is not verified.",
                ),
                required = listOf("Go", "Search", "Repeat"),
            ),
            Shot(
                "navigation-routing",
                UiState(screen = Screen.NAVIGATION),
                navigation.copy(
                    routing = true,
                    pendingNavigation = true,
                    message = "Finding route…",
                ),
                required = navKeys,
            ),
            Shot(
                "navigation-unsafe",
                UiState(
                    screen = Screen.NAVIGATION,
                    indicator = FootpathIndicator.OBSTRUCTION,
                    footpath = "Stop, motorcycle about 123 steps away. Estimated.",
                ),
                navigation,
                turn,
                required = navKeys,
            ),
            Shot(
                "navigation-destinations",
                UiState(screen = Screen.NAVIGATION),
                navigation.copy(
                    destinationDialog = true,
                    query = destination.name,
                    places =
                        List(6) {
                            destination.copy(id = "$it", name = "${it+1}. ${destination.name}")
                        },
                ),
                required = listOf("Where to?", "Place name", "Search", "Close"),
            ),
            Shot(
                "navigation-search-empty",
                UiState(screen = Screen.NAVIGATION),
                NavigationUiState(
                    destinationDialog = true,
                    query = "Hospital",
                    message = "No destinations found",
                ),
                required = listOf("Where to?", "Search", "Close"),
            ),
            Shot(
                "navigation-search-error",
                UiState(screen = Screen.NAVIGATION),
                NavigationUiState(
                    destinationDialog = true,
                    query = "Hospital",
                    message = "Search unavailable",
                ),
                required = listOf("Where to?", "Search", "Close"),
            ),
            Shot(
                "scene-paused",
                UiState(screen = Screen.SCENE),
                required = listOf("Record video", "Replay"),
            ),
            Shot(
                "scene-long",
                UiState(screen = Screen.SCENE, hasSceneDescription = true, scene = long.repeat(3)),
                required = listOf("Record video", "Replay"),
            ),
            Shot(
                "scene-consent",
                UiState(screen = Screen.SCENE),
                required = listOf("Start recording", "Cancel"),
                scene = SurroundingsState(SurroundingsPhase.CONSENT),
            ),
            Shot(
                "scene-capturing",
                UiState(screen = Screen.SCENE),
                required = listOf("Finish recording", "Cancel"),
                scene =
                    SurroundingsState(
                        SurroundingsPhase.CAPTURING,
                        7,
                        16,
                        "Recording…",
                    ),
            ),
            Shot(
                "scene-analyzing",
                UiState(screen = Screen.SCENE),
                required = listOf("Cancel", "Ask Muse"),
                scene =
                    SurroundingsState(
                        SurroundingsPhase.ANALYZING,
                        16,
                        16,
                        "Describing your surroundings…",
                    ),
            ),
            Shot(
                "scene-ready",
                UiState(screen = Screen.SCENE, hasSceneDescription = true),
                required = listOf("Record video", "Ask Muse", "Replay"),
                scene =
                    SurroundingsState(
                        SurroundingsPhase.READY,
                        16,
                        16,
                        summary =
                            "A pharmacy is to your left. Parked motorcycles partly obstruct the pavement ahead.",
                    ),
            ),
            Shot(
                "scene-answer",
                UiState(screen = Screen.SCENE, hasSceneDescription = true),
                required = listOf("Record video", "Ask Muse", "Replay"),
                scene =
                    SurroundingsState(
                        SurroundingsPhase.READY,
                        16,
                        16,
                        answer = long.repeat(4),
                    ),
            ),
            Shot(
                "scene-error",
                UiState(screen = Screen.SCENE),
                required = listOf("Record video", "Ask Muse"),
                scene =
                    SurroundingsState(
                        SurroundingsPhase.ERROR,
                        instruction = "Video could not be recorded. Please try again.",
                    ),
            ),
            Shot(
                "scene-network-error",
                UiState(screen = Screen.SCENE),
                required = listOf("Retry description", "Ask Muse"),
                scene =
                    SurroundingsState(
                        SurroundingsPhase.ERROR,
                        16,
                        16,
                        "Muse is unavailable. Your saved views are available to retry.",
                    ),
            ),
            Shot(
                "training",
                UiState(screen = Screen.TRAINING),
                required =
                    listOf(
                        "Listening",
                        "Turn left",
                        "Turn right",
                        "Obstruction",
                        "View captured",
                        "Complete",
                        "Unavailable",
                        "Vibration",
                        "Stop",
                    ),
            ),
            Shot(
                "training-feedback",
                UiState(
                    screen = Screen.TRAINING,
                    cue = "Practice: possible obstruction. Two short vibrations.",
                ),
                required =
                    listOf(
                        "Listening",
                        "Turn left",
                        "Turn right",
                        "Obstruction",
                        "View captured",
                        "Complete",
                        "Unavailable",
                        "Vibration",
                        "Stop",
                    ),
            ),
            Shot(
                "settings",
                UiState(screen = Screen.SETTINGS),
                required = listOf("Settings", "Standard", "Large", "Field", "Night"),
            ),
            Shot(
                "live-footpath-unsafe",
                UiState(
                    screen = Screen.FOOTPATH,
                    scanRequested = true,
                    scanPhase = com.librevision.data.vision.ScanPhase.RUNNING,
                    indicator = FootpathIndicator.OBSTRUCTION,
                    footpath = "Stop, car ahead.",
                    tilt = PhoneTilt.UPRIGHT,
                    detections = listOf(Detection("car", .9f, NormalizedBox(.3f, .3f, .7f, .6f))),
                ),
                required = listOf("Stop Scanning", "Ask Muse", "Repeat"),
            ),
            Shot(
                "live-footpath-upright",
                UiState(
                    screen = Screen.FOOTPATH,
                    scanRequested = true,
                    scanPhase = com.librevision.data.vision.ScanPhase.RUNNING,
                    indicator = FootpathIndicator.NO_OBSTRUCTION,
                    footpath = "No obstruction reported in the centre.",
                    tilt = PhoneTilt.UPRIGHT,
                ),
                required = listOf("Stop Scanning", "Ask Muse", "Repeat"),
            ),
            Shot(
                "live-footpath-adjust-tilt",
                UiState(
                    screen = Screen.FOOTPATH,
                    scanRequested = true,
                    scanPhase = com.librevision.data.vision.ScanPhase.RUNNING,
                    footpath = "Keep the phone upright for a clearer view.",
                    tilt = PhoneTilt.ADJUST,
                ),
                required = listOf("Stop Scanning", "Ask Muse", "Repeat"),
            ),
            Shot(
                "live-navigation-camera",
                UiState(
                    screen = Screen.NAVIGATION,
                    scanRequested = true,
                    scanPhase = com.librevision.data.vision.ScanPhase.RUNNING,
                    indicator = FootpathIndicator.NO_OBSTRUCTION,
                    tilt = PhoneTilt.UPRIGHT,
                ),
                navigation,
                turn,
                required = navKeys,
            ),
            Shot(
                "live-navigation-unsafe",
                UiState(
                    screen = Screen.NAVIGATION,
                    scanRequested = true,
                    scanPhase = com.librevision.data.vision.ScanPhase.RUNNING,
                    indicator = FootpathIndicator.OBSTRUCTION,
                    footpath = "Stop, motorcycle ahead.",
                    tilt = PhoneTilt.UPRIGHT,
                    detections =
                        listOf(Detection("motorcycle", .9f, NormalizedBox(.3f, .3f, .7f, .6f))),
                ),
                navigation,
                turn,
                required = navKeys,
            ),
            Shot(
                "muse-listening",
                panel = Panel("Listening"),
                required = listOf("Close Muse", "Speak"),
            ),
            Shot(
                "muse-thinking",
                panel = Panel("Thinking", busy = true),
                required = listOf("Close Muse", "Cancel"),
            ),
            Shot(
                "muse-recognizing",
                panel = Panel("Recognizing", busy = true),
                required = listOf("Close Muse", "Cancel"),
            ),
            Shot(
                "muse-answer",
                panel = Panel("Answer", "The pharmacy is on your left beside the blue sign."),
                required = listOf("Close Muse", "Speak", "Replay"),
            ),
            Shot(
                "muse-long",
                panel = Panel("Answer", long.repeat(3)),
                required = listOf("Close Muse", "Speak", "Replay"),
            ),
            Shot(
                "muse-unbroken",
                panel = Panel("Answer", "Hospital".repeat(65)),
                required = listOf("Close Muse", "Speak", "Replay"),
            ),
            Shot(
                "muse-offline",
                panel =
                    Panel(
                        "Try again",
                        "No internet. Please repeat when connected or use the app controls.",
                        failed = true,
                    ),
                required = listOf("Close Muse", "Try again"),
            ),
            Shot(
                "muse-microphone-denied",
                panel =
                    Panel(
                        "Try again",
                        "Microphone access is off. Enable it in Android settings.",
                        failed = true,
                    ),
                required = listOf("Close Muse", "Try again"),
            ),
        )
    }
}
