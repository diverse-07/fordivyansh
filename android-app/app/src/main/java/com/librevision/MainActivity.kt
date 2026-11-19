package com.librevision

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.view.KeyEvent
import android.widget.Toast
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.librevision.domain.alerts.SpeechSetupGate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private var speechSetup = SpeechSetupGate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speechSetup = SpeechSetupGate(savedInstanceState?.getBoolean("speech_store_opened") ?: false)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.map { it.speechEngineUnavailable }.distinctUntilChanged().collect { missing ->
                    if (speechSetup.shouldOpenStore(missing)) openSpeechEngineStore()
                }
            }
        }
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

    private fun openSpeechEngineStore() {
        Toast.makeText(this,
            "Install or update Speech Recognition & Synthesis from Google, then return to Netra Saathi.",
            Toast.LENGTH_LONG).show()
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=com.google.android.tts"))
                .setPackage("com.android.vending"))
        }.isSuccess
        if (!opened) runCatching {
            startActivity(Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")))
        }.onFailure {
            Toast.makeText(this, "Open Google Play and search for Speech Recognition & Synthesis from Google.",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("speech_store_opened", speechSetup.storeOpened)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        model.foreground()
    }

    override fun onStop() {
        cancelGesture()
        model.background()
        super.onStop()
    }

    private val keyHandler = Handler(Looper.getMainLooper())
    private val volumeGesture = com.librevision.domain.commands.VolumeUpGesture()
    private val gestureTick = Runnable {
        applyGesture(volumeGesture.tick(SystemClock.elapsedRealtime()))
        scheduleGesture()
    }
    private fun cancelGesture() { keyHandler.removeCallbacks(gestureTick); volumeGesture.cancel() }
    private fun applyGesture(actions: List<com.librevision.domain.commands.VolumeUpGesture.Action>) {
        for (action in actions) when (action) {
            com.librevision.domain.commands.VolumeUpGesture.Action.VOICE -> { vibrate(60); model.voice() }
            com.librevision.domain.commands.VolumeUpGesture.Action.ALARM -> { vibrate(300); model.triggerEmergencyLoudspeakerAlarm() }
            com.librevision.domain.commands.VolumeUpGesture.Action.VOLUME_UP ->
                (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }
    }
    private fun scheduleGesture() {
        keyHandler.removeCallbacks(gestureTick)
        volumeGesture.nextDeadline()?.let {
            keyHandler.postDelayed(gestureTick, (it - SystemClock.elapsedRealtime()).coerceAtLeast(1))
        }
    }
    private fun handleVolumeEvent(event: KeyEvent): Boolean {
        if (model.emergencyAlarm.isAlarmActive.get()) {
            cancelGesture()
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) model.triggerEmergencyLoudspeakerAlarm()
            return true
        }
        val now = SystemClock.elapsedRealtime()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) applyGesture(volumeGesture.down(now))
            KeyEvent.ACTION_UP -> applyGesture(volumeGesture.up(now, event.isCanceled))
        }
        scheduleGesture()
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) handleVolumeEvent(event) else super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) handleVolumeEvent(event) else super.onKeyUp(keyCode, event)
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) cancelGesture()
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
