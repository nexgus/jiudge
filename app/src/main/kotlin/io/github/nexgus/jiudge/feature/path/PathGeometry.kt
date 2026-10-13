package io.github.nexgus.jiudge.feature.path

import org.mapsforge.core.model.LatLong
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Everything a path overlay derives from a coordinate sequence that needs no drawing API: the
 * cumulative ground distance at each point, each point's position on the unit Web-Mercator square
 * (so a frame only has to scale and shift it), the kilometre-marker positions, and the pure helpers
 * that decide which line segments are stroked together ([forEachLineRun]) or skipped as off-screen
 * ([isSegmentOffscreen]). Kept free of mapsforge's graphics types so it runs in a plain JUnit test.
 *
 * An instance is immutable and is read by the render thread while the main thread builds the next
 * one, so [points] must not be mutated after being handed over.
 */
internal class PathGeometry private constructor(
    val points: List<LatLong>,
    /** Ground distance (m) from the first point to each point; same size as [points]. */
    val cumulativeMeters: DoubleArray,
    /** Each point's x on the unit Web-Mercator square (0 = 180 W, 1 = 180 E). */
    val unitX: DoubleArray,
    /** Each point's y on the unit Web-Mercator square (0 = north edge, 1 = south edge). */
    val unitY: DoubleArray,
) {
    /** One whole-kilometre mark along the path. */
    data class KmMarker(
        val point: LatLong,
        val km: Int,
        // Two points straddling [point] along the path, giving the local direction of travel so a
        // label can be pushed off to the side of the line rather than on top of it.
        val tangentBack: LatLong,
        val tangentFwd: LatLong,
    )

    val size: Int get() = points.size

    /** Total ground distance in metres. */
    val totalMeters: Double get() = if (cumulativeMeters.isEmpty()) 0.0 else cumulativeMeters[cumulativeMeters.lastIndex]

    /** True when [other] starts with exactly this geometry's points, i.e. it only appends to them. */
    fun isPrefixOf(other: List<LatLong>): Boolean {
        if (other === points) return true
        if (size > other.size) return false
        for (i in 0 until size) {
            val mine = points[i]
            val theirs = other[i]
            if (mine !== theirs && mine != theirs) return false
        }
        return true
    }

    /**
     * The geometry of [longer], which must start with this geometry's points ([isPrefixOf]). Only
     * the appended points are measured and projected; the result equals `PathGeometry.of(longer)`.
     */
    fun extendedTo(longer: List<LatLong>): PathGeometry {
        if (longer.size == size) return this
        val cumulative = cumulativeMeters.copyOf(longer.size)
        val xs = unitX.copyOf(longer.size)
        val ys = unitY.copyOf(longer.size)
        for (i in size until longer.size) {
            val point = longer[i]
            cumulative[i] = if (i == 0) 0.0 else cumulative[i - 1] + distanceMeters(longer[i - 1], point)
            xs[i] = unitMercatorX(point.longitude)
            ys[i] = unitMercatorY(point.latitude)
        }
        return PathGeometry(longer, cumulative, xs, ys)
    }

    /** Point at ground distance [meters] along the path, clamped to its two ends. The path must not be empty. */
    fun pointAt(meters: Double): LatLong {
        if (meters <= 0.0 || size < 2) return points.first()
        if (meters >= totalMeters) return points.last()
        // Binary search for the first point whose cumulative distance reaches `meters`.
        var lo = 1
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulativeMeters[mid] < meters) lo = mid + 1 else hi = mid
        }
        val segmentStart = cumulativeMeters[lo - 1]
        val segmentLength = cumulativeMeters[lo] - segmentStart
        val t = if (segmentLength > 0.0) (meters - segmentStart) / segmentLength else 0.0
        val a = points[lo - 1]
        val b = points[lo]
        return LatLong(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }

    /** One marker at each whole kilometre along the path; empty when the path is shorter than 1 km. */
    fun kmMarkers(): List<KmMarker> {
        val total = totalMeters
        if (size < 2 || total < METERS_PER_KM) return emptyList()
        val markers = ArrayList<KmMarker>((total / METERS_PER_KM).toInt())
        var km = 1
        while (km * METERS_PER_KM <= total) {
            val meters = km * METERS_PER_KM
            markers.add(
                KmMarker(
                    point = pointAt(meters),
                    km = km,
                    tangentBack = pointAt(max(0.0, meters - TANGENT_EPS_M)),
                    tangentFwd = pointAt(min(total, meters + TANGENT_EPS_M)),
                ),
            )
            km++
        }
        return markers
    }

    /**
     * A point shortly before the path's end, giving the direction of travel into the last point so
     * the total-distance label can be pushed off to the side of the line. The path must not be empty.
     */
    fun endTangentBack(): LatLong = pointAt(max(0.0, totalMeters - TANGENT_EPS_M))

    companion object {
        /** The geometry of no points at all. */
        val EMPTY = PathGeometry(emptyList(), DoubleArray(0), DoubleArray(0), DoubleArray(0))

        // Half-span (m) each side of a marker used to read the local direction of travel; long
        // enough to ignore per-vertex jitter, short enough to track real bends.
        const val TANGENT_EPS_M = 20.0

        // A path whose two ends are closer than this is drawn as a loop (one merged start/end dot).
        const val LOOP_JOIN_METERS = 30.0

        private const val METERS_PER_KM = 1000.0
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Measures and projects [points] from scratch. */
        fun of(points: List<LatLong>): PathGeometry = EMPTY.extendedTo(points)

        /** Haversine great-circle distance in metres - good enough for distance marks and loop detection. */
        fun distanceMeters(
            a: LatLong,
            b: LatLong,
        ): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
        }

        /** Whether a path running from [start] to [end] closes on itself (see [LOOP_JOIN_METERS]). */
        fun isLoop(
            start: LatLong,
            end: LatLong,
        ): Boolean = distanceMeters(start, end) <= LOOP_JOIN_METERS

        /**
         * Longitude as an x on the unit Web-Mercator square. Multiplying by the map size in pixels
         * gives the same value as mapsforge's `MercatorProjection.longitudeToPixelX`.
         */
        fun unitMercatorX(longitude: Double): Double = (longitude + 180.0) / 360.0

        /**
         * Latitude as a y on the unit Web-Mercator square. Multiplying by the map size in pixels
         * gives the same value as mapsforge's `MercatorProjection.latitudeToPixelY`.
         */
        fun unitMercatorY(latitude: Double): Double {
            val sinLatitude = sin(Math.toRadians(latitude))
            val y = 0.5 - ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * Math.PI)
            return y.coerceIn(0.0, 1.0)
        }

        /** Smallest "nice" kilometre interval (1/2/5/10...) that is at least [rawKm]. */
        fun niceKmInterval(rawKm: Double): Int {
            for (step in intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000)) {
                if (step >= rawKm) return step
            }
            return 1000
        }

        /** Formats a kilometre distance with one decimal place (e.g. 12.34 -> "12.3"), no unit suffix. */
        fun formatKm(km: Double): String {
            val tenths = (km * 10).roundToInt()
            return "${tenths / 10}.${tenths % 10}"
        }

        /**
         * True when the segment from ([x0], [y0]) to ([x1], [y1]) cannot touch the rectangle
         * [left]..[right] x [top]..[bottom] because both of its ends lie beyond the same edge. A
         * segment whose ends lie beyond different edges is kept even if it happens to miss the
         * rectangle: the test is deliberately the cheap one, and the canvas clips the rest.
         */
        fun isSegmentOffscreen(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ): Boolean =
            (x0 < left && x1 < left) ||
                (x0 > right && x1 > right) ||
                (y0 < top && y1 < top) ||
                (y0 > bottom && y1 > bottom)

        /**
         * Splits a projected polyline into the strokes to draw. Segment `s` joins vertex `s` to
         * vertex `s + 1` (coordinates in [xs] / [ys], [vertexCount] vertices in use). Segments that
         * are off-screen per [isSegmentOffscreen] are dropped; the remaining ones are grouped into
         * runs of consecutive segments sharing one colour, and [action] is called once per run with
         * its first and last vertex, so each run can be stroked as a single path. [colorOfSegment]
         * is asked only about segments that survive the off-screen test.
         */
        inline fun forEachLineRun(
            xs: FloatArray,
            ys: FloatArray,
            vertexCount: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            colorOfSegment: (segment: Int) -> Int,
            action: (firstVertex: Int, lastVertex: Int, color: Int) -> Unit,
        ) {
            var runStart = -1
            var runColor = 0
            for (segment in 0 until vertexCount - 1) {
                val next = segment + 1
                if (isSegmentOffscreen(xs[segment], ys[segment], xs[next], ys[next], left, top, right, bottom)) {
                    if (runStart >= 0) action(runStart, segment, runColor)
                    runStart = -1
                    continue
                }
                val color = colorOfSegment(segment)
                if (runStart >= 0 && color != runColor) {
                    action(runStart, segment, runColor)
                    runStart = -1
                }
                if (runStart < 0) {
                    runStart = segment
                    runColor = color
                }
            }
            if (runStart >= 0) action(runStart, vertexCount - 1, runColor)
        }
    }
}
