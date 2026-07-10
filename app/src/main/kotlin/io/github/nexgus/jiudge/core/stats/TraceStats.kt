package io.github.nexgus.jiudge.core.stats

/**
 * Derived statistics for a planned route or a recorded track (docs/stats.md). Elevation-derived
 * fields follow trace_spec.md §8: they come from DEM lookups at use time, never from stored values,
 * so they are null whenever the DEM is absent or has no data along the trace.
 *
 * Produced by [TraceStatsCalculator]; consumed by the stats screen (feature/stats).
 */
data class TraceStats(
    /** Total ground distance in metres, summed along consecutive points. */
    val distanceM: Double,
    /** Cumulative ascent in metres (DEM-resampled with hysteresis - docs/stats.md), or null without DEM data. */
    val ascentM: Double?,
    /** Cumulative descent in metres (positive value), or null without DEM data. */
    val descentM: Double?,
    /**
     * Total along-track distance in metres over hysteresis-confirmed ascending stretches, or null
     * without DEM data. Uses the same hysteresis pass as [ascentM], so gentle stretches that never
     * reach the threshold belong to neither side: ascent + descent distance < [distanceM] in general.
     */
    val ascentDistanceM: Double?,
    /** Total along-track distance in metres over hysteresis-confirmed descending stretches; see [ascentDistanceM]. */
    val descentDistanceM: Double?,
    /** Lowest DEM elevation along the trace in metres, or null without DEM data. */
    val minElevationM: Float?,
    /** Highest DEM elevation along the trace in metres, or null without DEM data. */
    val maxElevationM: Float?,
    /**
     * Along-track elevation profile, resampled every [io.github.nexgus.jiudge.core.elevation.SlopeScale.SAMPLE_SPACING_M]
     * metres (plus the exact end point). Empty when the trace is shorter than one sample step or has
     * fewer than 2 points. Individual samples may carry a null elevation where the DEM has no data.
     */
    val profile: List<ProfileSample>,
    /** Time-derived statistics; null for planned routes (no timestamps). */
    val time: TimeStats?,
) {
    /** One sample of the along-track elevation profile. */
    data class ProfileSample(
        /** Cumulative ground distance from the start, metres. */
        val distanceM: Double,
        /** DEM elevation at this sample, metres; null where the DEM has no data. */
        val elevationM: Float?,
        /** Signed slope (deg, + uphill) around this sample, 0 where elevation is unavailable. */
        val slopeDeg: Float,
        /**
         * Wall-clock time at this sample, linearly interpolated from the source points' timestamps
         * against cumulative distance (same segment lookup as the geometry interpolation). Null for
         * planned routes (no timestamps) or whenever the source timestamps are absent/mismatched in
         * length - same validity condition as [TraceStats.TimeStats].
         */
        val epochMs: Long?,
    )

    /** Time-derived statistics for a recorded track. Speeds are in metres per second. */
    data class TimeStats(
        val startEpochMs: Long,
        val endEpochMs: Long,
        /** Wall-clock span from first to last point. */
        val totalMs: Long,
        /** Sum of inter-point spans whose average speed reaches the moving threshold (docs/stats.md). */
        val movingMs: Long,
        /** totalMs - movingMs. */
        val stoppedMs: Long,
        /** distance / total time; 0 when totalMs is 0. */
        val avgSpeedMps: Double,
        /** distance / moving time; null when movingMs is 0. */
        val movingAvgSpeedMps: Double?,
        /** Highest average speed over a single inter-point span; null when no span has positive duration. */
        val maxSpeedMps: Double?,
    )
}
