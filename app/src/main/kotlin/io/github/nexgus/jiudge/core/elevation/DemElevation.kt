package io.github.nexgus.jiudge.core.elevation

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * On-device elevation lookup over RudyMap's `.hgt` DEM (the same tiles mapsforge hillshading reads,
 * under `<mapDir>/hgt/`). Per trace_spec.md §8, elevation - and therefore slope - is a derived value
 * recomputed at use time from (lat, lon) + DEM, never stored in trace files; this is the query path
 * that backs route slope colouring.
 *
 * `.hgt` is the SRTM format: a square grid of big-endian 16-bit elevations (metres), row 0 = the
 * north edge, column 0 = the west edge, samples evenly spaced over a 1x1-degree tile. The grid side
 * (3601 for 30 m / SRTM1, 1201 for 90 m / SRTM3) is inferred from the file length, so the RudyMap
 * hgtmix mix of 30 m Taiwan + 90 m island tiles is handled per tile. `-32768` marks a void sample.
 *
 * Tiles are memory-mapped and cached (LRU), so a lookup is a couple of array reads after the first
 * touch. [elevationAt] is safe to call from multiple threads.
 */
class DemElevation private constructor(
    private val demDir: File,
) {
    private val lock = Any()

    // Access-ordered LRU: the eldest tile is dropped once we hold more than MAX_TILES.
    private val cache =
        object : LinkedHashMap<String, Tile?>(MAX_TILES * 2, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Tile?>): Boolean = size > MAX_TILES
        }

    /** Bilinearly-interpolated elevation in metres at [lat]/[lon], or null if the tile or sample is absent. */
    fun elevationAt(
        lat: Double,
        lon: Double,
    ): Float? {
        val tileLat = floor(lat).toInt()
        val tileLon = floor(lon).toInt()
        return tile(tileLat, tileLon)?.sample(lat, lon)
    }

    private fun tile(
        tileLat: Int,
        tileLon: Int,
    ): Tile? =
        synchronized(lock) {
            val key = "$tileLat,$tileLon"
            // Cache nulls too (a missing tile stays missing) so we do not stat the file every query.
            if (cache.containsKey(key)) {
                cache[key]
            } else {
                Tile.open(File(demDir, fileName(tileLat, tileLon)), tileLat, tileLon).also { cache[key] = it }
            }
        }

    /** One memory-mapped `.hgt` tile covering the 1x1-degree cell whose SW corner is ([tileLat], [tileLon]). */
    private class Tile private constructor(
        private val samples: ShortBuffer,
        private val side: Int,
        private val tileLat: Int,
        private val tileLon: Int,
    ) {
        fun sample(
            lat: Double,
            lon: Double,
        ): Float? {
            // Fractional grid coordinates: column runs west->east, row runs north->south (row 0 = north edge).
            val col = (lon - tileLon) * (side - 1)
            val row = (tileLat + 1 - lat) * (side - 1)
            if (col < 0.0 || row < 0.0 || col > side - 1 || row > side - 1) return null
            val c0 = floor(col).toInt().coerceIn(0, side - 2)
            val r0 = floor(row).toInt().coerceIn(0, side - 2)
            val fc = col - c0
            val fr = row - r0
            val e00 = elevation(r0, c0)
            val e01 = elevation(r0, c0 + 1)
            val e10 = elevation(r0 + 1, c0)
            val e11 = elevation(r0 + 1, c0 + 1)
            // If any corner is void, fall back to the nearest valid corner rather than poisoning the result.
            if (e00 == null || e01 == null || e10 == null || e11 == null) {
                return nearestValid(fr, fc, e00, e01, e10, e11)
            }
            val top = e00 + (e01 - e00) * fc
            val bottom = e10 + (e11 - e10) * fc
            return (top + (bottom - top) * fr).toFloat()
        }

        private fun elevation(
            row: Int,
            col: Int,
        ): Float? {
            val raw = samples.get(row * side + col).toInt()
            return if (raw == VOID) null else raw.toFloat()
        }

        private fun nearestValid(
            fr: Double,
            fc: Double,
            e00: Float?,
            e01: Float?,
            e10: Float?,
            e11: Float?,
        ): Float? {
            // Pick the corner closest to the sample point that actually has data.
            val corners = listOf(Triple(0.0, 0.0, e00), Triple(0.0, 1.0, e01), Triple(1.0, 0.0, e10), Triple(1.0, 1.0, e11))
            return corners
                .filter { it.third != null }
                .minByOrNull { (r, c, _) -> (r - fr) * (r - fr) + (c - fc) * (c - fc) }
                ?.third
        }

        companion object {
            private const val VOID = -32768

            fun open(
                file: File,
                tileLat: Int,
                tileLon: Int,
            ): Tile? {
                if (!file.isFile) return null
                val length = file.length()
                val side = sqrt(length / 2.0).roundToInt()
                // Must be a square grid of 16-bit samples; anything else is not a usable .hgt tile.
                if (side < 2 || side.toLong() * side * 2L != length) return null
                val buffer =
                    RandomAccessFile(file, "r").use { raf ->
                        // The mapping outlives the channel, so the file handle can close immediately.
                        raf.channel
                            .map(FileChannel.MapMode.READ_ONLY, 0, length)
                            .order(ByteOrder.BIG_ENDIAN)
                            .asShortBuffer()
                    }
                return Tile(buffer, side, tileLat, tileLon)
            }
        }
    }

    companion object {
        private const val MAX_TILES = 8

        /** Standard SRTM tile name, e.g. `N24E121.hgt` (Taiwan is all N/E). */
        private fun fileName(
            tileLat: Int,
            tileLon: Int,
        ): String {
            val ns = if (tileLat >= 0) "N" else "S"
            val ew = if (tileLon >= 0) "E" else "W"
            return String.format(Locale.US, "%s%02d%s%03d.hgt", ns, abs(tileLat), ew, abs(tileLon))
        }

        /** Returns a reader for [demDir], or null if the DEM folder is absent (slope colouring then no-ops). */
        fun createOrNull(demDir: File): DemElevation? = if (demDir.isDirectory) DemElevation(demDir) else null
    }
}
