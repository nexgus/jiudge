package io.github.nexgus.jiudge.data.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * Covers the GPX 1.1 export rules documented on [GpxExporter]: element structure/order, elevation
 * sourced only from the injected `elevationAt` lookup (never a stored value), timestamp formatting,
 * XML escaping, and round-tripping through [GpxImporter].
 */
class GpxExporterTest {
    private fun exportRoute(
        route: PlannedRoute,
        elevationAt: ((Double, Double) -> Float?)? = null,
    ): String {
        val out = ByteArrayOutputStream()
        GpxExporter.write(route, out, elevationAt)
        return out.toString(Charsets.UTF_8.name())
    }

    private fun exportTrack(
        track: RecordedTrack,
        elevationAt: ((Double, Double) -> Float?)? = null,
    ): String {
        val out = ByteArrayOutputStream()
        GpxExporter.write(track, out, elevationAt)
        return out.toString(Charsets.UTF_8.name())
    }

    @Test
    fun `plan export yields only named wpt, single rte, and rtept sequence equal to polyline`() {
        val route =
            PlannedRoute(
                name = "Test Route",
                createdAtEpochMs = 1687123456789,
                waypoints =
                    listOf(
                        PlannedRoute.Waypoint(LatLong(24.0, 121.0)),
                        PlannedRoute.Waypoint(LatLong(24.2, 121.2), name = "三角點"),
                        PlannedRoute.Waypoint(LatLong(24.4, 121.4)),
                    ),
                segments =
                    listOf(
                        PlannedRoute.Segment(points = listOf(LatLong(24.0, 121.0), LatLong(24.1, 121.1), LatLong(24.2, 121.2))),
                        PlannedRoute.Segment(points = listOf(LatLong(24.2, 121.2), LatLong(24.3, 121.3), LatLong(24.4, 121.4))),
                    ),
            )

        val xml = exportRoute(route)

        // Only the one named waypoint becomes a <wpt>; the two unnamed routing control points do not.
        assertEquals(1, Regex("<wpt ").findAll(xml).count())
        assertTrue(xml.contains("<name>三角點</name>"))
        // Single <rte>.
        assertEquals(1, Regex("<rte>").findAll(xml).count())
        // rtept sequence equals the joined polyline (junction point de-duplicated): 5 points.
        val expectedPolyline = route.polyline
        assertEquals(5, expectedPolyline.size)
        assertEquals(5, Regex("<rtept ").findAll(xml).count())

        val rteptLats = Regex("""<rtept lat="([\d.]+)"""").findAll(xml).map { it.groupValues[1].toDouble() }.toList()
        val expectedLats = expectedPolyline.map { Trace.coord(it.latitude) }
        assertEquals(expectedLats, rteptLats)

        assertTrue(xml.contains("<name>Test Route</name>"))
        assertTrue(xml.contains("<time>${Instant.ofEpochMilli(1687123456789)}</time>"))
    }

    @Test
    fun `plan export omits rte when polyline is empty and omits wpt when waypoints is empty`() {
        val route =
            PlannedRoute(
                name = "Empty",
                createdAtEpochMs = 1687123456789,
                waypoints = emptyList(),
                segments = emptyList(),
            )

        val xml = exportRoute(route)

        assertTrue(!xml.contains("<rte>"))
        assertTrue(!xml.contains("<wpt "))
    }

    @Test
    fun `plan export omits wpt entirely when no waypoint is named`() {
        val route =
            PlannedRoute(
                name = "Unnamed",
                createdAtEpochMs = 1687123456789,
                waypoints =
                    listOf(
                        PlannedRoute.Waypoint(LatLong(24.0, 121.0)),
                        PlannedRoute.Waypoint(LatLong(24.2, 121.2)),
                    ),
                segments =
                    listOf(
                        PlannedRoute.Segment(points = listOf(LatLong(24.0, 121.0), LatLong(24.2, 121.2))),
                    ),
            )

        val xml = exportRoute(route)

        assertTrue(!xml.contains("<wpt "))
        assertTrue(xml.contains("<rte>"))
    }

    @Test
    fun `named wpt writes ele before name, per the GPX 1_1 wptType child order`() {
        val route =
            PlannedRoute(
                name = "Ordered",
                createdAtEpochMs = 1687123456789,
                waypoints = listOf(PlannedRoute.Waypoint(LatLong(24.0, 121.0), name = "登山口")),
                segments = emptyList(),
            )

        // A DEM that always resolves, so the <wpt> carries both children.
        val xml = exportRoute(route) { _, _ -> 1234.5f }

        val wpt = Regex("""<wpt .*?</wpt>""", RegexOption.DOT_MATCHES_ALL).find(xml)!!.value
        assertTrue(wpt.indexOf("<ele>") < wpt.indexOf("<name>"))
        assertTrue(wpt.contains("<ele>1234.5</ele>"))
        assertTrue(wpt.contains("<name>登山口</name>"))
    }

    @Test
    fun `waypoint name is xml-escaped`() {
        val route =
            PlannedRoute(
                name = "Escaping",
                createdAtEpochMs = 1687123456789,
                waypoints = listOf(PlannedRoute.Waypoint(LatLong(24.0, 121.0), name = "A & B <x>")),
                segments = emptyList(),
            )

        val xml = exportRoute(route)

        assertTrue(xml.contains("<name>A &amp; B &lt;x&gt;</name>"))
        assertTrue(!xml.contains("A & B <x>"))
    }

    @Test
    fun `track export writes trkpt in point order each with correct iso8601 time`() {
        val epochMs = 1687123460000L
        val track =
            RecordedTrack(
                name = "Test Track",
                createdAtEpochMs = 1687123456789,
                points =
                    listOf(
                        RecordedTrack.Point(latitude = 24.0, longitude = 121.0, timeMs = epochMs),
                        RecordedTrack.Point(latitude = 24.1, longitude = 121.1, timeMs = epochMs + 1000),
                    ),
            )

        val xml = exportTrack(track)

        assertEquals(2, Regex("<trkpt ").findAll(xml).count())
        val times = Regex("<time>([^<]+)</time>").findAll(xml).map { it.groupValues[1] }.toList()
        // First <time> belongs to <metadata>; the next two belong to the trkpts, in point order.
        assertEquals(3, times.size)
        assertEquals("2023-06-18T21:24:20Z", times[1])
        assertEquals("2023-06-18T21:24:21Z", times[2])

        val trkptLats = Regex("""<trkpt lat="([\d.]+)"""").findAll(xml).map { it.groupValues[1].toDouble() }.toList()
        assertEquals(listOf(24.0, 24.1), trkptLats)
    }

    @Test
    fun `track export includes ele formatted to one decimal when elevationAt is provided`() {
        val track =
            RecordedTrack(
                name = "Elevated",
                createdAtEpochMs = 1687123456789,
                points = listOf(RecordedTrack.Point(latitude = 24.0, longitude = 121.0, timeMs = 1687123460000)),
            )

        val xml = exportTrack(track, elevationAt = { _, _ -> 1820.44f })

        assertTrue(xml.contains("<ele>1820.4</ele>"))
    }

    @Test
    fun `all points lack ele when elevationAt lambda is null`() {
        val track =
            RecordedTrack(
                name = "No DEM",
                createdAtEpochMs = 1687123456789,
                points =
                    listOf(
                        RecordedTrack.Point(latitude = 24.0, longitude = 121.0, timeMs = 1687123460000),
                        RecordedTrack.Point(latitude = 24.1, longitude = 121.1, timeMs = 1687123461000),
                    ),
            )

        val xml = exportTrack(track, elevationAt = null)

        assertTrue(!xml.contains("<ele>"))
    }

    @Test
    fun `only the point elevationAt returns null for lacks ele, others keep it`() {
        val track =
            RecordedTrack(
                name = "Partial DEM",
                createdAtEpochMs = 1687123456789,
                points =
                    listOf(
                        RecordedTrack.Point(latitude = 24.0, longitude = 121.0, timeMs = 1687123460000),
                        RecordedTrack.Point(latitude = 24.1, longitude = 121.1, timeMs = 1687123461000),
                    ),
            )

        val xml = exportTrack(track, elevationAt = { lat, _ -> if (lat == 24.1) null else 1000f })

        val eleCount = Regex("<ele>").findAll(xml).count()
        assertEquals(1, eleCount)
        assertTrue(xml.contains("<ele>1000.0</ele>"))
    }

    @Test
    fun `name with ampersand and angle brackets is escaped correctly`() {
        val route =
            PlannedRoute(
                name = "A & B <Test> Route",
                createdAtEpochMs = 1687123456789,
                waypoints = emptyList(),
                segments = emptyList(),
            )

        val xml = exportRoute(route)

        assertTrue(xml.contains("<name>A &amp; B &lt;Test&gt; Route</name>"))
        assertTrue(!xml.contains("A & B <Test> Route"))
    }

    @Test
    fun `round-trip plan export through GpxImporter yields single segment equal to polyline`() {
        val route =
            PlannedRoute(
                name = "Round Trip Route",
                createdAtEpochMs = 1687123456789,
                waypoints =
                    listOf(
                        PlannedRoute.Waypoint(LatLong(24.0, 121.0)),
                        PlannedRoute.Waypoint(LatLong(24.2, 121.2)),
                    ),
                segments =
                    listOf(
                        PlannedRoute.Segment(points = listOf(LatLong(24.0, 121.0), LatLong(24.1, 121.1), LatLong(24.2, 121.2))),
                    ),
            )

        val xml = exportRoute(route)
        val result = GpxImporter.parse(xml.byteInputStream(Charsets.UTF_8))

        assertEquals(1, result.segments.size)
        val expected = route.polyline.map { Trace.coord(it.latitude) to Trace.coord(it.longitude) }
        val actual = result.segments[0].map { it.latitude to it.longitude }
        assertEquals(expected, actual)
    }

    @Test
    fun `round-trip track export through GpxImporter yields segment equal to point coordinates`() {
        val track =
            RecordedTrack(
                name = "Round Trip Track",
                createdAtEpochMs = 1687123456789,
                points =
                    listOf(
                        RecordedTrack.Point(latitude = 24.0, longitude = 121.0, timeMs = 1687123460000),
                        RecordedTrack.Point(latitude = 24.1, longitude = 121.1, timeMs = 1687123461000),
                        RecordedTrack.Point(latitude = 24.2, longitude = 121.2, timeMs = 1687123462000),
                    ),
            )

        val xml = exportTrack(track)
        val result = GpxImporter.parse(xml.byteInputStream(Charsets.UTF_8))

        assertEquals(1, result.segments.size)
        val expected = track.points.map { Trace.coord(it.latitude) to Trace.coord(it.longitude) }
        val actual = result.segments[0].map { it.latitude to it.longitude }
        assertEquals(expected, actual)
    }

    @Test
    fun `xml declaration and root element are well-formed gpx 1_1`() {
        val route =
            PlannedRoute(
                name = "Header Check",
                createdAtEpochMs = 1687123456789,
                waypoints = emptyList(),
                segments = emptyList(),
            )

        val xml = exportRoute(route)

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"))
        assertTrue(
            xml.contains(
                "<gpx version=\"1.1\" creator=\"jiudge\" xmlns=\"http://www.topografix.com/GPX/1/1\">",
            ),
        )
    }
}
