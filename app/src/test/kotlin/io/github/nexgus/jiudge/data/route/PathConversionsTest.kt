package io.github.nexgus.jiudge.data.route

import io.github.nexgus.jiudge.core.geo.simplifyPolyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import kotlin.math.cos

/**
 * Covers [toPlannedRoute], the conversion behind copying a track as a planned route: the copy is
 * one imported segment between the track's first and last fix, simplified with
 * [PATH_SIMPLIFY_TOLERANCE_M], and carries no time.
 */
class PathConversionsTest {
    // One metre expressed in degrees at the test latitude, accurate enough to place fixes a known
    // distance apart.
    private val latPerMeter = 1.0 / 111_320.0
    private val lonPerMeter = 1.0 / (111_320.0 * cos(Math.toRadians(24.0)))

    private fun point(
        eastMeters: Double,
        northMeters: Double,
        timeMs: Long,
    ): RecordedTrack.Point =
        RecordedTrack.Point(
            latitude = Trace.coord(24.0 + northMeters * latPerMeter),
            longitude = Trace.coord(121.0 + eastMeters * lonPerMeter),
            timeMs = timeMs,
        )

    /**
     * A 1 Hz walk: 200 m east with the fixes wobbling half a metre to either side of the line, then
     * a right-angle turn and 200 m north. The wobble is well inside the tolerance; the corner is far
     * outside it.
     */
    private fun walk(): RecordedTrack {
        val east =
            (0..100).map { i ->
                point(eastMeters = i * 2.0, northMeters = if (i % 2 == 0) 0.5 else -0.5, timeMs = 1_000L * i)
            }
        val north = (1..100).map { i -> point(eastMeters = 200.0, northMeters = i * 2.0, timeMs = 1_000L * (100 + i)) }
        return RecordedTrack(name = "walk", createdAtEpochMs = 0L, points = east + north)
    }

    private val corner = LatLong(walk().points[100].latitude, walk().points[100].longitude)

    @Test
    fun `copy is a single imported segment`() {
        val plan = walk().toPlannedRoute("walk 規劃", 5_000L)

        assertEquals(1, plan.segments.size)
        assertTrue(plan.segments.single().imported)
        assertEquals(PathKind.PLAN, plan.kind)
    }

    @Test
    fun `copy starts and ends where the track does`() {
        val track = walk()
        val plan = track.toPlannedRoute("walk 規劃", 5_000L)

        assertEquals(track.polyline.first(), plan.polyline.first())
        assertEquals(track.polyline.last(), plan.polyline.last())
        // The two waypoints are those same end points, unnamed like any imported boundary point.
        assertEquals(
            listOf(PlannedRoute.Waypoint(track.polyline.first()), PlannedRoute.Waypoint(track.polyline.last())),
            plan.waypoints,
        )
    }

    @Test
    fun `copy carries no time`() {
        val plan = walk().toPlannedRoute("walk 規劃", 5_000L)

        assertNull(plan.pointTimesMs)
        val records = plan.toRecords()
        assertTrue(records.none { it.optString("k") == "pt" })
        assertTrue(records.none { it.has("t") })
    }

    @Test
    fun `copy is simplified with the shared tolerance`() {
        val track = walk()
        val plan = track.toPlannedRoute("walk 規劃", 5_000L)
        val points = plan.segments.single().points

        assertEquals(simplifyPolyline(track.polyline, PATH_SIMPLIFY_TOLERANCE_M), points)
        // The sub-tolerance wobble is gone, the corner is not: only a handful of the 201 fixes
        // remain, each of them one of the track's own points, in travel order.
        assertTrue("expected far fewer than ${track.points.size} points, got ${points.size}", points.size < 10)
        assertTrue(points.contains(corner))
        val indices = points.map { track.polyline.indexOf(it) }
        assertTrue(indices.all { it >= 0 })
        assertEquals(indices.sorted(), indices)
    }

    @Test
    fun `copy takes the given name and creation time`() {
        val plan = walk().toPlannedRoute("walk 規劃", 5_000L)

        assertEquals("walk 規劃", plan.name)
        assertEquals(5_000L, plan.createdAtEpochMs)
    }

    @Test
    fun `copy reads back from its records as a plan with an imported segment`() {
        val plan = walk().toPlannedRoute("walk 規劃", 5_000L)

        val loaded = SavedPath.fromTrace(Trace.Parsed(plan.header(), plan.toRecords()))

        assertTrue(loaded is PlannedRoute)
        assertEquals(plan, loaded)
    }

    @Test
    fun `a track with no fix yields a plan with no segment`() {
        val plan = RecordedTrack(name = "empty", createdAtEpochMs = 0L, points = emptyList()).toPlannedRoute("empty 規劃", 5_000L)

        assertTrue(plan.segments.isEmpty())
        assertTrue(plan.waypoints.isEmpty())
        assertFalse(plan.toRecords().any { it.optString("k") == "seg" })
    }
}
