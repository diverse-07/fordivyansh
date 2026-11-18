package com.librevision

import android.content.Context
import android.media.AudioManager
import android.os.*
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librevision.core.design.*
import com.librevision.feature.footpath.FootpathScreen
import com.librevision.feature.home.*
import com.librevision.feature.navigation.NavigationScreen
import com.librevision.feature.scene.SceneScreen
import com.librevision.feature.settings.SettingsScreen
import com.librevision.feature.training.TrainingScreen
import com.librevision.feature.welcome.*

class MainActivity : ComponentActivity() {
    private val model: LibreVisionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            SideEffect {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !state.nightMode
                    isAppearanceLightNavigationBars = !state.nightMode
                }
            }
            LibreVisionTheme(state.largeText, state.nightMode) { LibreVisionApp(model, state) }
        }
    }

    override fun onStart() {
        super.onStart()
        model.foreground()
    }

    override fun onStop() {
        model.background()
        super.onStop()
    }

    private val keyHandler = Handler(Looper.getMainLooper())
    private var isVolumeUpLongPress = false
    private var clickCount = 0
    private var lastClickTime = 0L

    private val longPressRunnable = Runnable {
        isVolumeUpLongPress = true
        vibrate(60)
        model.voice()
    }

    private val singleClickVolumeRunnable = Runnable {
        if (clickCount == 1) {
            clickCount = 0
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
        }
    }

    /**
     * Physical Hardware Key Interceptor:
     * - Long Press Volume Up (>2.0s): Starts Voice Assistant hands-free with tactile vibration.
     * - Triple Click Volume Up (3x within 1.2s): Triggers Built-in Loudspeaker Emergency Siren.
     * - If Alarm is active: Single click immediately silences it.
     * - Single Click: Normal Volume Up increment (after 450ms debounce).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val now = SystemClock.elapsedRealtime()

            // If emergency alarm is active, tapping Volume Up immediately silences it
            if (model.emergencyAlarm.isAlarmActive.get()) {
                if (event.action == KeyEvent.ACTION_UP) {
                    model.triggerEmergencyLoudspeakerAlarm()
                }
                return true
            }

            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    isVolumeUpLongPress = false
                    keyHandler.removeCallbacks(singleClickVolumeRunnable)
                    keyHandler.postDelayed(longPressRunnable, 2000)
                }
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                keyHandler.removeCallbacks(longPressRunnable)
                if (isVolumeUpLongPress) {
                    isVolumeUpLongPress = false
                    return true
                }

                // Rapid triple click detection within 500ms intervals
                if (now - lastClickTime < 500) {
                    clickCount++
                } else {
                    clickCount = 1
                }
                lastClickTime = now

                if (clickCount >= 3) {
                    keyHandler.removeCallbacks(singleClickVolumeRunnable)
                    clickCount = 0
                    vibrate(300)
                    model.triggerEmergencyLoudspeakerAlarm()
                    return true
                } else {
                    // Schedule normal single volume step up if no subsequent clicks follow
                    keyHandler.removeCallbacks(singleClickVolumeRunnable)
                    keyHandler.postDelayed(singleClickVolumeRunnable, 450)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun vibrate(durationMs: Long) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        }
    }

    override fun onDestroy() {
        keyHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@Composable
internal fun Action(
    label: String,
    icon: Int? = null,
    color: Color? = null,
    enabled: Boolean = true,
    selected: Boolean? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalIndustrialColors.current
    InstrumentKey(
        label,
        onClick,
        modifier.fillMaxWidth(),
        icon,
        fill = color ?: colors.accent,
        ink = colors.onAccent,
        enabled = enabled,
        selected = selected,
    )
}

@Composable
internal fun ResultText(text: String) {
    val colors = LocalIndustrialColors.current
    Surface(
        color = colors.panel,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, colors.outline),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text, fontSize = 22.sp, modifier = Modifier.padding(18.dp))
    }
}

@Composable
fun LibreVisionApp(model: LibreVisionViewModel, state: UiState) {
    val welcome by model.welcome.collectAsStateWithLifecycle()
    if (welcome.stage != WelcomeStage.COMPLETE) {
        BackHandler(welcome.stage == WelcomeStage.TOUR) { model.pauseTour() }
        WelcomeScreen(
            welcome,
            model::beginWelcome,
            model::claimWelcomePermissions,
            model::welcomePermissionsFinished,
            model::retryModelSetup,
            model::startTour,
            model::finishWelcome,
            model::pauseTour,
            model::resumeTour,
            model::repeatTour,
            model::nextTour,
            state.speechAvailable,
        )
        return
    }
    BackHandler(state.screen != Screen.HOME || state.voiceOpen) {
        model.back()
    }
    Surface(color = Background, modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = state.screen,
            transitionSpec = {
                if (targetState != Screen.HOME && initialState == Screen.HOME) {
                    (slideInHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { width -> width / 4 } +
                            fadeIn(animationSpec = tween(320))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing)) { width -> -width / 4 } +
                                    fadeOut(animationSpec = tween(240)))
                } else if (targetState == Screen.HOME) {
                    (slideInHorizontally(animationSpec = tween(320, easing = FastOutSlowInEasing)) { width -> -width / 4 } +
                            fadeIn(animationSpec = tween(320))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(280, easing = FastOutSlowInEasing)) { width -> width / 4 } +
                                    fadeOut(animationSpec = tween(240)))
                } else {
                    (fadeIn(animationSpec = tween(260)) + scaleIn(initialScale = 0.96f, animationSpec = tween(260))) togetherWith
                            fadeOut(animationSpec = tween(200))
                }
            },
            label = "ScreenAnimatedTransition"
        ) { targetScreen ->
            when (targetScreen) {
                Screen.HOME -> HomeScreen(model, state)
                Screen.NAVIGATION -> NavigationScreen(model, state)
                Screen.FOOTPATH -> FootpathScreen(model, state)
                Screen.SCENE -> SceneScreen(model, state)
                Screen.TRAINING -> TrainingScreen(model, state)
                Screen.SETTINGS -> SettingsScreen(model, state)
            }
        }
    }
    val nav by model.navigationState.collectAsStateWithLifecycle()
    if (state.voiceOpen && !nav.contextConsent) MuseAssistantDialog(model, state)
    if (nav.contextConsent)
        InstrumentSheet(model::dismissContextConsent) {
            Text("Send one image?", fontSize = 26.sp, modifier = Modifier.semantics { heading() })
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(
                    "One selected image and app context go to Netra Saathi Cloud AI for analysis. Local obstacle alerts continue.",
                    fontSize = 20.sp,
                )
            }
            InstrumentKey(
                "Send image",
                model::confirmContextConsent,
                Modifier.fillMaxWidth(),
                LVIcons.Eye,
            )
            InstrumentKey(
                "Cancel",
                model::dismissContextConsent,
                Modifier.fillMaxWidth(),
                LVIcons.Close,
            )
        }
}
