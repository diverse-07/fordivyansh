package com.librevision.core.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Shared voice/search sheet. Carry the app's text scaling into the separate dialog window. */
@Composable
fun InstrumentSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalIndustrialColors.current
    val density = LocalDensity.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CompositionLocalProvider(LocalDensity provides density) {
            BoxWithConstraints(
                Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Surface(
                    color = colors.panel,
                    contentColor = colors.ink,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.outline),
                    modifier =
                        Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                        content = content,
                    )
                }
            }
        }
    }
}
