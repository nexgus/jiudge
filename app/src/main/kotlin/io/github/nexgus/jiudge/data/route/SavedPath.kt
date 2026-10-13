package io.github.nexgus.jiudge.data.route

import org.json.JSONObject
import org.mapsforge.core.model.LatLong

/**
 * A named line on the map as it is stored in one path file: either a [PlannedRoute] or a
 * [RecordedTrack]. Both persist through the same JSONL trace layer ([Trace]) into the same folder
 * (see [PathStore]); this interface is what callers need when they do not care which of the two
 * they hold.
 */
sealed interface SavedPath {
    val name: String

    val createdAtEpochMs: Long

    val kind: PathKind

    /** The whole geometry flattened into one coordinate sequence, in travel order. */
    val polyline: List<LatLong>

    /** Total ground distance in metres, summed along [polyline]. */
    val distanceMeters: Double

    /**
     * The wall-clock time of each [polyline] point, index-aligned with it. Null for a
     * [PathKind.PLAN], whose points carry no time.
     */
    val pointTimesMs: List<Long>?

    /** The header line for this path's trace file. */
    fun header(): TraceHeader

    /** This path's record lines, in file order. */
    fun toRecords(): List<JSONObject>

    companion object {
        /**
         * Reconstructs the path a parsed trace holds, choosing the model by [PathKind.of]. Returns
         * null when the trace is not a valid path file (it has neither `seg` nor `pt` records).
         */
        fun fromTrace(parsed: Trace.Parsed): SavedPath? =
            when (PathKind.of(parsed.records)) {
                PathKind.PLAN -> PlannedRoute.fromTrace(parsed)
                PathKind.TRACK -> RecordedTrack.fromTrace(parsed)
                null -> null
            }
    }
}
