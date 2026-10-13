package io.github.nexgus.jiudge.data.route

import org.json.JSONObject

/**
 * Which of the two things a path file holds. The kind is never stored: it is derived from the
 * file's records every time, so a file cannot disagree with its own contents.
 */
enum class PathKind {
    /** Waypoints joined by routed or imported segments (`wpt` + `seg` records); carries no time. */
    PLAN,

    /** A series of timestamped fixes (`pt` records), recorded or imported. */
    TRACK,
    ;

    companion object {
        /**
         * Derives the kind from a trace's [records]: at least one `seg` record makes a [PLAN];
         * otherwise at least one `pt` record makes a [TRACK]; a trace with neither is not a valid
         * path file and yields null.
         */
        fun of(records: List<JSONObject>): PathKind? =
            when {
                records.any { it.optString("k") == "seg" } -> PLAN
                records.any { it.optString("k") == "pt" } -> TRACK
                else -> null
            }
    }
}
