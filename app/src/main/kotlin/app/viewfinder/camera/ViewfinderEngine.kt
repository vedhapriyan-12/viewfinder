package app.viewfinder.camera

import android.content.Context
import android.os.SystemClock
import android.view.Surface
import app.viewfinder.optics.RectD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Glue between the UI and the camera + GL pipeline. The UI says WHAT to show (display rect, crop, zoom);
 * the engine owns lifecycle and rotation.
 */
class ViewfinderEngine(private val ctx: Context) {
    private val render = RenderThread()
    private val _phone = MutableStateFlow<PhoneCameraInfo?>(null)
    val phone: StateFlow<PhoneCameraInfo?> = _phone
    private val _camState = MutableStateFlow(CameraController.State.CLOSED to "")
    val cameraState: StateFlow<Pair<CameraController.State, String>> = _camState

    private val controller = CameraController(ctx, render) { s, m -> _camState.value = s to m }

    private var surfaceReady = false
    private var resumed = false
    private var permission = false
    private var running = false
    private var zoom = 1f
    private var displayDeg = 0
    private var rect = RectD(0.0, 0.0, 0.0, 0.0)
    private var cropX = 1f
    private var cropY = 1f

    fun init() {
        _phone.value = PhoneCameras.find(ctx)
    }

    // ---- lifecycle inputs ----
    fun onSurfaceCreated(surface: Surface) = render.setWindow(surface)
    fun onSurfaceChanged(w: Int, h: Int) {
        render.setViewSize(w, h)
        surfaceReady = true
        maybeStart()
    }

    fun onSurfaceDestroyed() {
        surfaceReady = false
        stopCamera()
        render.clearWindowBlocking()
    }

    fun setResumed(v: Boolean) {
        resumed = v
        if (v) maybeStart() else stopCamera()
    }

    fun setPermission(v: Boolean) {
        permission = v
        maybeStart()
    }

    fun setDisplayRotation(deg: Int) {
        displayDeg = deg
        pushParams()
    }

    // ---- what to show ----
    fun setFraming(display: RectD, cropX: Float, cropY: Float, zoomRatio: Float) {
        rect = display
        this.cropX = cropX
        this.cropY = cropY
        if (kotlin.math.abs(zoomRatio - zoom) > 0.005f) {
            zoom = zoomRatio
            if (running) controller.updateZoom(zoom)
        }
        pushParams()
    }

    private fun pushParams() {
        val p = _phone.value
        val rel = if (p == null) 0 else ((p.sensorOrientation - displayDeg) % 360 + 360) % 360
        render.params = RenderThread.Params(
            left = rect.left.toInt(), top = rect.top.toInt(),
            width = rect.width.toInt(), height = rect.height.toInt(),
            cropX = cropX, cropY = cropY, relRotationDeg = rel,
        )
    }

    private fun maybeStart() {
        val p = _phone.value ?: return
        if (running || !resumed || !surfaceReady || !permission) return
        running = true
        pushParams()
        controller.start(
            CameraController.Config(
                cameraId = p.cameraId, physicalId = null, size = p.streamSize,
                fps = 30, zoom = zoom, stabilizationOff = true,
            ),
        )
    }

    private fun stopCamera() {
        if (!running) return
        running = false
        controller.stop()
    }

    fun release() {
        controller.release()
        render.release()
    }

    /** One-line-per-fact text for the info overlay. Also advances the metric window. */
    fun diagnostics(): String {
        val m = render.metrics.snapshot(SystemClock.elapsedRealtimeNanos())
        val p = _phone.value
        val (state, msg) = _camState.value
        val text = buildString {
            appendLine("camera $state  $msg")
            if (controller.lastResultSummary.isNotEmpty()) appendLine("result: ${controller.lastResultSummary}")
            appendLine("fps %.1f  worst gap %.1f ms  late %d".format(m.fps, m.maxIntervalMs, m.lateFrames))
            appendLine("latency avg %s ms (sensor to queued)".format(if (m.latencyAvgMs.isNaN()) "n/a" else "%.0f".format(m.latencyAvgMs)))
            appendLine("rotation: display $displayDeg  sensor ${p?.sensorOrientation}  matrix ${render.texRotationDeg}  we add ${render.appliedRotationDeg}")
            if (p != null) {
                appendLine("phone widest %.1f deg via %s".format(p.widestHfovDeg, p.source))
                appendLine("stream ${p.streamSize}  zoom range %.1f-%.1f".format(p.zoomMin, p.zoomMax))
            }
            append("GL ${render.glRenderer.substringBefore('|').trim()}")
        }
        android.util.Log.i("VF_DIAG", text.replace('\n', '|'))
        return text
    }
}
