package io.github.nexgus.jiudge.core.index

import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.reader.MapFile
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Builds the peak index by reading the RudyMap basemap directly (fully offline). Peak names live on
 * `man_made=summit_board` POI nodes (not `natural=peak`, which are unnamed elevation points); the
 * whole set sits in the lowest zoom interval, so a single full sweep at [SCAN_ZOOM] over the
 * basemap's bounding box reaches every summit in a few hundred tiles.
 *
 * The same sweep also collects named `place` nodes (直轄市/鄉鎮/村里 settlements). After scanning,
 * near-coincident same-name summits are de-duplicated (RudyMap often records one peak twice, metres
 * apart) and each surviving summit is tagged with its nearest place name, so the search list can tell
 * same-named peaks apart without showing coordinates.
 */
object PeakIndexBuilder {
    // The summit_board POIs all resolve at zoom 10 (verified: identical counts at 10/12/14/16), so
    // this is the coarsest zoom that still returns them - the fewest tiles for a full sweep. The
    // place nodes used for localities sit at the same interval and come back in the same read.
    private const val SCAN_ZOOM: Byte = 10

    // POI reading ignores pixel size; any positive tile size works for addressing.
    private const val TILE_SIZE = 256

    private const val SUMMIT_KEY = "man_made"
    private const val SUMMIT_VALUE = "summit_board"

    // Same-named summits closer than this are treated as one record (a digitising artifact) and
    // collapsed; genuinely distinct same-named peaks are tens of km apart, far above this.
    private const val DEDUPE_RADIUS_M = 50.0

    // Place levels used for the locality label: 直轄市 / 鄉鎮(區) / 村里. County is excluded (too
    // coarse - two same-named peaks can share a county); hamlet is excluded (too granular/obscure).
    private val PLACE_TYPES = setOf("city", "town", "village")

    private data class Place(
        val name: String,
        val latitude: Double,
        val longitude: Double,
    )

    /**
     * Scans [basemap] and returns every named summit, de-duplicated and tagged with a nearby place.
     * [onProgress] is invoked once per tile with (tilesDone, tilesTotal) so the UI can show a
     * determinate bar; the total is known up front from the bounding box.
     */
    fun build(
        basemap: File,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<Peak> {
        val store = MapFile(basemap)
        try {
            val bbox = store.boundingBox()
            val minTileX = MercatorProjection.longitudeToTileX(bbox.minLongitude, SCAN_ZOOM)
            val maxTileX = MercatorProjection.longitudeToTileX(bbox.maxLongitude, SCAN_ZOOM)
            // tileY grows southward, so the northern (max) latitude maps to the smaller tile index.
            val minTileY = MercatorProjection.latitudeToTileY(bbox.maxLatitude, SCAN_ZOOM)
            val maxTileY = MercatorProjection.latitudeToTileY(bbox.minLatitude, SCAN_ZOOM)

            val total = ((maxTileX - minTileX + 1) * (maxTileY - minTileY + 1)).coerceAtLeast(1)
            // Identity de-dup across tile borders by name + rounded position (a POI belongs to one
            // tile, but rounding guards against any boundary double-count). Done for both layers.
            val seenPeak = HashSet<String>()
            val seenPlace = HashSet<String>()
            val rawPeaks = ArrayList<Peak>()
            val places = ArrayList<Place>()
            var done = 0
            for (tileX in minTileX..maxTileX) {
                for (tileY in minTileY..maxTileY) {
                    val result = store.readPoiData(Tile(tileX, tileY, SCAN_ZOOM, TILE_SIZE))
                    result?.pois?.forEach { poi ->
                        val lat = poi.position.latitude
                        val lon = poi.position.longitude
                        val peak = toPeak(poi.tags, lat, lon)
                        if (peak != null) {
                            if (seenPeak.add(identityKey(peak.name, lat, lon))) rawPeaks.add(peak)
                            return@forEach
                        }
                        val place = toPlace(poi.tags, lat, lon)
                        if (place != null && seenPlace.add(identityKey(place.name, lat, lon))) places.add(place)
                    }
                    done++
                    onProgress(done, total)
                }
            }
            return dedupeAndTag(rawPeaks, places)
        } finally {
            store.close()
        }
    }

    /** Collapses near-coincident same-name summits (< [DEDUPE_RADIUS_M]) and tags each with a place. */
    private fun dedupeAndTag(
        rawPeaks: List<Peak>,
        places: List<Place>,
    ): List<Peak> {
        val kept = ArrayList<Peak>()
        rawPeaks.groupBy { it.name }.forEach { (_, group) ->
            // Prefer a survivor that carries an elevation, so the kept record is the richer one.
            val ordered = group.sortedByDescending { it.eleMeters != null }
            val keptInGroup = ArrayList<Peak>()
            for (peak in ordered) {
                val isDuplicate =
                    keptInGroup.any {
                        distanceMeters(it.latitude, it.longitude, peak.latitude, peak.longitude) <
                            DEDUPE_RADIUS_M
                    }
                if (!isDuplicate) keptInGroup.add(peak)
            }
            kept.addAll(keptInGroup)
        }
        return kept.map { it.copy(locality = nearestPlaceName(it.latitude, it.longitude, places)) }
    }

    private fun nearestPlaceName(
        latitude: Double,
        longitude: Double,
        places: List<Place>,
    ): String? {
        var best: String? = null
        var bestDistance = Double.MAX_VALUE
        for (place in places) {
            val d = distanceMeters(latitude, longitude, place.latitude, place.longitude)
            if (d < bestDistance) {
                bestDistance = d
                best = place.name
            }
        }
        return best
    }

    private fun toPeak(
        tags: List<org.mapsforge.core.model.Tag>,
        latitude: Double,
        longitude: Double,
    ): Peak? {
        var isSummit = false
        var name: String? = null
        var eleTag: String? = null
        for (tag in tags) {
            when (tag.key) {
                SUMMIT_KEY -> if (tag.value == SUMMIT_VALUE) isSummit = true
                "name" -> name = tag.value
                "ele" -> eleTag = tag.value
            }
        }
        if (!isSummit || name.isNullOrBlank()) return null
        return Peak(
            name = name,
            eleMeters = parseEle(eleTag) ?: parseEleFromName(name),
            latitude = latitude,
            longitude = longitude,
        )
    }

    private fun toPlace(
        tags: List<org.mapsforge.core.model.Tag>,
        latitude: Double,
        longitude: Double,
    ): Place? {
        var isPlace = false
        var name: String? = null
        for (tag in tags) {
            when (tag.key) {
                "place" -> if (tag.value in PLACE_TYPES) isPlace = true
                "name" -> name = tag.value
            }
        }
        if (!isPlace || name.isNullOrBlank()) return null
        return Place(name, latitude, longitude)
    }

    /** Parses an `ele` value such as "3952" or "3952 m"; first integer run wins, null if none. */
    private fun parseEle(raw: String?): Int? {
        if (raw == null) return null
        return Regex("""\d+""").find(raw)?.value?.toIntOrNull()
    }

    /** Falls back to the height RudyMap bakes into the label, e.g. "玉山, 3952m" -> 3952. */
    private fun parseEleFromName(name: String): Int? =
        Regex(""",\s*(\d+)\s*m""")
            .find(name)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun identityKey(
        name: String,
        latitude: Double,
        longitude: Double,
    ): String = "$name@${"%.5f".format(latitude)},${"%.5f".format(longitude)}"

    /** Straight-line distance in metres via a local equirectangular metric (fine at these scales). */
    private fun distanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val dy = (lat1 - lat2) * 111_320.0
        val dx = (lon1 - lon2) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
        return hypot(dx, dy)
    }
}
