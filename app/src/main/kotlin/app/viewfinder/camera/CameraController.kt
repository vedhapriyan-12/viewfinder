package app.viewfinder.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Camera2 session feeding the render thread's SurfaceTexture.
 * All camera work happens on the "vf-camera" thread. State callbacks arrive on that thread too.
 */
class CameraController(
    ctx: Context,
    private val render: RenderThread,
    private val onState: (State, String) -> Unit,
) {
    enum class State { CLOSED, OPENING, STREAMING, ERROR }

    data class Config(
        val cameraId: String,
        val physicalId: String?,
        val size: Size,
        val fps: Int,
        val zoom: Float,
        val stabilizationOff: Boolean,
    )

    private val mgr = ctx.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("vf-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    // camera-thread state
    private var generation = 0
    private var config: Config? = null
    private var chars: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var surface: Surface? = null
    private var pendingAfterClose: (() -> Unit)? = null
    private var startedAtMs = 0L
    private var firstResult = true
    private var resultCount = 0L

    @Volatile var lastResultSummary: String = ""
        private set
    @Volatile var lastOpenMs: Long = -1
        private set

    fun characteristics(id: String): CameraCharacteristics = mgr.getCameraCharacteristics(id)

    /** Closes any open camera and opens [cfg]. Safe to call repeatedly. */
    fun start(cfg: Config) {
        handler.post {
            generation++
            val gen = generation
            closeThen {
                if (gen != generation) return@closeThen
                config = cfg
                chars = mgr.getCameraCharacteristics(cfg.cameraId)
                val c = chars!!
                render.metrics.targetFps = cfg.fps
                render.metrics.realtimeTimestamps =
                    c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
                startedAtMs = SystemClock.elapsedRealtime()
                firstResult = true
                resultCount = 0
                setState(State.OPENING, "opening ${cfg.cameraId}${cfg.physicalId?.let { "/$it" } ?: ""}")
                render.prepareCameraSurface(cfg.size.width, cfg.size.height) { s ->
                    handler.post { if (gen == generation) openDevice(cfg, s, gen) }
                }
            }
        }
    }

    fun stop() {
        handler.post {
            generation++
            closeThen { setState(State.CLOSED, "closed") }
        }
    }

    fun updateZoom(zoom: Float) {
        handler.post {
            val cfg = config ?: return@post
            config = cfg.copy(zoom = zoom)
            pushRepeating()
        }
    }

    fun release() {
        stop()
        thread.quitSafely()
    }

    // ---------------- camera-thread internals ----------------

    private fun closeThen(block: () -> Unit) {
        val d = device
        if (d == null) {
            block()
            return
        }
        pendingAfterClose = block
        try { session?.close() } catch (_: Throwable) {}
        session = null
        d.close() // onClosed continues
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(cfg: Config, surf: Surface, gen: Int) {
        surface = surf
        try {
            mgr.openCamera(cfg.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != generation) { camera.close(); return }
                    device = camera
                    createSession(camera, cfg, surf, gen)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (gen == generation) setState(State.ERROR, "disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (gen == generation) setState(State.ERROR, "device error $error")
                }

                override fun onClosed(camera: CameraDevice) {
                    if (device === camera) device = null
                    session = null
                    val p = pendingAfterClose
                    pendingAfterClose = null
                    p?.invoke()
                }
            }, handler)
        } catch (e: SecurityException) {
            setState(State.ERROR, "no camera permission")
        } catch (e: CameraAccessException) {
            setState(State.ERROR, "open failed: ${e.message}")
        } catch (e: IllegalArgumentException) {
            setState(State.ERROR, "bad camera id: ${e.message}")
        }
    }

    private fun createSession(cam: CameraDevice, cfg: Config, surf: Surface, gen: Int) {
        try {
            val out = OutputConfiguration(surf)
            cfg.physicalId?.let { out.setPhysicalCameraId(it) }
            val sc = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(out),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation) { s.close(); return }
                        session = s
                        try {
                            builder = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surf) }
                            pushRepeating()
                        } catch (e: Exception) {
                            setState(State.ERROR, "request failed: ${e.message}")
                        }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (gen == generation) setState(State.ERROR, "session configure failed")
                    }
                },
            )
            cam.createCaptureSession(sc)
        } catch (e: Exception) {
            setState(State.ERROR, "createSession: ${e.message}")
        }
    }

    private fun pushRepeating() {
        val b = builder ?: return
        val s = session ?: return
        val cfg = config ?: return
        val c = chars ?: return
        try {
            applyControls(b, c, cfg)
            s.setRepeatingRequest(b.build(), captureCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "setRepeatingRequest failed", e)
            setState(State.ERROR, "repeating: ${e.message}")
        }
    }

    private fun applyControls(b: CaptureRequest.Builder, c: CameraCharacteristics, cfg: Config) {
        // FPS: prefer a fixed [fps, fps] range, else the range with that upper bound and highest lower bound.
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val best = ranges.filter { it.upper == cfg.fps }.maxByOrNull { it.lower }
            ?: ranges.filter { it.upper <= cfg.fps }.maxByOrNull { it.upper * 1000 + it.lower }
        best?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        // Continuous AF so the preview is sharp for visual checks.
        val afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList().orEmpty()
        if (CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // Zoom: ZOOM_RATIO on API 30+, crop region on API 29.
        if (Build.VERSION.SDK_INT >= 30) {
            val range = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val z = if (range != null) cfg.zoom.coerceIn(range.lower, range.upper) else cfg.zoom
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
        } else {
            val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val maxZ = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            if (active != null) {
                val z = cfg.zoom.coerceIn(1f, maxZ)
                val w = (active.width() / z).toInt()
                val h = (active.height() / z).toInt()
                val l = active.left + (active.width() - w) / 2
                val t = active.top + (active.height() - h) / 2
                b.set(CaptureRequest.SCALER_CROP_REGION, Rect(l, t, l + w, t + h))
            }
        }

        // Do not let the HAL rotate/crop for us: FOV maths assumes the plain sensor stream.
        if (Build.VERSION.SDK_INT >= 31) {
            val rc = c.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)?.toList().orEmpty()
            if (CaptureRequest.SCALER_ROTATE_AND_CROP_NONE in rc) {
                b.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
            }
        }

        if (cfg.stabilizationOff) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty()
            if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF in ois) {
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, r: TotalCaptureResult) {
            if (firstResult) {
                firstResult = false
                lastOpenMs = SystemClock.elapsedRealtime() - startedAtMs
                setState(State.STREAMING, "streaming (first result ${lastOpenMs} ms)")
            }
            if (++resultCount % 10L == 0L) lastResultSummary = summarize(r)
        }
    }

    private fun summarize(r: TotalCaptureResult): String {
        val sb = StringBuilder()
        r.get(CaptureResult.LENS_FOCAL_LENGTH)?.let { sb.append("f=%.2fmm ".format(it)) }
        if (Build.VERSION.SDK_INT >= 30) r.get(CaptureResult.CONTROL_ZOOM_RATIO)?.let { sb.append("zoom=%.2f ".format(it)) }
        r.get(CaptureResult.SCALER_CROP_REGION)?.let { sb.append("crop=${it.width()}x${it.height()} ") }
        r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)?.let { sb.append("activePhys=$it ") }
        r.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { sb.append("focus=%.2fD ".format(it)) }
        r.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)?.let {
            sb.append("fx=%.0f fy=%.0f ".format(it[0], it[1]))
        }
        return sb.toString().trim()
    }

    private fun setState(s: State, msg: String) {
        Log.i(TAG, "$s: $msg")
        onState(s, msg)
    }

    private companion object {
        const val TAG = "VF_CAMERA"
    }
}

