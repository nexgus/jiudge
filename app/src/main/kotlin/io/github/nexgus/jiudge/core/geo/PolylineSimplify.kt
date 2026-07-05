package io.github.nexgus.jiudge.core.geo

import org.mapsforge.core.model.LatLong
import kotlin.math.cos

/** Approximate metres per degree of latitude/longitude, accurate enough at Taiwan's scale. */
private const val M_PER_DEG = 111_320.0

/**
 * Simplifies [points] using the Douglas-Peucker algorithm, measuring perpendicular distance to the
 * segment (clamped to [0,1], not the infinite line) in an equirectangular local projection centred
 * on the polyline's first point. Implemented iteratively with an explicit stack so tens of
 * thousands of points cannot overflow the call stack.
 *
 * The first and last points are always kept, point order is preserved, and points are only ever
 * removed (never moved or added). Inputs with fewer than 3 points are returned unchanged.
 */
fun simplifyPolyline(
    points: List<LatLong>,
    toleranceMeters: Double,
): List<LatLong> {
    if (points.size < 3) return points

    val refLat = points[0].latitude
    val refLatRadians = Math.toRadians(refLat)
    val refLon = points[0].longitude
    val cosRefLat = cos(refLatRadians)

    val xs = DoubleArray(points.size)
    val ys = DoubleArray(points.size)
    for (i in points.indices) {
        xs[i] = (points[i].longitude - refLon) * cosRefLat * M_PER_DEG
        ys[i] = (points[i].latitude - refLat) * M_PER_DEG
    }

    val keep = BooleanArray(points.size)
    keep[0] = true
    keep[points.size - 1] = true

    // Each stack entry is an inclusive index range [start, end] still to be processed.
    val stack = ArrayDeque<IntRange>()
    stack.addLast(0..(points.size - 1))

    while (stack.isNotEmpty()) {
        val range = stack.removeLast()
        val start = range.first
        val end = range.last
        if (end - start < 2) continue

        var maxDist = -1.0
        var maxIndex = -1
        for (i in (start + 1) until end) {
            val dist =
                perpendicularDistanceToSegment(
                    xs[i],
                    ys[i],
                    xs[start],
                    ys[start],
                    xs[end],
                    ys[end],
                )
            if (dist > maxDist) {
                maxDist = dist
                maxIndex = i
            }
        }

        if (maxIndex != -1 && maxDist > toleranceMeters) {
            keep[maxIndex] = true
            stack.addLast(start..maxIndex)
            stack.addLast(maxIndex..end)
        }
    }

    val result = ArrayList<LatLong>()
    for (i in points.indices) {
        if (keep[i]) result.add(points[i])
    }
    return result
}

/** Perpendicular distance from point (px, py) to the segment (ax, ay)-(bx, by), in the same units. */
private fun perpendicularDistanceToSegment(
    px: Double,
    py: Double,
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
): Double {
    val dx = bx - ax
    val dy = by - ay
    val lengthSquared = dx * dx + dy * dy

    if (lengthSquared == 0.0) {
        // Degenerate segment: both endpoints coincide.
        val ddx = px - ax
        val ddy = py - ay
        return Math.sqrt(ddx * ddx + ddy * ddy)
    }

    var t = ((px - ax) * dx + (py - ay) * dy) / lengthSquared
    t = t.coerceIn(0.0, 1.0)

    val projX = ax + t * dx
    val projY = ay + t * dy
    val ddx = px - projX
    val ddy = py - projY
    return Math.sqrt(ddx * ddx + ddy * ddy)
}
