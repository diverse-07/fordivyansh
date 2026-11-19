package com.librevision.feature.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.librevision.*
import com.librevision.core.design.*
import com.librevision.data.speech.ListeningPhase
import com.librevision.domain.assistant.*

@Composable
fun MuseAssistantDialog(model: LibreVisionViewModel, state: UiState) {
    val assistant by model.assistantState.collectAsStateWithLifecycle()
    val listening by model.listening.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) model.voice() else model.microphoneDenied()
        }
    val busy = assistant.phase == AssistantPhase.THINKING || state.voice == VoiceState.PROCESSING
    val failure =
        when {
            state.microphoneDenied -> "Microphone access is off. Enable it in Android settings."
            state.voice == VoiceState.FAILURE && state.message.isNotBlank() -> state.message
            assistant.phase == AssistantPhase.ERROR -> assistant.answer
            listening.phase == ListeningPhase.FAILURE -> listening.message
            else -> null
        }
    val status =
        when {
            failure != null -> "Try again"
            busy -> if (state.voice == VoiceState.PROCESSING) "Recognizing" else "Thinking"
            listening.phase == ListeningPhase.PREPARING -> "Preparing microphone"
            assistant.phase == AssistantPhase.LISTENING || assistant.expectsFollowup -> "Listening"
            assistant.answer.isNotBlank() -> "Answer"
            else -> "Ready"
        }
    MuseVoicePanel(
        status = status,
        answer = failure ?: assistant.answer.takeUnless { busy }.orEmpty(),
        busy = busy,
        listening = status == "Listening",
        failed = failure != null,
        onSpeak = {
            if (
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            ) {
                model.voice()
            } else if (model.permissionBatchRequested()) model.microphoneDenied()
            else permission.launch(Manifest.permission.RECORD_AUDIO)
        },
        onClose = model::dismissVoice,
        onCancel = model::cancelAssistant,
        onReplay = model::replayAssistant,
    )
}

/** Uses the same square keys, colour tokens and readable typography as the main screens. */
@Composable
internal fun MuseVoicePanel(
    status: String,
    answer: String,
    busy: Boolean,
    listening: Boolean,
    failed: Boolean,
    onSpeak: () -> Unit,
    onClose: () -> Unit,
    onCancel: () -> Unit,
    onReplay: () -> Unit,
) {
    val colors = LocalIndustrialColors.current
    val large = LocalDensity.current.fontScale > 1.5f
    InstrumentSheet(onClose) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                color = colors.muse,
                contentColor = colors.onMuse,
                shape = RoundedCornerShape(8.dp),
            ) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Glyph(LVIcons.Mic, modifier = Modifier.size(38.dp))
                }
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Saathi AI",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    status,
                    fontSize = 18.sp,
                    color = colors.secondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            InstrumentIcon(LVIcons.Close, "Close Saathi AI", onClose)
        }
        HorizontalDivider(color = colors.outline)
        Column(
            Modifier.weight(1f, fill = false)
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    color = colors.muse,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(
                answer.ifBlank {
                    when {
                        busy -> "One moment…"
                        listening -> "Ask a question or say what you want to do. Try “Open Settings” or “What can I say?”"
                        else -> "Say Hey Vision or tap Speak."
                    }
                },
                fontSize = 24.sp,
                lineHeight = 32.sp,
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
            )
        }
        if (busy) {
            InstrumentKey("Cancel", onCancel, Modifier.fillMaxWidth(), LVIcons.Stop)
        } else if (answer.isNotBlank() && !failed && !large) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InstrumentKey(
                    "Speak",
                    onSpeak,
                    Modifier.weight(1f),
                    LVIcons.Mic,
                    fill = colors.muse,
                    ink = colors.onMuse,
                )
                InstrumentKey("Replay", onReplay, Modifier.weight(1f), LVIcons.Speaker)
            }
        } else {
            InstrumentKey(
                if (failed) "Try again" else "Speak",
                onSpeak,
                Modifier.fillMaxWidth(),
                LVIcons.Mic,
                fill = colors.muse,
                ink = colors.onMuse,
            )
            if (answer.isNotBlank() && !failed)
                InstrumentKey(
                    "Replay",
                    onReplay,
                    Modifier.fillMaxWidth(),
                    LVIcons.Speaker,
                )
        }
    }
}
