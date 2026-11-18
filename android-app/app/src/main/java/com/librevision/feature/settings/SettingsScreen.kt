package com.librevision.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.librevision.*
import com.librevision.core.design.*
import com.librevision.feature.navigation.*

@Composable
fun SettingsScreen(model: LibreVisionViewModel, state: UiState) {
    val colors = LocalIndustrialColors.current
    val context = LocalContext.current
    val advanced = state.settingsExpanded
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        ScreenHeader("Settings", model::back, model::voice)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            InstrumentKey("Replay tour", model::startTour, Modifier.fillMaxWidth(), LVIcons.Speaker)
            InstrumentKey(
                "App permissions",
                {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                },
                Modifier.fillMaxWidth(),
                LVIcons.Settings,
            )
            Surface(
                color = colors.panel,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, colors.outline),
            ) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    ChoiceSetting(
                        "Text size",
                        listOf("Standard", "Large"),
                        if (state.largeText) 1 else 0,
                    ) {
                        model.settings(large = it == 1)
                    }
                    ChoiceSetting(
                        "Contrast",
                        listOf("Field", "Night"),
                        if (state.nightMode) 1 else 0,
                    ) {
                        model.appearance(it == 1)
                    }
                    ChoiceSetting(
                        "Audio detail",
                        listOf("Brief", "Detailed"),
                        if (state.detailed) 1 else 0,
                    ) {
                        model.settings(detailed = it == 1)
                    }
                    ChoiceSetting(
                        "Language preference",
                        listOf("English", "বাংলা"),
                        if (state.bangla) 1 else 0,
                        divider = false,
                    ) {
                        model.settings(bangla = it == 1)
                    }
                }
            }
            InstrumentKey(
                "Test speech",
                model::testSpeech,
                Modifier.fillMaxWidth(),
                LVIcons.Speaker,
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Hands-free voice", fontSize = 20.sp, modifier = Modifier.weight(1f))
                Switch(
                    state.handsFree,
                    model::handsFree,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "Keep microphone on for local Hey Saathi wake detection"
                        },
                )
            }
            Text(
                if (state.handsFree)
                    "Say Hey Saathi to activate commands. Listening stays active for 18 seconds after each request finishes, then returns to local wake detection."
                else "Microphone stays off until you tap a voice button.",
                fontSize = 17.sp,
                color = colors.secondary,
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Online speech fallback", fontSize = 20.sp, modifier = Modifier.weight(1f))
                Switch(
                    state.audioFallback,
                    model::audioFallback,
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "Send unrecognized speech utterances to Meta for transcription"
                        },
                )
            }
            Text(
                "Only after wake or a voice-button tap: sends an uncertain utterance or place name to Meta. Idle conversation is never uploaded. The app does not save recordings.",
                fontSize = 17.sp,
                color = colors.secondary,
            )
            Text(
                "Uses your selected Android voice. Offline recognition is English; online speech also supports Bengali. Language preference controls speech output.",
                fontSize = 17.sp,
                color = colors.secondary,
            )
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Short sound cues", fontSize = 20.sp, modifier = Modifier.weight(1f))
                Switch(
                    state.soundCues,
                    { model.feedbackSettings(sound = it) },
                    modifier = Modifier.semantics { contentDescription = "Short sound cues" },
                )
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Haptic cues", fontSize = 20.sp, modifier = Modifier.weight(1f))
                Switch(
                    state.haptics,
                    { model.feedbackSettings(haptics = it) },
                    modifier = Modifier.semantics { contentDescription = "Haptic cues" },
                )
            }
            TextButton(
                onClick = model::toggleSettingsExpanded,
                modifier =
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics {
                        stateDescription = if (advanced) "Expanded" else "Collapsed"
                    },
            ) {
                Text(if (advanced) "Less settings" else "More settings", fontSize = 20.sp)
            }
            if (advanced) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Estimated depth distances",
                        fontSize = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        state.depthEnabled,
                        { model.settings(depthEnabled = it) },
                        modifier =
                            Modifier.semantics { contentDescription = "Estimated depth distances" },
                    )
                }
                Text(
                    "Outdoor depth is unvalidated. Vehicle alerts never wait for depth or give a numeric vehicle distance.",
                    fontSize = 17.sp,
                    color = colors.secondary,
                )
                Text("Assumed step length: ${(state.stepLength*100).toInt()} cm", fontSize = 20.sp)
                Slider(
                    state.stepLength,
                    { model.settings(stepLength = it) },
                    valueRange = .4f..1f,
                    steps = 11,
                    modifier =
                        Modifier.semantics {
                            contentDescription = "Step length used for estimated obstacle distances"
                        },
                )
                InstrumentKey(
                    "Preview audio detail",
                    model::verbosityExample,
                    Modifier.fillMaxWidth(),
                    LVIcons.Audio,
                )
                WalkingServiceSettings(model)
                MuseGatewaySettings(model)
            }
            if (!state.speechAvailable) ResultText(state.speechIssue ?: "Speech unavailable.")
        }
    }
}

@Composable
private fun ChoiceSetting(
    label: String,
    options: List<String>,
    selected: Int,
    divider: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalIndustrialColors.current
    Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        if (LocalDensity.current.fontScale > 1.8f) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier.semantics {
                        isTraversalGroup = true
                        contentDescription = label
                    },
            ) {
                options.forEachIndexed { index, value ->
                    SettingOption(
                        value,
                        index == selected,
                        { onSelect(index) },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
        } else
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier =
                    Modifier.semantics {
                        isTraversalGroup = true
                        contentDescription = label
                    },
            ) {
                options.forEachIndexed { index, value ->
                    SettingOption(
                        value,
                        index == selected,
                        { onSelect(index) },
                        Modifier.weight(1f),
                    )
                }
            }
    }
    if (divider) HorizontalDivider(color = colors.outline)
}

@Composable
private fun SettingOption(
    value: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier,
) {
    val colors = LocalIndustrialColors.current
    OutlinedButton(
        onClick = onSelect,
        shape = RoundedCornerShape(5.dp),
        border = BorderStroke(1.dp, if (selected) colors.ink else colors.outline),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = if (selected) colors.well else colors.key,
                contentColor = if (selected) colors.onWell else colors.ink,
            ),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
        modifier =
            modifier.heightIn(min = 56.dp).semantics {
                this.selected = selected
                stateDescription = if (selected) "Selected" else "Not selected"
            },
    ) {
        if (selected) {
            Glyph(LVIcons.Check, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
