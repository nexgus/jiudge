package io.github.nexgus.jiudge.core.index

import java.io.File

/**
 * On-disk persistence for the peak index. The index is a small tab-separated text file living beside
 * the basemap under `map/`, so it shares the basemap's lifecycle (wiped with the app, rebuilt on
 * demand). It is deliberately not the trace JSONL format: this is a flat lookup table loaded whole
 * into memory for substring search, not an append log.
 *
 * File layout:
 * ```
 * #jiudge-peaks  2  mapSize=<bytes>  mapMtime=<ms>  count=<n>      <- header (tab-separated)
 * <name>  <ele>  <lat>  <lon>  <locality>                          <- one peak per line, ele/locality may be blank
 * ```
 *
 * The header stamps the source basemap's size + mtime. On launch the caller compares that stamp
 * against the live basemap: a mismatch (or a missing/corrupt file) means the map was (re)installed
 * or updated, so the index is rebuilt - this covers a future map update overwriting the `.map`
 * without the updater needing to trigger a rebuild explicitly.
 */
object PeakIndexStore {
    private const val INDEX_FILE_NAME = "peaks.index"
    private const val MAGIC = "#jiudge-peaks"

    // Bumped to 2 when the locality column was added: an old v1 index fails the version check and is
    // rebuilt automatically (no migration needed, the index is cheap to regenerate from the basemap).
    private const val VERSION = "2"
    private const val SEP = "\t"

    /** Identifies the basemap an index was built from: a rebuild is forced when either field drifts. */
    data class Stamp(
        val mapSizeBytes: Long,
        val mapModifiedMs: Long,
    )

    fun indexFile(mapDir: File): File = File(mapDir, INDEX_FILE_NAME)

    /** The stamp of the live basemap, used both to build the header and to detect staleness. */
    fun stampOf(basemap: File): Stamp = Stamp(basemap.length(), basemap.lastModified())

    /**
     * Writes [peaks] with [stamp] atomically: a sibling `.tmp` is filled then renamed into place, so
     * an interrupted write never leaves a half-file that a stamp check would accept as valid.
     */
    fun write(
        mapDir: File,
        stamp: Stamp,
        peaks: List<Peak>,
    ) {
        val dest = indexFile(mapDir)
        val tmp = File(mapDir, "$INDEX_FILE_NAME.tmp")
        tmp.bufferedWriter().use { w ->
            w
                .append(MAGIC)
                .append(SEP)
                .append(VERSION)
                .append(SEP)
                .append("mapSize=")
                .append(stamp.mapSizeBytes.toString())
                .append(SEP)
                .append("mapMtime=")
                .append(stamp.mapModifiedMs.toString())
                .append(SEP)
                .append("count=")
                .append(peaks.size.toString())
                .append('\n')
            for (p in peaks) {
                // Names/localities are free OSM text; strip any stray tab/newline so columns stay aligned.
                w
                    .append(sanitize(p.name))
                    .append(SEP)
                    .append(p.eleMeters?.toString() ?: "")
                    .append(SEP)
                    .append(p.latitude.toString())
                    .append(SEP)
                    .append(p.longitude.toString())
                    .append(SEP)
                    .append(p.locality?.let(::sanitize) ?: "")
                    .append('\n')
            }
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw java.io.IOException("cannot publish peak index")
    }

    /** Reads only the header stamp (cheap), or null if the file is absent or its header is unusable. */
    fun readStamp(mapDir: File): Stamp? {
        val file = indexFile(mapDir)
        if (!file.isFile) return null
        return runCatching {
            file.bufferedReader().use { it.readLine() }?.let(::parseHeader)
        }.getOrNull()
    }

    /** Loads every peak, or null if the file is absent or malformed (caller treats null as "rebuild"). */
    fun readAll(mapDir: File): List<Peak>? {
        val file = indexFile(mapDir)
        if (!file.isFile) return null
        return runCatching {
            file.useLines { lines ->
                val rows = lines.iterator()
                if (!rows.hasNext() || parseHeader(rows.next()) == null) return@useLines null
                val peaks = ArrayList<Peak>()
                while (rows.hasNext()) {
                    parsePeak(rows.next())?.let { peaks.add(it) }
                }
                peaks
            }
        }.getOrNull()
    }

    private fun parseHeader(line: String): Stamp? {
        val parts = line.split(SEP)
        if (parts.size < 4 || parts[0] != MAGIC || parts[1] != VERSION) return null
        val fields =
            parts
                .mapNotNull { field ->
                    val eq = field.indexOf('=')
                    if (eq < 0) null else field.substring(0, eq) to field.substring(eq + 1)
                }.toMap()
        val size = fields["mapSize"]?.toLongOrNull() ?: return null
        val mtime = fields["mapMtime"]?.toLongOrNull() ?: return null
        return Stamp(size, mtime)
    }

    private fun parsePeak(line: String): Peak? {
        val parts = line.split(SEP)
        if (parts.size < 4) return null
        val lat = parts[2].toDoubleOrNull() ?: return null
        val lon = parts[3].toDoubleOrNull() ?: return null
        return Peak(
            name = parts[0],
            eleMeters = parts[1].toIntOrNull(),
            latitude = lat,
            longitude = lon,
            locality = parts.getOrNull(4)?.takeIf { it.isNotBlank() },
        )
    }

    private fun sanitize(s: String): String = s.replace('\t', ' ').replace('\n', ' ').trim()
}
