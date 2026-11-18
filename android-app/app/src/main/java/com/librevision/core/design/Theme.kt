package com.librevision.core.design

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Colour tokens match design/LibreVision_Industrial_Prototype.html. */
data class IndustrialColors(
    val background: Color,
    val panel: Color,
    val key: Color,
    val ink: Color,
    val secondary: Color,
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val well: Color,
    val onWell: Color,
    val footpath: Color,
    val onFootpath: Color,
    val muse: Color,
    val onMuse: Color,
    val safe: Color,
    val onSafe: Color,
    val danger: Color,
    val onDanger: Color,
    val night: Boolean,
)

val FieldColors =
    IndustrialColors(
        Color(0xFFE5E5DF),
        Color(0xFFF2F2EB),
        Color(0xFFE4E5DE),
        Color(0xFF202621),
        Color(0xFF50584F),
        Color(0xFFB8BCB1),
        Color(0xFFF16C45),
        Color(0xFF20251F),
        Color(0xFF2A312C),
        Color(0xFFF3F4EB),
        Color(0xFF2A312C),
        Color(0xFFF3F4EB),
        Color(0xFF2A312C),
        Color(0xFFF3F4EB),
        Color(0xFFB0D7A6),
        Color(0xFF18361D),
        Color(0xFFEE947F),
        Color(0xFF481C12),
        false,
    )
val NightColors =
    IndustrialColors(
        Color.Black,
        Color(0xFF111111),
        Color(0xFF242424),
        Color(0xFFF4F7FC),
        Color(0xFFAAB8CE),
        Color(0xFF52627C),
        Color(0xFFFF927F),
        Color(0xFF151C29),
        Color(0xFF111111),
        Color(0xFFF4F7FC),
        Color(0xFFB6FF42),
        Color(0xFF172400),
        Color(0xFFB6FF42),
        Color(0xFF172400),
        Color(0xFF5CE1C6),
        Color(0xFF092E26),
        Color(0xFFFF927F),
        Color(0xFF421B13),
        true,
    )
val LocalIndustrialColors = staticCompositionLocalOf { FieldColors }
val Background: Color
    @Composable get() = LocalIndustrialColors.current.background
val Surface: Color
    @Composable get() = LocalIndustrialColors.current.panel
val Text: Color
    @Composable get() = LocalIndustrialColors.current.ink
val Secondary: Color
    @Composable get() = LocalIndustrialColors.current.secondary
val Mint = Color(0xFF5CE1C6)
val Lavender = Color(0xFFB6A8FF)
val OnLavender = Color(0xFF24183D)
val Coral = Color(0xFFFF927F)

@Composable
fun LibreVisionTheme(large: Boolean, night: Boolean = false, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val colors = if (night) NightColors else FieldColors
    val scheme = if (night) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(
        LocalIndustrialColors provides colors,
        LocalDensity provides Density(density.density, density.fontScale * if (large) 1.2f else 1f),
    ) {
        MaterialTheme(
            colorScheme =
                scheme.copy(
                    primary = colors.accent,
                    onPrimary = colors.onAccent,
                    background = colors.background,
                    onBackground = colors.ink,
                    surface = colors.panel,
                    onSurface = colors.ink,
                    surfaceVariant = colors.key,
                    onSurfaceVariant = colors.secondary,
                    secondary = colors.muse,
                    onSecondary = colors.onMuse,
                    tertiary = Lavender,
                    onTertiary = OnLavender,
                    outline = colors.outline,
                    surfaceContainerHighest = colors.key,
                    surfaceContainer = colors.panel,
                ),
            shapes =
                Shapes(
                    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    small = androidx.compose.foundation.shape.RoundedCornerShape(5.dp),
                    medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    large = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                ),
            typography =
                Typography().let {
                    it.copy(
                        bodyLarge = it.bodyLarge.copy(fontFamily = FontFamily.SansSerif),
                        titleLarge = it.titleLarge.copy(fontFamily = FontFamily.SansSerif),
                    )
                },
            content = content,
        )
    }
}
