package io.github.nexgus.jiudge.core.stats

import io.github.nexgus.jiudge.core.elevation.SlopeScale
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.LatLongUtils
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Derives [TraceStats] from a raw polyline, shared by planned routes (no timestamps) and recorded
 * tracks (docs/stats.md). Pure Kotlin/JVM so it can run in a plain JUnit test without an Android
 * runtime; the DEM lookup is injected as a function so this module never depends on
 * [io.github.nexgus.jiudge.core.elevation.DemElevation] directly.
 *
 * The elevation-profile resampling mirrors
 * [io.github.nexgus.jiudge.feature.planning.PlannedRouteLayer]'s `sampleSlopes`/`interpolateAlong`
 * (same 30 m step, same hold-forward/hold-backward gap fill, same central-difference slope), so the
 * stats screen and the map route line always agree on where the DEM has data and how steep it looks.
 */
object TraceStatsCalculator {
    /** Minimum net elevation change (m) before it counts as ascent/descent - filters GPS/DEM noise. */
    const val HYSTERESIS_M = 10.0

    /** Minimum inter-point average speed (m/s) for a span to count as "moving" rather than "stopped". */
    const val MOVING_SPEED_MPS = 0.3

    /**
     * Computes all derived statistics for [points]. [timesMs] must be the same length as [points] to
     * be used (a mismatched length is treated as absent, i.e. no time stats); pass null for a planned
     * route, which has no timestamps. [elevationAt] is a DEM lookup (lat, lon) -> metres, or null when
     * no DEM is available at all.
     */
    fun compute(
        points: List<LatLong>,
        timesMs: List<Long>?,
        elevationAt: ((Double, Double) -> Float?)?,
    ): TraceStats {
        val cumulative = cumulativeMeters(points)
        val total = cumulative.lastOrNull() ?: 0.0
        val validTimes = if (timesMs != null && timesMs.size == points.size) timesMs else null
        val profile = buildProfile(points, cumulative, total, elevationAt, validTimes)
        val (ascent, descent) = ascentDescent(profile)
        val (minEle, maxEle) = minMaxElevation(profile)
        val time = computeTime(points, timesMs, total)
        return TraceStats(
            distanceM = total,
            ascentM = ascent,
            descentM = descent,
            minElevationM = minEle,
            maxElevationM = maxEle,
            profile = profile,
            time = time,
        )
    }

    /** Per-point cumulative ground distance in metres, summed via Vincenty's formula. */
    private fun cumulativeMeters(points: List<LatLong>): DoubleArray {
        if (points.size < 2) return DoubleArray(points.size)
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + LatLongUtils.vincentyDistance(points[i - 1], points[i])
        }
        return out
    }

    /**
     * Resamples the DEM elevation every [SlopeScale.SAMPLE_SPACING_M] along the track (plus the exact
     * end point when the total is not an integer multiple of the step), then derives a signed slope
     * (deg, + uphill) per sample via central difference over a gap-filled elevation sequence. Empty
     * when the track is too short/small or there is no DEM at all.
     */
    private fun buildProfile(
        points: List<LatLong>,
        cumulative: DoubleArray,
        total: Double,
        elevationAt: ((Double, Double) -> Float?)?,
        timesMs: List<Long>?,
    ): List<TraceStats.ProfileSample> {
        if (points.size < 2 || total < SlopeScale.SAMPLE_SPACING_M || elevationAt == null) return emptyList()

        val n = (total / SlopeScale.SAMPLE_SPACING_M).toInt()
        val hasExtraEnd = total > n * SlopeScale.SAMPLE_SPACING_M
        val sampleCount = n + 1 + if (hasExtraEnd) 1 else 0
        val distances = DoubleArray(sampleCount)
        val rawElevations = arrayOfNulls<Float>(sampleCount)
        val epochs = arrayOfNulls<Long>(sampleCount)

        for (k in 0..n) {
            val d = k * SlopeScale.SAMPLE_SPACING_M
            val point = interpolateAlong(points, cumulative, d)
            distances[k] = d
            rawElevations[k] = elevationAt(point.latitude, point.longitude)
            epochs[k] = timesMs?.let { interpolateEpoch(cumulative, it, d) }
        }
        if (hasExtraEnd) {
            val point = interpolateAlong(points, cumulative, total)
            distances[n + 1] = total
            rawElevations[n + 1] = elevationAt(point.latitude, point.longitude)
            epochs[n + 1] = timesMs?.let { interpolateEpoch(cumulative, it, total) }
        }

        // Hold gaps forward then backward, matching PlannedRouteLayer.sampleSlopes, so every sample
        // has a value to feed the central difference (the reported elevationM stays the raw/null value).
        val filled = rawElevations.copyOf()
        var hold: Float? = null
        for (k in filled.indices) {
            if (filled[k] == null) filled[k] = hold else hold = filled[k]
        }
        hold = null
        for (k in filled.indices.reversed()) {
            if (filled[k] == null) filled[k] = hold else hold = filled[k]
        }

        val allNull = filled.all { it == null }
        val slopes = FloatArray(sampleCount)
        if (!allNull) {
            for (k in 0 until sampleCount) {
                val lo = max(0, k - 1)
                val hi = min(sampleCount - 1, k + 1)
                val run = distances[hi] - distances[lo]
                val rise = (filled[hi]!! - filled[lo]!!).toDouble()
                slopes[k] = if (run > 0.0) Math.toDegrees(atan2(rise, run)).toFloat() else 0f
            }
        }

        return (0 until sampleCount).map { k ->
            TraceStats.ProfileSample(
                distanceM = distances[k],
                elevationM = rawElevations[k],
                slopeDeg = slopes[k],
                epochMs = epochs[k],
            )
        }
    }

    /** Point at cumulative ground distance [meters] along the track, linearly interpolated. */
    private fun interpolateAlong(
        points: List<LatLong>,
        cumulative: DoubleArray,
        meters: Double,
    ): LatLong {
        if (meters <= 0.0) return points.first()
        if (meters >= cumulative.last()) return points.last()
        var i = 1
        while (i < cumulative.size && cumulative[i] < meters) i++
        val segStart = cumulative[i - 1]
        val segLen = cumulative[i] - segStart
        val t = if (segLen > 0.0) (meters - segStart) / segLen else 0.0
        val a = points[i - 1]
        val b = points[i]
        return LatLong(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }

    /**
     * Wall-clock time at cumulative ground distance [meters] along the track, linearly interpolated
     * against [timesMs] using the same segment lookup as [interpolateAlong] (find the segment where
     * `cumulative[i-1] <= meters <= cumulative[i]`, interpolate the two timestamps by the same
     * fraction). [timesMs] must already be validated as same-length as the source points.
     */
    private fun interpolateEpoch(
        cumulative: DoubleArray,
        timesMs: List<Long>,
        meters: Double,
    ): Long {
        if (meters <= 0.0) return timesMs.first()
        if (meters >= cumulative.last()) return timesMs.last()
        var i = 1
        while (i < cumulative.size && cumulative[i] < meters) i++
        val segStart = cumulative[i - 1]
        val segLen = cumulative[i] - segStart
        val t = if (segLen > 0.0) (meters - segStart) / segLen else 0.0
        val a = timesMs[i - 1]
        val b = timesMs[i]
        return a + ((b - a) * t).toLong()
    }

    /**
     * Cumulative ascent/descent over the profile's valid (non-null) elevations, with a hysteresis
     * threshold so small oscillations (GPS/DEM noise) below [HYSTERESIS_M] do not accumulate. Both are
     * null when fewer than 2 samples have a valid elevation.
     */
    private fun ascentDescent(profile: List<TraceStats.ProfileSample>): Pair<Double?, Double?> {
        val valid = profile.mapNotNull { it.elevationM }
        if (valid.size < 2) return null to null
        var ascent = 0.0
        var descent = 0.0
        var ref = valid.first().toDouble()
        for (i in 1 until valid.size) {
            val e = valid[i].toDouble()
            val d = e - ref
            when {
                d >= HYSTERESIS_M -> {
                    ascent += d
                    ref = e
                }
                d <= -HYSTERESIS_M -> {
                    descent += -d
                    ref = e
                }
            }
        }
        return ascent to descent
    }

    /** Min/max of the profile's valid (non-null) elevations, or null/null when none are valid. */
    private fun minMaxElevation(profile: List<TraceStats.ProfileSample>): Pair<Float?, Float?> {
        val valid = profile.mapNotNull { it.elevationM }
        if (valid.isEmpty()) return null to null
        return valid.min() to valid.max()
    }

    /**
     * Time-derived stats from [timesMs] (ignored, i.e. null result, when absent, mismatched in length,
     * or there are fewer than 2 points). Per-span speed gates whether that span's duration counts as
     * "moving" (docs/stats.md); spans with non-positive duration are skipped entirely (no distance
     * "for free" from a duplicate or out-of-order timestamp).
     */
    private fun computeTime(
        points: List<LatLong>,
        timesMs: List<Long>?,
        total: Double,
    ): TraceStats.TimeStats? {
        if (timesMs == null || timesMs.size != points.size || points.size < 2) return null

        val start = timesMs.first()
        val end = timesMs.last()
        val totalMs = max(0L, end - start)

        var movingMs = 0L
        var maxSpeed: Double? = null
        for (i in 1 until points.size) {
            val dtMs = timesMs[i] - timesMs[i - 1]
            if (dtMs <= 0) continue
            val segDistance = LatLongUtils.vincentyDistance(points[i - 1], points[i])
            val segSpeed = segDistance / (dtMs / 1000.0)
            if (segSpeed >= MOVING_SPEED_MPS) movingMs += dtMs
            maxSpeed = if (maxSpeed == null) segSpeed else max(maxSpeed, segSpeed)
        }
        val stoppedMs = max(0L, totalMs - movingMs)
        val avgSpeed = if (totalMs > 0) total / (totalMs / 1000.0) else 0.0
        val movingAvgSpeed = if (movingMs > 0) total / (movingMs / 1000.0) else null

        return TraceStats.TimeStats(
            startEpochMs = start,
            endEpochMs = end,
            totalMs = totalMs,
            movingMs = movingMs,
            stoppedMs = stoppedMs,
            avgSpeedMps = avgSpeed,
            movingAvgSpeedMps = movingAvgSpeed,
            maxSpeedMps = maxSpeed,
        )
    }
}
