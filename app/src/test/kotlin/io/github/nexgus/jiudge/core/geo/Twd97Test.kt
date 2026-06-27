package io.github.nexgus.jiudge.core.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Twd97Test {
    @Test
    fun `on the central meridian easting equals the false easting`() {
        // Anywhere along 121E (the main-island zone meridian) the easting collapses to the 250 km
        // false easting, and the northing is just the scaled meridian arc - a strong closed-form check.
        val c = Twd97.convert(lat = 24.0, lon = 121.0)
        assertEquals(121, c.centralMeridianDeg)
        assertEquals(250_000.0, c.easting, 0.001)
        // Meridian arc to 24N on GRS80, times the 0.9999 scale factor, is ~2,655,000 m.
        assertEquals(2_655_000.0, c.northing, 500.0)
    }

    @Test
    fun `the equator on the central meridian is the projection origin`() {
        val c = Twd97.convert(lat = 0.0, lon = 121.0)
        assertEquals(250_000.0, c.easting, 0.001)
        assertEquals(0.0, c.northing, 0.001)
    }

    @Test
    fun `outlying-island longitudes select the 119 zone`() {
        // Penghu (~119.6E) falls in the 119E zone; the main island stays in 121E.
        assertEquals(119, Twd97.convert(lat = 23.57, lon = 119.6).centralMeridianDeg)
        assertEquals(121, Twd97.convert(lat = 25.03, lon = 121.56).centralMeridianDeg)
    }

    @Test
    fun `a known Taipei point lands in the expected grid neighbourhood`() {
        // Taipei 101 (WGS84). TWD97 TM2 easting/northing are around 305 km / 2769 km; a loose
        // tolerance guards against gross formula errors without overfitting to a single reference.
        val c = Twd97.convert(lat = 25.033611, lon = 121.564444)
        assertTrue("easting=${c.easting}", c.easting in 304_000.0..307_000.0)
        assertTrue("northing=${c.northing}", c.northing in 2_768_000.0..2_771_000.0)
    }
}
