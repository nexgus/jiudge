package io.github.nexgus.jiudge.feature.identify

import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.reader.MapFile
import java.io.Closeable
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Identifies the map symbol nearest a long-pressed point by reading the RudyMap `.map` directly
 * (fully offline). Opens its own read-only [MapFile] over the same basemap the renderer draws.
 *
 * Matching is approximate: it returns the closest POI or point on a way within a screen-relative
 * tolerance, so in dense areas it may pick a neighbouring feature. The result always carries the
 * raw tags so the user sees the underlying data even when no friendly label is known.
 */
class SymbolIdentifier(
    basemap: File,
    private val table: SymbolTable,
) : Closeable {
    private val store = MapFile(basemap)

    /**
     * A matched feature: its friendly label (null if unknown), raw tags, distance in metres, and the
     * point that was actually grabbed (the POI itself, or the nearest vertex of a matched way) so the
     * UI can mark it on the map.
     */
    data class Match(
        val info: SymbolInfo?,
        val tags: List<Pair<String, String>>,
        val distanceMeters: Double,
        val position: LatLong,
    )

    /**
     * Returns the candidate features under the crosshair, best first. The caller shows the single one
     * directly, or a chooser when several share the spot (dense summits stack a peak, a summit board
     * and trig points within a metre - aiming cannot separate them, so the user picks).
     *
     * @param tap the crosshair location
     * @param zoom the current *display* zoom (drives the on-screen tolerance)
     * @param tilePixelSize the map's tile size
     */
    fun identify(
        tap: LatLong,
        zoom: Byte,
        tilePixelSize: Int,
    ): List<Match> {
        // The basemap's detail tops out below the max display zoom; clamp the *query* tile so
        // readMapData hits a real zoom interval and the tile does not balloon at very low zoom.
        val queryZoom = zoom.coerceIn(MIN_QUERY_ZOOM, MAX_QUERY_ZOOM)
        val tileX = MercatorProjection.longitudeToTileX(tap.longitude, queryZoom)
        val tileY = MercatorProjection.latitudeToTileY(tap.latitude, queryZoom)
        val result = store.readMapData(Tile(tileX, tileY, queryZoom, tilePixelSize)) ?: return emptyList()

        // Tolerance tracks the *display* zoom (~32 px on screen), so zooming in narrows what is
        // caught; floored/capped so it is neither pixel-perfect-tight nor region-wide.
        val mapSize = MercatorProjection.getMapSize(zoom, tilePixelSize)
        val metersPerPixel = MercatorProjection.calculateGroundResolution(tap.latitude, mapSize)
        val maxMeters = (metersPerPixel * TAP_TOLERANCE_PX).coerceIn(MIN_TOLERANCE_M, MAX_TOLERANCE_M)

        fun toMatch(
            tags: List<org.mapsforge.core.model.Tag>,
            distance: Double,
            position: LatLong,
        ): Match {
            val pairs = tags.map { it.key to it.value }
            val info = table.lookup(tags.associate { it.key to it.value })
            return Match(info, pairs, distance, position)
        }

        // The symbols a user aims at are POIs (points); lines/areas (trails, buildings) are
        // background. Prefer POIs within tolerance, ordered best-first (known label, then named,
        // then nearest). Only if no POI is near do we fall back to the single nearest way.
        val pois =
            result.pois
                .mapNotNull { poi ->
                    val d = LatLongUtils.vincentyDistance(tap, poi.position)
                    if (d <= maxMeters) toMatch(poi.tags, d, poi.position) else null
                }.sortedWith(compareByDescending<Match> { rankOf(it) }.thenBy { it.distanceMeters })
        if (pois.isNotEmpty()) return pois

        var bestWay: Match? = null
        for (way in result.ways) {
            val (point, distance) = nearestPointOnWay(tap, way.latLongs) ?: continue
            if (distance > maxMeters) continue
            if (bestWay == null || distance < bestWay.distanceMeters) bestWay = toMatch(way.tags, distance, point)
        }
        return listOfNotNull(bestWay)
    }

    private fun rankOf(match: Match): Int {
        val named = match.tags.any { it.first == "name" || it.first == "ref" }
        return (if (match.info != null) 2 else 0) + (if (named) 1 else 0)
    }

    override fun close() = store.close()

    private companion object {
        const val TAP_TOLERANCE_PX = 32.0
        const val MIN_TOLERANCE_M = 4.0
        const val MAX_TOLERANCE_M = 25.0
        const val MIN_QUERY_ZOOM: Byte = 12
        const val MAX_QUERY_ZOOM: Byte = 17

        /**
         * Nearest point *on* a way to [tap] (the foot of the perpendicular onto the closest segment,
         * clamped to its ends), and its distance (m). Returns null if the way has no points.
         *
         * Works in a local equirectangular metric (metres) anchored at [tap]'s latitude so the
         * projection is undistorted at the small scale of trail segments; the returned distance is
         * then re-measured with vincentyDistance to stay consistent with POI distances.
         *
         * If [tap] falls *inside* a closed ring (e.g. a building footprint), the returned marker
         * point is the tap itself - the user aimed within the polygon, so the marker should sit
         * there rather than jump to the nearest wall. The distance still uses the boundary, so big
         * land-cover polygons do not crowd out nearby points.
         */
        fun nearestPointOnWay(
            tap: LatLong,
            latLongs: Array<Array<LatLong>>,
        ): Pair<LatLong, Double>? {
            val metresPerLat = 111_320.0
            val metresPerLon = 111_320.0 * cos(Math.toRadians(tap.latitude))
            if (metresPerLon == 0.0) return null
            val px = tap.longitude * metresPerLon
            val py = tap.latitude * metresPerLat

            var best: LatLong? = null
            var bestPlanar = Double.MAX_VALUE
            var inside = false
            for (ring in latLongs) {
                if (ring.isEmpty()) continue
                if (ring.size == 1) {
                    val a = ring[0]
                    val d = hypot(px - a.longitude * metresPerLon, py - a.latitude * metresPerLat)
                    if (d < bestPlanar) {
                        bestPlanar = d
                        best = a
                    }
                    continue
                }
                if (containsPoint(ring, tap)) inside = true
                for (i in 0 until ring.size - 1) {
                    val a = ring[i]
                    val b = ring[i + 1]
                    val ax = a.longitude * metresPerLon
                    val ay = a.latitude * metresPerLat
                    val dx = b.longitude * metresPerLon - ax
                    val dy = b.latitude * metresPerLat - ay
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
                    val cx = ax + t * dx
                    val cy = ay + t * dy
                    val d = hypot(px - cx, py - cy)
                    if (d < bestPlanar) {
                        bestPlanar = d
                        best = LatLong(cy / metresPerLat, cx / metresPerLon)
                    }
                }
            }
            val boundary = best ?: return null
            val marker = if (inside) tap else boundary
            return marker to LatLongUtils.vincentyDistance(tap, boundary)
        }

        /** Ray-casting point-in-polygon test on a ring (in lon/lat; fine for the inside test). */
        fun containsPoint(
            ring: Array<LatLong>,
            tap: LatLong,
        ): Boolean {
            var inside = false
            var j = ring.size - 1
            for (i in ring.indices) {
                val xi = ring[i].longitude
                val yi = ring[i].latitude
                val xj = ring[j].longitude
                val yj = ring[j].latitude
                if ((yi > tap.latitude) != (yj > tap.latitude) &&
                    tap.longitude < (xj - xi) * (tap.latitude - yi) / (yj - yi) + xi
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }
    }
}
