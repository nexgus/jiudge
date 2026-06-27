package io.github.nexgus.jiudge.core.geo

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Converts WGS84 geographic coordinates to TWD97 TM2 (二度分帶) grid coordinates - the projected
 * easting/northing in metres that Taiwanese topographic maps and hikers use. TWD97's datum is
 * GRS80-based and, for field use at this app's precision, interchangeable with WGS84, so the lat/lon
 * a GPS reports is fed in directly without a datum shift.
 *
 * TM2 is a transverse Mercator with a 0.9999 scale factor, a 250 km false easting and no false
 * northing. Taiwan officially uses two zones by central meridian: 119°E for the four outlying-island
 * regions (Kinmen, Wuqiu, Penghu, Matsu) and 121°E for everywhere else, including the offshore
 * islands administered with main-island counties (Green Island, Orchid Island, Liuqiu, Guishan).
 * The 119°E and 121°E zones overlap in longitude - Matsu's Dongyin reaches 120.5°E, east of
 * Taiwan's west coast around 120.0°E - so a single longitude threshold cannot pick the right zone.
 * [convert] tests the point against per-region bounding boxes for the 119°E zones and falls back to
 * 121°E elsewhere.
 */
object Twd97 {
    /** A TWD97 TM2 grid coordinate in metres, tagged with the central meridian of the zone used. */
    data class Coordinate(
        val easting: Double,
        val northing: Double,
        /** Central meridian of the zone (121 for the main island, 119 for the outlying islands). */
        val centralMeridianDeg: Int,
    )

    // GRS80 ellipsoid (the TWD97 reference), with the TM2 projection constants.
    private const val A = 6_378_137.0
    private const val F = 1.0 / 298.257222101
    private const val K0 = 0.9999
    private const val FALSE_EASTING = 250_000.0

    private data class LatLonBox(
        val latRange: ClosedFloatingPointRange<Double>,
        val lonRange: ClosedFloatingPointRange<Double>,
    ) {
        fun contains(
            lat: Double,
            lon: Double,
        ): Boolean = lat in latRange && lon in lonRange
    }

    // Bounding boxes of regions that use the 119°E zone. Each box wraps the actual island geometry
    // plus ~0.05° (~5 km) of slack to cover irregular coastlines, jetties and GPS jitter at the edge.
    // Anything outside all of these falls back to the 121°E main-island zone.
    private val OUTLYING_ISLAND_BOXES =
        listOf(
            // 金門 (大金門, 烈嶼, 大膽, 二膽, 復興嶼)
            LatLonBox(latRange = 24.30..24.60, lonRange = 118.15..118.55),
            // 烏坵 (烏坵嶼, 小坵嶼; 行政屬金門縣, 地理上孤立於金門與馬祖之間)
            LatLonBox(latRange = 24.94..25.05, lonRange = 119.40..119.51),
            // 澎湖 (七美, 望安, 馬公, 白沙, 西嶼, 吉貝, 目斗嶼, 花嶼, 查母嶼)
            LatLonBox(latRange = 23.13..23.84, lonRange = 119.25..119.76),
            // 馬祖 (莒光, 南竿, 北竿, 亮島, 東引)
            LatLonBox(latRange = 25.91..26.43, lonRange = 119.88..120.55),
        )

    /** Projects WGS84 [lat]/[lon] (decimal degrees) onto the appropriate TWD97 TM2 zone. */
    fun convert(
        lat: Double,
        lon: Double,
    ): Coordinate {
        val centralMeridianDeg = if (OUTLYING_ISLAND_BOXES.any { it.contains(lat, lon) }) 119 else 121
        val phi = Math.toRadians(lat)
        val lambda = Math.toRadians(lon)
        val lambda0 = Math.toRadians(centralMeridianDeg.toDouble())

        val e2 = F * (2 - F)
        val ep2 = e2 / (1 - e2)
        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val tanPhi = tan(phi)

        val n = A / sqrt(1 - e2 * sinPhi * sinPhi)
        val t = tanPhi * tanPhi
        val c = ep2 * cosPhi * cosPhi
        val a1 = (lambda - lambda0) * cosPhi

        // Meridional arc length from the equator to phi.
        val m =
            A * (
                (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2 * e2 * e2 / 256) * phi -
                    (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * e2 * e2 * e2 / 1024) * sin(2 * phi) +
                    (15 * e2 * e2 / 256 + 45 * e2 * e2 * e2 / 1024) * sin(4 * phi) -
                    (35 * e2 * e2 * e2 / 3072) * sin(6 * phi)
            )

        val easting =
            FALSE_EASTING +
                K0 * n * (
                    a1 +
                        (1 - t + c) * a1 * a1 * a1 / 6 +
                        (5 - 18 * t + t * t + 72 * c - 58 * ep2) * a1 * a1 * a1 * a1 * a1 / 120
                )
        val northing =
            K0 * (
                m +
                    n * tanPhi * (
                        a1 * a1 / 2 +
                            (5 - t + 9 * c + 4 * c * c) * a1 * a1 * a1 * a1 / 24 +
                            (61 - 58 * t + t * t + 600 * c - 330 * ep2) * a1 * a1 * a1 * a1 * a1 * a1 / 720
                    )
            )

        return Coordinate(easting, northing, centralMeridianDeg)
    }
}
