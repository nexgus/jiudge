package io.github.nexgus.jiudge.data.route

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

class PlannedRouteTest {
    private val a = LatLong(24.1, 121.1)
    private val b = LatLong(24.2, 121.2)
    private val c = LatLong(24.3, 121.3)

    private fun roundTrip(route: PlannedRoute): PlannedRoute = PlannedRoute.fromTrace(Trace.Parsed(route.header(), route.toRecords()))

    @Test
    fun `imported flag round-trips through seg records`() {
        val route =
            PlannedRoute(
                name = "mixed",
                createdAtEpochMs = 123L,
                waypoints = listOf(a, b, c),
                segments =
                    listOf(
                        PlannedRoute.Segment(points = listOf(a, b), imported = true),
                        PlannedRoute.Segment(points = listOf(b, c), imported = false),
                    ),
            )
        val loaded = roundTrip(route)
        assertEquals(2, loaded.segments.size)
        assertTrue(loaded.segments[0].imported)
        assertFalse(loaded.segments[1].imported)
        assertEquals(route.waypoints, loaded.waypoints)
        assertEquals(route.segments[0].points, loaded.segments[0].points)
    }

    @Test
    fun `imported segment writes origin=import and routed segment omits origin`() {
        val route =
            PlannedRoute(
                name = "r",
                createdAtEpochMs = 1L,
                waypoints = listOf(a, b, c),
                segments =
                    listOf(
                        PlannedRoute.Segment(points = listOf(a, b), imported = true),
                        PlannedRoute.Segment(points = listOf(b, c)),
                    ),
            )
        val segRecords = route.toRecords().filter { it.optString("k") == "seg" }
        assertEquals("import", segRecords[0].getString("origin"))
        assertFalse(segRecords[1].has("origin"))
    }

    @Test
    fun `unknown origin value is treated as imported`() {
        val header = TraceHeader(type = Trace.TYPE_PLAN, name = "r", createdAtEpochMs = 1L)
        val records =
            listOf(
                JSONObject("""{"k":"wpt","i":0,"lat":24.1,"lon":121.1}"""),
                JSONObject("""{"k":"wpt","i":1,"lat":24.2,"lon":121.2}"""),
                JSONObject("""{"k":"seg","i":0,"origin":"someday-new-kind","pts":[[24.1,121.1],[24.2,121.2]]}"""),
            )
        val loaded = PlannedRoute.fromTrace(Trace.Parsed(header, records))
        assertTrue(loaded.segments.single().imported)
    }

    @Test
    fun `absent origin reads as routed`() {
        val header = TraceHeader(type = Trace.TYPE_PLAN, name = "r", createdAtEpochMs = 1L)
        val records =
            listOf(
                JSONObject("""{"k":"wpt","i":0,"lat":24.1,"lon":121.1}"""),
                JSONObject("""{"k":"wpt","i":1,"lat":24.2,"lon":121.2}"""),
                JSONObject("""{"k":"seg","i":0,"pts":[[24.1,121.1],[24.2,121.2]]}"""),
            )
        val loaded = PlannedRoute.fromTrace(Trace.Parsed(header, records))
        assertFalse(loaded.segments.single().imported)
    }

    @Test
    fun `fromImportedSegments builds boundary waypoints and flags every segment`() {
        val route =
            PlannedRoute.fromImportedSegments(
                name = "gpx",
                createdAtEpochMs = 9L,
                segments = listOf(listOf(a, b), listOf(b, c)),
            )
        assertEquals(listOf(a, b, c), route.waypoints)
        assertTrue(route.segments.all { it.imported })
        assertEquals(listOf(a, b, c), route.polyline)
    }

    @Test
    fun `fromImportedSegments with no segments yields an empty plan`() {
        val route = PlannedRoute.fromImportedSegments("empty", 1L, emptyList())
        assertTrue(route.waypoints.isEmpty())
        assertTrue(route.segments.isEmpty())
    }
}
