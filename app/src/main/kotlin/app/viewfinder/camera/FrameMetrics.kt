package app.viewfinder.camera

/**
 * Per-second frame statistics.
 *
 * Latency here = (time after eglSwapBuffers returned) - (sensor timestamp). It covers sensor, ISP, camera HAL,
 * buffer queue and GL, but NOT display scan-out. It is only valid when the camera timestamp source is REALTIME.
 */
class FrameMetrics {
    data class Snapshot(
        val fps: Float,
        val maxIntervalMs: Float,
        val lateFrames: Int,
        val latencyAvgMs: Float,
        val latencyMaxMs: Float,
        val totalFrames: Long,
        val totalLate: Long,
    )

    @Volatile var targetFps: Int = 30
    @Volatile var realtimeTimestamps: Boolean = false

    private val lock = Any()
    private var lastTsNs = 0L
    private var windowStartNs = 0L
    private var wFrames = 0
    private var wMaxIntervalMs = 0.0
    private var wLate = 0
    private var wLatSum = 0.0
    private var wLatMax = 0.0
    private var wLatN = 0
    private var totalFrames = 0L
    private var totalLate = 0L

    fun reset(nowNs: Long) = synchronized(lock) {
        lastTsNs = 0L
        windowStartNs = nowNs
        wFrames = 0; wMaxIntervalMs = 0.0; wLate = 0; wLatSum = 0.0; wLatMax = 0.0; wLatN = 0
    }

    fun onFrame(sensorTsNs: Long, presentedNs: Long) = synchronized(lock) {
        totalFrames++
        wFrames++
        if (lastTsNs != 0L) {
            val intervalMs = (sensorTsNs - lastTsNs) / 1e6
            if (intervalMs > wMaxIntervalMs) wMaxIntervalMs = intervalMs
            if (intervalMs > 1.5 * 1000.0 / targetFps) {
                wLate++
                totalLate++
            }
        }
        lastTsNs = sensorTsNs
        if (realtimeTimestamps) {
            val lat = (presentedNs - sensorTsNs) / 1e6
            wLatSum += lat
            wLatN++
            if (lat > wLatMax) wLatMax = lat
        }
    }

    /** Returns stats for the window since the previous call and starts a new window. */
    fun snapshot(nowNs: Long): Snapshot = synchronized(lock) {
        val secs = ((nowNs - windowStartNs) / 1e9).coerceAtLeast(0.001)
        val s = Snapshot(
            fps = (wFrames / secs).toFloat(),
            maxIntervalMs = wMaxIntervalMs.toFloat(),
            lateFrames = wLate,
            latencyAvgMs = if (wLatN > 0) (wLatSum / wLatN).toFloat() else Float.NaN,
            latencyMaxMs = if (wLatN > 0) wLatMax.toFloat() else Float.NaN,
            totalFrames = totalFrames,
            totalLate = totalLate,
        )
        windowStartNs = nowNs
        wFrames = 0; wMaxIntervalMs = 0.0; wLate = 0; wLatSum = 0.0; wLatMax = 0.0; wLatN = 0
        s
    }
}

