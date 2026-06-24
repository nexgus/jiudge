package io.github.nexgus.jiudge.data.route

import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.core.model.LatLong

/**
 * A saved route plan: the user-placed waypoints plus the routed geometry BRouter produced for each
 * gap between consecutive waypoints. Persisted as one JSONL trace file per route (see [RouteStore]
 * and [Trace]) - filesystem storage fits this personal, file-sharing-oriented app better than a
 * database (see CLAUDE.md).
 *
 * [segments] is the source of truth (segments[i] is the path from waypoints[i] to waypoints[i+1]),
 * which keeps a loaded route fully editable - "-" can drop one segment at a time. The flattened
 * track is derived on demand via [polyline]. In the trace file each waypoint becomes a `wpt` record
 * and each segment a `seg` record, ordered by their index `i` (spec §5.1, §5.2).
 */
data class PlannedRoute(
    val name: String,
    val createdAtEpochMs: Long,
    val waypoints: List<LatLong>,
    val segments: List<List<LatLong>>,
) {
    /** The full routed track: segment geometries joined, dropping the duplicated junction points. */
    val polyline: List<LatLong>
        get() {
            val out = mutableListOf<LatLong>()
            for (seg in segments) {
                if (out.isNotEmpty() && seg.isNotEmpty() && out.last() == seg.first()) {
                    out.addAll(seg.drop(1))
                } else {
                    out.addAll(seg)
                }
            }
            return out
        }

    /** The header line for this plan's trace file (spec §4). */
    fun header(): TraceHeader = TraceHeader(type = Trace.TYPE_PLAN, name = name, createdAtEpochMs = createdAtEpochMs)

    /** This plan's record lines: all `wpt` records, then all `seg` records, each ordered by `i`. */
    fun toRecords(): List<JSONObject> {
        val records = mutableListOf<JSONObject>()
        waypoints.forEachIndexed { i, point ->
            records +=
                JSONObject().apply {
                    put("k", "wpt")
                    put("i", i)
                    put("lat", Trace.coord(point.latitude))
                    put("lon", Trace.coord(point.longitude))
                }
        }
        segments.forEachIndexed { i, segment ->
            records +=
                JSONObject().apply {
                    put("k", "seg")
                    put("i", i)
                    put("pts", segment.toPointArray())
                }
        }
        return records
    }

    companion object {
        /** Reconstructs a plan from a parsed trace, ordering waypoints and segments by their `i`. */
        fun fromTrace(parsed: Trace.Parsed): PlannedRoute =
            PlannedRoute(
                name = parsed.header.name,
                createdAtEpochMs = parsed.header.createdAtEpochMs,
                waypoints =
                    parsed.records
                        .filter { it.optString("k") == "wpt" }
                        .sortedBy { it.getInt("i") }
                        .map { LatLong(it.getDouble("lat"), it.getDouble("lon")) },
                segments =
                    parsed.records
                        .filter { it.optString("k") == "seg" }
                        .sortedBy { it.getInt("i") }
                        .map { it.getJSONArray("pts").toLatLongs() },
            )

        /**
         * Parses a legacy single-object plan (`{name, createdAtEpochMs, waypoints, segments}`) -
         * the pre-trace `.json` format. Used only by [TraceMigration] to upgrade old files (spec §13).
         */
        fun fromLegacyJson(json: JSONObject): PlannedRoute =
            PlannedRoute(
                name = json.getString("name"),
                createdAtEpochMs = json.getLong("createdAtEpochMs"),
                waypoints = json.getJSONArray("waypoints").toLatLongs(),
                segments =
                    json.optJSONArray("segments")?.let { segs ->
                        (0 until segs.length()).map { segs.getJSONArray(it).toLatLongs() }
                    } ?: emptyList(),
            )

        private fun List<LatLong>.toPointArray(): JSONArray =
            JSONArray().also { arr ->
                forEach { p -> arr.put(JSONArray().put(Trace.coord(p.latitude)).put(Trace.coord(p.longitude))) }
            }

        private fun JSONArray.toLatLongs(): List<LatLong> =
            (0 until length()).map { i ->
                val pair = getJSONArray(i)
                LatLong(pair.getDouble(0), pair.getDouble(1))
            }
    }
}
