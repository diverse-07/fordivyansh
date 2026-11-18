package com.librevision.feature.footpath

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.librevision.core.design.*
import com.librevision.domain.vision.FootpathIndicator

@Composable
fun FootpathStatusBanner(
    indicator: FootpathIndicator,
    compact: Boolean = false,
    scanning: Boolean = true,
) {
    val colors = LocalIndustrialColors.current
    val (title, detail, icon) =
        when (indicator) {
            com.librevision.domain.vision.FootpathIndicator.OBSTRUCTION ->
                Triple("Unsafe", "Possible obstruction ahead", LVIcons.Alert)
            com.librevision.domain.vision.FootpathIndicator.NO_OBSTRUCTION ->
                Triple("No obstruction", "Path safety is not verified", LVIcons.Check)
            else ->
                Triple(
                    if (scanning) "Checking" else "Camera paused",
                    if (scanning) "A current, upright scan is needed" else "Path safety unknown",
                    if (scanning) LVIcons.Phone else LVIcons.Pause,
                )
        }
    val fill =
        when (indicator) {
            FootpathIndicator.OBSTRUCTION -> colors.danger
            FootpathIndicator.NO_OBSTRUCTION -> colors.safe
            else -> colors.panel
        }
    val ink =
        when (indicator) {
            FootpathIndicator.OBSTRUCTION -> colors.onDanger
            FootpathIndicator.NO_OBSTRUCTION -> colors.onSafe
            else -> colors.ink
        }
    val animatedFill by animateColorAsState(
        targetValue = fill,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "BannerColor"
    )
    Surface(
        color = animatedFill,
        contentColor = ink,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, colors.outline),
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 66.dp).semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                stateDescription = detail
            },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedContent(targetState = icon, label = "BannerIconTransition") { currentIcon ->
                Glyph(currentIcon, modifier = Modifier.size(34.dp))
            }
            Column {
                AnimatedContent(targetState = title, label = "BannerTitleTransition") { currentTitle ->
                    Text(
                        currentTitle,
                        fontSize =
                            if (compact && LocalDensity.current.fontScale > 1.8f) 17.sp else 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-.5).sp,
                    )
                }
                if (!compact) Text(detail, fontSize = 18.sp)
            }
        }
    }
}
