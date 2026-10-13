package io.github.nexgus.jiudge.data.route

import io.github.nexgus.jiudge.core.geo.simplifyPolyline

/**
 * Douglas-Peucker tolerance applied to geometry that becomes an imported segment of a planned
 * route: every segment of an imported GPX file, and a track copied as a plan. 3 m is visually
 * lossless at the deepest zoom yet cuts a 1 Hz-recorded track's point count by an order of
 * magnitude, keeping the saved file small and the per-frame overlay drawing fast.
 */
const val PATH_SIMPLIFY_TOLERANCE_M = 3.0

/**
 * Builds the planned route this track is copied as: all of its fixes, simplified with
 * [PATH_SIMPLIFY_TOLERANCE_M], become one imported segment running between two waypoints - the
 * track's first and last fix - which is the same shape a single-segment GPX file is imported as.
 * The timestamps are dropped, since a plan carries no time. The track itself is not changed.
 *
 * [name] and [createdAtEpochMs] are the copy's own; a track with no fix yields a plan with no
 * segment, which [PathStore] refuses to save.
 */
fun RecordedTrack.toPlannedRoute(
    name: String,
    createdAtEpochMs: Long,
): PlannedRoute {
    val line = simplifyPolyline(polyline, PATH_SIMPLIFY_TOLERANCE_M)
    return PlannedRoute.fromImportedSegments(
        name = name,
        createdAtEpochMs = createdAtEpochMs,
        segments = if (line.isEmpty()) emptyList() else listOf(line),
    )
}
