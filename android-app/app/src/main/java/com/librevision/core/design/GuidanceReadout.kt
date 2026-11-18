package com.librevision.core.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

data class ReadoutText(val title: String, val detail: String = "") {
    companion object {
        private val distance = Regex("^Stop, (.+) about (\\d+) steps away\\. Estimated\\.$")

        fun from(text: String, fallbackDetail: String = ""): ReadoutText {
            distance.matchEntire(text)?.let {
                return ReadoutText(
                    "Stop · ${it.groupValues[1]} ahead",
                    "≈${it.groupValues[2]} steps · estimated",
                )
            }
            val first = text.substringBefore('\n')
            Regex("^(.*?) · (≈[0-9,]+ steps)$").matchEntire(first)?.let {
                return ReadoutText(
                    it.groupValues[1],
                    it.groupValues[2] + if ('\n' in text) " · " + text.substringAfter('\n') else "",
                )
            }
            return ReadoutText(
                text.substringBefore('\n'),
                if ('\n' in text) text.substringAfter('\n') else fallbackDetail,
            )
        }
    }
}

/** Bounded wrapping keeps warning names and turn details readable without displacing controls. */
@Composable
fun GuidanceReadout(text: String, icon: Int, fallbackDetail: String = "") {
    val display = ReadoutText.from(text, fallbackDetail)
    val colors = LocalIndustrialColors.current
    val density = LocalDensity.current
    val large = density.fontScale > 1.8f
    val detail =
        if (large)
            display.detail
                .substringBefore(" · Total")
                .replace("Location updating · route preview", "GPS updating · preview")
        else display.detail
    val readoutHeight =
        with(density) { 30.sp.toDp() + if (detail.isNotBlank()) 24.sp.toDp() else 0.dp } + 12.dp
    Row(
        Modifier.fillMaxWidth().heightIn(min = readoutHeight).clearAndSetSemantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = text + if (fallbackDetail.isNotBlank()) ". $fallbackDetail" else ""
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(icon, modifier = Modifier.size(34.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                display.title.replace(Regex("([0-9.,]+) (km|m)\\b"), "$1\u00a0$2"),
                fontSize = if (large) 22.sp else 23.sp,
                lineHeight = if (large) 26.sp else 28.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotBlank())
                Text(
                    detail,
                    fontSize = if (large) 15.sp else 17.sp,
                    lineHeight = if (large) 20.sp else 22.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.secondary,
                )
        }
    }
}
