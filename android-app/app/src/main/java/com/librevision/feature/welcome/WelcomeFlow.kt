package com.librevision.feature.welcome

import com.librevision.domain.alerts.FeedbackCue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class WelcomeStage {
    WELCOME,
    PERMISSIONS,
    DOWNLOAD,
    TOUR_OFFER,
    TOUR,
    COMPLETE,
}

data class WelcomeSnapshot(
    val permissionsAsked: Boolean = false,
    val completed: Boolean = false,
    val tourStep: Int? = null,
)

data class WelcomeState(
    val stage: WelcomeStage = WelcomeStage.WELCOME,
    val percentage: Int = 0,
    val failed: Boolean = false,
    val denied: List<String> = emptyList(),
    val step: Int = 0,
    val paused: Boolean = false,
)

data class TourLesson(
    val title: String,
    val text: String,
    val icon: TourIcon,
    val cue: FeedbackCue? = null,
)

enum class TourIcon {
    HOME,
    MIC,
    NAVIGATE,
    FOOTPATH,
    EYE,
    AUDIO,
    ALERT,
    SETTINGS,
    DONE,
}

/**
 * First-run decisions and tour progression are independent of Android permission dialogs and TTS.
 */
class WelcomeFlow(initial: WelcomeSnapshot, private val save: (WelcomeSnapshot) -> Unit) {
    private var snapshot = initial
    private var permissionRequest = false
    private val mutable =
        MutableStateFlow(
            WelcomeState(
                stage =
                    when {
                        initial.tourStep != null -> WelcomeStage.TOUR
                        initial.completed -> WelcomeStage.COMPLETE
                        initial.permissionsAsked -> WelcomeStage.DOWNLOAD
                        else -> WelcomeStage.WELCOME
                    },
                step = initial.tourStep?.coerceIn(lessons.indices) ?: 0,
                paused = initial.tourStep != null,
            )
        )
    val state = mutable.asStateFlow()
    val permissionsAsked
        get() = snapshot.permissionsAsked

    fun begin() {
        if (state.value.stage != WelcomeStage.WELCOME) return
        permissionRequest = true
        mutable.value = state.value.copy(stage = WelcomeStage.PERMISSIONS)
    }

    fun claimPermissionRequest(): Boolean {
        if (!permissionRequest) return false
        permissionRequest = false
        snapshot = snapshot.copy(permissionsAsked = true)
        save(snapshot)
        return true
    }

    fun permissionsFinished(denied: List<String>) {
        if (state.value.stage !in setOf(WelcomeStage.PERMISSIONS, WelcomeStage.DOWNLOAD)) return
        mutable.value = state.value.copy(stage = WelcomeStage.DOWNLOAD, denied = denied)
    }

    fun preparation(percent: Int, ready: Boolean, failed: Boolean) {
        if (state.value.stage != WelcomeStage.DOWNLOAD) return
        mutable.value =
            state.value.copy(
                stage = if (ready) WelcomeStage.TOUR_OFFER else WelcomeStage.DOWNLOAD,
                percentage = if (ready) 100 else percent.coerceIn(0, 99),
                failed = failed,
            )
    }

    fun startTour() {
        if (state.value.stage !in setOf(WelcomeStage.TOUR_OFFER, WelcomeStage.COMPLETE)) return
        snapshot = snapshot.copy(tourStep = 0)
        save(snapshot)
        mutable.value = state.value.copy(stage = WelcomeStage.TOUR, step = 0, paused = false)
    }

    fun pause() {
        if (state.value.stage == WelcomeStage.TOUR) mutable.value = state.value.copy(paused = true)
    }

    fun resume() {
        if (state.value.stage == WelcomeStage.TOUR) mutable.value = state.value.copy(paused = false)
    }

    fun next() {
        if (state.value.stage != WelcomeStage.TOUR) return
        if (state.value.step == lessons.lastIndex) finish()
        else {
            val next = state.value.step + 1
            snapshot = snapshot.copy(tourStep = next)
            save(snapshot)
            mutable.value = state.value.copy(step = next)
        }
    }

    fun finish() {
        if (state.value.stage !in setOf(WelcomeStage.TOUR_OFFER, WelcomeStage.TOUR)) return
        snapshot = snapshot.copy(completed = true, tourStep = null)
        save(snapshot)
        mutable.value = state.value.copy(stage = WelcomeStage.COMPLETE, paused = false)
    }

    companion object {
        val lessons =
            listOf(
                TourLesson(
                    "Welcome",
                    "This tour continues after each spoken lesson. You can pause, repeat, move to the next lesson, or skip at any time.",
                    TourIcon.HOME,
                ),
                TourLesson(
                    "Voice commands",
                    "Say Hey Saathi, then ask a question or name a feature. For example: Hey Saathi, check footpath. You have 18 seconds for follow-up commands. You can also tap the large microphone button.",
                    TourIcon.MIC,
                ),
                TourLesson(
                    "Navigate",
                    "Tap Navigate. Choose a destination, then tap Go to start. The map and spoken turn instructions guide you. Obstacle warnings take priority over directions.",
                    TourIcon.NAVIGATE,
                ),
                TourLesson(
                    "Check Footpath",
                    "Tap Check Footpath, then Start Scanning. Hold the phone upright at chest height. Listen for obstructions in the centre of the camera view. Tap Stop Scanning to finish. A clear view does not prove the path is safe.",
                    TourIcon.FOOTPATH,
                ),
                TourLesson(
                    "Surroundings",
                    "Tap Describe Surroundings. Agree to send selected pictures, record a video, then tap Finish recording. Netra Saathi AI gives a short overview. You can ask questions about the same video and replay the answer.",
                    TourIcon.EYE,
                ),
                TourLesson(
                    "Ready cue",
                    "A short rising chime and gentle tap confirm that the app is ready to listen. Hear a practice cue now. Learn Audio Cues lets you practise sounds and vibrations again.",
                    TourIcon.AUDIO,
                    FeedbackCue.LISTENING,
                ),
                TourLesson(
                    "Warning cue",
                    "This is a practice warning cue. In use, listen to the spoken obstacle alert. Warnings can interrupt directions or a scene description. Keep using your cane, guide dog, or established mobility skills.",
                    TourIcon.ALERT,
                    FeedbackCue.OBSTACLE,
                ),
                TourLesson(
                    "Settings",
                    "Tap the gear icon on the Home screen. Adjust text size, contrast, language, audio detail, sounds, and haptics. Replay this tour from Settings whenever you need it.",
                    TourIcon.SETTINGS,
                ),
                TourLesson(
                    "You're ready",
                    "The Home buttons and voice microphone are ready. Take your time exploring. You can replay this tour from Settings.",
                    TourIcon.DONE,
                ),
            )
    }
}
