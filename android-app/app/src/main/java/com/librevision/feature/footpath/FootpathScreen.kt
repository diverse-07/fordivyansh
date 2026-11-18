package com.librevision.feature.footpath

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librevision.*
import com.librevision.core.design.*
import com.librevision.data.models.SetupPhase

@Composable
fun FootpathScreen(model: LibreVisionViewModel, state: UiState) {
    val colors = LocalIndustrialColors.current
    val setup by model.modelSetup.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Check Footpath", model::back, model::voice)
        FootpathStatusBanner(state.indicator, compact = true, scanning = state.scanRequested)
        Box(
            Modifier.weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                .background(colors.panel),
            contentAlignment = Alignment.Center,
        ) {
            if (state.scanRequested) CameraPreview(model, state, Modifier.fillMaxSize())
            else
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(24.dp),
                ) {
                    Glyph(LVIcons.Footpath, modifier = Modifier.size(64.dp))
                    Text("Ready when you are.", fontSize = 24.sp, fontWeight = FontWeight.Medium)
                }
        }
        val result =
            if (setup.phase != SetupPhase.READY) setup.message
            else if (!state.speechAvailable) state.speechIssue ?: "Check speech settings"
            else state.footpath
        GuidanceReadout(
            result,
            if (state.indicator == com.librevision.domain.vision.FootpathIndicator.OBSTRUCTION)
                LVIcons.Alert
            else LVIcons.Footpath,
        )
        InstrumentKey(
            if (state.scanRequested) "Stop Scanning"
            else if (setup.phase == SetupPhase.ERROR) "Retry model setup" else "Start Scanning",
            {
                if (state.scanRequested) model.stopScanning(true)
                else if (setup.phase == SetupPhase.ERROR) model.retryModelSetup() else model.scan()
            },
            Modifier.fillMaxWidth().heightIn(min = 76.dp),
            if (state.scanRequested) LVIcons.Stop else LVIcons.Play,
            fill =
                if (colors.night) colors.footpath
                else if (state.scanRequested) colors.well else colors.accent,
            ink =
                if (colors.night) colors.onFootpath
                else if (state.scanRequested) colors.onWell else colors.onAccent,
            enabled =
                state.scanRequested || setup.phase in setOf(SetupPhase.READY, SetupPhase.ERROR),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InstrumentKey(
                "Ask Saathi AI",
                model::voice,
                Modifier.weight(1f),
                LVIcons.Mic,
                fill = if (colors.night) colors.muse else colors.key,
                ink = if (colors.night) colors.onMuse else colors.ink,
                compact = true,
            )
            InstrumentKey(
                "Repeat",
                model::repeatFootpath,
                Modifier.weight(1f),
                LVIcons.Speaker,
                compact = true,
            )
        }
    }
}
