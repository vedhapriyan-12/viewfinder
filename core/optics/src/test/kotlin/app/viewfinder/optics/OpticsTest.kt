package app.viewfinder.optics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reference values come from docs/phase0/OPTICS_TEST_VECTORS.md (computed independently in PowerShell). */
class OpticsTest {
    private fun near(expected: Double, actual: Double, tol: Double = 1e-4) =
        assertTrue(abs(expected - actual) <= tol, "expected $expected but was $actual")

    @Test fun horizontalAov_fullFrame50() = near(39.5978, Optics.horizontalAovDeg(36.0, 50.0))
    @Test fun horizontalAov_s35_50() = near(27.9646, Optics.horizontalAovDeg(24.9, 50.0))
    @Test fun horizontalAov_anamorphic133() = near(36.6466, Optics.horizontalAovDeg(24.9, 50.0, 1.33))
    @Test fun horizontalAov_anamorphic2x() = near(52.9466, Optics.horizontalAovDeg(24.9, 50.0, 2.0))
    @Test fun verticalAov_unaffectedBySqueeze() = near(15.3769, Optics.verticalAovDeg(13.5, 50.0))

    @Test fun fullFrameCropFactorIsOne() = near(1.0, Optics.cropFactor(36.0, 24.0), 1e-9)
    @Test fun cropFactor_s35() = near(1.5146, Optics.cropFactor(24.9, 14.0))
    @Test fun desqueezedAspect_2x() = near(3.5571, Optics.desqueezedAspect(24.9, 14.0, 2.0))
    @Test fun fNumberFromT() = near(1.8974, Optics.fNumberFromT(2.0, 0.9))

    @Test fun squeezeOneEqualsSpherical() =
        assertEquals(Optics.horizontalAovDeg(24.9, 50.0), Optics.horizontalAovDeg(24.9, 50.0, 1.0), 0.0)

    @Test fun aovMonotonic() {
        assertTrue(Optics.horizontalAovDeg(24.9, 35.0) > Optics.horizontalAovDeg(24.9, 50.0))
        assertTrue(Optics.horizontalAovDeg(36.0, 50.0) > Optics.horizontalAovDeg(24.9, 50.0))
        assertTrue(Optics.horizontalAovDeg(24.9, 50.0, 2.0) > Optics.horizontalAovDeg(24.9, 50.0, 1.33))
    }

    @Test fun rejectsNonPositiveInputs() {
        assertFailsWith<IllegalArgumentException> { Optics.halfTan(24.9, 0.0) }
        assertFailsWith<IllegalArgumentException> { Optics.halfTan(-1.0, 50.0) }
    }

    // ---- DOF ----

    @Test fun dof_50mm_f28_3m() {
        val r = Dof.compute(50.0, 2.8, 0.030, 3000.0)
        near(29811.905, r.hyperfocalMm, 1e-3)
        near(2729.456, r.nearMm, 1e-3)
        near(3330.077, r.farMm, 1e-3)
    }

    @Test fun dof_35mm_f2_2m() {
        val r = Dof.compute(35.0, 2.0, 0.025, 2000.0)
        near(24535.0, r.hyperfocalMm, 1e-3)
        near(1851.502, r.nearMm, 1e-3)
        near(2174.395, r.farMm, 1e-3)
    }

    @Test fun dof_100mm_f4_20m() {
        val r = Dof.compute(100.0, 4.0, 0.030, 20000.0)
        near(83433.333, r.hyperfocalMm, 1e-3)
        near(16144.656, r.nearMm, 1e-3)
        near(26274.304, r.farMm, 1e-3)
    }

    @Test fun dof_beyondHyperfocalIsInfinite() {
        val r = Dof.compute(100.0, 4.0, 0.030, 90000.0)
        near(43294.208, r.nearMm, 1e-3)
        assertTrue(r.isFarInfinite)
    }

    @Test fun dof_totalGrowsWithAperture() {
        val wide = Dof.compute(50.0, 2.0, 0.03, 3000.0)
        val narrow = Dof.compute(50.0, 5.6, 0.03, 3000.0)
        assertTrue(narrow.totalMm > wide.totalMm)
        assertTrue(wide.nearMm < 3000.0 && 3000.0 < wide.farMm)
    }

    @Test fun dof_rejectsDistanceInsideFocalLength() {
        assertFailsWith<IllegalArgumentException> { Dof.compute(50.0, 2.8, 0.03, 40.0) }
    }

    // ---- Framing (phone numbers from the Redmi Note 13 Pro 5G probe: fx 2763.39, array 4080 x 3060) ----

    private val redmi = Framing.Phone(
        halfTanH = 2040.0 / 2763.392822265625,
        halfTanV = 1530.0 / 2763.392822265625,
        zoomMin = 1.0,
        zoomMax = 10.0,
    )

    @Test fun phoneNativeHfovMatchesProbe() = near(72.87, Optics.angleDeg(redmi.halfTanH), 0.05)

    @Test fun framing_50mmS35_usesCameraZoomAndKeepsAspect() {
        val tH = Optics.halfTan(24.9, 50.0)
        val tV = Optics.halfTan(14.0, 50.0)
        val r = Framing.solve(tH, tV, redmi)
        assertFalse(r.limited)
        near(2.964, r.zoomRatio, 0.01)
        near(1.0, r.cropX, 1e-9) // width-limited: full width is used
        near(0.7494, r.cropY, 1e-3)
        // The displayed aspect must equal the wanted frame aspect: image aspect (4:3) * cropX / cropY.
        near(24.9 / 14.0, (4.0 / 3.0) * r.cropX / r.cropY, 1e-3)
        near(r.targetHfovDeg, r.achievedHfovDeg, 1e-6)
    }

    @Test fun framing_threeTwoOpenGateIsStillWidthLimitedOnA43Phone() {
        val tH = Optics.halfTan(27.99, 50.0) // 3:2 (1.456) is wider than the phone's 4:3 (1.333)
        val tV = Optics.halfTan(19.22, 50.0)
        val r = Framing.solve(tH, tV, redmi)
        assertFalse(r.limited)
        near(1.0, r.cropX, 1e-9)
        assertTrue(r.cropY < 1.0)
        near(27.99 / 19.22, (4.0 / 3.0) * r.cropX / r.cropY, 1e-3)
    }

    @Test fun framing_squareFrameIsHeightLimited() {
        val tH = Optics.halfTan(20.0, 50.0) // 1:1 is narrower than 4:3, so height decides the zoom
        val tV = Optics.halfTan(20.0, 50.0)
        val r = Framing.solve(tH, tV, redmi)
        assertFalse(r.limited)
        near(1.0, r.cropY, 1e-9)
        assertTrue(r.cropX < 1.0)
        near(1.0, (4.0 / 3.0) * r.cropX / r.cropY, 1e-3)
    }

    @Test fun framing_widerThanPhoneIsLimitedAndClampedToWidest() {
        val tH = Optics.halfTan(24.9, 14.0) // 14 mm on S35: wider than this phone
        val tV = Optics.halfTan(14.0, 14.0)
        val r = Framing.solve(tH, tV, redmi)
        assertTrue(r.limited)
        near(1.0, r.zoomRatio, 1e-9)
        assertTrue(r.achievedHfovDeg < r.targetHfovDeg)
        assertTrue(r.cropX <= 1.0 + 1e-9 && r.cropY <= 1.0 + 1e-9)
        assertTrue(r.achievedHfovDeg <= Optics.angleDeg(redmi.halfTanH) + 1e-6)
    }

    @Test fun framing_veryLongLensFallsBackToGpuCropAboveMaxZoom() {
        val tH = Optics.halfTan(24.9, 400.0)
        val tV = Optics.halfTan(14.0, 400.0)
        val r = Framing.solve(tH, tV, redmi)
        near(10.0, r.zoomRatio, 1e-9)
        assertTrue(r.cropX < 1.0 && r.cropY < 1.0)
        assertFalse(r.limited)
        near(r.targetHfovDeg, r.achievedHfovDeg, 1e-6)
    }

    // ---- Zoom limits, manual zoom, pinch ----

    @Test fun framing_longLensWithDigitalOffIsCappedAtCameraMax() {
        val tH = Optics.halfTan(24.9, 400.0)
        val tV = Optics.halfTan(14.0, 400.0)
        val r = Framing.solve(tH, tV, redmi, allowDigital = false)
        near(10.0, r.zoomRatio, 1e-9)
        assertTrue(r.zoomCapped)
        near(1.0, r.digitalExtra, 1e-9)
        assertTrue(maxOf(r.cropX, r.cropY) >= 1.0 - 1e-9) // nothing is magnified on the GPU
        assertTrue(r.achievedHfovDeg > r.targetHfovDeg) // wider than wanted, and the UI must say so
        near(24.9 / 14.0, (4.0 / 3.0) * r.cropX / r.cropY, 1e-3)
    }

    @Test fun framing_longLensWithDigitalOnReportsTheExtraMagnification() {
        val r = Framing.solve(Optics.halfTan(24.9, 400.0), Optics.halfTan(14.0, 400.0), redmi, allowDigital = true)
        assertFalse(r.zoomCapped)
        assertTrue(r.digitalExtra > 1.0)
        near(r.zoomRatio * r.digitalExtra, r.totalZoom, 1e-9)
        near(r.targetHfovDeg, r.achievedHfovDeg, 1e-6)
    }

    @Test fun framing_normalLensNeverUsesDigital() {
        val r = Framing.solve(Optics.halfTan(24.9, 50.0), Optics.halfTan(14.0, 50.0), redmi)
        near(1.0, r.digitalExtra, 1e-9)
        assertFalse(r.zoomCapped)
    }

    @Test fun manualZoom_oneXShowsWholeImageCroppedToAspect() {
        val r = Framing.solveManual(16.0 / 9.0, redmi, 1.0, allowDigital = false)
        near(1.0, r.zoomRatio, 1e-9)
        near(1.0, r.cropX, 1e-9) // 16:9 is wider than the phone's 4:3: full width, shorter height
        near((4.0 / 3.0) / (16.0 / 9.0), r.cropY, 1e-9)
        near(Optics.angleDeg(redmi.halfTanH), r.achievedHfovDeg, 1e-6)
        near(16.0 / 9.0, (4.0 / 3.0) * r.cropX / r.cropY, 1e-9)
    }

    @Test fun manualZoom_doublesTheTangentScale() {
        val r1 = Framing.solveManual(4.0 / 3.0, redmi, 1.0, false)
        val r2 = Framing.solveManual(4.0 / 3.0, redmi, 2.0, false)
        near(2.0, r2.zoomRatio, 1e-9)
        near(Math.tan(Math.toRadians(r1.achievedHfovDeg / 2)) / 2.0, Math.tan(Math.toRadians(r2.achievedHfovDeg / 2)), 1e-9)
    }

    @Test fun manualZoom_clampsToCameraRangeWhenDigitalOff() {
        val r = Framing.solveManual(16.0 / 9.0, redmi, 50.0, allowDigital = false)
        near(10.0, r.zoomRatio, 1e-9)
        near(1.0, r.digitalExtra, 1e-9)
        near(10.0, r.totalZoom, 1e-9)
    }

    @Test fun manualZoom_digitalOnExtendsBeyondCameraMaxByTheFactor() {
        val r = Framing.solveManual(4.0 / 3.0, redmi, 50.0, allowDigital = true)
        near(10.0, r.zoomRatio, 1e-9)
        near(Framing.MAX_DIGITAL_FACTOR, r.digitalExtra, 1e-9)
        near(20.0, r.totalZoom, 1e-9)
        near(1.0 / Framing.MAX_DIGITAL_FACTOR, r.cropX, 1e-9)
    }

    @Test fun manualZoom_belowCameraMinimumIsClampedUp() {
        val r = Framing.solveManual(16.0 / 9.0, redmi, 0.5, allowDigital = true)
        near(1.0, r.zoomRatio, 1e-9)
        near(1.0, r.totalZoom, 1e-9)
    }

    @Test fun manualZoom_ultrawideRangeIsRespectedWhenTheCameraHasOne() {
        val withUltra = redmi.copy(zoomMin = 0.5)
        val r = Framing.solveManual(4.0 / 3.0, withUltra, 0.5, allowDigital = false)
        near(0.5, r.zoomRatio, 1e-9)
        assertTrue(r.achievedHfovDeg > Optics.angleDeg(redmi.halfTanH))
    }

    @Test fun pinch_scalesAndClamps() {
        near(2.0, Framing.pinch(1.0, 2.0, redmi, false), 1e-9)
        near(10.0, Framing.pinch(8.0, 3.0, redmi, false), 1e-9)
        near(20.0, Framing.pinch(8.0, 3.0, redmi, true), 1e-9)
        near(1.0, Framing.pinch(1.2, 0.1, redmi, false), 1e-9)
    }

    @Test fun pinch_rejectsNonPositiveInput() {
        assertFailsWith<IllegalArgumentException> { Framing.pinch(1.0, 0.0, redmi, false) }
    }

    // ---- Guides ----

    @Test fun fit_pillarboxesNarrowAspect() {
        val r = Guides.fit(2712.0, 1220.0, 16.0 / 9.0)
        near(1220.0, r.height, 1e-9)
        near(1220.0 * 16.0 / 9.0, r.width, 1e-6)
        near((2712.0 - r.width) / 2.0, r.left, 1e-6)
    }

    @Test fun fit_letterboxesWideAspect() {
        val r = Guides.fit(1000.0, 1000.0, 2.0)
        near(1000.0, r.width, 1e-9)
        near(500.0, r.height, 1e-9)
        near(250.0, r.top, 1e-9)
    }

    @Test fun delivery239InsideDisplay178() {
        val display = Guides.fit(1000.0, 1000.0 * 9.0 / 16.0, 16.0 / 9.0)
        val d = Guides.delivery(display, 16.0 / 9.0, 2.39)
        near(display.width, d.width, 1e-6) // full width, bars top and bottom
        near(display.width / 2.39, d.height, 1e-6)
        near(display.centerY, d.centerY, 1e-6)
    }

    @Test fun delivery133InsideDisplay178PillarBoxes() {
        val display = Guides.fit(1000.0, 1000.0 * 9.0 / 16.0, 16.0 / 9.0)
        val d = Guides.delivery(display, 16.0 / 9.0, 4.0 / 3.0)
        near(display.height, d.height, 1e-6)
        near(display.height * 4.0 / 3.0, d.width, 1e-6)
    }

    @Test fun insetShrinksEvenly() {
        val r = Guides.inset(RectD(0.0, 0.0, 100.0, 50.0), 0.9)
        near(90.0, r.width, 1e-9)
        near(45.0, r.height, 1e-9)
        near(5.0, r.left, 1e-9)
    }
}
