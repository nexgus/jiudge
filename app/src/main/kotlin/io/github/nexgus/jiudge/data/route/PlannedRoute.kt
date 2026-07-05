package io.github.nexgus.jiudge.data.route

import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.LatLongUtils

/**
 * A saved route plan: the user-placed waypoints plus the geometry between each pair of consecutive
 * waypoints - routed by BRouter, or carried in verbatim from an imported GPX file. Persisted as one
 * JSONL trace file per route (see [RouteStore] and [Trace]) - filesystem storage fits this personal,
 * file-sharing-oriented app better than a database (see CLAUDE.md).
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
    val segments: List<Segment>,
) {
    /**
     * One leg of the plan. [imported] marks geometry carried in from an external GPX file rather
     * than routed by BRouter: the editor refuses to "-"-delete such a leg (it cannot be recomputed),
     * and the flag round-trips through the trace file as the `seg` record's optional `origin` field.
     */
    data class Segment(
        val points: List<LatLong>,
        val imported: Boolean = false,
    )

    /**
     * The full routed track: segment geometries joined, dropping the duplicated junction points.
     * Lazily memoised - imported GPX routes carry thousands of points, and a single save-and-show
     * flow reads this several times (persisting, fitting the viewport, measuring). Safe because the
     * route is immutable; a `copy()` recomputes on its own instance.
     */
    val polyline: List<LatLong> by lazy { joinRouteSegments(segments.map { it.points }) }

    /** Total routed length in metres: great-circle distances summed along [polyline]. */
    val distanceMeters: Double by lazy { polyline.zipWithNext { a, b -> LatLongUtils.vincentyDistance(a, b) }.sum() }

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
                    put("pts", segment.points.toPointArray())
                    if (segment.imported) put("origin", ORIGIN_IMPORT)
                }
        }
        return records
    }

    companion object {
        /** `seg.origin` value marking geometry imported from an external GPX file (spec §5.2). */
        const val ORIGIN_IMPORT = "import"

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
                        .map {
                            Segment(
                                points = it.getJSONArray("pts").toLatLongs(),
                                // Absent origin = BRouter-routed. Any non-empty value (known or not)
                                // is treated as imported: erring towards protecting the geometry from
                                // "-"-deletion beats mistaking a foreign leg for a recomputable one.
                                imported = it.optString("origin").isNotEmpty(),
                            )
                        },
            )

        /**
         * Builds a plan around geometry imported from an external GPX file: the waypoints are the
         * segment boundary points (first segment's start, each segment's end) and every segment is
         * flagged [Segment.imported]. [segments] must contain no empty segment (the GPX parser drops
         * those).
         */
        fun fromImportedSegments(
            name: String,
            createdAtEpochMs: Long,
            segments: List<List<LatLong>>,
        ): PlannedRoute =
            PlannedRoute(
                name = name,
                createdAtEpochMs = createdAtEpochMs,
                waypoints =
                    if (segments.isEmpty()) {
                        emptyList()
                    } else {
                        listOf(segments.first().first()) + segments.map { it.last() }
                    },
                segments = segments.map { Segment(points = it, imported = true) },
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

/**
 * Joins routed segment geometries into one continuous track, dropping the duplicated junction point
 * where one segment ends and the next begins. Shared by [PlannedRoute.polyline] and the overlay
 * renderer so both derive the same track from a list of segments.
 */
internal fun joinRouteSegments(segments: List<List<LatLong>>): List<LatLong> {
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
