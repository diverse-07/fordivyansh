package com.librevision.feature.scene

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librevision.*
import com.librevision.core.design.*
import com.librevision.domain.scene.SurroundingsPhase

@Composable
fun SceneScreen(model: LibreVisionViewModel, state: UiState) {
    val colors = LocalIndustrialColors.current
    val large = LocalDensity.current.fontScale > 1.8f
    val scene by model.surroundingsState.collectAsStateWithLifecycle()
    val preparing = scene.phase == SurroundingsPhase.PREPARING
    val recording = scene.phase == SurroundingsPhase.CAPTURING
    val busy = scene.phase in setOf(SurroundingsPhase.EXTRACTING, SurroundingsPhase.ANALYZING)
    val preview = preparing || recording
    val response =
        when {
            scene.phase == SurroundingsPhase.ERROR -> scene.instruction
            scene.answer.isNotBlank() -> scene.answer
            scene.summary.isNotBlank() -> scene.summary
            state.hasSceneDescription -> state.scene
            busy -> scene.instruction
            else -> "Point your camera at what you want described."
        }
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Surroundings", model::back, model::voice)
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                .background(colors.panel),
            contentAlignment = Alignment.Center,
        ) {
            if (preview) SceneVideoPreview(model, Modifier.fillMaxSize())
            else
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (
                        scene.captured == 0 &&
                            scene.phase != SurroundingsPhase.ERROR &&
                            !state.hasSceneDescription
                    )
                        Glyph(LVIcons.Eye, modifier = Modifier.size(64.dp))
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Lavender)
                    Text(
                        response,
                        fontSize = 24.sp,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            if (recording)
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(12.dp),
                    color = colors.well,
                    contentColor = colors.onWell,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(
                        "Recording ${scene.elapsedMs/1000}s",
                        fontSize = 22.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
        }
        InstrumentKey(
            when {
                recording -> "Finish recording"
                preparing || busy -> "Cancel"
                scene.phase == SurroundingsPhase.ERROR && scene.captured > 0 -> "Retry description"
                else -> "Describe surroundings"
            },
            {
                when {
                    recording -> model.finishSurroundingsRecording()
                    preparing -> model.cancelSurroundingsCapture()
                    busy && scene.phase == SurroundingsPhase.EXTRACTING ->
                        model.cancelSurroundingsCapture()
                    busy -> model.cancelSceneQuestion()
                    scene.phase == SurroundingsPhase.ERROR && scene.captured > 0 ->
                        model.retrySurroundingsDescription()
                    else -> model.describe()
                }
            },
            Modifier.fillMaxWidth(),
            if (preview || busy) LVIcons.Stop else LVIcons.Play,
            fill = Lavender,
            ink = OnLavender,
        )
        if (preview)
            InstrumentKey(
                "Cancel",
                model::cancelSurroundingsCapture,
                Modifier.fillMaxWidth(),
                LVIcons.Close,
            )
        else
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InstrumentKey(
                    "Ask Saathi AI",
                    model::voice,
                    Modifier.weight(1f),
                    LVIcons.Mic,
                    fill = colors.muse,
                    ink = colors.onMuse,
                    compact = true,
                    stacked = large,
                )
                InstrumentKey(
                    "Replay",
                    model::replayScene,
                    Modifier.weight(1f),
                    LVIcons.Speaker,
                    enabled =
                        state.hasSceneDescription &&
                            !busy &&
                            scene.phase != SurroundingsPhase.ERROR,
                    compact = true,
                    stacked = large,
                )
            }
    }
    if (scene.phase == SurroundingsPhase.CONSENT)
        InstrumentSheet(model::dismissSurroundingsConsent) {
            Text("Allow surroundings descriptions", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(
                    "Allow Saathi to send selected pictures to Google Gemini when you ask for a surroundings description. You can change this in Settings.",
                    fontSize = 20.sp,
                )
            }
            InstrumentKey(
                "Allow and describe",
                model::confirmSurroundingsCapture,
                Modifier.fillMaxWidth(),
                LVIcons.Play,
                fill = Lavender,
                ink = OnLavender,
            )
            InstrumentKey(
                "Cancel",
                model::dismissSurroundingsConsent,
                Modifier.fillMaxWidth(),
                LVIcons.Close,
            )
        }
}
