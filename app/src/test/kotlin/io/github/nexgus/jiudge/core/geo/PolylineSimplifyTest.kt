package io.github.nexgus.jiudge.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import kotlin.random.Random

/**
 * Covers the Douglas-Peucker simplification guarantees documented on [simplifyPolyline]: endpoints
 * are always kept, collinear points are dropped, a tolerance-dependent deviation is kept or dropped
 * as appropriate, and large inputs complete without a stack overflow (the algorithm must be
 * iterative, not recursive).
 */
class PolylineSimplifyTest {
    @Test
    fun `collinear middle points are removed`() {
        val points =
            listOf(
                LatLong(24.0, 121.0),
                LatLong(24.1, 121.1),
                LatLong(24.2, 121.2),
                LatLong(24.3, 121.3),
            )

        val result = simplifyPolyline(points, toleranceMeters = 1.0)

        assertEquals(listOf(points.first(), points.last()), result)
    }

    @Test
    fun `a deviation larger than tolerance is kept`() {
        val points =
            listOf(
                LatLong(24.0, 121.0),
                // Roughly 0.01 degrees of latitude offset, i.e. ~1100m, deviating from the straight line.
                LatLong(24.05, 121.11),
                LatLong(24.1, 121.0),
            )

        val result = simplifyPolyline(points, toleranceMeters = 10.0)

        assertEquals(3, result.size)
        assertEquals(points[1], result[1])
    }

    @Test
    fun `endpoints are always kept`() {
        val points =
            listOf(
                LatLong(24.0, 121.0),
                LatLong(24.1, 121.1),
                LatLong(24.2, 121.2),
            )

        val result = simplifyPolyline(points, toleranceMeters = 1_000_000.0)

        assertEquals(2, result.size)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
    }

    @Test
    fun `input smaller than 3 points is returned unchanged`() {
        val empty = emptyList<LatLong>()
        val one = listOf(LatLong(24.0, 121.0))
        val two = listOf(LatLong(24.0, 121.0), LatLong(24.1, 121.1))

        assertEquals(empty, simplifyPolyline(empty, toleranceMeters = 5.0))
        assertEquals(one, simplifyPolyline(one, toleranceMeters = 5.0))
        assertEquals(two, simplifyPolyline(two, toleranceMeters = 5.0))
    }

    @Test
    fun `zigzag - small tolerance keeps points, large tolerance drops them`() {
        // A small zigzag deviating roughly 5m off the straight line between endpoints.
        val points =
            listOf(
                LatLong(24.0, 121.0),
                LatLong(24.05, 121.0 + 0.00004),
                LatLong(24.1, 121.0),
            )

        val keptSmallTolerance = simplifyPolyline(points, toleranceMeters = 1.0)
        val droppedLargeTolerance = simplifyPolyline(points, toleranceMeters = 100.0)

        assertEquals(3, keptSmallTolerance.size)
        assertEquals(2, droppedLargeTolerance.size)
    }

    @Test
    fun `long noisy polyline completes without stack overflow and shrinks substantially`() {
        val random = Random(42)
        val points = ArrayList<LatLong>(50_000)
        for (i in 0 until 50_000) {
            val lat = 24.0 + i * 0.00001
            // Tiny noise, well under the tolerance used below, on an otherwise straight line.
            val noise = (random.nextDouble() - 0.5) * 0.0000005
            points.add(LatLong(lat, 121.0 + noise))
        }

        val result = simplifyPolyline(points, toleranceMeters = 5.0)

        assertTrue(result.size < points.size / 10)
        assertEquals(points.first(), result.first())
        assertEquals(points.last(), result.last())
    }
}
