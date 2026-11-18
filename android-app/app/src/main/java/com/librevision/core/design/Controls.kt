package com.librevision.core.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*

@Composable
fun Glyph(
    @DrawableRes icon: Int,
    description: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(icon), description, modifier.size(32.dp), tint = tint)
}

@Composable
fun InstrumentKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
    fill: Color? = null,
    ink: Color? = null,
    enabled: Boolean = true,
    selected: Boolean? = null,
    stacked: Boolean = false,
    compact: Boolean = false,
) {
    val colors = LocalIndustrialColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors =
            ButtonDefaults.buttonColors(
                containerColor = fill ?: colors.key,
                contentColor = ink ?: colors.ink,
                disabledContainerColor = colors.key,
                disabledContentColor = colors.secondary,
            ),
        elevation =
            ButtonDefaults.buttonElevation(
                defaultElevation = 2.dp,
                pressedElevation = 0.dp,
                disabledElevation = 0.dp,
            ),
        border =
            BorderStroke(
                if (selected == true) 2.dp else 1.dp,
                if (selected == true) colors.ink else colors.outline,
            ),
        contentPadding = PaddingValues(horizontal = if (compact) 8.dp else 14.dp, vertical = 12.dp),
        modifier =
            modifier
                .heightIn(min = if (stacked) 76.dp else 68.dp)
                .then(
                    if (selected != null)
                        Modifier.semantics {
                            this.selected = selected
                            stateDescription = if (selected) "Selected" else "Not selected"
                        }
                    else Modifier
                ),
    ) {
        if (stacked)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                icon?.let { Glyph(it, modifier = Modifier.size(if (compact) 26.dp else 34.dp)) }
                Text(
                    label,
                    fontSize =
                        if (compact && LocalDensity.current.fontScale > 1.8f) 12.sp
                        else if (compact) 17.sp else 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
        else {
            icon?.let {
                Glyph(it, modifier = Modifier.size(if (compact) 26.dp else 32.dp))
                Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            }
            Text(
                label,
                fontSize = if (compact) 19.sp else 21.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
fun InstrumentIcon(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    muse: Boolean = false,
) {
    val colors = LocalIndustrialColors.current
    FilledIconButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor =
                    if (muse) if (colors.night) colors.muse else colors.accent else colors.key,
                contentColor =
                    if (muse) if (colors.night) colors.onMuse else colors.onAccent else colors.ink,
            ),
        modifier = modifier.size(56.dp).border(1.dp, colors.outline, RoundedCornerShape(8.dp)),
    ) {
        Glyph(icon, label, Modifier.size(30.dp))
    }
}

@Composable
fun ScreenHeader(
    title: String,
    back: () -> Unit,
    muse: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalIndustrialColors.current
    val large = LocalDensity.current.fontScale > 1.8f
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InstrumentIcon(LVIcons.Back, "Back to Home", back)
            if (!large)
                Text(
                    title,
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-.7).sp,
                    lineHeight = 28.sp,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            else Spacer(Modifier.weight(1f))
            muse?.let { InstrumentIcon(LVIcons.Mic, "Ask Muse", it, muse = true) }
        }
        if (large) {
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                fontSize = 23.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 26.sp,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.outline)
        Spacer(Modifier.height(12.dp))
    }
}
