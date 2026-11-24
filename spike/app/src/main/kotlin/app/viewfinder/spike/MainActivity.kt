package app.viewfinder.spike

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Phase 0 spike. Throwaway UI built from plain Views: one SurfaceView, one overlay, one row of toggle buttons.
 * Everything here exists to answer the experiments in docs/phase0/SPIKE_PLAN.md (E1-E7).
 */
class MainActivity : Activity(), SensorEventListener {

    // Diagnostic: gravity in the phone's natural frame (x right, y up the screen, z out of the screen), m/s^2.
    private val gravity = FloatArray(3)

    override fun onSensorChanged(e: SensorEvent) {
        gravity[0] = e.values[0]; gravity[1] = e.values[1]; gravity[2] = e.values[2]
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    /** What the accelerometer says about how the phone is held, independent of what Android decided to draw. */
    private fun physicalPose(): String {
        val (x, y, z) = Triple(gravity[0], gravity[1], gravity[2])
        return when {
            abs(z) > 7f -> "flat/${if (z > 0) "face-up" else "face-down"}"
            y > 6f -> "portrait"
            y < -6f -> "reverse-portrait"
            x > 6f -> "landscape top-to-LEFT (ROTATION_90)"
            x < -6f -> "landscape top-to-RIGHT (ROTATION_270)"
            else -> "tilted"
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var render: RenderThread
    private lateinit var controller: CameraController
    private lateinit var camMgr: CameraManager

    // views
    private lateinit var surfaceView: SurfaceView
    private lateinit var overlay: TextView
    private lateinit var zoomBar: SeekBar
    private lateinit var zoomLabel: TextView
    private val buttons = LinkedHashMap<String, Button>()

    // selection state
    private var cameraIds: List<String> = emptyList()
    private var camIdx = 0
    private var physList: List<String> = emptyList()
    private var physIdx = -1 // -1 = logical camera
    private var sizes: List<Size> = emptyList()
    private var sizeIdx = 0
    private var fps = 30
    private var zoom = 1f
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var stabOff = true
    private val desqueezeValues = floatArrayOf(1f, 1.25f, 1.33f, 1.5f, 1.8f, 2f)
    private var desqIdx = 0
    private val glZoomValues = floatArrayOf(1f, 1.5f, 2f, 3f)
    private var glZoomIdx = 0
    private var rotOverride = -1 // -1 = auto

    // lifecycle
    private var surfaceReady = false
    private var resumed = false
    private var camState = CameraController.State.CLOSED
    private var camMsg = ""

    // stress (E5)
    private var stressLeft = 0
    private var stressOk = 0
    private var stressFail = 0
    private var stressMsg = ""
    private var stressAwaiting = false
    private val stressTimeout = Runnable { onStressResult(false) }

    // soak (E4)
    private var soak = false
    private var soakStartMs = 0L
    private var tick = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        camMgr = getSystemService(CameraManager::class.java)
        render = RenderThread()
        controller = CameraController(this, render) { s, m -> main.post { onCamState(s, m) } }

        cameraIds = camMgr.cameraIdList.toList()
        buildUi()
        hideSystemBars() // needs the decor view, so only after setContentView
        selectCamera(0)

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }

        // Dev hook: `adb shell am start -n ... --ez probe true` runs the probe without any tapping.
        if (intent?.getBooleanExtra("probe", false) == true) main.postDelayed({ runProbe() }, 2000)
        // `--ei stress 200` starts the stress test once the camera is streaming; `--ez soak true` starts soak logging.
        val autoStress = intent?.getIntExtra("stress", 0) ?: 0
        if (autoStress > 0) main.postDelayed({ startStress(autoStress) }, 5000)
        if (intent?.getBooleanExtra("soak", false) == true) main.postDelayed({ if (!soak) toggleSoak() }, 3000)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        getSystemService(SensorManager::class.java).let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        }
        main.post(ticker)
        maybeStart()
    }

    override fun onPause() {
        resumed = false
        getSystemService(SensorManager::class.java).unregisterListener(this)
        main.removeCallbacks(ticker)
        main.removeCallbacks(stressTimeout)
        stressLeft = 0
        controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        controller.release()
        render.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA) maybeStart()
    }

    // ---------------------------------------------------------------- camera selection

    private fun hasPermission() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun maybeStart() {
        if (resumed && surfaceReady && hasPermission() && cameraIds.isNotEmpty() && sizes.isNotEmpty()) startCamera()
    }

    private fun startCamera() {
        val cfg = CameraController.Config(
            cameraId = cameraIds[camIdx],
            physicalId = if (physIdx >= 0) physList[physIdx] else null,
            size = sizes[sizeIdx],
            fps = fps,
            zoom = zoom,
            stabilizationOff = stabOff,
        )
        updateRenderParams()
        controller.start(cfg)
        refreshButtons()
    }

    private fun selectCamera(idx: Int) {
        camIdx = idx
        val c = camMgr.getCameraCharacteristics(cameraIds[camIdx])
        sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        frontFacing = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        physList = c.physicalCameraIds.sorted()
        physIdx = -1
        loadSizes(c)
        if (Build.VERSION.SDK_INT >= 30) {
            val r = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            zoomMin = r?.lower ?: 1f
            zoomMax = r?.upper ?: 1f
        } else {
            zoomMin = 1f
            zoomMax = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        }
        zoomMax = minOf(zoomMax, 10f)
        zoom = 1f.coerceIn(zoomMin, zoomMax)
        syncZoomBar()
    }

    private fun loadSizes(c: CameraCharacteristics) {
        val all = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        fun area(s: Size) = s.width.toLong() * s.height
        val pref = all.filter {
            val a = it.width.toFloat() / it.height
            it.width in 1280..2560 && (abs(a - 16f / 9) < 0.02f || abs(a - 4f / 3) < 0.02f)
        }.sortedBy(::area)
        sizes = pref.ifEmpty { all.sortedBy(::area).takeLast(5) }
        sizeIdx = sizes.indexOfFirst { it.width == 1920 && it.height == 1080 }
            .takeIf { it >= 0 } ?: (sizes.size / 2)
    }

    private fun cycleCamera() {
        selectCamera((camIdx + 1) % cameraIds.size)
        startCamera()
    }

    private fun cyclePhysical() {
        if (physList.isEmpty()) { toast("This camera has no physical sub-cameras"); return }
        physIdx = if (physIdx + 1 >= physList.size) -1 else physIdx + 1
        val c = camMgr.getCameraCharacteristics(if (physIdx >= 0) physList[physIdx] else cameraIds[camIdx])
        loadSizes(c)
        startCamera()
    }

    private fun cycleSize() {
        sizeIdx = (sizeIdx + 1) % sizes.size
        startCamera()
    }

    private fun toggleFps() {
        fps = if (fps == 30) 60 else 30
        startCamera()
    }

    private fun toggleStab() {
        stabOff = !stabOff
        startCamera()
    }

    private fun cycleDesqueeze() {
        desqIdx = (desqIdx + 1) % desqueezeValues.size
        updateRenderParams()
        refreshButtons()
    }

    private fun cycleGlZoom() {
        glZoomIdx = (glZoomIdx + 1) % glZoomValues.size
        updateRenderParams()
        refreshButtons()
    }

    private fun cycleRotation() {
        rotOverride = when (rotOverride) { -1 -> 0; 0 -> 90; 90 -> 180; 180 -> 270; else -> -1 }
        updateRenderParams()
        refreshButtons()
    }

    /** Clockwise degrees needed to show the camera buffer upright (standard Camera2 relative-rotation formula). */
    private var sensorOrientation = 0
    private var frontFacing = false

    private fun displayDegrees(): Int {
        @Suppress("DEPRECATION")
        return windowManager.defaultDisplay.rotation * 90
    }

    private fun autoRotation(): Int {
        val disp = displayDegrees()
        return if (frontFacing) (sensorOrientation + disp) % 360 else (sensorOrientation - disp + 360) % 360
    }

    private fun effectiveRotation() = if (rotOverride >= 0) rotOverride else autoRotation()

    private fun updateRenderParams() {
        render.params = RenderThread.Params(
            desqueeze = desqueezeValues[desqIdx],
            glZoom = glZoomValues[glZoomIdx],
            rotationDeg = effectiveRotation(),
        )
    }

    // ---------------------------------------------------------------- zoom bar

    private fun syncZoomBar() {
        val span = ln(zoomMax / zoomMin)
        zoomBar.isEnabled = zoomMax > zoomMin * 1.001f
        zoomBar.progress = if (span > 0f) (ln(zoom / zoomMin) / span * ZOOM_STEPS).toInt() else 0
        zoomLabel.text = "zoom %.2fx (%.2f-%.2f)".format(zoom, zoomMin, zoomMax)
    }

    private fun progressToZoom(p: Int): Float =
        if (zoomMax <= zoomMin) zoomMin else zoomMin * (zoomMax / zoomMin).pow(p / ZOOM_STEPS.toFloat())

    // ---------------------------------------------------------------- state / stress / soak

    private fun onCamState(s: CameraController.State, msg: String) {
        camState = s
        camMsg = msg
        if (stressLeft > 0) {
            if (s == CameraController.State.STREAMING) onStressResult(true)
            else if (s == CameraController.State.ERROR) onStressResult(false)
        }
    }

    private fun startStress(n: Int) {
        if (stressLeft > 0) { toast("Stress test already running"); return }
        stressLeft = n; stressOk = 0; stressFail = 0
        stressMsg = "stress running"
        stressStep()
    }

    private fun stressStep() {
        if (stressLeft <= 0) {
            stressMsg = "stress done ok=$stressOk fail=$stressFail"
            Log.i(TAG, stressMsg)
            return
        }
        controller.stop()
        main.postDelayed({
            if (stressLeft > 0) {
                stressAwaiting = true
                startCamera()
                main.postDelayed(stressTimeout, 4000)
            }
        }, 300)
    }

    /** Exactly one result per attempt: a camera error often fires several callbacks (error + disconnected). */
    private fun onStressResult(ok: Boolean) {
        if (stressLeft <= 0 || !stressAwaiting) return
        stressAwaiting = false
        main.removeCallbacks(stressTimeout)
        if (ok) stressOk++ else stressFail++
        stressLeft--
        stressMsg = "stress left=$stressLeft ok=$stressOk fail=$stressFail"
        main.postDelayed({ stressStep() }, 700)
    }

    private fun toggleSoak() {
        soak = !soak
        if (soak) {
            soakStartMs = SystemClock.elapsedRealtime()
            val f = metricsFile()
            if (!f.exists()) {
                f.writeText("elapsed_s,fps,max_interval_ms,late,lat_avg_ms,lat_max_ms,frames,late_total,thermal,batt_pct,batt_temp_c,camera,size,target_fps,zoom\n")
            }
            toast("Soak logging to ${f.absolutePath}")
        }
        refreshButtons()
    }

    private fun metricsFile() = File(getExternalFilesDir(null), "metrics.csv")

    private fun runProbe() {
        try {
            val json = CameraProbe.run(this, render.glRenderer)
            val text = json.toString(2)
            val f = File(getExternalFilesDir(null), "probe_${Build.MODEL.replace(' ', '_')}.json")
            f.writeText(text)
            text.chunked(3000).forEach { Log.i("VF_PROBE", it) }
            toast("Probe saved: ${f.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "probe failed", t)
            toast("Probe failed: ${t.message}")
        }
    }

    // ---------------------------------------------------------------- periodic overlay

    private val ticker = object : Runnable {
        override fun run() {
            updateRenderParams() // follows display rotation, including a 180 degree flip that fires no resize
            val now = SystemClock.elapsedRealtimeNanos()
            val m = render.metrics.snapshot(now)
            val thermal = thermalName()
            val batt = battery()
            tick++

            overlay.text = buildString {
                appendLine("${Build.MANUFACTURER} ${Build.MODEL}  API ${Build.VERSION.SDK_INT}")
                appendLine("GL: ${render.glRenderer}")
                appendLine("cam ${cameraIds.getOrNull(camIdx)}${if (physIdx >= 0) "/phys ${physList[physIdx]}" else ""}  ${sizes.getOrNull(sizeIdx)}  target ${fps}fps")
                appendLine("state: $camState  $camMsg")
                appendLine("fps %.1f  maxGap %.1fms  late %d  (total %d / %d)".format(m.fps, m.maxIntervalMs, m.lateFrames, m.totalLate, m.totalFrames))
                appendLine("latency avg %s  max %s ms (sensor->queued)".format(fmt(m.latencyAvgMs), fmt(m.latencyMaxMs)))
                appendLine("rot ${effectiveRotation()}${if (rotOverride >= 0) " (manual)" else " (auto)"}  disp ${displayDegrees()}  sensor $sensorOrientation  desq %.2f  glZoom %.1f".format(desqueezeValues[desqIdx], glZoomValues[glZoomIdx]))
                appendLine("thermal $thermal  batt ${batt.first}% ${batt.second}C  open ${controller.lastOpenMs}ms")
                appendLine("pose: ${physicalPose()}  g=(%.1f, %.1f, %.1f)".format(gravity[0], gravity[1], gravity[2]))
                appendLine("tex: ${render.texMatrixText}")
                appendLine("rawNeeds ${effectiveRotation()}  texDid ${render.texRotationDeg}  weAdd ${render.appliedRotationDeg}")
                if (controller.lastResultSummary.isNotEmpty()) appendLine("result: ${controller.lastResultSummary}")
                if (stressMsg.isNotEmpty()) appendLine(stressMsg)
                if (soak) appendLine("SOAK ${(SystemClock.elapsedRealtime() - soakStartMs) / 1000}s")
            }

            if (soak && tick % 10 == 0) {
                val line = "%d,%.1f,%.1f,%d,%s,%s,%d,%d,%s,%d,%.1f,%s,%s,%d,%.2f\n".format(
                    (SystemClock.elapsedRealtime() - soakStartMs) / 1000, m.fps, m.maxIntervalMs, m.lateFrames,
                    fmt(m.latencyAvgMs), fmt(m.latencyMaxMs), m.totalFrames, m.totalLate, thermal,
                    batt.first, batt.second, "${cameraIds.getOrNull(camIdx)}${if (physIdx >= 0) "/${physList[physIdx]}" else ""}",
                    sizes.getOrNull(sizeIdx), fps, zoom,
                )
                try { metricsFile().appendText(line) } catch (t: Throwable) { Log.w(TAG, "soak write failed", t) }
            }
            main.postDelayed(this, 1000)
        }
    }

    private fun fmt(v: Float) = if (v.isNaN()) "n/a" else "%.1f".format(v)

    private fun thermalName(): String {
        val pm = getSystemService(PowerManager::class.java)
        return when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "?"
        }
    }

    /** (percent, temperature in C). */
    private fun battery(): Pair<Int, Float> {
        val i: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        return Pair(if (level >= 0) level * 100 / scale else -1, temp)
    }

    // ---------------------------------------------------------------- UI construction

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                render.setWindow(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                render.setViewSize(width, height)
                updateRenderParams() // display rotation may have changed
                if (!surfaceReady) {
                    surfaceReady = true
                    maybeStart()
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                controller.stop()
                render.clearWindowBlocking()
            }
        })
        root.addView(surfaceView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        root.addView(
            overlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START),
        )

        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0x99000000.toInt()) }

        val zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        zoomLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        zoomBar = SeekBar(this).apply {
            max = ZOOM_STEPS
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    zoom = progressToZoom(p)
                    zoomLabel.text = "zoom %.2fx (%.2f-%.2f)".format(zoom, zoomMin, zoomMax)
                    controller.updateZoom(zoom)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        zoomRow.addView(zoomLabel)
        zoomRow.addView(zoomBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(zoomRow)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addButton(row, "cam") { cycleCamera() }
        addButton(row, "phys") { cyclePhysical() }
        addButton(row, "size") { cycleSize() }
        addButton(row, "fps") { toggleFps() }
        addButton(row, "stab") { toggleStab() }
        addButton(row, "desq") { cycleDesqueeze() }
        addButton(row, "glzoom") { cycleGlZoom() }
        addButton(row, "rot") { cycleRotation() }
        addButton(row, "probe") { runProbe() }
        addButton(row, "stress") { startStress(200) }
        addButton(row, "soak") { toggleSoak() }
        val scroller = HorizontalScrollView(this).apply { addView(row) }
        bottom.addView(scroller)

        root.addView(
            bottom,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        setContentView(root)
        refreshButtons()
    }

    private fun addButton(parent: LinearLayout, key: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
            // While stress runs, any other tap would start a competing camera open and corrupt the result.
            setOnClickListener {
                if (stressLeft > 0) toast("Stress test running, wait for it to finish") else onClick()
            }
        }
        buttons[key] = b
        parent.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
    }

    private fun refreshButtons() {
        buttons["cam"]?.text = "cam ${cameraIds.getOrNull(camIdx)}"
        buttons["phys"]?.text = "phys ${if (physIdx >= 0) physList[physIdx] else if (physList.isEmpty()) "-" else "off"}"
        buttons["size"]?.text = sizes.getOrNull(sizeIdx)?.let { "${it.width}x${it.height}" } ?: "size"
        buttons["fps"]?.text = "${fps}fps"
        buttons["stab"]?.text = "stab ${if (stabOff) "off" else "default"}"
        buttons["desq"]?.text = "desq %.2f".format(desqueezeValues[desqIdx])
        buttons["glzoom"]?.text = "glzoom %.1fx".format(glZoomValues[glZoomIdx])
        buttons["rot"]?.text = "rot ${if (rotOverride >= 0) rotOverride.toString() else "auto"}"
        buttons["probe"]?.text = "probe"
        buttons["stress"]?.text = "stress x200"
        buttons["soak"]?.text = if (soak) "soak ON" else "soak"
        syncZoomBar()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val TAG = "VF_MAIN"
        const val REQ_CAMERA = 1
        const val ZOOM_STEPS = 1000
    }
}
