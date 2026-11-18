package com.librevision.feature.welcome

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.librevision.core.design.*

val welcomePermissions =
    arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

@Composable
fun WelcomeScreen(
    state: WelcomeState,
    onBegin: () -> Unit,
    claimPermissions: () -> Boolean,
    onPermissions: () -> Unit,
    onRetry: () -> Unit,
    onTour: () -> Unit,
    onSkip: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRepeat: () -> Unit,
    onNext: () -> Unit,
    speechAvailable: Boolean = true,
) {
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            onPermissions()
        }
    LaunchedEffect(state.stage) {
        if (state.stage == WelcomeStage.PERMISSIONS && claimPermissions())
            request.launch(welcomePermissions)
    }
    val colors = LocalIndustrialColors.current
    val large = LocalDensity.current.fontScale > 1.8f
    val lesson = WelcomeFlow.lessons[state.step]
    val title =
        when (state.stage) {
            WelcomeStage.WELCOME -> "Welcome"
            WelcomeStage.PERMISSIONS -> "Allow access"
            WelcomeStage.DOWNLOAD -> if (state.failed) "Let's try again" else "Getting ready"
            WelcomeStage.TOUR_OFFER -> "You're ready"
            WelcomeStage.TOUR -> lesson.title
            WelcomeStage.COMPLETE -> "Netra Saathi"
        }
    val body =
        when (state.stage) {
            WelcomeStage.WELCOME -> "Let's set up camera, microphone, and location access."
            WelcomeStage.PERMISSIONS -> "Choose which permissions to allow in the Android prompts."
            WelcomeStage.DOWNLOAD ->
                if (state.failed) "Check your internet connection and free space, then retry."
                else ""
            WelcomeStage.TOUR_OFFER -> "Would you like a spoken tour?"
            WelcomeStage.TOUR -> lesson.text
            WelcomeStage.COMPLETE -> ""
        }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.background,
        contentColor = colors.ink,
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(colors.background)
                .safeDrawingPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!large && state.stage == WelcomeStage.TOUR)
                    Glyph(LVIcons.Brand, modifier = Modifier.size(36.dp))
                Text(
                    "Netra Saathi",
                    fontSize = if (large) 24.sp else 28.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Spacer(Modifier.height(12.dp))
                Glyph(
                    if (state.stage == WelcomeStage.TOUR) icon(lesson.icon) else LVIcons.Brand,
                    modifier = Modifier.size(80.dp),
                )
                Text(
                    title,
                    fontSize = if (large) 24.sp else 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                if (body.isNotBlank()) Text(body, fontSize = 23.sp)
                if (state.stage == WelcomeStage.DOWNLOAD && !state.failed) {
                    Text("${state.percentage}%", fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(
                        progress = { state.percentage / 100f },
                        modifier = Modifier.fillMaxWidth().height(14.dp),
                        color = colors.muse,
                    )
                }
                if (state.stage == WelcomeStage.TOUR_OFFER && state.denied.isNotEmpty())
                    Text(
                        "${state.denied.joinToString(", ")} access is off. You can enable it in Settings.",
                        fontSize = 20.sp,
                    )
                if (state.stage == WelcomeStage.TOUR && !speechAvailable)
                    Text(
                        "Audio is unavailable. You can read the tour and tap Next, or adjust media volume and try Repeat.",
                        fontSize = 20.sp,
                    )
            }
            when (state.stage) {
                WelcomeStage.WELCOME ->
                    InstrumentKey(
                        "Get started",
                        onBegin,
                        Modifier.fillMaxWidth(),
                        LVIcons.Play,
                        fill = colors.muse,
                        ink = colors.onMuse,
                    )
                WelcomeStage.PERMISSIONS ->
                    CircularProgressIndicator(color = colors.muse, modifier = Modifier.size(40.dp))
                WelcomeStage.DOWNLOAD ->
                    if (state.failed)
                        InstrumentKey(
                            "Retry",
                            onRetry,
                            Modifier.fillMaxWidth(),
                            LVIcons.Play,
                            fill = colors.muse,
                            ink = colors.onMuse,
                        )
                WelcomeStage.TOUR_OFFER -> {
                    InstrumentKey(
                        "Take the tour",
                        onTour,
                        Modifier.fillMaxWidth(),
                        LVIcons.Speaker,
                        fill = colors.muse,
                        ink = colors.onMuse,
                    )
                    InstrumentKey("Skip", onSkip, Modifier.fillMaxWidth(), LVIcons.Right)
                }
                WelcomeStage.TOUR -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InstrumentKey(
                            if (state.paused) "Resume" else "Pause",
                            if (state.paused) onResume else onPause,
                            Modifier.weight(1f),
                            if (state.paused) LVIcons.Play else LVIcons.Pause,
                            compact = true,
                            stacked = true,
                        )
                        InstrumentKey(
                            if (state.step == WelcomeFlow.lessons.lastIndex) "Finish" else "Next",
                            onNext,
                            Modifier.weight(1f),
                            LVIcons.Right,
                            fill = colors.muse,
                            ink = colors.onMuse,
                            compact = true,
                            stacked = true,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        InstrumentKey(
                            "Repeat",
                            onRepeat,
                            Modifier.weight(1f),
                            LVIcons.Speaker,
                            compact = true,
                            stacked = true,
                        )
                        InstrumentKey(
                            "Skip",
                            onSkip,
                            Modifier.weight(1f),
                            LVIcons.Close,
                            compact = true,
                            stacked = true,
                        )
                    }
                }
                WelcomeStage.COMPLETE -> Unit
            }
        }
    }
}

private fun icon(icon: TourIcon) =
    when (icon) {
        TourIcon.HOME -> LVIcons.Brand
        TourIcon.MIC -> LVIcons.Mic
        TourIcon.NAVIGATE -> LVIcons.Navigate
        TourIcon.FOOTPATH -> LVIcons.Footpath
        TourIcon.EYE -> LVIcons.Eye
        TourIcon.AUDIO -> LVIcons.Audio
        TourIcon.ALERT -> LVIcons.Alert
        TourIcon.SETTINGS -> LVIcons.Settings
        TourIcon.DONE -> LVIcons.Check
    }
