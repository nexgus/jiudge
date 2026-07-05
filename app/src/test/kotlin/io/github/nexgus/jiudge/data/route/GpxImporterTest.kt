package io.github.nexgus.jiudge.data.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the GPX 1.0 / 1.1 / no-namespace geometry and name-resolution rules documented on
 * [GpxImporter]. Inputs are built as inline XML strings since the parser only needs an
 * [java.io.InputStream].
 */
class GpxImporterTest {
    private fun parse(xml: String): GpxImporter.Result = GpxImporter.parse(xml.byteInputStream())

    @Test
    fun `gpx 1_1 with namespace, one trk with two trksegs yields two segments and trk name`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <name>My Hike</name>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
                  <trkpt lat="24.1" lon="121.1"></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="25.0" lon="122.0"></trkpt>
                  <trkpt lat="25.1" lon="122.1"></trkpt>
                  <trkpt lat="25.2" lon="122.2"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals("My Hike", result.name)
        assertEquals(2, result.segments.size)
        assertEquals(2, result.segments[0].size)
        assertEquals(24.0, result.segments[0][0].latitude, 1e-9)
        assertEquals(121.0, result.segments[0][0].longitude, 1e-9)
        assertEquals(24.1, result.segments[0][1].latitude, 1e-9)
        assertEquals(3, result.segments[1].size)
        assertEquals(25.2, result.segments[1][2].latitude, 1e-9)
        assertEquals(122.2, result.segments[1][2].longitude, 1e-9)
    }

    @Test
    fun `gpx 1_0 with namespace parses trkseg points`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <trk>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
                  <trkpt lat="24.1" lon="121.1"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals(1, result.segments.size)
        assertEquals(2, result.segments[0].size)
    }

    @Test
    fun `gpx with no namespace parses trkseg points`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
                  <trkpt lat="24.1" lon="121.1"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals(1, result.segments.size)
        assertEquals(2, result.segments[0].size)
    }

    @Test
    fun `rte-only file yields one segment from rtept points`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <rte>
                <name>Route A</name>
                <rtept lat="24.0" lon="121.0"></rtept>
                <rtept lat="24.5" lon="121.5"></rtept>
              </rte>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals(1, result.segments.size)
        assertEquals(2, result.segments[0].size)
        // "Route A" is a <name> inside <rte>, not <trk>/<metadata>/<gpx>, so it must not leak.
        assertNull(result.name)
    }

    @Test
    fun `mixed trk and rte are collected in document order`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <rte>
                <rtept lat="10.0" lon="110.0"></rtept>
                <rtept lat="10.1" lon="110.1"></rtept>
              </rte>
              <trk>
                <trkseg>
                  <trkpt lat="20.0" lon="120.0"></trkpt>
                  <trkpt lat="20.1" lon="120.1"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals(2, result.segments.size)
        assertEquals(10.0, result.segments[0][0].latitude, 1e-9)
        assertEquals(20.0, result.segments[1][0].latitude, 1e-9)
    }

    @Test
    fun `wpt-only file yields empty segments and wpt name does not leak`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="24.0" lon="121.0">
                <name>Some Waypoint</name>
              </wpt>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertTrue(result.segments.isEmpty())
        assertNull(result.name)
    }

    @Test
    fun `metadata name is used when there is no trk name`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata>
                <name>Meta Name</name>
              </metadata>
              <trk>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
                  <trkpt lat="24.1" lon="121.1"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals("Meta Name", result.name)
    }

    @Test
    fun `trk name wins over metadata name`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata>
                <name>Meta Name</name>
              </metadata>
              <trk>
                <name>Trk Name</name>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
                  <trkpt lat="24.1" lon="121.1"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals("Trk Name", result.name)
    }

    @Test
    fun `gpx direct name is used when there is no trk or metadata name`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
              <name>Gpx Level Name</name>
              <trk>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
                  <trkpt lat="24.1" lon="121.1"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals("Gpx Level Name", result.name)
    }

    @Test
    fun `single-point trkseg is dropped`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
                </trkseg>
                <trkseg>
                  <trkpt lat="25.0" lon="122.0"></trkpt>
                  <trkpt lat="25.1" lon="122.1"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals(1, result.segments.size)
        assertEquals(2, result.segments[0].size)
    }

    @Test
    fun `trkpt with missing lon is skipped`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
                  <trkpt lat="24.1"></trkpt>
                  <trkpt lat="24.2" lon="121.2"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals(1, result.segments.size)
        assertEquals(2, result.segments[0].size)
        assertEquals(24.0, result.segments[0][0].latitude, 1e-9)
        assertEquals(24.2, result.segments[0][1].latitude, 1e-9)
    }

    @Test
    fun `malformed xml throws GpxParseException`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <trk>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
            </gpx>
            """.trimIndent()

        assertThrows(GpxParseException::class.java) {
            parse(xml)
        }
    }

    @Test
    fun `all-whitespace trk name falls through to metadata name`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata>
                <name>Meta Name</name>
              </metadata>
              <trk>
                <name>   </name>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
                  <trkpt lat="24.1" lon="121.1"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals("Meta Name", result.name)
    }

    @Test
    fun `nameless first trk falls through to metadata even when a later trk is named`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <metadata>
                <name>Metadata Name</name>
              </metadata>
              <trk>
                <trkseg>
                  <trkpt lat="24.0" lon="121.0"></trkpt>
                  <trkpt lat="24.1" lon="121.1"></trkpt>
                </trkseg>
              </trk>
              <trk>
                <name>Side Trip</name>
                <trkseg>
                  <trkpt lat="25.0" lon="122.0"></trkpt>
                  <trkpt lat="25.1" lon="122.1"></trkpt>
                </trkseg>
              </trk>
            </gpx>
            """.trimIndent()

        val result = parse(xml)

        assertEquals("Metadata Name", result.name)
        assertEquals(2, result.segments.size)
    }
}
