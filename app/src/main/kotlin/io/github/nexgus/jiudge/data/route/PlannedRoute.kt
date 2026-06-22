package io.github.nexgus.jiudge.data.route

import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.core.model.LatLong

/**
 * A saved route plan: the user-placed waypoints plus the routed geometry BRouter produced for each
 * gap between consecutive waypoints. Persisted as one JSON file per route (see [RouteStore]) -
 * filesystem storage fits this personal, file-sharing-oriented app better than a database
 * (see CLAUDE.md).
 *
 * [segments] is the source of truth (segments[i] is the path from waypoints[i] to waypoints[i+1]),
 * which keeps a loaded route fully editable - "-" can drop one segment at a time. The flattened
 * track is derived on demand via [polyline]. Coordinates serialize as `[lat, lng]` pairs.
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

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("createdAtEpochMs", createdAtEpochMs)
            put("waypoints", waypoints.toPointArray())
            put("segments", JSONArray().also { arr -> segments.forEach { arr.put(it.toPointArray()) } })
        }

    companion object {
        const val FILE_SUFFIX = ".json"

        fun fromJson(json: JSONObject): PlannedRoute =
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
                forEach { p -> arr.put(JSONArray().put(p.latitude).put(p.longitude)) }
            }

        private fun JSONArray.toLatLongs(): List<LatLong> =
            (0 until length()).map { i ->
                val pair = getJSONArray(i)
                LatLong(pair.getDouble(0), pair.getDouble(1))
            }
    }
}
