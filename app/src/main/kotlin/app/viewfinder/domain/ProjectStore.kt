package app.viewfinder.domain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saves the project settings ([Rig]) as one small JSON file. Deliberately simple and behind [ProjectRepository]
 * so Room can replace it in Phase 6 without touching callers. Reading never throws: a missing or damaged file
 * gives a clean default project instead of a crash on launch.
 */
interface ProjectRepository {
    fun load(): Rig
    fun save(rig: Rig)
}

object ProjectJson {
    const val VERSION = 1

    fun encode(rig: Rig): String = JSONObject().apply {
        put("version", VERSION)
        put("projectName", rig.projectName)
        put("cameraId", rig.cameraId)
        put("modeId", rig.modeId)
        put("lensId", rig.lensId)
        put("focalMm", rig.focalMm)
        put("tStop", rig.tStop)
        put("squeezeOverride", rig.squeezeOverride ?: JSONObject.NULL)
        put("framelines", JSONArray(rig.framelines))
        put("customRatios", JSONArray(rig.customRatios))
        put("safeAreas", JSONArray(rig.safeAreas))
        put("showThirds", rig.showThirds)
        put("showDof", rig.showDof)
        put("showInfo", rig.showInfo)
        put("focusM", rig.focusM)
        put("zoomManual", rig.zoomManual ?: JSONObject.NULL)
        put("allowDigital", rig.allowDigital)
    }.toString(2)

    /** Tolerant decode: unknown ids are dropped, numbers are clamped, anything unreadable becomes the default. */
    fun decode(text: String?): Rig {
        val d = Rig()
        if (text.isNullOrBlank()) return d
        return try {
            val o = JSONObject(text)

            val camera = PlaceholderCatalog.cameras.firstOrNull { it.id == o.optString("cameraId") } ?: PlaceholderCatalog.camera(d.cameraId)
            val mode = camera.modes.firstOrNull { it.id == o.optString("modeId") } ?: camera.modes.first()
            val lens = PlaceholderCatalog.lenses.firstOrNull { it.id == o.optString("lensId") } ?: PlaceholderCatalog.lens(d.lensId)

            val customs = doubles(o.optJSONArray("customRatios"))
                .filter { it.isFinite() }
                .map { Framelines.custom(it).aspect }
                .distinct()
                .take(Framelines.MAX_CUSTOMS)
            val frames = strings(o.optJSONArray("framelines"))
                .filter { Framelines.find(it, customs) != null }
                .distinct()
            val safe = ints(o.optJSONArray("safeAreas")).filter { it in Framelines.safeAreas }.distinct().sorted()

            val squeeze = if (o.isNull("squeezeOverride")) null else o.optDouble("squeezeOverride", Double.NaN)
                .takeIf { it.isFinite() && it in PlaceholderCatalog.squeezeChoices }
            val zoom = if (o.isNull("zoomManual")) null else o.optDouble("zoomManual", Double.NaN)
                .takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.1, 100.0)

            Rig(
                projectName = o.optString("projectName", d.projectName).ifBlank { d.projectName }.take(60),
                cameraId = camera.id,
                modeId = mode.id,
                lensId = lens.id,
                focalMm = lens.clampFocal(o.optDouble("focalMm", d.focalMm).takeIf { it.isFinite() } ?: d.focalMm),
                tStop = o.optDouble("tStop", d.tStop).takeIf { it.isFinite() && it in 0.7..32.0 } ?: d.tStop,
                squeezeOverride = squeeze,
                framelines = frames,
                customRatios = customs,
                safeAreas = safe,
                showThirds = o.optBoolean("showThirds", d.showThirds),
                showDof = o.optBoolean("showDof", d.showDof),
                showInfo = o.optBoolean("showInfo", d.showInfo),
                focusM = o.optDouble("focusM", d.focusM).takeIf { it.isFinite() }?.coerceIn(0.5, 60.0) ?: d.focusM,
                zoomManual = zoom,
                allowDigital = o.optBoolean("allowDigital", d.allowDigital),
            )
        } catch (_: Exception) {
            d
        }
    }

    private fun strings(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optString(it).takeIf { s -> s.isNotEmpty() } }
    private fun doubles(a: JSONArray?): List<Double> = (0 until (a?.length() ?: 0)).map { a!!.optDouble(it, Double.NaN) }
    private fun ints(a: JSONArray?): List<Int> = (0 until (a?.length() ?: 0)).map { a!!.optInt(it, -1) }
}

class FileProjectRepository(private val file: File) : ProjectRepository {
    override fun load(): Rig = try {
        if (file.exists()) ProjectJson.decode(file.readText()) else Rig()
    } catch (_: Exception) {
        Rig()
    }

    override fun save(rig: Rig) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(ProjectJson.encode(rig))
            if (!tmp.renameTo(file)) { // some filesystems refuse to replace: fall back to a plain overwrite
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (_: Exception) {
            // Losing a save must never crash the viewfinder.
        }
    }
}
