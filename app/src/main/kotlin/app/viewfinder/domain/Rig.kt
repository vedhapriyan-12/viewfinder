package app.viewfinder.domain

import app.viewfinder.camera.PhoneCameraInfo
import app.viewfinder.optics.Dof
import app.viewfinder.optics.Framing
import app.viewfinder.optics.Optics

/**
 * Everything the user has chosen. This is also the project settings document that gets saved.
 * Immutable; the ViewModel replaces it on every change.
 */
data class Rig(
    val projectName: String = "Untitled project",
    val cameraId: String = "generic-s35",
    val modeId: String = "16x9",
    val lensId: String = "sph-primes",
    val focalMm: Double = 50.0,
    val tStop: Double = 2.0,
    /** null = use the lens set's own squeeze. */
    val squeezeOverride: Double? = null,
    /** Enabled framelines in the order they were switched on. The last one is the active one (gets the dim mask). */
    val framelines: List<String> = emptyList(),
    /** Custom aspect ratios (width / height) the user has created in this project. */
    val customRatios: List<Double> = emptyList(),
    /** Safe-area percentages shown inside the active frameline (any of 80, 90, 95). */
    val safeAreas: List<Int> = emptyList(),
    val showThirds: Boolean = false,
    val showDof: Boolean = true,
    val showInfo: Boolean = false,
    val focusM: Double = 3.0,
    /** null = zoom is matched to the lens automatically; otherwise the user's own zoom ratio. */
    val zoomManual: Double? = null,
    /** Off by default: never magnify beyond the camera's real zoom range unless the user opts in. */
    val allowDigital: Boolean = false,
)

/** Everything computed from a [Rig] plus the phone camera. Pure; unit-testable. */
data class Derived(
    val camera: CameraBody,
    val mode: SensorMode,
    val lens: LensSet,
    val squeeze: Double,
    val hfovDeg: Double,
    val vfovDeg: Double,
    /** Aspect of the displayed (desqueezed) frame. */
    val displayAspect: Double,
    /** Active frameline aspect, or the full frame when none is enabled. */
    val deliveryAspect: Double,
    /** Enabled framelines, oldest first. The last is the active one. */
    val framelines: List<Frameline>,
    val framing: Framing.Result,
    val isManualZoom: Boolean,
    val zoomMin: Double,
    val zoomMaxCamera: Double,
    val zoomMaxTotal: Double,
    val phoneWidestHfovDeg: Double?,
    val dof: Dof.Result,
    val fullFrameEquivalentMm: Double,
) {
    val active: Frameline? get() = framelines.lastOrNull()
}

object RigMath {
    /** Used before the phone camera has been probed, so the UI can still lay itself out. */
    private val FALLBACK_PHONE = Framing.Phone(0.7382, 0.5537, 1.0, 10.0)

    fun phoneModel(phone: PhoneCameraInfo?): Framing.Phone =
        phone?.let { Framing.Phone(it.halfTanH, it.halfTanV, it.zoomMin, it.zoomMax) } ?: FALLBACK_PHONE

    fun derive(rig: Rig, phone: PhoneCameraInfo?): Derived {
        val camera = PlaceholderCatalog.camera(rig.cameraId)
        val mode = camera.modes.firstOrNull { it.id == rig.modeId } ?: camera.modes.first()
        val lens = PlaceholderCatalog.lens(rig.lensId)
        val squeeze = rig.squeezeOverride ?: lens.squeeze
        val f = lens.clampFocal(rig.focalMm)

        val tanH = Optics.halfTan(mode.widthMm, f, squeeze)
        val tanV = Optics.halfTan(mode.heightMm, f)
        val displayAspect = Optics.desqueezedAspect(mode.widthMm, mode.heightMm, squeeze)

        val pm = phoneModel(phone)
        val manual = rig.zoomManual
        val framing = if (manual != null) {
            Framing.solveManual(displayAspect, pm, manual, rig.allowDigital)
        } else {
            Framing.solve(tanH, tanV, pm, rig.allowDigital)
        }

        // The achieved view is what is really on screen; the vertical angle follows from the frame aspect.
        val achievedHalfTanH = Math.tan(Math.toRadians(framing.achievedHfovDeg / 2.0))
        val vfov = Optics.angleDeg(achievedHalfTanH / displayAspect)

        val frames = rig.framelines.mapNotNull { Framelines.find(it, rig.customRatios) }

        val n = Optics.fNumberFromT(rig.tStop)
        val coc = Dof.defaultCocMm(mode.widthMm, mode.heightMm)
        val distanceMm = (rig.focusM * 1000.0).coerceAtLeast(f * 2.0)

        return Derived(
            camera = camera,
            mode = mode,
            lens = lens,
            squeeze = squeeze,
            hfovDeg = framing.achievedHfovDeg,
            vfovDeg = vfov,
            displayAspect = displayAspect,
            deliveryAspect = frames.lastOrNull()?.aspect ?: displayAspect,
            framelines = frames,
            framing = framing,
            isManualZoom = manual != null,
            zoomMin = pm.zoomMin,
            zoomMaxCamera = pm.zoomMax,
            zoomMaxTotal = Framing.maxTotalZoom(pm, rig.allowDigital),
            phoneWidestHfovDeg = phone?.widestHfovDeg,
            dof = Dof.compute(f, n, coc, distanceMm),
            fullFrameEquivalentMm = Optics.equivalentFocalMm(f, mode.widthMm, mode.heightMm),
        )
    }
}
