package app.viewfinder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Original look: near-black, one cool teal accent, amber only for limits and warnings. */
object Vf {
    val Bg = Color(0xFF0B0D10)
    val Panel = Color(0xEB16181C)
    val PanelSolid = Color(0xFF16181C)
    val Text = Color(0xFFE8EAED)
    val Muted = Color(0xFF8B949E)
    val Accent = Color(0xFF5DCAA5)
    val Warn = Color(0xFFEF9F27)
    val Line = Color(0xFF3A3F45)
    val Scrim = Color(0xA6000000)
}

@Composable
fun VfText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Vf.Text,
    size: TextUnit = 12.sp,
    mono: Boolean = false,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
    )
}

/** Small pill. [accent] outlines it in teal, [warn] in amber. */
@Composable
fun Chip(
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    warn: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val border = when {
        warn -> Vf.Warn
        accent -> Vf.Accent
        else -> Vf.Line
    }
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .background(Vf.Panel, shape)
            .border(0.5.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) { content() }
}
