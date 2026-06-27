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
 * northing. Taiwan splits into two zones by central meridian: 121°E for the main island and 119°E
 * for the outlying islands (Penghu/Kinmen/Matsu); [convert] picks the zone from the longitude so a
 * fix anywhere in the country lands in the right grid.
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

    // Longitudes below this fall in the 119°E outlying-island zone; the rest use the 121°E main zone.
    private const val ZONE_SPLIT_LON = 120.5

    /** Projects WGS84 [lat]/[lon] (decimal degrees) onto the appropriate TWD97 TM2 zone. */
    fun convert(
        lat: Double,
        lon: Double,
    ): Coordinate {
        val centralMeridianDeg = if (lon < ZONE_SPLIT_LON) 119 else 121
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
