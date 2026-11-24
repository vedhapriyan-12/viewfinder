package app.viewfinder.spike

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/** E1: dumps everything the viewfinder needs to know about every camera (logical and physical) to JSON. */
object CameraProbe {

    fun run(ctx: Context, glRenderer: String): JSONObject {
        val mgr = ctx.getSystemService(CameraManager::class.java)
        val root = JSONObject()
        root.put(
            "device",
            JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE)
                .put("socModel", if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "")
                .put("glRenderer", glRenderer),
        )
        val cams = JSONArray()
        for (id in mgr.cameraIdList) {
            val c = mgr.getCameraCharacteristics(id)
            cams.put(describe(id, c, null))
            for (pid in c.physicalCameraIds) {
                cams.put(describe(pid, mgr.getCameraCharacteristics(pid), id))
            }
        }
        root.put("cameras", cams)
        return root
    }

    private fun describe(id: String, c: CameraCharacteristics, logicalParent: String?): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("logicalParent", logicalParent ?: JSONObject.NULL)
        o.put("facing", facing(c.get(CameraCharacteristics.LENS_FACING)))
        o.put("hardwareLevel", level(c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)))
        o.put("capabilities", ints(c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)))
        o.put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
        o.put("physicalIds", JSONArray(c.physicalCameraIds.toList()))
        o.put("focalLengthsMm", floats(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)))
        o.put("aperturesF", floats(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)))
        c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
            o.put("sensorPhysicalSizeMm", JSONArray(listOf(it.width.toDouble(), it.height.toDouble())))
        }
        c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.let {
            o.put("pixelArraySize", JSONArray(listOf(it.width, it.height)))
        }
        c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { o.put("activeArray", rect(it)) }
        o.put("maxDigitalZoom", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
        if (Build.VERSION.SDK_INT >= 30) {
            c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let {
                o.put("zoomRatioRange", JSONArray(listOf(it.lower.toDouble(), it.upper.toDouble())))
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            o.put("rotateAndCropModes", ints(c.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)))
        }
        o.put("intrinsicCalibration", floats(c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)))
        o.put("lensDistortion", floats(c.get(CameraCharacteristics.LENS_DISTORTION)))
        o.put("poseReference", c.get(CameraCharacteristics.LENS_POSE_REFERENCE) ?: JSONObject.NULL)
        o.put("minFocusDistanceDiopters", c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE))
        o.put("focusDistanceCalibration", c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION))
        o.put("videoStabilizationModes", ints(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)))
        o.put("opticalStabilizationModes", ints(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)))
        o.put("timestampSource", c.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)?.let {
            if (it == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME) "REALTIME" else "UNKNOWN"
        } ?: JSONObject.NULL)
        val fps = JSONArray()
        c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.forEach {
            fps.put(JSONArray(listOf(it.lower, it.upper)))
        }
        o.put("aeTargetFpsRanges", fps)
        val sizes = JSONArray()
        c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)
            ?.sortedByDescending { it.width.toLong() * it.height }
            ?.forEach { sizes.put("${it.width}x${it.height}") }
        o.put("surfaceTextureSizes", sizes)
        return o
    }

    private fun rect(r: Rect) = JSONArray(listOf(r.left, r.top, r.right, r.bottom))
    private fun floats(a: FloatArray?): Any = a?.let { JSONArray(it.map { f -> f.toDouble() }) } ?: JSONObject.NULL
    private fun ints(a: IntArray?): Any = a?.let { JSONArray(it.toList()) } ?: JSONObject.NULL

    private fun facing(v: Int?) = when (v) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun level(v: Int?) = when (v) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($v)"
    }
}
