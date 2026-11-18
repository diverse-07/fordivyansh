package com.librevision.feature.home

import androidx.compose.runtime.Composable
import com.librevision.LibreVisionViewModel
import com.librevision.UiState

/** Kept as a compatible entry point for existing callers. */
@Composable
fun VoiceCommandsDialog(model: LibreVisionViewModel, state: UiState) =
    MuseAssistantDialog(model, state)
