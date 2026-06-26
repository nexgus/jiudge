package io.github.nexgus.jiudge.core.routing

import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.reader.MapFile
import java.io.Closeable
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Decides whether a point lies on a RudyMap "艱難路線" (hike-tough) path, by reading the same `.map`
 * basemap the renderer draws (fully offline). [RoutePlanner] uses this to route a leg with BRouter's
 * tough-allowing profile only when the user placed a waypoint on a tough path - so the route can go
 * straight there rather than detour around it.
 *
 * The tough test matches RudyMap's render theme (MOI_OSM.xml, cat="hike-tough") and BRouter's
 * hiking-mountain.brf `is_tough` verbatim, so "tough here" == "drawn tough" == "blocked by the
 * strict profile":
 *   trail_visibility = bad|horrible|no   OR   sac_scale = demanding_alpine_hiking|difficult_alpine_hiking
 */
class ToughPathDetector(
    basemap: File,
) : Closeable {
    private val store = MapFile(basemap)

    /**
     * True when [point] sits on a tough path. Reads one map tile around the point, so this does file
     * I/O - call off the main thread.
     *
     * It is NOT enough to test only the single nearest way: RudyMap often draws a tough path and a
     * non-tough one in the same corridor a few metres apart (e.g. 廖添丁洞步道 has an easy steps way at
     * ~0 m and its "(艱難路線)" path at ~5 m). So a tough way counts when it is within [TOLERANCE_M]
     * AND no more than [CORRIDOR_M] farther than the closest way of any kind - i.e. it shares the
     * corridor the user aimed at, rather than being an unrelated tough path that merely passes nearby.
     * Allowing tough here only *permits* it on the leg (the route still takes the shortest path), so
     * erring slightly toward "tough" avoids the far worse failure of detouring around the aimed path.
     */
    fun isOnToughPath(point: LatLong): Boolean {
        val tileX = MercatorProjection.longitudeToTileX(point.longitude, QUERY_ZOOM)
        val tileY = MercatorProjection.latitudeToTileY(point.latitude, QUERY_ZOOM)
        val result = store.readMapData(Tile(tileX, tileY, QUERY_ZOOM, TILE_SIZE)) ?: return false

        // Local equirectangular metric (metres) anchored at the point's latitude: undistorted at the
        // scale of trail segments, and lets us compare perpendicular distances cheaply.
        val metresPerLat = 111_320.0
        val metresPerLon = 111_320.0 * cos(Math.toRadians(point.latitude))
        if (metresPerLon == 0.0) return false
        val px = point.longitude * metresPerLon
        val py = point.latitude * metresPerLat

        var nearest = Double.MAX_VALUE
        var nearestTough = Double.MAX_VALUE
        for (way in result.ways) {
            val tough = isTough(way.tags.associate { it.key to it.value })
            for (ring in way.latLongs) {
                for (i in 0 until ring.size - 1) {
                    val a = ring[i]
                    val b = ring[i + 1]
                    val ax = a.longitude * metresPerLon
                    val ay = a.latitude * metresPerLat
                    val dx = b.longitude * metresPerLon - ax
                    val dy = b.latitude * metresPerLat - ay
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                    val d = hypot(px - (ax + t * dx), py - (ay + t * dy))
                    if (d < nearest) nearest = d
                    if (tough && d < nearestTough) nearestTough = d
                }
            }
        }
        return nearestTough <= TOLERANCE_M && nearestTough <= nearest + CORRIDOR_M
    }

    override fun close() = store.close()

    companion object {
        // A tap within this distance of a tough way counts as aimed "on" it.
        private const val TOLERANCE_M = 30.0

        // How much farther than the closest way a tough way may be and still count as the same
        // corridor (handles a tough path drawn parallel to a non-tough one a few metres apart).
        private const val CORRIDOR_M = 12.0

        // High-detail zoom where trails are present; the aimed way passes through the point's tile.
        private const val QUERY_ZOOM: Byte = 16
        private const val TILE_SIZE = 256

        private val TOUGH_TRAIL_VISIBILITY = setOf("bad", "horrible", "no")
        private val TOUGH_SAC_SCALE = setOf("demanding_alpine_hiking", "difficult_alpine_hiking")

        /** RudyMap hike-tough test - keep in sync with hiking-mountain.brf and MOI_OSM.xml. */
        fun isTough(tags: Map<String, String>): Boolean =
            tags["trail_visibility"] in TOUGH_TRAIL_VISIBILITY ||
                tags["sac_scale"] in TOUGH_SAC_SCALE
    }
}
