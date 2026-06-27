package io.github.nexgus.jiudge.data.route

import org.json.JSONObject
import java.io.File

/**
 * Low-level reader/writer for the JSONL "Trace" format (docs/trace_spec.md). A trace file is one
 * header line describing the whole file, followed by one self-contained JSON object per record,
 * each keyed by `k`. Planned routes (`type:"plan"`) and recorded tracks (`type:"track"`, not yet
 * built) share this line layer; only the header `type` and the record kinds differ.
 *
 * The format is line-oriented so a track can be appended a point at a time and a torn final line
 * (crash / power loss mid-write) can be skipped without losing the rest (spec §3, §9). Domain models
 * such as [PlannedRoute] build their typed records on top of this layer.
 */
object Trace {
    /** Trace schema version; bumped only on a structurally incompatible change (spec §10). */
    const val SCHEMA_VERSION = 1

    /** Application identifier written into every header's `app` field. */
    const val APP_ID = "jiudge"

    /** File extension for trace files (spec §12). */
    const val FILE_SUFFIX = ".jsonl"

    const val TYPE_PLAN = "plan"
    const val TYPE_TRACK = "track"

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
     * exists and is the route's only copy (e.g. [RouteStore.rename] hitting the same slug + createdAt
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
 * The trace header (first line). [type] tells planned routes apart from recorded tracks while both
 * reuse the same record layer (spec §4). [appVersion] is optional and aids later debugging.
 */
data class TraceHeader(
    val type: String,
    val name: String,
    val createdAtEpochMs: Long,
    val version: Int = Trace.SCHEMA_VERSION,
    val appVersion: String? = null,
) {
    fun toLine(): String =
        JSONObject()
            .apply {
                put("v", version)
                put("type", type)
                put("name", name)
                put("createdAt", createdAtEpochMs)
                put("app", Trace.APP_ID)
                appVersion?.let { put("appVersion", it) }
            }.toString()

    companion object {
        fun fromJson(json: JSONObject): TraceHeader =
            TraceHeader(
                type = json.getString("type"),
                name = json.getString("name"),
                createdAtEpochMs = json.getLong("createdAt"),
                version = json.optInt("v", Trace.SCHEMA_VERSION),
                appVersion = json.optString("appVersion").ifEmpty { null },
            )
    }
}
