package io.github.nexgus.jiudge.feature.path

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.MercatorProjection

/**
 * Covers the drawing-independent path calculations: cumulative distance (whole and extended),
 * kilometre-marker positions, the projection onto the unit Mercator square, merging of same-coloured
 * segments into single strokes, and the off-screen segment test.
 */
class PathGeometryTest {
    @Test
    fun `cumulative distance sums the legs and starts at zero`() {
        // 0.01 degrees of latitude is ~1112 m on the 6371 km sphere used for path distances.
        val geometry = PathGeometry.of(listOf(LatLong(24.0, 121.0), LatLong(24.01, 121.0), LatLong(24.02, 121.0)))

        assertEquals(3, geometry.size)
        assertEquals(0.0, geometry.cumulativeMeters[0], 0.0)
        assertEquals(1111.95, geometry.cumulativeMeters[1], 0.5)
        assertEquals(2223.9, geometry.cumulativeMeters[2], 1.0)
        assertEquals(geometry.cumulativeMeters[2], geometry.totalMeters, 0.0)
    }

    @Test
    fun `empty and single point geometries have no length`() {
        assertEquals(0, PathGeometry.EMPTY.size)
        assertEquals(0.0, PathGeometry.EMPTY.totalMeters, 0.0)
        assertTrue(PathGeometry.EMPTY.kmMarkers().isEmpty())

        val single = PathGeometry.of(listOf(LatLong(24.0, 121.0)))
        assertEquals(1, single.size)
        assertEquals(0.0, single.totalMeters, 0.0)
        assertEquals(LatLong(24.0, 121.0), single.pointAt(500.0))
        assertTrue(single.kmMarkers().isEmpty())
    }

    @Test
    fun `extending a geometry equals measuring the longer path from scratch`() {
        val points = meander(200)
        val whole = PathGeometry.of(points)

        var grown = PathGeometry.of(points.take(1))
        for (size in intArrayOf(2, 3, 50, 51, 199, 200)) {
            grown = grown.extendedTo(points.take(size))
        }

        assertEquals(whole.size, grown.size)
        // Exact equality: the slope sampler relies on an extended path's distances being the very
        // same numbers a fresh measurement gives.
        assertArrayEquals(whole.cumulativeMeters, grown.cumulativeMeters, 0.0)
        assertArrayEquals(whole.unitX, grown.unitX, 0.0)
        assertArrayEquals(whole.unitY, grown.unitY, 0.0)
    }

    @Test
    fun `extending to the same points returns the same geometry`() {
        val points = meander(20)
        val geometry = PathGeometry.of(points)

        assertSame(geometry, geometry.extendedTo(points.toList()))
    }

    @Test
    fun `prefix test accepts appended points only`() {
        val points = meander(30)
        val geometry = PathGeometry.of(points.take(20))

        assertTrue(geometry.isPrefixOf(points))
        assertTrue(geometry.isPrefixOf(points.take(20)))
        // Equal coordinates count, not only the same instances.
        assertTrue(geometry.isPrefixOf(points.map { LatLong(it.latitude, it.longitude) }))
        assertTrue(PathGeometry.EMPTY.isPrefixOf(points))
        // Shorter than the geometry.
        assertFalse(geometry.isPrefixOf(points.take(19)))
        // Same length, one point moved.
        val moved = points.take(20).toMutableList().also { it[7] = LatLong(25.0, 121.5) }
        assertFalse(geometry.isPrefixOf(moved))
    }

    @Test
    fun `point along the path interpolates within a leg and clamps at both ends`() {
        val start = LatLong(24.0, 121.0)
        val end = LatLong(24.01, 121.0)
        val geometry = PathGeometry.of(listOf(start, end))

        assertEquals(start, geometry.pointAt(-10.0))
        assertEquals(start, geometry.pointAt(0.0))
        assertEquals(end, geometry.pointAt(geometry.totalMeters))
        assertEquals(end, geometry.pointAt(geometry.totalMeters + 1000.0))
        val middle = geometry.pointAt(geometry.totalMeters / 2)
        assertEquals(24.005, middle.latitude, 1e-9)
        assertEquals(121.0, middle.longitude, 1e-9)
    }

    @Test
    fun `kilometre markers sit at each whole kilometre along the path`() {
        // ~3.56 km heading north-east over 80 legs, so markers fall inside legs, not on vertices.
        val points = (0..80).map { i -> LatLong(24.0 + i * 0.0003, 121.0 + i * 0.0003) }
        val geometry = PathGeometry.of(points)
        assertTrue("total was ${geometry.totalMeters}", geometry.totalMeters in 3500.0..3999.0)

        val markers = geometry.kmMarkers()

        assertEquals(listOf(1, 2, 3), markers.map { it.km })
        for (marker in markers) {
            // The path is straight, so the distance from its start is the distance along it.
            val fromStart = PathGeometry.distanceMeters(points.first(), marker.point)
            assertEquals("km ${marker.km}", marker.km * 1000.0, fromStart, 1.0)
            // The tangent points straddle the marker, 20 m either side, in travel order.
            val back = PathGeometry.distanceMeters(points.first(), marker.tangentBack)
            val forward = PathGeometry.distanceMeters(points.first(), marker.tangentFwd)
            assertEquals("km ${marker.km} back", marker.km * 1000.0 - PathGeometry.TANGENT_EPS_M, back, 1.0)
            assertEquals("km ${marker.km} forward", marker.km * 1000.0 + PathGeometry.TANGENT_EPS_M, forward, 1.0)
        }
    }

    @Test
    fun `a path shorter than one kilometre has no kilometre markers`() {
        // ~890 m.
        val geometry = PathGeometry.of(listOf(LatLong(24.0, 121.0), LatLong(24.008, 121.0)))

        assertTrue(geometry.kmMarkers().isEmpty())
    }

    @Test
    fun `a marker near the path end has its forward tangent clamped to the end`() {
        // Two legs of 0.0045 degrees of latitude: a hair over 1 km in total.
        val points = listOf(LatLong(24.0, 121.0), LatLong(24.0045, 121.0), LatLong(24.009, 121.0))
        val geometry = PathGeometry.of(points)
        assertTrue("total was ${geometry.totalMeters}", geometry.totalMeters in 1000.0..1010.0)

        val marker = geometry.kmMarkers().single()

        assertEquals(1, marker.km)
        assertEquals(points.last(), marker.tangentFwd)
        assertEquals(geometry.pointAt(geometry.totalMeters - PathGeometry.TANGENT_EPS_M), geometry.endTangentBack())
    }

    @Test
    fun `loop test compares the two ends against the join distance`() {
        val start = LatLong(24.0, 121.0)

        // ~22 m apart: within the 30 m join distance.
        assertTrue(PathGeometry.isLoop(start, LatLong(24.0002, 121.0)))
        // ~44 m apart.
        assertFalse(PathGeometry.isLoop(start, LatLong(24.0004, 121.0)))
    }

    @Test
    fun `unit Mercator coordinates scale to mapsforge pixel coordinates`() {
        val mapSize = MercatorProjection.getMapSize(15.toByte(), 256)
        val samples = listOf(LatLong(25.1363861, 121.4275306), LatLong(21.9, 120.85), LatLong(-33.86, 151.2), LatLong(0.0, 0.0))
        for (point in samples) {
            assertEquals(
                MercatorProjection.longitudeToPixelX(point.longitude, mapSize),
                PathGeometry.unitMercatorX(point.longitude) * mapSize,
                1e-6,
            )
            assertEquals(
                MercatorProjection.latitudeToPixelY(point.latitude, mapSize),
                PathGeometry.unitMercatorY(point.latitude) * mapSize,
                1e-6,
            )
        }
        val geometry = PathGeometry.of(listOf(LatLong(25.1363861, 121.4275306)))
        assertEquals(PathGeometry.unitMercatorX(121.4275306), geometry.unitX[0], 0.0)
        assertEquals(PathGeometry.unitMercatorY(25.1363861), geometry.unitY[0], 0.0)
    }

    @Test
    fun `kilometre label interval and total label text`() {
        assertEquals(1, PathGeometry.niceKmInterval(0.2))
        assertEquals(1, PathGeometry.niceKmInterval(1.0))
        assertEquals(2, PathGeometry.niceKmInterval(1.1))
        assertEquals(5, PathGeometry.niceKmInterval(3.0))
        assertEquals(10, PathGeometry.niceKmInterval(7.5))
        assertEquals(1000, PathGeometry.niceKmInterval(5000.0))

        assertEquals("12.3", PathGeometry.formatKm(12.34))
        assertEquals("0.4", PathGeometry.formatKm(0.44))
        assertEquals("10.0", PathGeometry.formatKm(9.96))
    }

    @Test
    fun `adjacent segments of one colour merge into a single run`() {
        // Six vertices on a horizontal line, all inside the viewport; segment colours A A B B A.
        val xs = floatArrayOf(10f, 20f, 30f, 40f, 50f, 60f)
        val ys = FloatArray(6) { 50f }
        val colors = intArrayOf(RED, RED, BLUE, BLUE, RED)

        val runs = lineRuns(xs, ys, colors)

        assertEquals(listOf(Run(0, 2, RED), Run(2, 4, BLUE), Run(4, 5, RED)), runs)
    }

    @Test
    fun `a single coloured line is one run`() {
        val xs = floatArrayOf(10f, 20f, 30f, 40f)
        val ys = floatArrayOf(10f, 90f, 10f, 90f)

        assertEquals(listOf(Run(0, 3, RED)), lineRuns(xs, ys, intArrayOf(RED, RED, RED)))
    }

    @Test
    fun `fewer than two vertices give no run`() {
        assertTrue(lineRuns(FloatArray(0), FloatArray(0), IntArray(0)).isEmpty())
        assertTrue(lineRuns(floatArrayOf(50f), floatArrayOf(50f), IntArray(0)).isEmpty())
    }

    @Test
    fun `only the vertices in use are considered`() {
        // The arrays are scratch buffers longer than the path; the tail holds stale values.
        val xs = floatArrayOf(10f, 20f, 30f, 999f, 999f)
        val ys = floatArrayOf(50f, 50f, 50f, 999f, 999f)

        val runs = lineRuns(xs, ys, intArrayOf(RED, RED, RED, RED), vertexCount = 3)

        assertEquals(listOf(Run(0, 2, RED)), runs)
    }

    @Test
    fun `off-screen segments are dropped and split the run around them`() {
        // The line leaves the 0..100 viewport on the right, wanders outside, and comes back:
        // segments 2 and 3 have both ends beyond the right edge.
        val xs = floatArrayOf(10f, 60f, 120f, 150f, 130f, 80f, 20f)
        val ys = FloatArray(7) { 50f }
        val colors = IntArray(6) { RED }

        val runs = lineRuns(xs, ys, colors)

        // Segments 0-1 (the second one crosses the edge) and 4-5 (the first one crosses back).
        assertEquals(listOf(Run(0, 2, RED), Run(4, 6, RED)), runs)
    }

    @Test
    fun `a segment crossing the viewport with both ends outside is kept`() {
        // Ends beyond opposite edges: the segment runs straight across the viewport.
        val xs = floatArrayOf(-50f, 150f)
        val ys = floatArrayOf(50f, 50f)

        assertEquals(listOf(Run(0, 1, RED)), lineRuns(xs, ys, intArrayOf(RED)))
    }

    @Test
    fun `colour is not asked for off-screen segments`() {
        val xs = floatArrayOf(-30f, -20f, -10f, 10f)
        val ys = FloatArray(4) { 50f }
        val asked = mutableListOf<Int>()

        PathGeometry.forEachLineRun(
            xs,
            ys,
            4,
            0f,
            0f,
            100f,
            100f,
            colorOfSegment = { segment ->
                asked.add(segment)
                RED
            },
        ) { _, _, _ -> }

        assertEquals(listOf(2), asked)
    }

    @Test
    fun `segment is off-screen only when both ends lie beyond the same edge`() {
        fun offscreen(
            x0: Float,
            y0: Float,
            x1: Float,
            y1: Float,
        ) = PathGeometry.isSegmentOffscreen(x0, y0, x1, y1, 0f, 0f, 100f, 100f)

        // Both ends beyond one edge: left, right, top, bottom.
        assertTrue(offscreen(-20f, 10f, -5f, 90f))
        assertTrue(offscreen(120f, 10f, 105f, 90f))
        assertTrue(offscreen(10f, -20f, 90f, -5f))
        assertTrue(offscreen(10f, 120f, 90f, 105f))
        // Inside, or with one end inside.
        assertFalse(offscreen(10f, 10f, 90f, 90f))
        assertFalse(offscreen(-20f, 50f, 50f, 50f))
        // Ends beyond different edges are kept, whether or not the segment actually crosses.
        assertFalse(offscreen(-20f, 50f, 120f, 50f))
        assertFalse(offscreen(-20f, 50f, 50f, -20f))
        // Exactly on an edge is not beyond it.
        assertFalse(offscreen(0f, 10f, 0f, 90f))
        assertFalse(offscreen(100f, 10f, 100f, 90f))
    }

    private data class Run(
        val firstVertex: Int,
        val lastVertex: Int,
        val color: Int,
    )

    /** Collects the runs of a line against a 0..100 square viewport. */
    private fun lineRuns(
        xs: FloatArray,
        ys: FloatArray,
        colors: IntArray,
        vertexCount: Int = xs.size,
    ): List<Run> {
        val runs = mutableListOf<Run>()
        PathGeometry.forEachLineRun(
            xs,
            ys,
            vertexCount,
            0f,
            0f,
            100f,
            100f,
            colorOfSegment = { colors[it] },
        ) { first, last, color -> runs.add(Run(first, last, color)) }
        return runs
    }

    /** A deterministic meandering path with legs of varying length. */
    private fun meander(pointCount: Int): List<LatLong> {
        val points = ArrayList<LatLong>(pointCount)
        var lat = 24.0
        var lon = 121.0
        for (i in 0 until pointCount) {
            points.add(LatLong(lat, lon))
            lat += ((i * 37) % 50 + 1) * 1e-5
            lon += ((i * 13) % 7 - 3) * 2e-5
        }
        return points
    }

    private companion object {
        const val RED = 0xFFD32F2F.toInt()
        const val BLUE = 0xFF1976D2.toInt()
    }
}
