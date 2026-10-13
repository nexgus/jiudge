package io.github.nexgus.jiudge.data.route

import org.json.JSONObject
import java.io.File

/**
 * Low-level reader/writer for the JSONL "Trace" format. A trace file is one header line describing
 * the whole file, followed by one self-contained JSON object per record, each keyed by `k`. Planned
 * routes and recorded tracks share this line layer and one file layout; which of the two a file
 * holds is decided by its record kinds alone (see [PathKind]), never by the header.
 *
 * The format is line-oriented so a track can be appended a point at a time and a torn final line
 * (crash / power loss mid-write) can be skipped without losing the rest. Domain models such as
 * [PlannedRoute] and [RecordedTrack] build their typed records on top of this layer.
 */
object Trace {
    /**
     * Trace schema version stamped into every header this build writes. Version 2 dropped the
     * version-1 header field `type`; the record layer is unchanged, so both versions are read.
     */
    const val SCHEMA_VERSION = 2

    /** Application identifier written into every header's `app` field. */
    const val APP_ID = "jiudge"

    /** File extension for trace files (spec §12). */
    const val FILE_SUFFIX = ".jsonl"

    /** A parsed trace: its header plus every record line that parsed cleanly (torn lines dropped). */
    data class Parsed(
        val header: TraceHeader,
        val records: List<JSONObject>,
    )

    /**
     * Reads [file] line by line. The first parseable line is the header; each later line is parsed
     * independently and a line that fails to parse (e.g. a torn final line) is skipped, not thrown
     * (spec §9). Returns null if no usable header is found.
     */
    fun read(file: File): Parsed? {
        var header: TraceHeader? = null
        val records = mutableListOf<JSONObject>()
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (header == null) {
                    header = runCatching { TraceHeader.fromJson(json) }.getOrNull()
                } else {
                    records += json
                }
            }
        }
        return header?.let { Parsed(it, records) }
    }

    /**
     * Writes [header] then every line in [records] as one JSONL file, atomically replacing [file].
     *
     * A sibling `.tmp` is filled then renamed into place, so an interrupted write (process kill,
     * power loss) never truncates the existing file to a half-written state - the old contents stay
     * intact until the rename swaps in the complete new file. This matters most when [file] already
     * exists and is the path's only copy (e.g. [PathStore.rename] hitting the same slug + createdAt
     * as the original).
     */
    fun write(
        file: File,
        header: TraceHeader,
        records: List<JSONObject>,
    ) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.bufferedWriter().use { writer ->
            writer.append(header.toLine()).append('\n')
            for (record in records) writer.append(record.toString()).append('\n')
        }
        if (file.exists() && !file.delete()) {
            tmp.delete()
            throw java.io.IOException("cannot replace trace file: ${file.name}")
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw java.io.IOException("cannot publish trace file: ${file.name}")
        }
    }

    /** Rounds a coordinate to 7 decimal places (~11 mm), the precision the spec recommends (§5). */
    fun coord(value: Double): Double = Math.round(value * 1e7) / 1e7
}

/**
 * The trace header (first line): the display [name] and creation time of the whole file.
 * [appVersion] is optional and aids later debugging.
 *
 * [version] is the schema version a parsed header declared; version 1 and version 2 files are both
 * accepted, and it is not checked on read. Whatever [toLine] writes is in the current layout, so it
 * always stamps [Trace.SCHEMA_VERSION] regardless of [version] - rewriting a version-1 file (rename,
 * continued recording) upgrades its header. A version-1 header's `type` field is ignored on read and
 * therefore dropped by any rewrite.
 */
data class TraceHeader(
    val name: String,
    val createdAtEpochMs: Long,
    val version: Int = Trace.SCHEMA_VERSION,
    val appVersion: String? = null,
) {
    fun toLine(): String =
        JSONObject()
            .apply {
                put("v", Trace.SCHEMA_VERSION)
                put("name", name)
                put("createdAt", createdAtEpochMs)
                put("app", Trace.APP_ID)
                appVersion?.let { put("appVersion", it) }
            }.toString()

    companion object {
        fun fromJson(json: JSONObject): TraceHeader =
            TraceHeader(
                name = json.getString("name"),
                createdAtEpochMs = json.getLong("createdAt"),
                version = json.optInt("v", Trace.SCHEMA_VERSION),
                appVersion = json.optString("appVersion").ifEmpty { null },
            )
    }
}
