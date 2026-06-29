package io.github.nexgus.jiudge.data.route

import org.json.JSONObject
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.LatLongUtils

/**
 * A recorded GPS track: an append-only series of fixes captured while the user moves. Persisted as one
 * JSONL trace file per track (see [TrackStore] and [Trace]) - the line-oriented layer suits append-as-
 * you-go writes (spec §3) and survives a torn final line on crash without losing the prior points.
 *
 * Each fix becomes a `pt` record carrying the position and the wall-clock time of the fix; the
 * timestamp records when the point was observed, independent of any per-record write order.
 *
 * Compared with [PlannedRoute] there are no waypoints or segments - a recorded track is just the path
 * actually walked, ordered by `t` ascending.
 */
data class RecordedTrack(
    val name: String,
    val createdAtEpochMs: Long,
    val points: List<Point>,
) {
    /** A single recorded GPS sample. */
    data class Point(
        val latitude: Double,
        val longitude: Double,
        val timeMs: Long,
    )

    /** The geometry as a [LatLong] list, for layer rendering and bounds calculations. */
    val polyline: List<LatLong>
        get() = points.map { LatLong(it.latitude, it.longitude) }

    /** Total ground distance in metres, summed along consecutive points. */
    val distanceMeters: Double
        get() = polyline.zipWithNext { a, b -> LatLongUtils.vincentyDistance(a, b) }.sum()

    /** The header line for this track's trace file (spec §4). */
    fun header(): TraceHeader = TraceHeader(type = Trace.TYPE_TRACK, name = name, createdAtEpochMs = createdAtEpochMs)

    /** This track's record lines: all `pt` records, ordered by their `t`. */
    fun toRecords(): List<JSONObject> =
        points.map { p ->
            JSONObject().apply {
                put("k", "pt")
                put("lat", Trace.coord(p.latitude))
                put("lon", Trace.coord(p.longitude))
                put("t", p.timeMs)
            }
        }

    companion object {
        /** Reconstructs a track from a parsed trace, ordering points by their `t`. */
        fun fromTrace(parsed: Trace.Parsed): RecordedTrack =
            RecordedTrack(
                name = parsed.header.name,
                createdAtEpochMs = parsed.header.createdAtEpochMs,
                points =
                    parsed.records
                        .filter { it.optString("k") == "pt" }
                        .map {
                            Point(
                                latitude = it.getDouble("lat"),
                                longitude = it.getDouble("lon"),
                                timeMs = it.getLong("t"),
                            )
                        }.sortedBy { it.timeMs },
            )

        /** Serialises a single fix as one `pt` record line (no trailing newline). */
        fun pointRecord(
            latitude: Double,
            longitude: Double,
            timeMs: Long,
        ): String =
            JSONObject()
                .apply {
                    put("k", "pt")
                    put("lat", Trace.coord(latitude))
                    put("lon", Trace.coord(longitude))
                    put("t", timeMs)
                }.toString()
    }
}
