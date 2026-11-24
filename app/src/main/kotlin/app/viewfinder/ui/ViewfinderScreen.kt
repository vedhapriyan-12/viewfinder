package app.viewfinder.ui

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.viewfinder.camera.ViewfinderEngine
import app.viewfinder.domain.RigMath
import app.viewfinder.optics.Framing
import app.viewfinder.optics.Guides
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ViewfinderScreen(vm: ViewfinderViewModel, engine: ViewfinderEngine, hasPermission: Boolean) {
    val rig by vm.rig.collectAsStateWithLifecycle()
    val phone by engine.phone.collectAsStateWithLifecycle()
    val pending by vm.pendingPicker.collectAsStateWithLifecycle()
    val d = remember(rig, phone) { RigMath.derive(rig, phone) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var diag by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pending) {
        if (pending != null) { picker = pending; vm.pendingPicker.value = null }
    }
    LaunchedEffect(rig.showInfo) {
        if (rig.showInfo) while (true) {
            diag = engine.diagnostics()
            delay(1000)
        }
    }

    // Latest values for gesture handlers, which are created once.
    val totalNow by rememberUpdatedState(d.framing.totalZoom)
    val digitalNow by rememberUpdatedState(rig.allowDigital)
    val phoneNow by rememberUpdatedState(RigMath.phoneModel(phone))
    var zoomJob by remember { mutableStateOf<Job?>(null) }

    fun setZoomNow(z: Double) {
        zoomJob?.cancel()
        vm.setZoom(z.coerceIn(phoneNow.zoomMin, Framing.maxTotalZoom(phoneNow, digitalNow)))
    }

    /** Preset taps glide to the target instead of jumping, so the picture zooms smoothly like a real lens. */
    fun glideZoomTo(target: Double) {
        zoomJob?.cancel()
        zoomJob = scope.launch {
            val a = Animatable(totalNow.toFloat())
            a.animateTo(target.toFloat(), tween(240)) { vm.setZoom(value.toDouble()) }
        }
    }

    Box(Modifier.fillMaxSize().background(Vf.Bg)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) = engine.onSurfaceCreated(h.surface)
                        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) = engine.onSurfaceChanged(w, hh)
                        override fun surfaceDestroyed(h: SurfaceHolder) = engine.onSurfaceDestroyed()
                    })
                }
            },
        )

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wPx = constraints.maxWidth.toDouble()
            val hPx = constraints.maxHeight.toDouble()
            val display = remember(wPx, hPx, d.displayAspect) { Guides.fit(wPx, hPx, d.displayAspect) }
            val frames = remember(display, d.framelines, d.displayAspect) {
                d.framelines.mapIndexed { i, f ->
                    FrameDraw(Guides.delivery(display, d.displayAspect, f.aspect), f.label, i == d.framelines.lastIndex)
                }
            }

            LaunchedEffect(display, d.framing) {
                engine.setFraming(display, d.framing.cropX.toFloat(), d.framing.cropY.toFloat(), d.framing.zoomRatio.toFloat())
            }
            LaunchedEffect(frames, d.framing) {
                frames.forEach {
                    Log.i("VF_FRAME", "label=${it.label} active=${it.active} aspect=%.4f rect=%.0f,%.0f %.0fx%.0f display=%.0fx%.0f zoomCam=%.3f extra=%.3f total=%.3f".format(
                        it.rect.width / it.rect.height, it.rect.left, it.rect.top, it.rect.width, it.rect.height,
                        display.width, display.height, d.framing.zoomRatio, d.framing.digitalExtra, d.framing.totalZoom,
                    ))
                }
                if (frames.isEmpty()) Log.i("VF_FRAME", "no framelines display=%.0fx%.0f zoomCam=%.3f extra=%.3f total=%.3f".format(
                    display.width, display.height, d.framing.zoomRatio, d.framing.digitalExtra, d.framing.totalZoom))
            }

            // Pinch anywhere on the picture. This layer sits under every control, so controls keep their own touches.
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTransformGestures { _, _, scale, _ ->
                        if (scale != 1f) {
                            zoomJob?.cancel()
                            vm.setZoom(Framing.pinch(totalNow, scale.toDouble(), phoneNow, digitalNow))
                        }
                    }
                },
            )

            FrameOverlay(display, frames, rig.safeAreas, rig.showThirds, Modifier.fillMaxSize())

            // Controls keep clear of a display cutout on whichever side it ends up after rotation.
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {

                // ---- left edge: tools stacked in the side bar, clear of the frame labels ----
                Column(
                    Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ToolButton(rig.framelines.isNotEmpty() || rig.safeAreas.isNotEmpty() || rig.showThirds, null, { picker = Picker.Guides }, { iconFrame(it) })
                    ToolButton(rig.squeezeOverride != null, "%.2fx".format(d.squeeze), vm::cycleSqueeze, { iconSqueeze(it) })
                    ToolButton(rig.showDof, null, vm::toggleDof, { iconDof(it) })
                    ToolButton(rig.showInfo, null, vm::toggleInfo, { iconInfo(it) })
                }
                if (rig.showInfo) {
                    VfText(
                        diag, mono = true, size = 11.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 52.dp)
                            .background(Vf.Panel, RoundedCornerShape(8.dp)).padding(8.dp),
                    )
                }

                // ---- top: the rig strip ----
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Chip(onClick = { picker = Picker.Camera }) {
                        Row { VfText("Body  ", color = Vf.Muted); VfText(d.camera.displayName) }
                    }
                    Chip(onClick = { picker = Picker.Camera }) {
                        Row { VfText("Mode  ", color = Vf.Muted); VfText(d.mode.label) }
                    }
                    Chip(accent = true, onClick = { picker = Picker.Lens }) {
                        Row {
                            VfText("Lens  ", color = Vf.Muted)
                            VfText("%.0f mm  T%.1f".format(rig.focalMm.coerceIn(d.lens.minFocal, d.lens.maxFocal), rig.tStop), color = Vf.Accent)
                        }
                    }
                }

                // ---- left: depth of field ribbon ----
                if (rig.showDof) {
                    DofRibbon(
                        focusM = rig.focusM,
                        nearM = d.dof.nearMm / 1000.0,
                        farM = if (d.dof.isFarInfinite) Double.POSITIVE_INFINITY else d.dof.farMm / 1000.0,
                        onFocus = vm::setFocus,
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp).height(150.dp),
                    )
                }

                // ---- right: zoom rail ----
                ZoomRail(
                    total = d.framing.totalZoom,
                    zoomMin = d.zoomMin,
                    maxCamera = d.zoomMaxCamera,
                    maxTotal = d.zoomMaxTotal,
                    manual = d.isManualZoom,
                    allowDigital = rig.allowDigital,
                    onZoom = ::setZoomNow,
                    onPreset = ::glideZoomTo,
                    onAuto = { zoomJob?.cancel(); vm.zoomAuto() },
                    onToggleDigital = vm::toggleDigital,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                )

                // ---- bottom: readout and lens barrel ----
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).fillMaxWidth(0.58f).widthIn(max = 560.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Limits and notes sit just above the readout, only while there is something to say.
                    if (d.framing.limited && !d.isManualZoom) {
                        Chip(warn = true) {
                            VfText(
                                "Wider than this phone can show. Widest is %.1f deg, wanted %.1f deg.".format(d.framing.achievedHfovDeg, d.framing.targetHfovDeg),
                                color = Vf.Warn,
                            )
                        }
                    }
                    if (d.framing.zoomCapped) {
                        Chip(warn = true) {
                            VfText(
                                "Camera zoom stops here: showing %.1f deg, lens wants %.1f deg. Turn Dig on to go further.".format(d.framing.achievedHfovDeg, d.framing.targetHfovDeg),
                                color = Vf.Warn,
                            )
                        }
                    }
                    if (d.framing.digitalExtra > 1.001) {
                        Chip(warn = true) { VfText("Digital zoom x%.2f, picture is softer".format(d.framing.digitalExtra), color = Vf.Warn) }
                    }

                    val aspect = d.active?.let { "${it.label} ${it.name}" } ?: "%.2f:1 full".format(d.displayAspect)
                    val manualTag = if (d.isManualZoom) " | manual zoom" else ""
                    VfText(
                        "%.1f x %.1f deg | %s | squeeze %.2fx%s".format(d.hfovDeg, d.vfovDeg, aspect, d.squeeze, manualTag),
                        mono = true, size = 12.sp, maxLines = 1,
                        modifier = Modifier.background(Vf.Panel, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    // While manual zoom overrides the lens, the ruler is dimmed. Touching it hands control back to the lens.
                    LensBarrel(rig.focalMm, d.lens, vm::setFocal, Modifier.fillMaxWidth().height(38.dp).alpha(if (d.isManualZoom) 0.45f else 1f))
                }

                if (!hasPermission) {
                    Chip(modifier = Modifier.align(Alignment.Center), warn = true) {
                        VfText("Camera permission needed to show the live view", color = Vf.Warn, size = 13.sp)
                    }
                }
            }

            // ---- panels ----
            when (picker) {
                Picker.Camera -> CameraPicker(rig, vm::selectCamera) { picker = null }
                Picker.Lens -> LensPicker(rig, vm::selectLens, vm::setFocal, vm::setT) { picker = null }
                Picker.Guides -> FramelinePicker(
                    rig, vm::toggleFrameline, vm::clearFramelines, vm::addCustomRatio, vm::removeCustomRatio,
                    vm::toggleSafeArea, vm::toggleThirds,
                ) { picker = null }
                null -> Unit
            }
        }
    }
}
