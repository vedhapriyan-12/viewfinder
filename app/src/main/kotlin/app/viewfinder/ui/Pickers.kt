package app.viewfinder.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import app.viewfinder.domain.Frameline
import app.viewfinder.domain.Framelines
import app.viewfinder.optics.Guides
import kotlin.math.exp
import kotlin.math.ln
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.viewfinder.domain.CameraBody
import app.viewfinder.domain.LensSet
import app.viewfinder.domain.PlaceholderCatalog
import app.viewfinder.domain.Rig
import app.viewfinder.domain.SensorMode

enum class Picker { Camera, Lens, Guides }

/** Dimmed backdrop with a centered panel. Tapping the backdrop closes it. */
@Composable
fun Sheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Vf.Scrim)
            .clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.82f)
                .widthIn(max = 640.dp)
                .heightIn(max = 330.dp)
                .background(Vf.PanelSolid, RoundedCornerShape(14.dp))
                .border(0.5.dp, Vf.Line, RoundedCornerShape(14.dp))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VfText(title, size = 14.sp, weight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Chip(accent = true, onClick = onClose) { VfText("Done", color = Vf.Accent) }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun PickRow(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Vf.Accent.copy(alpha = 0.14f) else Vf.Bg, shape)
            .border(0.5.dp, if (selected) Vf.Accent else Vf.Line, shape)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) { content() }
}

@Composable
private fun UnverifiedTag() = VfText("unverified data", color = Vf.Warn, size = 11.sp)

/** Rows of chips, [perRow] per row, without needing a flow layout. */
@Composable
private fun <T> ChipGrid(items: List<T>, perRow: Int, chip: @Composable (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(perRow).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { chip(it) } }
        }
    }
}

@Composable
fun CameraPicker(rig: Rig, onPick: (CameraBody, SensorMode) -> Unit, onClose: () -> Unit) {
    var bodyId by remember { mutableStateOf(rig.cameraId) }
    val body = PlaceholderCatalog.camera(bodyId)
    Sheet("Camera and sensor mode", onClose) {
        Row(Modifier.heightIn(max = 240.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(PlaceholderCatalog.cameras) { c ->
                    PickRow(c.id == bodyId, { bodyId = c.id }) {
                        Column {
                            VfText(c.displayName, color = if (c.id == bodyId) Vf.Accent else Vf.Text, size = 13.sp)
                            if (!c.verified) UnverifiedTag()
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(body.modes) { m ->
                    PickRow(body.id == rig.cameraId && m.id == rig.modeId, { onPick(body, m); onClose() }) {
                        Column {
                            VfText(m.label, size = 13.sp)
                            VfText("%.2f x %.2f mm  %.2f:1".format(m.widthMm, m.heightMm, m.aspect), color = Vf.Muted, size = 11.sp, mono = true)
                        }
                    }
                }
                item { if (body.note.isNotEmpty()) VfText(body.note, color = Vf.Muted, size = 11.sp) }
            }
        }
    }
}

@Composable
fun LensPicker(
    rig: Rig,
    onLens: (LensSet) -> Unit,
    onFocal: (Double) -> Unit,
    onT: (Double) -> Unit,
    onClose: () -> Unit,
) {
    val lens = PlaceholderCatalog.lens(rig.lensId)
    Sheet("Lens", onClose) {
        Row(Modifier.heightIn(max = 240.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(PlaceholderCatalog.lenses) { l ->
                    PickRow(l.id == rig.lensId, { onLens(l) }) {
                        Column {
                            VfText(l.name, color = if (l.id == rig.lensId) Vf.Accent else Vf.Text, size = 13.sp)
                            VfText(if (l.squeeze == 1.0) "spherical" else "%.2fx squeeze".format(l.squeeze), color = Vf.Muted, size = 11.sp)
                            if (!l.verified) UnverifiedTag()
                        }
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                VfText("Focal length", color = Vf.Muted, size = 11.sp)
                if (lens.isZoom) {
                    VfText("Zoom %.0f-%.0f mm. Slide the lens barrel on the viewfinder.".format(lens.minFocal, lens.maxFocal), size = 12.sp)
                } else {
                    ChipGrid(lens.primes, 4) { p ->
                        Chip(accent = kotlin.math.abs(p - rig.focalMm) < 0.5, onClick = { onFocal(p) }) {
                            VfText("%.0f".format(p), mono = true)
                        }
                    }
                }
                VfText("T-stop", color = Vf.Muted, size = 11.sp)
                ChipGrid(PlaceholderCatalog.tStops, 4) { t ->
                    Chip(accent = t == rig.tStop, onClick = { onT(t) }) { VfText("T%.1f".format(t), mono = true) }
                }
            }
        }
    }
}

/** Small picture of an aspect ratio: the frame drawn to scale inside a fixed box. */
@Composable
fun AspectThumb(aspect: Double, enabled: Boolean, active: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 50.dp, height = 32.dp)) {
        val box = Guides.fit(size.width.toDouble() - 6.dp.toPx(), size.height.toDouble() - 6.dp.toPx(), aspect)
        val l = (box.left + 3.dp.toPx()).toFloat()
        val t = (box.top + 3.dp.toPx()).toFloat()
        val w = box.width.toFloat()
        val h = box.height.toFloat()
        val color = if (enabled) Vf.Accent else Vf.Muted
        drawRoundRect(color.copy(alpha = if (enabled) 0.28f else 0.12f), Offset(l, t), Size(w, h), CornerRadius(2.dp.toPx()))
        drawRoundRect(color, Offset(l, t), Size(w, h), CornerRadius(2.dp.toPx()), style = Stroke(if (active) 2.dp.toPx() else 1.dp.toPx()))
    }
}

@Composable
private fun FramelineCell(f: Frameline, on: Boolean, active: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(if (on) Vf.Accent.copy(alpha = 0.12f) else Vf.Bg, shape)
            .border(if (active) 1.5.dp else 0.5.dp, if (on) Vf.Accent else Vf.Line, shape)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AspectThumb(f.aspect, on, active)
            Column(Modifier.weight(1f)) {
                VfText(f.label, mono = true, size = 13.sp, color = if (on) Vf.Accent else Vf.Text, maxLines = 1)
                VfText(f.name, color = Vf.Muted, size = 11.sp, maxLines = 1)
            }
        }
        if (onRemove != null) {
            Box(Modifier.align(Alignment.TopEnd).clickable { onRemove() }.padding(horizontal = 6.dp, vertical = 0.dp)) {
                VfText("x", color = Vf.Muted, size = 13.sp)
            }
        }
    }
}

/** Ratio chooser for a custom frameline: coarse drag bar plus fine plus/minus steps. */
@Composable
private fun CustomRatioEditor(onAdd: (Double) -> Unit) {
    var value by remember { mutableStateOf(2.10) }
    val lo = ln(0.4)
    val hi = ln(4.0)
    fun set(v: Double) { value = (Math.round(Framelines.clampCustom(v) * 100.0) / 100.0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AspectThumb(value, true, false)
            Chip(onClick = { set(value - 0.01) }) { VfText("-", mono = true, size = 14.sp) }
            VfText("%.2f:1".format(value), mono = true, size = 15.sp, color = Vf.Accent)
            Chip(onClick = { set(value + 0.01) }) { VfText("+", mono = true, size = 14.sp) }
            Chip(accent = true, onClick = { onAdd(value) }) { VfText("Add custom", color = Vf.Accent) }
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, _ ->
                        set(exp(lo + (change.position.x / size.width).coerceIn(0f, 1f) * (hi - lo)))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { o -> set(exp(lo + (o.x / size.width).coerceIn(0f, 1f) * (hi - lo))) }
                },
        ) {
            val cy = size.height / 2f
            drawLine(Vf.Line, Offset(0f, cy), Offset(size.width, cy), 2.dp.toPx())
            val x = ((ln(value.coerceIn(0.4, 4.0)) - lo) / (hi - lo)).toFloat() * size.width
            drawLine(Vf.Accent, Offset(0f, cy), Offset(x, cy), 2.dp.toPx())
            drawCircle(Vf.Accent, 7.dp.toPx(), Offset(x, cy))
        }
    }
}

@Composable
fun FramelinePicker(
    rig: Rig,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onAddCustom: (Double) -> Unit,
    onRemoveCustom: (Double) -> Unit,
    onSafe: (Int) -> Unit,
    onThirds: () -> Unit,
    onClose: () -> Unit,
) {
    val activeId = rig.framelines.lastOrNull()
    Sheet("Frame lines", onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { VfText("Cinema and film", color = Vf.Muted, size = 11.sp) }
                items(Framelines.cinema) { f -> FramelineCell(f, f.id in rig.framelines, f.id == activeId, { onToggle(f.id) }) }
                item(span = { GridItemSpan(maxLineSpan) }) { VfText("Other ratios", color = Vf.Muted, size = 11.sp) }
                items(Framelines.other) { f -> FramelineCell(f, f.id in rig.framelines, f.id == activeId, { onToggle(f.id) }) }
                item(span = { GridItemSpan(maxLineSpan) }) { VfText("Custom", color = Vf.Muted, size = 11.sp) }
                item(span = { GridItemSpan(maxLineSpan) }) { CustomRatioEditor(onAddCustom) }
                items(rig.customRatios) { a ->
                    val f = Framelines.custom(a)
                    FramelineCell(f, f.id in rig.framelines, f.id == activeId, { onToggle(f.id) }, { onRemoveCustom(a) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VfText("Safe area", color = Vf.Muted, size = 11.sp)
                Framelines.safeAreas.forEach { p ->
                    Chip(accent = p in rig.safeAreas, onClick = { onSafe(p) }) { VfText("$p%", mono = true, size = 12.sp) }
                }
                Chip(accent = rig.showThirds, onClick = onThirds) { VfText("Thirds", size = 12.sp) }
                Box(Modifier.weight(1f))
                Chip(onClick = onClear) { VfText("Clear all", color = Vf.Muted, size = 12.sp) }
            }
        }
    }
}
