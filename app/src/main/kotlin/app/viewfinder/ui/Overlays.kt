package app.viewfinder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.viewfinder.domain.LensSet
import app.viewfinder.optics.Guides
import app.viewfinder.optics.RectD
import kotlin.math.exp
import kotlin.math.ln

/** One frameline to draw. [active] ones get the dim mask and the bright line. */
data class FrameDraw(val rect: RectD, val label: String, val active: Boolean)

/**
 * Draws all enabled framelines over the live picture without touching the picture itself.
 * The active frameline also dims everything outside it, and carries the safe-area insets and the centre mark.
 */
@Composable
fun FrameOverlay(
    display: RectD,
    frames: List<FrameDraw>,
    safeAreas: List<Int>,
    showThirds: Boolean,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color(0xCCE8EAED), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val safeStyle = TextStyle(color = Vf.Accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

    Canvas(modifier) {
        fun f(v: Double) = v.toFloat()
        val active = frames.lastOrNull { it.active }
        val dim = Color(0x99000000)

        if (active != null) {
            val a = active.rect
            val topH = f(a.top - display.top).coerceAtLeast(0f)
            val botH = f(display.bottom - a.bottom).coerceAtLeast(0f)
            val leftW = f(a.left - display.left).coerceAtLeast(0f)
            val rightW = f(display.right - a.right).coerceAtLeast(0f)
            if (topH > 0f) drawRect(dim, Offset(f(display.left), f(display.top)), Size(f(display.width), topH))
            if (botH > 0f) drawRect(dim, Offset(f(display.left), f(a.bottom)), Size(f(display.width), botH))
            if (leftW > 0f) drawRect(dim, Offset(f(display.left), f(a.top)), Size(leftW, f(a.height)))
            if (rightW > 0f) drawRect(dim, Offset(f(a.right), f(a.top)), Size(rightW, f(a.height)))
        }

        // Inactive framelines first (thin, dashed, teal), the active one last so it sits on top.
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
        val placed = mutableListOf<Offset>()
        for (fr in frames.filter { !it.active } + frames.filter { it.active }) {
            val r = fr.rect
            if (fr.active) {
                drawRect(Color(0xE6E8EAED), Offset(f(r.left), f(r.top)), Size(f(r.width), f(r.height)), style = Stroke(1.2.dp.toPx()))
            } else {
                drawRect(Vf.Accent.copy(alpha = 0.8f), Offset(f(r.left), f(r.top)), Size(f(r.width), f(r.height)), style = Stroke(0.9.dp.toPx(), pathEffect = dash))
            }
            // Keep labels clear of the tool column on the left and the rig strip on top, and off each other.
            val step = 13.dp.toPx()
            val x = maxOf(f(r.left) + 4.dp.toPx(), 62.dp.toPx())
            var y = maxOf(f(r.top) + 2.dp.toPx(), 50.dp.toPx())
            while (placed.any { kotlin.math.abs(it.y - y) < step && kotlin.math.abs(it.x - x) < 80.dp.toPx() }) y += step
            if (y < f(r.bottom) - step && x < f(r.right) - 40.dp.toPx()) {
                // A dark pill keeps the label readable over bright scenes.
                val m = tm.measure(fr.label, labelStyle)
                val padX = 4.dp.toPx()
                val padY = 1.dp.toPx()
                drawRoundRect(
                    Color(0xB3000000),
                    Offset(x - padX, y - padY),
                    Size(m.size.width + 2 * padX, m.size.height + 2 * padY),
                    CornerRadius(4.dp.toPx()),
                )
                drawText(tm, fr.label, Offset(x, y), labelStyle.copy(color = if (fr.active) Color(0xFFE8EAED) else Vf.Accent))
                placed += Offset(x, y)
            }
        }

        // Safe areas and thirds follow the active frameline, or the whole picture when none is on.
        val base = active?.rect ?: display
        val safeDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        for (pct in safeAreas) {
            val r = Guides.inset(base, pct / 100.0)
            drawRect(Vf.Accent.copy(alpha = 0.7f), Offset(f(r.left), f(r.top)), Size(f(r.width), f(r.height)), style = Stroke(0.8.dp.toPx(), pathEffect = safeDash))
            val txt = "$pct%"
            val m = tm.measure(txt, safeStyle)
            drawText(tm, txt, Offset(f(r.right) - m.size.width - 4.dp.toPx(), f(r.bottom) - m.size.height - 2.dp.toPx()), safeStyle)
        }

        if (showThirds) {
            val faint = Color(0x66E8EAED)
            for (i in 1..2) {
                val x = f(base.left + base.width * i / 3.0)
                val y = f(base.top + base.height * i / 3.0)
                drawLine(faint, Offset(x, f(base.top)), Offset(x, f(base.bottom)), 0.5.dp.toPx())
                drawLine(faint, Offset(f(base.left), y), Offset(f(base.right), y), 0.5.dp.toPx())
            }
        }
        val cx = f(base.centerX)
        val cy = f(base.centerY)
        val r = 7.dp.toPx()
        drawLine(Vf.Accent, Offset(cx - r, cy), Offset(cx + r, cy), 1.dp.toPx())
        drawLine(Vf.Accent, Offset(cx, cy - r), Offset(cx, cy + r), 1.dp.toPx())
    }
}

private val ZOOM_PRESETS = listOf(0.5, 1.0, 2.0, 3.0, 5.0)

/**
 * Vertical camera-style zoom control for the right edge. Top = zoom in. Drag the thumb for smooth zoom or tap a preset.
 * Presets the camera cannot reach are not drawn on the track; the 0.5x one is shown dimmed so the limit is visible.
 */
@Composable
fun ZoomRail(
    total: Double,
    zoomMin: Double,
    maxCamera: Double,
    maxTotal: Double,
    manual: Boolean,
    allowDigital: Boolean,
    onZoom: (Double) -> Unit,
    onPreset: (Double) -> Unit,
    onAuto: () -> Unit,
    onToggleDigital: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    val onZoomNow by rememberUpdatedState(onZoom)
    val onPresetNow by rememberUpdatedState(onPreset)
    val lo = ln(zoomMin)
    val hi = ln(maxTotal.coerceAtLeast(zoomMin * 1.0001))
    val inRange = ZOOM_PRESETS.filter { it >= zoomMin - 1e-6 && it <= maxTotal + 1e-6 }
    val labelStyle = TextStyle(color = Vf.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val hotStyle = TextStyle(color = Vf.Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

    Column(
        modifier
            .background(Vf.Panel, RoundedCornerShape(14.dp))
            .border(0.5.dp, Vf.Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        VfText("%.1fx".format(total), color = if (manual) Vf.Text else Vf.Accent, size = 15.sp, mono = true, weight = FontWeight.Medium)
        VfText(if (manual) "MANUAL" else "AUTO", color = Vf.Muted, size = 11.sp, mono = true)

        Canvas(
            Modifier
                .width(56.dp)
                .height(150.dp)
                .pointerInput(zoomMin, maxTotal) {
                    // The track leaves a margin at both ends so the end labels are never clipped.
                    val pad = 9.dp.toPx()
                    detectVerticalDragGestures { change, _ ->
                        val t = 1f - ((change.position.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                        onZoomNow(exp(lo + t * (hi - lo)))
                    }
                }
                .pointerInput(zoomMin, maxTotal) {
                    val pad = 9.dp.toPx()
                    detectTapGestures { o ->
                        val t = 1f - ((o.y - pad) / (size.height - 2 * pad)).coerceIn(0f, 1f)
                        val z = exp(lo + t * (hi - lo))
                        // Snap to a preset when the tap lands near its tick.
                        val near = inRange.minByOrNull { kotlin.math.abs(ln(it) - ln(z)) }
                        val nearY = near?.let { pad + (size.height - 2 * pad) * (1f - ((ln(it) - lo) / (hi - lo)).toFloat()) }
                        if (near != null && nearY != null && kotlin.math.abs(nearY - o.y) < 16.dp.toPx()) onPresetNow(near) else onZoomNow(z)
                    }
                },
        ) {
            val pad = 9.dp.toPx()
            fun y(z: Double) = pad + (size.height - 2 * pad) * (1f - ((ln(z.coerceIn(zoomMin, maxTotal)) - lo) / (hi - lo)).toFloat())
            val tx = size.width - 12.dp.toPx()
            drawLine(Vf.Line, Offset(tx, pad), Offset(tx, size.height - pad), 1.5.dp.toPx())
            if (allowDigital && maxTotal > maxCamera) {
                // The stretch above the camera's own range is digital magnification.
                drawLine(Vf.Warn.copy(alpha = 0.8f), Offset(tx, pad), Offset(tx, y(maxCamera)), 3.dp.toPx())
            }
            val zNow = total.coerceIn(zoomMin, maxTotal)
            drawLine(Vf.Accent.copy(alpha = 0.5f), Offset(tx, size.height - pad), Offset(tx, y(zNow)), 3.dp.toPx())

            for (p in inRange) {
                val py = y(p)
                val hot = kotlin.math.abs(ln(p) - ln(zNow)) < 0.05
                drawLine(if (hot) Vf.Accent else Vf.Muted, Offset(tx - 8.dp.toPx(), py), Offset(tx, py), 1.dp.toPx())
                val label = "%.0fx".format(p)
                val style = if (hot) hotStyle else labelStyle
                val m = tm.measure(label, style)
                drawText(tm, label, Offset(tx - 12.dp.toPx() - m.size.width, py - m.size.height / 2f), style)
            }
            drawCircle(Vf.Bg, 8.dp.toPx(), Offset(tx, y(zNow)))
            drawCircle(if (manual) Vf.Text else Vf.Accent, 6.dp.toPx(), Offset(tx, y(zNow)))
        }

        if (zoomMin > 0.5 + 1e-6) VfText("0.5x n/a", color = Vf.Muted.copy(alpha = 0.6f), size = 11.sp, mono = true)

        Chip(accent = !manual, onClick = onAuto) { VfText("Auto", color = if (!manual) Vf.Accent else Vf.Text, size = 11.sp) }
        Chip(warn = allowDigital, onClick = onToggleDigital) {
            VfText(if (allowDigital) "Dig on" else "Dig off", color = if (allowDigital) Vf.Warn else Vf.Muted, size = 11.sp)
        }
    }
}

/** Vertical focus ribbon: the teal band is what is acceptably sharp. Drag to change the focus distance. */
@Composable
fun DofRibbon(
    focusM: Double,
    nearM: Double,
    farM: Double,
    onFocus: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lo = ln(0.5)
    val hi = ln(60.0)
    fun frac(d: Double) = ((ln(d.coerceIn(0.5, 60.0)) - lo) / (hi - lo)).toFloat()
    val onFocusNow by rememberUpdatedState(onFocus)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(
            Modifier
                .width(26.dp)
                .fillMaxHeight()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, _ ->
                        val t = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        onFocusNow(exp(lo + t * (hi - lo)))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val t = 1f - (o.y / size.height).coerceIn(0f, 1f)
                        onFocusNow(exp(lo + t * (hi - lo)))
                    }
                },
        ) {
            val cx = size.width / 2f
            drawLine(Vf.Line, Offset(cx, 0f), Offset(cx, size.height), 1.dp.toPx())
            val yNear = size.height * (1f - frac(nearM))
            val yFar = size.height * (1f - frac(if (farM.isInfinite()) 60.0 else farM))
            drawRoundRect(
                Vf.Accent,
                Offset(cx - 3.dp.toPx(), yFar),
                Size(6.dp.toPx(), (yNear - yFar).coerceAtLeast(2.dp.toPx())),
                CornerRadius(3.dp.toPx()),
            )
            val yF = size.height * (1f - frac(focusM))
            drawLine(Vf.Text, Offset(cx - 10.dp.toPx(), yF), Offset(cx + 10.dp.toPx(), yF), 1.5.dp.toPx())
        }
        Column(
            Modifier
                .padding(start = 2.dp)
                .background(Vf.Panel, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            VfText("Sharp zone", color = Vf.Muted, size = 11.sp)
            VfText(
                "%.2f - %s m".format(nearM, if (farM.isInfinite()) "inf" else "%.2f".format(farM)),
                mono = true, size = 12.sp,
            )
            VfText("focus %.1f m".format(focusM), color = Vf.Muted, size = 11.sp, mono = true)
        }
    }
}

private val RULER_MARKS = listOf(14, 16, 18, 20, 24, 28, 35, 40, 50, 60, 75, 85, 100, 120, 135)
private val RULER_LABELS = setOf(18, 24, 35, 50, 75, 100)
private const val RULER_LO = 12.0
private const val RULER_HI = 150.0

/** Horizontal focal-length ruler. Primes snap to the set's real focal lengths; zooms slide continuously. */
@Composable
fun LensBarrel(
    focalMm: Double,
    lens: LensSet,
    onFocal: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tm = rememberTextMeasurer()
    val onFocalNow by rememberUpdatedState(onFocal)
    val lo = ln(RULER_LO)
    val hi = ln(RULER_HI)
    val labelStyle = TextStyle(color = Vf.Muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val valueStyle = TextStyle(color = Vf.Accent, fontSize = 12.sp, fontFamily = FontFamily.Monospace)

    Canvas(
        modifier
            .background(Vf.Panel, RoundedCornerShape(8.dp))
            .border(0.5.dp, Vf.Line, RoundedCornerShape(8.dp))
            .pointerInput(lens) {
                detectTapGestures { o -> onFocalNow(exp(lo + (o.x / size.width).coerceIn(0f, 1f) * (hi - lo))) }
            }
            .pointerInput(lens) {
                detectHorizontalDragGestures { change, _ ->
                    onFocalNow(exp(lo + (change.position.x / size.width).coerceIn(0f, 1f) * (hi - lo)))
                }
            },
    ) {
        fun x(mm: Double) = ((ln(mm) - lo) / (hi - lo)).toFloat() * size.width
        val base = size.height

        // Zoom range band, or the prime set's focal lengths as tall teal ticks.
        if (lens.isZoom) {
            drawRect(Vf.Accent.copy(alpha = 0.25f), Offset(x(lens.minFocal), 0f), Size(x(lens.maxFocal) - x(lens.minFocal), base))
        }
        for (mm in RULER_MARKS) {
            val isPrime = !lens.isZoom && lens.primes.any { kotlin.math.abs(it - mm) < 0.5 }
            val h = when {
                isPrime -> 16.dp.toPx()
                mm in RULER_LABELS -> 10.dp.toPx()
                else -> 5.dp.toPx()
            }
            drawLine(if (isPrime) Vf.Accent else Vf.Line, Offset(x(mm.toDouble()), base), Offset(x(mm.toDouble()), base - h), 1.dp.toPx())
            // The active value gets its own label next to the marker, so skip the static one underneath it.
            if (mm in RULER_LABELS && kotlin.math.abs(ln(mm.toDouble()) - ln(focalMm)) > 0.08) {
                val t = tm.measure(mm.toString(), labelStyle)
                drawText(tm, mm.toString(), Offset(x(mm.toDouble()) - t.size.width / 2f, 2.dp.toPx()), labelStyle)
            }
        }
        if (!lens.isZoom) {
            for (p in lens.primes) {
                if (p.toInt() !in RULER_MARKS) drawLine(Vf.Accent, Offset(x(p), base), Offset(x(p), base - 16.dp.toPx()), 1.dp.toPx())
            }
        }

        // Current value marker with its own label.
        val cx = x(focalMm)
        drawLine(Vf.Accent, Offset(cx, 0f), Offset(cx, base), 2.dp.toPx())
        val label = "%.0f".format(focalMm)
        val m = tm.measure(label, valueStyle)
        val lx = (cx + 4.dp.toPx()).coerceAtMost(size.width - m.size.width - 2.dp.toPx())
        drawText(tm, label, Offset(lx, base - m.size.height - 1.dp.toPx()), valueStyle)
    }
}

/** Round tool button with a small drawn icon and an optional caption. */
@Composable
fun ToolButton(
    active: Boolean,
    caption: String?,
    onClick: () -> Unit,
    icon: DrawScope.(Color) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(40.dp)
                .background(Vf.Panel, CircleShape)
                .border(0.5.dp, if (active) Vf.Accent else Vf.Line, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) { icon(if (active) Vf.Accent else Vf.Text) }
        }
        if (caption != null) VfText(caption, color = Vf.Muted, size = 11.sp, mono = true)
    }
}

fun DrawScope.iconFrame(c: Color) {
    val s = Stroke(1.5.dp.toPx())
    drawRoundRect(c, Offset(0f, size.height * 0.18f), Size(size.width, size.height * 0.64f), CornerRadius(2.dp.toPx()), style = s)
    drawRoundRect(c.copy(alpha = 0.55f), Offset(size.width * 0.22f, size.height * 0.34f), Size(size.width * 0.56f, size.height * 0.32f), CornerRadius(1.dp.toPx()), style = Stroke(1.dp.toPx()))
}

fun DrawScope.iconSqueeze(c: Color) {
    val w = 1.5.dp.toPx()
    val y = size.height / 2f
    val a = size.width * 0.28f
    drawLine(c, Offset(2.dp.toPx(), y), Offset(size.width - 2.dp.toPx(), y), w)
    drawLine(c, Offset(2.dp.toPx(), y), Offset(2.dp.toPx() + a / 2f, y - a / 2f), w)
    drawLine(c, Offset(2.dp.toPx(), y), Offset(2.dp.toPx() + a / 2f, y + a / 2f), w)
    drawLine(c, Offset(size.width - 2.dp.toPx(), y), Offset(size.width - 2.dp.toPx() - a / 2f, y - a / 2f), w)
    drawLine(c, Offset(size.width - 2.dp.toPx(), y), Offset(size.width - 2.dp.toPx() - a / 2f, y + a / 2f), w)
}

fun DrawScope.iconDof(c: Color) {
    val ctr = Offset(size.width / 2f, size.height / 2f)
    drawCircle(c, size.width * 0.46f, ctr, style = Stroke(1.5.dp.toPx()))
    drawCircle(c, size.width * 0.14f, ctr)
}

fun DrawScope.iconInfo(c: Color) {
    val ctr = Offset(size.width / 2f, size.height / 2f)
    drawCircle(c, size.width * 0.46f, ctr, style = Stroke(1.5.dp.toPx()))
    drawCircle(c, 1.4.dp.toPx(), Offset(ctr.x, size.height * 0.30f))
    drawLine(c, Offset(ctr.x, size.height * 0.46f), Offset(ctr.x, size.height * 0.72f), 1.6.dp.toPx())
}
