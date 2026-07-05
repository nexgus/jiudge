package io.github.nexgus.jiudge.core.mapdata

import java.io.File
import java.io.IOException

/**
 * Per-asset record of the server file each installed RudyMap asset came from, used by
 * [MapUpdateChecker] to decide whether a mirror holds something newer. Stores the download
 * response's `Last-Modified` (identical across the RudyMap mirrors - rsync preserves mtime - so it
 * is a mirror-independent version identifier) plus the byte size as a secondary check.
 *
 * Lives beside the assets it describes (`map/mapdata_versions.tsv`), sharing their lifecycle. The
 * format is the same magic-header TSV as `PeakIndexStore`, not JSON: the only JSON codec available
 * is Android's framework org.json, which pure-JVM unit tests cannot load.
 *
 * File layout:
 * ```
 * #jiudge-mapdata-versions  1                       <- header (tab-separated)
 * <assetId>  <sizeBytes>  <lastModified>            <- one asset per line
 * ```
 * `lastModified` is the last column and read with `split(limit = 3)` because the RFC-1123 value
 * contains spaces (but never a tab).
 *
 * A missing, corrupt, or partially written file degrades to "no records": the update check then
 * reports the assets as unverifiable rather than wrongly up to date.
 */
class MapDataVersionStore(
    mapDir: File,
) {
    private val file = File(mapDir, FILE_NAME)

    data class Record(
        val sizeBytes: Long,
        val lastModified: String,
    )

    /** All stored records; empty on a missing/corrupt file, silently skipping unparsable lines. */
    fun readAll(): Map<String, Record> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            file.useLines { lines ->
                val rows = lines.iterator()
                if (!rows.hasNext() || !isHeader(rows.next())) return@useLines emptyMap()
                buildMap {
                    while (rows.hasNext()) {
                        parseRecord(rows.next())?.let { (id, record) -> put(id, record) }
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    fun put(
        assetId: String,
        record: Record,
    ) = write(readAll() + (assetId to record))

    fun remove(assetId: String) = write(readAll() - assetId)

    /** Atomic replace via `.tmp` + rename, so an interrupted write never leaves a half-file. */
    private fun write(records: Map<String, Record>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            w
                .append(MAGIC)
                .append(SEP)
                .append(VERSION)
                .append('\n')
            for ((id, record) in records) {
                w
                    .append(sanitize(id))
                    .append(SEP)
                    .append(record.sizeBytes.toString())
                    .append(SEP)
                    .append(sanitize(record.lastModified))
                    .append('\n')
            }
        }
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) throw IOException("cannot publish ${file.name}")
    }

    private fun isHeader(line: String): Boolean {
        val parts = line.split(SEP)
        return parts.size >= 2 && parts[0] == MAGIC && parts[1] == VERSION
    }

    private fun parseRecord(line: String): Pair<String, Record>? {
        val parts = line.split(SEP, limit = 3)
        if (parts.size < 3 || parts[0].isEmpty()) return null
        val size = parts[1].toLongOrNull() ?: return null
        val lastModified = parts[2].takeIf { it.isNotBlank() } ?: return null
        return parts[0] to Record(size, lastModified)
    }

    private fun sanitize(s: String): String = s.replace('\t', ' ').replace('\n', ' ').trim()

    private companion object {
        const val FILE_NAME = "mapdata_versions.tsv"
        const val MAGIC = "#jiudge-mapdata-versions"
        const val VERSION = "1"
        const val SEP = "\t"
    }
}
