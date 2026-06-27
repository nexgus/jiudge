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
    fun `outlying islands select the 119 zone`() {
        // Each of the four 119-zone regions, sampled near its centre.
        assertEquals(119, Twd97.convert(lat = 24.43, lon = 118.32).centralMeridianDeg) // 金門 金城
        assertEquals(119, Twd97.convert(lat = 24.99, lon = 119.45).centralMeridianDeg) // 烏坵
        assertEquals(119, Twd97.convert(lat = 23.57, lon = 119.57).centralMeridianDeg) // 澎湖 馬公
        assertEquals(119, Twd97.convert(lat = 26.16, lon = 119.92).centralMeridianDeg) // 馬祖 南竿
    }

    @Test
    fun `Matsu Dongyin selects 119 despite being east of the western mainland`() {
        // 東引 ~120.50E is east of 雲林/嘉義 west coast (~120.18E) yet still belongs to the 119 zone -
        // the property that defeats any single-threshold longitude split.
        assertEquals(119, Twd97.convert(lat = 26.37, lon = 120.50).centralMeridianDeg)
    }

    @Test
    fun `mainland west coast selects 121 even when longitude is below 120 point 5`() {
        // Hotspots that the old single-threshold split (lon less than 120.5 yields 119) sent to the
        // wrong zone. They must land in 121E now.
        assertEquals(121, Twd97.convert(lat = 22.62, lon = 120.27).centralMeridianDeg) // 高雄 壽山
        assertEquals(121, Twd97.convert(lat = 23.48, lon = 120.45).centralMeridianDeg) // 嘉義市
        assertEquals(121, Twd97.convert(lat = 23.00, lon = 120.21).centralMeridianDeg) // 台南市
    }

    @Test
    fun `nearshore islands of mainland counties select 121`() {
        // Administered with main-island counties (Taitung / Pingtung / Yilan), officially 121E.
        assertEquals(121, Twd97.convert(lat = 22.65, lon = 121.49).centralMeridianDeg) // 綠島
        assertEquals(121, Twd97.convert(lat = 22.05, lon = 121.55).centralMeridianDeg) // 蘭嶼
        assertEquals(121, Twd97.convert(lat = 22.35, lon = 120.37).centralMeridianDeg) // 小琉球
        assertEquals(121, Twd97.convert(lat = 24.84, lon = 121.95).centralMeridianDeg) // 龜山島
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
