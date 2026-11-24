package app.viewfinder.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Size
import kotlin.math.abs

/** What the optics code needs to know about the phone's own camera. */
data class PhoneCameraInfo(
    val cameraId: String,
    val sensorOrientation: Int,
    /** tan(half horizontal angle) of the full-width image at zoom 1.0. */
    val halfTanH: Double,
    /** tan(half vertical angle) of the chosen stream at zoom 1.0. */
    val halfTanV: Double,
    val zoomMin: Double,
    val zoomMax: Double,
    val streamSize: Size,
    /** Where halfTanH came from, for display and debugging. */
    val source: String,
) {
    val widestHfovDeg: Double get() = Math.toDegrees(2.0 * Math.atan(halfTanH))
}

object PhoneCameras {
    /**
     * Finds the back camera apps are allowed to use. On the test phone that is a single logical camera;
     * the ultrawide is hidden by the vendor, so the widest view is simply what this camera gives at zoom 1.0.
     */
    fun find(ctx: Context): PhoneCameraInfo? {
        val mgr = ctx.getSystemService(CameraManager::class.java)
        val id = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return null
        val c = mgr.getCameraCharacteristics(id)
        val active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null

        // Prefer lens intrinsics (pixels); fall back to focal length and physical sensor width.
        val intrinsics = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        val physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val halfTanH: Double
        val source: String
        if (intrinsics != null && intrinsics.size >= 1 && intrinsics[0] > 0f) {
            halfTanH = (active.width() / 2.0) / intrinsics[0]
            source = "lens intrinsics"
        } else if (focal != null && physical != null && focal > 0f) {
            halfTanH = (physical.width / 2.0) / focal
            source = "focal length + sensor size"
        } else return null

        val stream = pickStream(c)
        val zoomMin: Double
        val zoomMax: Double
        if (Build.VERSION.SDK_INT >= 30) {
            val r = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            // The camera's own reported range. Below 1.0 only exists when the vendor exposes an ultrawide behind the
            // logical camera; the test phone reports 1.0 so its zoom UI correctly offers nothing wider.
            zoomMin = (r?.lower ?: 1f).toDouble().coerceAtLeast(0.1)
            zoomMax = (r?.upper ?: 1f).toDouble()
        } else {
            zoomMin = 1.0
            zoomMax = (c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f).toDouble()
        }
        val streamAspect = stream.width.toDouble() / stream.height
        return PhoneCameraInfo(
            cameraId = id,
            sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            halfTanH = halfTanH,
            halfTanV = halfTanH / streamAspect,
            zoomMin = zoomMin,
            zoomMax = minOf(zoomMax, 10.0),
            streamSize = stream,
            source = source,
        )
    }

    /** Prefer a 4:3 stream (it covers the full sensor, so tall frames fit); fall back to 16:9. About 1920 wide. */
    private fun pickStream(c: CameraCharacteristics): Size {
        val all = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        fun closest(aspect: Double): Size? = all
            .filter { abs(it.width.toDouble() / it.height - aspect) < 0.02 && it.width in 1280..2304 }
            .minByOrNull { abs(it.width - 1920) }
        return closest(4.0 / 3.0) ?: closest(16.0 / 9.0) ?: all.minByOrNull { abs(it.width - 1920) } ?: Size(1920, 1080)
    }
}
