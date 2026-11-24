package app.viewfinder.domain

import app.viewfinder.optics.Guides
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FramelinesTest {
    @Test fun cinemaPresetsAreExactlyTheRequestedTwelve() {
        assertEquals(
            listOf("1.33:1", "1.37:1", "1.66:1", "1.78:1", "1.85:1", "2.00:1", "2.20:1", "2.35:1", "2.39:1", "2.40:1", "2.55:1", "2.76:1"),
            Framelines.cinema.map { it.label },
        )
    }

    @Test fun otherPresetsAreExactlyTheRequestedFive() {
        assertEquals(listOf("1:1", "4:5", "9:16", "3:2", "5:4"), Framelines.other.map { it.label })
    }

    @Test fun namedRatiosCarryTheirNames() {
        val byLabel = Framelines.presets.associateBy { it.label }
        assertEquals("4:3", byLabel.getValue("1.33:1").name)
        assertEquals("Academy", byLabel.getValue("1.37:1").name)
        assertEquals("16:9", byLabel.getValue("1.78:1").name)
        assertEquals("70mm", byLabel.getValue("2.20:1").name)
        assertEquals("Anamorphic", byLabel.getValue("2.39:1").name)
        assertEquals("Ultra Panavision", byLabel.getValue("2.76:1").name)
        assertEquals("Square", byLabel.getValue("1:1").name)
    }

    @Test fun exactRatioValues() {
        val a = Framelines.presets.associate { it.label to it.aspect }
        assertEquals(4.0 / 3.0, a.getValue("1.33:1"), 1e-12)
        assertEquals(16.0 / 9.0, a.getValue("1.78:1"), 1e-12)
        assertEquals(1.0, a.getValue("1:1"), 1e-12)
        assertEquals(0.8, a.getValue("4:5"), 1e-12)
        assertEquals(9.0 / 16.0, a.getValue("9:16"), 1e-12)
        assertEquals(1.5, a.getValue("3:2"), 1e-12)
        assertEquals(1.25, a.getValue("5:4"), 1e-12)
        assertEquals(2.39, a.getValue("2.39:1"), 1e-12)
    }

    @Test fun idsAreUniqueAndNamesNotBlank() {
        assertEquals(17, Framelines.presets.size)
        assertEquals(17, Framelines.presets.map { it.id }.toSet().size)
        assertTrue(Framelines.presets.all { it.name.isNotBlank() && it.label.isNotBlank() })
    }

    @Test fun safeAreasAreEightyNinetyNinetyFive() = assertEquals(listOf(80, 90, 95), Framelines.safeAreas)

    @Test fun customIdsAreStableAndClamped() {
        assertEquals("custom:2.10", Framelines.customId(2.1))
        assertEquals(Framelines.MIN_CUSTOM, Framelines.custom(0.01).aspect, 1e-9)
        assertEquals(Framelines.MAX_CUSTOM, Framelines.custom(99.0).aspect, 1e-9)
        assertEquals("2.10:1", Framelines.custom(2.1).label)
    }

    @Test fun findResolvesPresetsAndCustoms() {
        assertNotNull(Framelines.find("2.39", emptyList()))
        assertNull(Framelines.find("custom:2.10", emptyList()))
        assertEquals(2.1, Framelines.find("custom:2.10", listOf(2.1))!!.aspect, 1e-9)
        assertNull(Framelines.find("nope", listOf(2.1)))
    }

    /** Every preset, on both a 16:9 picture and a very wide 2x-anamorphic picture, lands inside it at the right shape. */
    @Test fun everyPresetFitsInsideBothDisplayShapes() {
        val screenW = 2712.0
        val screenH = 1220.0
        for (displayAspect in listOf(16.0 / 9.0, 24.9 / 14.0 * 2.0)) {
            val display = Guides.fit(screenW, screenH, displayAspect)
            for (f in Framelines.presets) {
                val r = Guides.delivery(display, displayAspect, f.aspect)
                assertTrue(r.left >= display.left - 1e-6 && r.right <= display.right + 1e-6, "${f.label} exceeds width")
                assertTrue(r.top >= display.top - 1e-6 && r.bottom <= display.bottom + 1e-6, "${f.label} exceeds height")
                assertTrue(abs(r.width / r.height - f.aspect) < 1e-6, "${f.label} has wrong shape: ${r.width / r.height}")
                assertTrue(abs(r.centerX - display.centerX) < 1e-6 && abs(r.centerY - display.centerY) < 1e-6, "${f.label} not centred")
                // One side always touches the picture edge: the frameline is as large as it can be.
                assertTrue(abs(r.width - display.width) < 1e-6 || abs(r.height - display.height) < 1e-6, "${f.label} not maximal")
            }
        }
    }

    @Test fun safeAreaInsetsAreCentredFractionsOfTheActiveFrame() {
        val display = Guides.fit(2712.0, 1220.0, 16.0 / 9.0)
        val frame = Guides.delivery(display, 16.0 / 9.0, 2.39)
        for (p in Framelines.safeAreas) {
            val s = Guides.inset(frame, p / 100.0)
            assertEquals(frame.width * p / 100.0, s.width, 1e-6)
            assertEquals(frame.height * p / 100.0, s.height, 1e-6)
            assertEquals(frame.centerX, s.centerX, 1e-6)
            assertEquals(frame.centerY, s.centerY, 1e-6)
        }
    }
}
