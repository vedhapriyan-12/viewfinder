package app.viewfinder.domain

/**
 * PLACEHOLDER catalog for the first UI build. Phase 2 replaces it with the sourced Room catalog.
 * Every entry says whether its numbers are verified against a manufacturer document. None are yet.
 */
data class SensorMode(val id: String, val label: String, val widthMm: Double, val heightMm: Double) {
    val aspect: Double get() = widthMm / heightMm
}

data class CameraBody(
    val id: String,
    val make: String,
    val model: String,
    val modes: List<SensorMode>,
    val verified: Boolean,
    val note: String = "",
) {
    val displayName: String get() = if (make.isEmpty()) model else "$make $model"
}

data class LensSet(
    val id: String,
    val name: String,
    /** Anamorphic horizontal squeeze (1.0 = spherical). */
    val squeeze: Double,
    /** Focal lengths of a prime set; empty for a zoom. */
    val primes: List<Double>,
    val zoomMinMm: Double? = null,
    val zoomMaxMm: Double? = null,
    val verified: Boolean,
) {
    val isZoom: Boolean get() = zoomMinMm != null && zoomMaxMm != null
    val minFocal: Double get() = if (isZoom) zoomMinMm!! else primes.min()
    val maxFocal: Double get() = if (isZoom) zoomMaxMm!! else primes.max()

    fun clampFocal(mm: Double): Double =
        if (isZoom) mm.coerceIn(zoomMinMm!!, zoomMaxMm!!) else primes.minBy { kotlin.math.abs(it - mm) }
}

object PlaceholderCatalog {
    val cameras: List<CameraBody> = listOf(
        CameraBody(
            id = "generic-s35", make = "", model = "Generic Super 35",
            modes = listOf(
                SensorMode("16x9", "16:9", 24.9, 14.0),
                SensorMode("4x3", "4:3", 18.7, 14.0),
            ),
            verified = false, note = "Illustrative generic format.",
        ),
        CameraBody(
            id = "generic-ff", make = "", model = "Generic full frame",
            modes = listOf(
                SensorMode("3x2", "3:2", 36.0, 24.0),
                SensorMode("16x9", "16:9", 36.0, 20.25),
            ),
            verified = false, note = "Illustrative generic format.",
        ),
        CameraBody(
            id = "alexa-35", make = "ARRI", model = "Alexa 35",
            modes = listOf(
                SensorMode("og32", "4.6K 3:2 open gate", 27.99, 19.22),
                SensorMode("169-46", "4.6K 16:9", 27.99, 15.75),
                SensorMode("169-4k", "4K 16:9", 24.88, 14.0),
            ),
            verified = false, note = "Sizes taken from a screenshot, not yet checked against the maker's documents.",
        ),
    )

    val lenses: List<LensSet> = listOf(
        LensSet("sph-primes", "Generic spherical primes", 1.0, listOf(18.0, 24.0, 35.0, 50.0, 75.0, 100.0), verified = false),
        LensSet("ana2-primes", "Generic 2x anamorphic primes", 2.0, listOf(35.0, 40.0, 50.0, 75.0, 100.0), verified = false),
        LensSet("ana133-primes", "Generic 1.33x anamorphic primes", 1.33, listOf(28.0, 35.0, 50.0, 75.0), verified = false),
        LensSet("zoom-24-70", "Generic cine zoom 24-70", 1.0, emptyList(), 24.0, 70.0, verified = false),
    )

    val tStops: List<Double> = listOf(1.4, 2.0, 2.8, 4.0, 5.6, 8.0, 11.0)

    val squeezeChoices: List<Double?> = listOf(null, 1.0, 1.33, 1.5, 1.8, 2.0)

    fun camera(id: String) = cameras.first { it.id == id }
    fun lens(id: String) = lenses.first { it.id == id }
}
