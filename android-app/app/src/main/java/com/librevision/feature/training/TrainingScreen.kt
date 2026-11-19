package com.librevision.feature.training

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.librevision.*
import com.librevision.core.design.*
import com.librevision.domain.alerts.FeedbackCue

@Composable
fun TrainingScreen(model: LibreVisionViewModel, state: UiState) {
    val colors = LocalIndustrialColors.current
    val large = LocalDensity.current.fontScale > 1.8f
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenHeader("Audio cues", model::back, model::voice)
        if (state.audioTrainingReady)
            InstrumentKey("Yes, I am ready", model::finishAudioTraining, Modifier.fillMaxWidth(), LVIcons.Check)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TrainingKey("Listening", "Two bright notes · one tap", LVIcons.Mic) {
                model.practiceCue(FeedbackCue.LISTENING)
            }
            TrainingKey("Turn left", "Falling notes · one tap", LVIcons.Left) {
                model.practiceCue(FeedbackCue.TURN_LEFT)
            }
            TrainingKey("Turn right", "Rising notes · two taps", LVIcons.Right) {
                model.practiceCue(FeedbackCue.TURN_RIGHT)
            }
            TrainingKey("Obstruction", "Low double pulse · two taps", LVIcons.Alert) {
                model.cue(true)
            }
            TrainingKey("View captured", "Short chime · tiny tap", LVIcons.Eye) {
                model.practiceCue(FeedbackCue.CAPTURED)
            }
            TrainingKey("Complete", "Three-note chime · two taps", LVIcons.Check) {
                model.practiceCue(FeedbackCue.COMPLETE)
            }
            TrainingKey(
                "Unavailable",
                "Soft falling notes · three taps",
                LVIcons.Pause,
                model::unavailableCue,
            )
            if (large)
                InstrumentKey(
                    "Vibration",
                    model::vibrationCue,
                    Modifier.fillMaxWidth(),
                    LVIcons.Vibrate,
                )
            Surface(
                color = colors.panel,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, colors.outline),
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = state.cue
                    },
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val feedback =
                        when {
                            state.cue.startsWith("Practice: possible obstruction") -> "Obstruction"
                            state.cue.startsWith("Practice: routine maneuver") -> "Direction"
                            state.cue.startsWith("Practice: guidance unavailable") -> "Unavailable"
                            state.cue == "Audio and vibration stopped." -> "Stopped"
                            else -> state.cue
                        }
                    Glyph(LVIcons.Audio)
                    Text(feedback, fontSize = 19.sp, modifier = Modifier.weight(1f))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!large)
                InstrumentKey(
                    "Vibration",
                    model::vibrationCue,
                    Modifier.weight(1f),
                    LVIcons.Vibrate,
                    compact = true,
                )
            InstrumentKey(
                "Stop",
                model::stopAudio,
                Modifier.weight(1f),
                LVIcons.Stop,
                fill = colors.well,
                ink = colors.onWell,
                compact = true,
            )
        }
    }
}

@Composable
private fun TrainingKey(label: String, detail: String, icon: Int, onClick: () -> Unit) {
    val colors = LocalIndustrialColors.current
    val large = LocalDensity.current.fontScale > 1.8f
    Surface(
        onClick = onClick,
        color = colors.key,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, colors.outline),
        shadowElevation = 2.dp,
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 108.dp).semantics(mergeDescendants = true) {
                role = Role.Button
            },
    ) {
        Row(
            Modifier.padding(if (large) 14.dp else 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (large) 12.dp else 20.dp),
        ) {
            Glyph(icon, modifier = Modifier.size(if (large) 36.dp else 50.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    label,
                    fontSize = if (large) 20.sp else 23.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    detail,
                    fontSize = 17.sp,
                    color = colors.secondary,
                )
            }
            if (!large) Glyph(LVIcons.Play, modifier = Modifier.size(26.dp))
        }
    }
}
