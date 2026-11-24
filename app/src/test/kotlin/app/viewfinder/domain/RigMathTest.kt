package app.viewfinder.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Uses the built-in fallback phone model (phone = null), so no Android classes are needed. */
class RigMathTest {
    private fun near(e: Double, a: Double, tol: Double = 1e-3) = assertTrue(abs(e - a) <= tol, "expected $e but was $a")

    @Test fun autoModeMatchesTheLens() {
        val d = RigMath.derive(Rig(), null) // 50 mm on Generic Super 35 16:9
        assertFalse(d.isManualZoom)
        near(2.96, d.framing.zoomRatio, 0.02)
        near(27.96, d.hfovDeg, 0.05)
        assertEquals(1.0, d.framing.digitalExtra, 1e-9)
    }

    @Test fun manualZoomOverridesLensMatching() {
        val d = RigMath.derive(Rig(zoomManual = 2.0), null)
        assertTrue(d.isManualZoom)
        near(2.0, d.framing.zoomRatio)
        near(2.0, d.framing.totalZoom)
    }

    @Test fun manualZoomIsClampedToTheCameraRangeWhenDigitalIsOff() {
        val d = RigMath.derive(Rig(zoomManual = 99.0, allowDigital = false), null)
        near(10.0, d.framing.totalZoom)
        near(10.0, d.zoomMaxTotal)
    }

    @Test fun digitalZoomExtendsTheRangeOnlyWhenEnabled() {
        near(10.0, RigMath.derive(Rig(allowDigital = false), null).zoomMaxTotal)
        near(20.0, RigMath.derive(Rig(allowDigital = true), null).zoomMaxTotal)
        near(15.0, RigMath.derive(Rig(zoomManual = 15.0, allowDigital = true), null).framing.totalZoom)
    }

    @Test fun lensChoiceNeverUsesDigitalUnlessAllowed() {
        val zoomLens = Rig(lensId = "zoom-24-70", focalMm = 70.0, cameraId = "generic-ff", modeId = "3x2")
        val d = RigMath.derive(zoomLens, null)
        assertEquals(1.0, d.framing.digitalExtra, 1e-9)
    }

    @Test fun framelinesResolveInOrderAndTheLastIsActive() {
        val d = RigMath.derive(Rig(framelines = listOf("1.85", "2.39", "bogus")), null)
        assertEquals(listOf("1.85:1", "2.39:1"), d.framelines.map { it.label })
        assertEquals("2.39:1", d.active!!.label)
        near(2.39, d.deliveryAspect, 1e-9)
    }

    @Test fun withoutFramelinesTheDeliveryIsTheFullFrame() {
        val d = RigMath.derive(Rig(), null)
        assertNull(d.active)
        near(d.displayAspect, d.deliveryAspect, 1e-9)
    }

    @Test fun anamorphicLensWidensTheDisplayedFrame() {
        val d = RigMath.derive(Rig(lensId = "ana2-primes", focalMm = 50.0), null)
        near(24.9 / 14.0 * 2.0, d.displayAspect, 1e-6)
        near(2.0, d.squeeze, 1e-9)
    }

    @Test fun widerThanThePhoneIsFlaggedInAutoMode() {
        val d = RigMath.derive(Rig(lensId = "sph-primes", focalMm = 18.0, cameraId = "generic-ff", modeId = "3x2"), null)
        assertTrue(d.framing.limited)
        assertTrue(d.hfovDeg < d.framing.targetHfovDeg)
    }

    @Test fun verticalAngleFollowsTheFrameAspect() {
        val d = RigMath.derive(Rig(), null)
        val vtan = Math.tan(Math.toRadians(d.hfovDeg / 2)) / d.displayAspect
        near(Math.toDegrees(2 * Math.atan(vtan)), d.vfovDeg, 1e-6)
    }
}
