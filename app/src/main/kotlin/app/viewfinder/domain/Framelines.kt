package app.viewfinder.domain

import kotlin.math.abs

enum class FramelineGroup { Cinema, Other, Custom }

/** A delivery aspect ratio that can be drawn over the live preview. [aspect] is width / height. */
data class Frameline(
    val id: String,
    /** Short ratio text, for example "2.39:1". */
    val label: String,
    /** Human name, for example "Anamorphic". */
    val name: String,
    val aspect: Double,
    val group: FramelineGroup,
)

object Framelines {
    val cinema: List<Frameline> = listOf(
        Frameline("1.33", "1.33:1", "4:3", 4.0 / 3.0, FramelineGroup.Cinema),
        Frameline("1.37", "1.37:1", "Academy", 1.37, FramelineGroup.Cinema),
        Frameline("1.66", "1.66:1", "European widescreen", 1.66, FramelineGroup.Cinema),
        Frameline("1.78", "1.78:1", "16:9", 16.0 / 9.0, FramelineGroup.Cinema),
        Frameline("1.85", "1.85:1", "Flat", 1.85, FramelineGroup.Cinema),
        Frameline("2.00", "2.00:1", "Univisium", 2.0, FramelineGroup.Cinema),
        Frameline("2.20", "2.20:1", "70mm", 2.2, FramelineGroup.Cinema),
        Frameline("2.35", "2.35:1", "Scope", 2.35, FramelineGroup.Cinema),
        Frameline("2.39", "2.39:1", "Anamorphic", 2.39, FramelineGroup.Cinema),
        Frameline("2.40", "2.40:1", "Modern scope", 2.40, FramelineGroup.Cinema),
        Frameline("2.55", "2.55:1", "CinemaScope", 2.55, FramelineGroup.Cinema),
        Frameline("2.76", "2.76:1", "Ultra Panavision", 2.76, FramelineGroup.Cinema),
    )

    val other: List<Frameline> = listOf(
        Frameline("1:1", "1:1", "Square", 1.0, FramelineGroup.Other),
        Frameline("4:5", "4:5", "Portrait", 4.0 / 5.0, FramelineGroup.Other),
        Frameline("9:16", "9:16", "Vertical video", 9.0 / 16.0, FramelineGroup.Other),
        Frameline("3:2", "3:2", "Photo", 3.0 / 2.0, FramelineGroup.Other),
        Frameline("5:4", "5:4", "Large format", 5.0 / 4.0, FramelineGroup.Other),
    )

    val presets: List<Frameline> = cinema + other

    /** Safe-area insets offered over the active frameline, as percentages of its size. */
    val safeAreas: List<Int> = listOf(80, 90, 95)

    const val MIN_CUSTOM = 0.25
    const val MAX_CUSTOM = 6.0
    const val MAX_CUSTOMS = 12

    fun clampCustom(aspect: Double): Double = aspect.coerceIn(MIN_CUSTOM, MAX_CUSTOM)

    fun customId(aspect: Double): String = "custom:%.2f".format(java.util.Locale.ROOT, aspect)

    fun custom(aspect: Double): Frameline {
        val a = Math.round(clampCustom(aspect) * 100.0) / 100.0
        return Frameline(customId(a), "%.2f:1".format(java.util.Locale.ROOT, a), "Custom", a, FramelineGroup.Custom)
    }

    /** Resolves an id against the presets and the project's custom ratios. Null if unknown. */
    fun find(id: String, customs: List<Double>): Frameline? =
        presets.firstOrNull { it.id == id } ?: customs.map(::custom).firstOrNull { it.id == id }

    fun nearlyEqual(a: Double, b: Double) = abs(a - b) < 0.005
}
