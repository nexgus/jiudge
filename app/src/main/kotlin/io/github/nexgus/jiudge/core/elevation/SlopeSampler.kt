package io.github.nexgus.jiudge.core.elevation

import org.mapsforge.core.model.LatLong
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Samples elevation every [SlopeScale.SAMPLE_SPACING_M] along a polyline and derives the signed
 * slope (degrees, + uphill) at each sample by central difference. This is the slope source of the
 * map's slope-coloured path lines. Pure Kotlin/JVM: the elevation lookup is injected as a function
 * (lat, lon) -> metres, or null where there is no data, so the sampler never touches the DEM reader
 * or any Android API and runs in a plain JUnit test.
 *
 * A path can be sampled whole ([sample]) or continued from an earlier result after points were
 * appended ([extend]). Continuing looks up elevation only at the sample positions the appended points
 * add; the two ways give the same [Profile] for the same point sequence.
 *
 * Rules: sample `k` sits `k * SAMPLE_SPACING_M` metres along the path. A sample with no elevation is
 * held from the nearest valid sample (forward first, then backward for a leading gap), so one void
 * does not blank the path. A path shorter than one spacing, or one with no valid elevation at all,
 * has no slope data.
 *
 * The sampler holds no state between calls, so one instance may be used from several threads at
 * once provided [elevationAt] itself allows that.
 */
class SlopeSampler(
    private val elevationAt: (latitude: Double, longitude: Double) -> Float?,
) {
    /**
     * The sampled slopes of the first [pointCount] points of a path, [totalMeters] long. Immutable,
     * so it can be handed between threads freely.
     */
    class Profile internal constructor(
        /** How many leading points of the path this profile covers. */
        val pointCount: Int,
        /** Ground distance covered, in metres. */
        val totalMeters: Double,
        // Raw elevation per sample (NaN where the lookup had no data). Kept so a later extension
        // only has to look up the samples that are new.
        internal val elevations: FloatArray,
        private val slopes: FloatArray?,
    ) {
        /** False when the covered stretch is too short to sample or has no valid elevation at all. */
        val hasData: Boolean get() = slopes != null

        /** Number of slope samples; 0 when there is no slope data. */
        val sampleCount: Int get() = slopes?.size ?: 0

        /**
         * Signed slope (degrees, + uphill) at ground distance [meters] along the path, read from the
         * nearest sample. Null when there is no slope data.
         */
        fun slopeDegAt(meters: Double): Float? {
            val values = slopes ?: return null
            val index = (meters / SlopeScale.SAMPLE_SPACING_M).roundToInt().coerceIn(0, values.lastIndex)
            return values[index]
        }

        internal companion object {
            val EMPTY = Profile(pointCount = 0, totalMeters = 0.0, elevations = FloatArray(0), slopes = null)
        }
    }

    /**
     * Samples the whole path. [cumulativeMeters] is the ground distance from the first point to each
     * point (same size as [points]). [isActive] is polled between elevation lookups; once it returns
     * false the sampling is abandoned and null is returned.
     */
    fun sample(
        points: List<LatLong>,
        cumulativeMeters: DoubleArray,
        isActive: () -> Boolean = { true },
    ): Profile? = build(Profile.EMPTY, points, cumulativeMeters, isActive)

    /**
     * Continues [previous] over a path that has grown: [points] must begin with the very points
     * [previous] was sampled from, in the same order, and [cumulativeMeters] must be their distances
     * computed the same way. Elevation is looked up only for the samples beyond [previous]. If
     * [previous] evidently does not belong to a prefix of [points] (it covers more points, or the
     * distance at its last point differs) the whole path is sampled afresh instead. Returns null when
     * abandoned through [isActive].
     */
    fun extend(
        previous: Profile,
        points: List<LatLong>,
        cumulativeMeters: DoubleArray,
        isActive: () -> Boolean = { true },
    ): Profile? {
        val continues =
            previous.pointCount <= points.size &&
                (previous.pointCount == 0 || cumulativeMeters[previous.pointCount - 1] == previous.totalMeters)
        return build(if (continues) previous else Profile.EMPTY, points, cumulativeMeters, isActive)
    }

    private fun build(
        base: Profile,
        points: List<LatLong>,
        cumulativeMeters: DoubleArray,
        isActive: () -> Boolean,
    ): Profile? {
        require(points.size == cumulativeMeters.size) { "points and cumulativeMeters differ in size" }
        if (points.isEmpty()) return Profile.EMPTY
        if (base.pointCount == points.size) return base
        val total = cumulativeMeters[cumulativeMeters.lastIndex]
        val lastSample = lastSampleIndex(total)
        val elevations = base.elevations.copyOf(lastSample + 1)
        // Every new sample lies beyond the distance the base covers, so the walk can start at the
        // first appended point instead of rescanning the path from its start.
        var segmentEnd = max(1, base.pointCount)
        for (k in base.elevations.size..lastSample) {
            if (!isActive()) return null
            val meters = k * SlopeScale.SAMPLE_SPACING_M
            while (segmentEnd < points.lastIndex && cumulativeMeters[segmentEnd] < meters) segmentEnd++
            val point = pointAlong(points, cumulativeMeters, segmentEnd, meters)
            elevations[k] = elevationAt(point.latitude, point.longitude) ?: Float.NaN
        }
        return Profile(points.size, total, elevations, deriveSlopes(points.size, total, elevations))
    }

    /**
     * Index of the last sample that fits within [total] metres. Decided by `k * spacing <= total`
     * rather than by dividing, so whether sample `k` exists never depends on rounding - and therefore
     * never changes when the path later grows.
     */
    private fun lastSampleIndex(total: Double): Int {
        var index = (total / SlopeScale.SAMPLE_SPACING_M).toInt()
        if (index > 0 && index * SlopeScale.SAMPLE_SPACING_M > total) index--
        return index
    }

    /** Point [meters] along the path, on the segment ending at index [segmentEnd] (or the first point). */
    private fun pointAlong(
        points: List<LatLong>,
        cumulativeMeters: DoubleArray,
        segmentEnd: Int,
        meters: Double,
    ): LatLong {
        if (meters <= 0.0 || points.size < 2) return points.first()
        val a = points[segmentEnd - 1]
        val b = points[segmentEnd]
        val segmentStart = cumulativeMeters[segmentEnd - 1]
        val segmentLength = cumulativeMeters[segmentEnd] - segmentStart
        // Land exactly on a vertex when the distance does, instead of on its rounded interpolation.
        if (meters >= cumulativeMeters[segmentEnd]) return b
        if (meters <= segmentStart || segmentLength <= 0.0) return a
        val t = (meters - segmentStart) / segmentLength
        return LatLong(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }

    /** Gap-fills [elevations] and takes the central-difference slope per sample; null when there is no slope data. */
    private fun deriveSlopes(
        pointCount: Int,
        total: Double,
        elevations: FloatArray,
    ): FloatArray? {
        if (pointCount < 2 || total < SlopeScale.SAMPLE_SPACING_M) return null
        val last = elevations.lastIndex
        val filled = elevations.copyOf()
        // Hold gaps forward, then backward for a gap at the very start, so every sample has a value.
        var hold = Float.NaN
        for (k in 0..last) {
            if (filled[k].isNaN()) filled[k] = hold else hold = filled[k]
        }
        if (hold.isNaN()) return null
        for (k in last downTo 0) {
            if (filled[k].isNaN()) filled[k] = hold else hold = filled[k]
        }
        val slopes = FloatArray(last + 1)
        for (k in 0..last) {
            val lo = max(0, k - 1)
            val hi = min(last, k + 1)
            val run = (hi - lo) * SlopeScale.SAMPLE_SPACING_M
            val rise = (filled[hi] - filled[lo]).toDouble()
            slopes[k] = if (run > 0.0) Math.toDegrees(atan2(rise, run)).toFloat() else 0f
        }
        return slopes
    }
}
