package app.viewfinder.optics

import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure optics maths, no Android dependencies. Units: millimetres and degrees unless a name says otherwise.
 * Rectilinear model. Focus breathing and lens distortion are NOT modeled.
 */
object Optics {
    const val FULL_FRAME_WIDTH_MM = 36.0
    const val FULL_FRAME_HEIGHT_MM = 24.0

    /** tan(half angle) of a frame extent seen through a lens. [squeeze] > 1 widens the horizontal view (anamorphic). */
    fun halfTan(extentMm: Double, focalMm: Double, squeeze: Double = 1.0): Double {
        require(extentMm > 0 && focalMm > 0 && squeeze > 0) { "extent, focal and squeeze must be positive" }
        return squeeze * extentMm / (2.0 * focalMm)
    }

    /** Full angle in degrees for a given tan(half angle). */
    fun angleDeg(halfTan: Double): Double = Math.toDegrees(2.0 * atan(halfTan))

    fun horizontalAovDeg(widthMm: Double, focalMm: Double, squeeze: Double = 1.0): Double =
        angleDeg(halfTan(widthMm, focalMm, squeeze))

    fun verticalAovDeg(heightMm: Double, focalMm: Double): Double = angleDeg(halfTan(heightMm, focalMm))

    fun diagonalMm(widthMm: Double, heightMm: Double): Double = hypot(widthMm, heightMm)

    fun cropFactor(widthMm: Double, heightMm: Double): Double =
        diagonalMm(FULL_FRAME_WIDTH_MM, FULL_FRAME_HEIGHT_MM) / diagonalMm(widthMm, heightMm)

    /** Full-frame equivalent focal length (matches diagonal field of view for a spherical lens). */
    fun equivalentFocalMm(focalMm: Double, widthMm: Double, heightMm: Double): Double =
        focalMm * cropFactor(widthMm, heightMm)

    /** Aspect ratio of the image after desqueezing. */
    fun desqueezedAspect(widthMm: Double, heightMm: Double, squeeze: Double = 1.0): Double =
        widthMm / heightMm * squeeze

    /** T-stop to geometric f-number: N = T * sqrt(transmittance). Lower N than T for any real lens. */
    fun fNumberFromT(tStop: Double, transmittance: Double = 0.9): Double {
        require(tStop > 0 && transmittance in 0.0..1.0 && transmittance > 0.0)
        return tStop * sqrt(transmittance)
    }
}

object Dof {
    data class Result(
        val hyperfocalMm: Double,
        val nearMm: Double,
        /** [Double.POSITIVE_INFINITY] when the subject is at or beyond the hyperfocal distance. */
        val farMm: Double,
    ) {
        val isFarInfinite: Boolean get() = farMm.isInfinite()
        val totalMm: Double get() = farMm - nearMm
    }

    fun hyperfocalMm(focalMm: Double, fNumber: Double, cocMm: Double): Double =
        focalMm * focalMm / (fNumber * cocMm) + focalMm

    /** Default circle of confusion: sensor diagonal / 1500. A starting point, always user-adjustable. */
    fun defaultCocMm(widthMm: Double, heightMm: Double): Double = Optics.diagonalMm(widthMm, heightMm) / 1500.0

    fun compute(focalMm: Double, fNumber: Double, cocMm: Double, distanceMm: Double): Result {
        require(focalMm > 0 && fNumber > 0 && cocMm > 0) { "focal, f-number and CoC must be positive" }
        require(distanceMm > focalMm) { "subject distance must exceed the focal length" }
        val h = hyperfocalMm(focalMm, fNumber, cocMm)
        val near = distanceMm * (h - focalMm) / (h + distanceMm - 2.0 * focalMm)
        val far = if (distanceMm >= h) Double.POSITIVE_INFINITY else distanceMm * (h - focalMm) / (h - distanceMm)
        return Result(h, near, far)
    }
}

/** Axis-aligned rectangle in a y-down pixel space. */
data class RectD(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2.0
    val centerY: Double get() = (top + bottom) / 2.0
}

/**
 * Maps a wanted frame (what the cinema camera + lens would record) onto the phone camera.
 *
 * Everything is in tangent space because a rectilinear image is linear in tan(angle), not in angle.
 */
object Framing {
    /**
     * @property halfTanH tan(half horizontal angle) of the phone image at zoom 1.0
     * @property halfTanV tan(half vertical angle) of the phone image at zoom 1.0
     */
    data class Phone(val halfTanH: Double, val halfTanV: Double, val zoomMin: Double, val zoomMax: Double)

    /**
     * @property zoomRatio camera-side zoom to request (sensor-quality crop)
     * @property cropX fraction of the (zoomed) image width to keep, 0..1 (GL-side crop)
     * @property cropY fraction of the (zoomed) image height to keep, 0..1
     * @property limited true when the wanted view is wider than the phone can show; the result is the widest possible
     */
    /**
     * @property digitalExtra extra GPU-side magnification on top of [zoomRatio] (1.0 = none). Above 1.0 the picture is
     *   digitally enlarged beyond what the camera's own zoom range allows, so it is softer.
     * @property zoomCapped true when the wanted view needs more zoom than the camera supports and digital zoom is off,
     *   so the result is the narrowest view the camera can give (wider than wanted).
     */
    data class Result(
        val zoomRatio: Double,
        val cropX: Double,
        val cropY: Double,
        val limited: Boolean,
        val targetHfovDeg: Double,
        val achievedHfovDeg: Double,
        val digitalExtra: Double = 1.0,
        val zoomCapped: Boolean = false,
    ) {
        /** Total magnification relative to the phone camera's 1.0x view. */
        val totalZoom: Double get() = zoomRatio * digitalExtra
    }

    /** Largest GPU-side magnification offered when digital zoom is enabled, as a multiple of the camera's maximum zoom. */
    const val MAX_DIGITAL_FACTOR = 2.0

    /**
     * Lens-matched framing: find the view a cinema lens would give.
     * @param allowDigital when false, never magnify on the GPU: a view narrower than the camera zoom allows is capped.
     */
    fun solve(targetHalfTanH: Double, targetHalfTanV: Double, phone: Phone, allowDigital: Boolean = true): Result {
        require(targetHalfTanH > 0 && targetHalfTanV > 0) { "target extents must be positive" }
        // Largest zoom that still keeps the wanted region inside the image: use the camera, not the GPU, for the crop.
        val ideal = min(phone.halfTanH / targetHalfTanH, phone.halfTanV / targetHalfTanV)
        val zoom = ideal.coerceIn(phone.zoomMin, phone.zoomMax)
        val tanH = phone.halfTanH / zoom
        val tanV = phone.halfTanV / zoom
        var cx = targetHalfTanH / tanH
        var cy = targetHalfTanV / tanV
        val k = max(cx, cy)
        val limited = k > 1.0 + 1e-9
        var extra = 1.0
        var capped = false
        if (limited) {
            cx /= k
            cy /= k
        } else if (k < 1.0 - 1e-9) {
            // The wanted view is narrower than the camera's zoom range can frame: only the GPU can magnify further.
            if (allowDigital) {
                extra = 1.0 / k
            } else {
                cx /= k
                cy /= k
                capped = true
            }
        }
        return Result(
            zoomRatio = zoom,
            cropX = cx,
            cropY = cy,
            limited = limited,
            targetHfovDeg = Optics.angleDeg(targetHalfTanH),
            achievedHfovDeg = Optics.angleDeg(cx * tanH),
            digitalExtra = extra,
            zoomCapped = capped,
        )
    }

    /** Highest total zoom a manual zoom control may reach. */
    fun maxTotalZoom(phone: Phone, allowDigital: Boolean): Double =
        if (allowDigital) phone.zoomMax * MAX_DIGITAL_FACTOR else phone.zoomMax

    /**
     * Manual zoom: show what the camera sees at [zoomTotal], cropped only to the frame aspect ([displayAspect]).
     * Camera zoom is clamped to the camera's real range; anything above it is GPU magnification and only used when
     * [allowDigital] is true. There is no lens matching here, so the achieved view is simply what the zoom gives.
     */
    fun solveManual(displayAspect: Double, phone: Phone, zoomTotal: Double, allowDigital: Boolean): Result {
        require(displayAspect > 0 && zoomTotal > 0) { "aspect and zoom must be positive" }
        val total = zoomTotal.coerceIn(phone.zoomMin, maxTotalZoom(phone, allowDigital))
        val cam = total.coerceIn(phone.zoomMin, phone.zoomMax)
        val extra = if (allowDigital) (total / cam).coerceAtLeast(1.0) else 1.0
        val imageAspect = phone.halfTanH / phone.halfTanV
        // Keep the whole image in the limiting direction, crop the other to match the frame aspect.
        val cx0 = min(1.0, displayAspect / imageAspect)
        val cy0 = min(1.0, imageAspect / displayAspect)
        val cx = cx0 / extra
        val cy = cy0 / extra
        val hfov = Optics.angleDeg(cx * phone.halfTanH / cam)
        return Result(
            zoomRatio = cam,
            cropX = cx,
            cropY = cy,
            limited = false,
            targetHfovDeg = hfov,
            achievedHfovDeg = hfov,
            digitalExtra = extra,
            zoomCapped = false,
        )
    }

    /** Applies a pinch gesture scale factor to the current zoom, clamped to what the camera (and settings) allow. */
    fun pinch(current: Double, scale: Double, phone: Phone, allowDigital: Boolean): Double {
        require(current > 0 && scale > 0) { "zoom and scale must be positive" }
        return (current * scale).coerceIn(phone.zoomMin, maxTotalZoom(phone, allowDigital))
    }
}

object Guides {
    /** Largest rectangle of [aspect] (width / height) centered inside the container. */
    fun fit(containerW: Double, containerH: Double, aspect: Double): RectD {
        require(containerW > 0 && containerH > 0 && aspect > 0)
        val w: Double
        val h: Double
        if (containerW / containerH > aspect) {
            h = containerH; w = h * aspect
        } else {
            w = containerW; h = w / aspect
        }
        val l = (containerW - w) / 2.0
        val t = (containerH - h) / 2.0
        return RectD(l, t, l + w, t + h)
    }

    /** Delivery frame (for example 2.39:1) inside the displayed frame of aspect [displayAspect]. Never larger than it. */
    fun delivery(display: RectD, displayAspect: Double, deliveryAspect: Double): RectD {
        val inner = fit(display.width, display.height, deliveryAspect.coerceAtMost(Double.MAX_VALUE))
        // `fit` already centers and contains; shift into the display rect. displayAspect is accepted for symmetry/clarity.
        return RectD(display.left + inner.left, display.top + inner.top, display.left + inner.right, display.top + inner.bottom)
    }

    /** Safe area as a centered fraction (for example 0.9) of a rect. */
    fun inset(rect: RectD, fraction: Double): RectD {
        val dx = rect.width * (1.0 - fraction) / 2.0
        val dy = rect.height * (1.0 - fraction) / 2.0
        return RectD(rect.left + dx, rect.top + dy, rect.right - dx, rect.bottom - dy)
    }
}
