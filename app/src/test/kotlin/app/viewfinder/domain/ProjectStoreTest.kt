package app.viewfinder.domain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectStoreTest {
    private val sample = Rig(
        projectName = "Night shoot",
        cameraId = "alexa-35",
        modeId = "169-46",
        lensId = "ana2-primes",
        focalMm = 75.0,
        tStop = 2.8,
        squeezeOverride = 1.5,
        framelines = listOf("1.85", "2.39"),
        customRatios = listOf(2.1),
        safeAreas = listOf(90, 95),
        showThirds = true,
        showDof = false,
        showInfo = true,
        focusM = 4.5,
        zoomManual = 2.5,
        allowDigital = true,
    )

    @Test fun roundTripKeepsEverything() = assertEquals(sample, ProjectJson.decode(ProjectJson.encode(sample)))

    @Test fun selectedAspectRatioIsSavedInTheProjectSettings() {
        val saved = ProjectJson.decode(ProjectJson.encode(Rig(framelines = listOf("2.39"))))
        assertEquals(listOf("2.39"), saved.framelines)
    }

    @Test fun framelineOrderIsPreserved_lastIsActive() {
        val r = ProjectJson.decode(ProjectJson.encode(Rig(framelines = listOf("2.39", "1.85", "1:1"))))
        assertEquals(listOf("2.39", "1.85", "1:1"), r.framelines)
    }

    @Test fun customFramelineIsKeptWhenItsRatioExists() {
        val r = ProjectJson.decode(ProjectJson.encode(Rig(customRatios = listOf(2.1), framelines = listOf("custom:2.10"))))
        assertEquals(listOf("custom:2.10"), r.framelines)
        assertEquals(listOf(2.1), r.customRatios)
    }

    @Test fun customFramelineWithoutItsRatioIsDropped() {
        val text = ProjectJson.encode(Rig(framelines = listOf("custom:2.10", "2.39")))
        assertEquals(listOf("2.39"), ProjectJson.decode(text).framelines)
    }

    @Test fun unknownIdsAreDropped() {
        val text = ProjectJson.encode(sample).replace("\"2.39\"", "\"9.99\"")
        assertEquals(listOf("1.85"), ProjectJson.decode(text).framelines)
        val badCamera = ProjectJson.encode(sample).replace("alexa-35", "no-such-camera")
        val r = ProjectJson.decode(badCamera)
        assertEquals("generic-s35", r.cameraId)
        assertTrue(PlaceholderCatalog.camera(r.cameraId).modes.any { it.id == r.modeId })
    }

    @Test fun customRatiosAreClampedDedupedAndCapped() {
        val text = ProjectJson.encode(Rig()).replace("\"customRatios\": []", "\"customRatios\": [0.1, 10, 2.1, 2.1004, 1.5]")
        assertEquals(listOf(Framelines.MIN_CUSTOM, Framelines.MAX_CUSTOM, 2.1, 1.5), ProjectJson.decode(text).customRatios)
        val many = (1..40).joinToString(",") { "1.${it + 10}" }
        val capped = ProjectJson.decode(ProjectJson.encode(Rig()).replace("\"customRatios\": []", "\"customRatios\": [$many]"))
        assertTrue(capped.customRatios.size <= Framelines.MAX_CUSTOMS)
    }

    @Test fun safeAreasAreFilteredSortedAndDeduped() {
        val text = ProjectJson.encode(Rig()).replace("\"safeAreas\": []", "\"safeAreas\": [95, 85, 80, 95]")
        assertEquals(listOf(80, 95), ProjectJson.decode(text).safeAreas)
    }

    @Test fun badNumbersFallBackToSafeValues() {
        val text = """{"focalMm": 9999, "tStop": -3, "focusM": 5000, "zoomManual": -2, "squeezeOverride": 7.7}"""
        val r = ProjectJson.decode(text)
        assertEquals(PlaceholderCatalog.lens(r.lensId).maxFocal, r.focalMm, 1e-9) // clamped into the lens range
        assertEquals(Rig().tStop, r.tStop, 1e-9)
        assertEquals(60.0, r.focusM, 1e-9)
        assertNull(r.zoomManual)
        assertNull(r.squeezeOverride)
    }

    @Test fun missingBlankAndCorruptTextGiveTheDefaultProject() {
        assertEquals(Rig(), ProjectJson.decode(null))
        assertEquals(Rig(), ProjectJson.decode(""))
        assertEquals(Rig(), ProjectJson.decode("{ this is not json"))
        assertEquals(Rig(), ProjectJson.decode("[1,2,3]"))
        assertEquals(Rig(), ProjectJson.decode(ProjectJson.encode(sample).take(40)))
    }

    @Test fun repositorySavesAndReloadsFromDisk() {
        val dir = File(System.getProperty("java.io.tmpdir"), "vf-test-${System.nanoTime()}")
        try {
            val file = File(dir, "projects/default.json")
            assertEquals(Rig(), FileProjectRepository(file).load()) // nothing saved yet
            FileProjectRepository(file).save(sample)
            assertEquals(sample, FileProjectRepository(file).load()) // a fresh instance, as after an app restart
            assertTrue(!File(dir, "projects/default.json.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun repositoryTreatsADamagedFileAsEmpty() {
        val dir = File(System.getProperty("java.io.tmpdir"), "vf-test-${System.nanoTime()}")
        try {
            val file = File(dir, "default.json").also { it.parentFile.mkdirs(); it.writeText("garbage \u0000\u0001") }
            assertEquals(Rig(), FileProjectRepository(file).load())
        } finally {
            dir.deleteRecursively()
        }
    }
}
