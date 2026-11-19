package com.librevision.feature.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librevision.*
import com.librevision.core.design.*
import com.librevision.data.models.SetupPhase

private data class HomeAction(val label: String, val icon: Int, val screen: Screen)

private fun openHomeAction(model: LibreVisionViewModel, action: HomeAction) {
    when (action.screen) {
        Screen.SCENE -> model.describe()
        Screen.TRAINING -> model.startAudioTraining()
        else -> model.open(action.screen)
    }
}

@Composable
fun HomeScreen(model: LibreVisionViewModel, state: UiState) {
    val colors = LocalIndustrialColors.current
    val setup by model.modelSetup.collectAsStateWithLifecycle()
    val actions =
        listOf(
            HomeAction("Navigate", LVIcons.Navigate, Screen.NAVIGATION),
            HomeAction("Check Footpath", LVIcons.Footpath, Screen.FOOTPATH),
            HomeAction("Describe Surroundings", LVIcons.Eye, Screen.SCENE),
            HomeAction("Learn Audio Cues", LVIcons.Audio, Screen.TRAINING),
        )
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val rows = LocalDensity.current.fontScale > 1.35f || maxHeight < 650.dp
        val scroll =
            maxHeight < 640.dp ||
                LocalDensity.current.fontScale > 1.8f ||
                setup.phase != SetupPhase.READY
        val voiceSize = if (maxHeight < 700.dp) 140.dp else 164.dp
        Column(
            Modifier.fillMaxSize()
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Glyph(LVIcons.Brand, modifier = Modifier.size(36.dp))
                    Text(
                        "Netra Saathi",
                        fontSize = 29.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-1.3).sp,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    InstrumentIcon(LVIcons.Alert, "Emergency Siren", { model.triggerEmergencyLoudspeakerAlarm() })
                    InstrumentIcon(LVIcons.Settings, "Settings", { model.open(Screen.SETTINGS) })
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = colors.outline)
            }
            if (setup.phase != SetupPhase.READY) {
                Text(
                    setup.message,
                    fontSize = 18.sp,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (setup.phase == SetupPhase.ERROR)
                    InstrumentKey(
                        "Retry model setup",
                        model::retryModelSetup,
                        Modifier.fillMaxWidth(),
                        LVIcons.Play,
                    )
            }
            if (rows)
                actions.forEachIndexed { index, item ->
                    HomeKey(item, index, Modifier.fillMaxWidth().heightIn(min = 80.dp), true) {
                        openHomeAction(model, item)
                    }
                }
            else
                actions.chunked(2).forEachIndexed { row, items ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items.forEachIndexed { column, item ->
                            HomeKey(
                                item,
                                row * 2 + column,
                                Modifier.weight(1f).aspectRatio(1f),
                                false,
                            ) {
                                openHomeAction(model, item)
                            }
                        }
                    }
                }
            if (!scroll) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(8.dp))
            val voiceTransition = rememberInfiniteTransition(label = "VoiceHaloPulse")
            val haloScale by voiceTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.15f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1300, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "HaloScale"
            )
            val haloAlpha by voiceTransition.animateFloat(
                initialValue = 0.40f,
                targetValue = 0.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1300, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "HaloAlpha"
            )
            Column(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Futuristic glowing halo pulsing behind mic
                    Box(
                        Modifier
                            .size(voiceSize)
                            .graphicsLayer(scaleX = haloScale, scaleY = haloScale)
                            .border(3.dp, colors.muse.copy(alpha = haloAlpha), CircleShape)
                    )
                    Box(
                        Modifier.size(voiceSize)
                            .border(
                                if (colors.night) 2.dp else 1.dp,
                                if (colors.night) colors.muse else colors.outline,
                                CircleShape,
                            )
                            .padding(10.dp)
                    ) {
                        Surface(
                            onClick = model::voice,
                            color = colors.muse,
                            contentColor = colors.onMuse,
                            shape = CircleShape,
                            shadowElevation = 4.dp,
                            modifier =
                                Modifier.fillMaxSize().semantics {
                                    contentDescription = "Ask Saathi AI. Voice assistant"
                                    role = Role.Button
                                },
                        ) {
                            Box(
                                Modifier.fillMaxSize()
                                    .padding(10.dp)
                                    .border(1.dp, colors.onMuse.copy(alpha = .25f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Glyph(LVIcons.Mic, modifier = Modifier.size(68.dp))
                            }
                        }
                    }
                }
                Text("Ask Saathi AI", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.handsFree) "Hey Vision" else "Tap to speak",
                    fontSize = 18.sp,
                    color = colors.secondary,
                )
            }
            if (!state.speechAvailable)
                Text(state.speechIssue ?: "Speech unavailable. Check Settings.", fontSize = 18.sp)
            if (state.microphoneDenied)
                Text("Enable microphone in Ask Saathi AI to use voice commands.", fontSize = 18.sp)
        }
    }
}

@Composable
private fun HomeKey(
    item: HomeAction,
    index: Int,
    modifier: Modifier,
    wide: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalIndustrialColors.current
    val (fill, ink) =
        when (item.screen) {
            Screen.NAVIGATION -> colors.accent to colors.onAccent
            Screen.FOOTPATH -> colors.footpath to colors.onFootpath
            Screen.SCENE -> Lavender to OnLavender
            else -> colors.key to colors.ink
        }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "HomeKeyBounce"
    )
    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        color = fill,
        contentColor = ink,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, colors.outline),
        shadowElevation = if (isPressed) 1.dp else 3.dp,
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .semantics(mergeDescendants = true) { role = Role.Button },
    ) {
        // Only square keys fill their height; wide keys must wrap content at large font scales.
        Box(
            (if (wide) Modifier.fillMaxWidth() else Modifier.fillMaxSize()).padding(
                if (wide) 14.dp else 16.dp
            )
        ) {
            val tint =
                if (item.screen == Screen.FOOTPATH && !colors.night)
                    androidx.compose.ui.graphics.Color(0xFFB6CE9E)
                else ink
            if (wide)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Glyph(item.icon, modifier = Modifier.size(46.dp), tint = tint)
                    Text(
                        item.label,
                        fontSize = 23.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-.6).sp,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            else
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                    Glyph(item.icon, modifier = Modifier.size(58.dp), tint = tint)
                    Text(
                        item.label
                            .replace("Check ", "Check\n")
                            .replace("Describe ", "Describe\n")
                            .replace("Learn ", "Learn\n"),
                        fontSize = 23.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-.6).sp,
                    )
                }
        }
    }
}
