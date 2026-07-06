package io.github.nexgus.jiudge.core.elevation

/**
 * Shared slope classification: five discrete classes over the signed grade along travel
 * (degrees, + uphill), used by both the map route line ([io.github.nexgus.jiudge.feature.planning.PlannedRouteLayer])
 * and the elevation-profile chart so the two always colour the same slope the same way.
 * Thresholds follow the common hiking grade bands (<=4 deg flat, 4-16 moderate, 16+ steep);
 * see docs/slope_vs_color.md.
 */
object SlopeScale {
    const val MODERATE_DEG = 4f // >= moderate grade (gentle/green band is -4..+4 deg)
    const val STEEP_DEG = 16f // >= steep grade (hiking convention)

    // DEM sampling step for slope: ~30 m matches the 30 m DEM and smooths per-point noise.
    const val SAMPLE_SPACING_M = 30.0

    // ARGB class colours: warm uphill, cool downhill, grey-green gentle.
    val STEEP_UP_COLOR = 0xFFC62828.toInt() // red, >= STEEP_DEG up
    val MODERATE_UP_COLOR = 0xFFF57C00.toInt() // orange
    val GENTLE_COLOR = 0xFF9CCC65.toInt() // light green, |slope| < MODERATE_DEG (also the no-DEM fallback)
    val MODERATE_DOWN_COLOR = 0xFF29B6F6.toInt() // cyan
    val STEEP_DOWN_COLOR = 0xFF1565C0.toInt() // blue, >= STEEP_DEG down

    /** Maps a signed slope (deg, + uphill) to its class colour (ARGB int). */
    fun colorFor(slopeDeg: Float): Int =
        when {
            slopeDeg >= STEEP_DEG -> STEEP_UP_COLOR
            slopeDeg >= MODERATE_DEG -> MODERATE_UP_COLOR
            slopeDeg > -MODERATE_DEG -> GENTLE_COLOR
            slopeDeg > -STEEP_DEG -> MODERATE_DOWN_COLOR
            else -> STEEP_DOWN_COLOR
        }
}
