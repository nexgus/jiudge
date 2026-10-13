package io.github.nexgus.jiudge.core.elevation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.util.LatLongUtils
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

/**
 * Covers the rules documented on [SlopeSampler]: 30 m sample spacing, central-difference slope,
 * gap holding, the "no slope data" cases, and above all that continuing a profile over appended
 * points gives exactly what sampling the whole path gives, while looking elevation up only for the
 * appended stretch.
 */
class SlopeSamplerTest {
    @Test
    fun `extending one point at a time matches sampling each prefix whole`() {
        val points = wigglyPath(300)
        val cumulative = cumulativeMeters(points)
        val sampler = SlopeSampler(::terrainWithVoids)

        var incremental = requireNotNull(sampler.sample(points.take(1), cumulative.copyOf(1)))
        for (size in 2..points.size) {
            val prefix = points.take(size)
            val prefixCumulative = cumulative.copyOf(size)
            incremental = requireNotNull(sampler.extend(incremental, prefix, prefixCumulative))
            val whole = requireNotNull(sampler.sample(prefix, prefixCumulative))
            assertSameProfile("prefix of $size points", whole, incremental)
        }
        // Guard against the comparison passing vacuously on a path with no slope data.
        assertTrue(incremental.hasData)
        assertTrue("sampleCount was ${incremental.sampleCount}", incremental.sampleCount > 100)
    }

    @Test
    fun `extending in uneven chunks matches sampling the whole path`() {
        val points = wigglyPath(300)
        val cumulative = cumulativeMeters(points)
        val sampler = SlopeSampler(::terrainWithVoids)

        var incremental = requireNotNull(sampler.sample(emptyList(), DoubleArray(0)))
        for (size in intArrayOf(1, 2, 40, 41, 150, 299, 300)) {
            incremental = requireNotNull(sampler.extend(incremental, points.take(size), cumulative.copyOf(size)))
        }

        assertSameProfile("whole path", requireNotNull(sampler.sample(points, cumulative)), incremental)
    }

    @Test
    fun `extending looks elevation up only at the samples the appended points add`() {
        val points = wigglyPath(300)
        val cumulative = cumulativeMeters(points)
        var lookups = 0
        val sampler =
            SlopeSampler { lat, lon ->
                lookups++
                terrainWithVoids(lat, lon)
            }
        val headSize = 200
        val headSamples = expectedSampleCount(cumulative[headSize - 1])
        val allSamples = expectedSampleCount(cumulative.last())

        val head = requireNotNull(sampler.sample(points.take(headSize), cumulative.copyOf(headSize)))
        assertEquals(headSamples, lookups)

        lookups = 0
        sampler.extend(head, points, cumulative)
        assertEquals(allSamples - headSamples, lookups)
        assertTrue("the appended stretch must add samples for this test to mean anything", allSamples > headSamples)
    }

    @Test
    fun `extending by less than one sample spacing looks nothing up`() {
        // 0.0001 degrees of latitude is ~11 m: the third point adds 11 m to a 100 m path, which
        // reaches no new 30 m sample position.
        val head = listOf(LatLong(24.0, 121.0), LatLong(24.0009, 121.0))
        val grown = head + LatLong(24.001, 121.0)
        var lookups = 0
        val sampler =
            SlopeSampler { lat, _ ->
                lookups++
                (lat * 1000).toFloat()
            }
        val profile = requireNotNull(sampler.sample(head, cumulativeMeters(head)))

        lookups = 0
        val extended = requireNotNull(sampler.extend(profile, grown, cumulativeMeters(grown)))

        assertEquals(0, lookups)
        assertEquals(3, extended.pointCount)
        assertSameProfile("grown path", requireNotNull(sampler.sample(grown, cumulativeMeters(grown))), extended)
    }

    @Test
    fun `extending with no new points returns the previous profile`() {
        val points = wigglyPath(50)
        val cumulative = cumulativeMeters(points)
        val sampler = SlopeSampler(::terrainWithVoids)
        val profile = requireNotNull(sampler.sample(points, cumulative))

        assertSame(profile, sampler.extend(profile, points, cumulative))
    }

    @Test
    fun `extending from a profile of another path samples afresh`() {
        val other = wigglyPath(80)
        val points = straightNorth(lengthMeters = 900.0, pointCount = 40)
        val cumulative = cumulativeMeters(points)
        val sampler = SlopeSampler(::terrainWithVoids)
        // Covers more points than the new path has, and a different distance: not a prefix of it.
        val unrelated = requireNotNull(sampler.sample(other, cumulativeMeters(other)))

        val extended = requireNotNull(sampler.extend(unrelated, points, cumulative))

        assertSameProfile("fresh sample", requireNotNull(sampler.sample(points, cumulative)), extended)
    }

    @Test
    fun `no elevation at all gives no slope data`() {
        val points = wigglyPath(100)
        val profile = requireNotNull(SlopeSampler { _, _ -> null }.sample(points, cumulativeMeters(points)))

        assertFalse(profile.hasData)
        assertEquals(0, profile.sampleCount)
        assertNull(profile.slopeDegAt(0.0))
        assertNull(profile.slopeDegAt(500.0))
        assertEquals(100, profile.pointCount)
    }

    @Test
    fun `a path shorter than one sample spacing gives no slope data`() {
        // ~22 m: under the 30 m spacing.
        val points = listOf(LatLong(24.0, 121.0), LatLong(24.0002, 121.0))
        val profile = requireNotNull(SlopeSampler { _, _ -> 1000f }.sample(points, cumulativeMeters(points)))

        assertFalse(profile.hasData)
        assertNull(profile.slopeDegAt(10.0))
    }

    @Test
    fun `empty and single point paths give no slope data`() {
        val sampler = SlopeSampler { _, _ -> 1000f }

        val empty = requireNotNull(sampler.sample(emptyList(), DoubleArray(0)))
        assertEquals(0, empty.pointCount)
        assertFalse(empty.hasData)

        val single = requireNotNull(sampler.sample(listOf(LatLong(24.0, 121.0)), DoubleArray(1)))
        assertEquals(1, single.pointCount)
        assertFalse(single.hasData)
    }

    @Test
    fun `steady climb reads as a constant positive slope and its reverse as negative`() {
        val up = straightNorth(lengthMeters = 600.0, pointCount = 25)
        val cumulativeUp = cumulativeMeters(up)
        // 10 m of climb per 100 m travelled north: a 10% grade.
        val grade = 0.1
        val elevation: (Double, Double) -> Float? = { lat, _ -> (1000.0 + metersNorthOf(START_LAT, lat) * grade).toFloat() }
        val expectedDeg = Math.toDegrees(atan(grade))

        val climbing = requireNotNull(SlopeSampler(elevation).sample(up, cumulativeUp))
        assertTrue(climbing.hasData)
        assertEquals(expectedSampleCount(cumulativeUp.last()), climbing.sampleCount)
        for (k in 0 until climbing.sampleCount) {
            assertEquals("sample $k", expectedDeg, requireNotNull(climbing.slopeDegAt(k * SPACING)).toDouble(), 0.1)
        }

        val down = up.reversed()
        val descending = requireNotNull(SlopeSampler(elevation).sample(down, cumulativeMeters(down)))
        for (k in 0 until descending.sampleCount) {
            assertEquals("sample $k", -expectedDeg, requireNotNull(descending.slopeDegAt(k * SPACING)).toDouble(), 0.1)
        }
    }

    @Test
    fun `samples without elevation are held from the nearest valid sample`() {
        val points = straightNorth(lengthMeters = 600.0, pointCount = 25)
        val cumulative = cumulativeMeters(points)
        val grade = 0.1
        // Elevation exists only between 195 m and 405 m along the path: samples 7..13 (210..390 m).
        val elevation: (Double, Double) -> Float? = { lat, _ ->
            val meters = metersNorthOf(START_LAT, lat)
            if (meters in 195.0..405.0) (1000.0 + meters * grade).toFloat() else null
        }

        val profile = requireNotNull(SlopeSampler(elevation).sample(points, cumulative))

        assertTrue(profile.hasData)
        val climbDeg = Math.toDegrees(atan(grade))
        // Well inside the leading and trailing gaps every neighbour holds the same value: flat.
        assertEquals(0.0, requireNotNull(profile.slopeDegAt(2 * SPACING)).toDouble(), 1e-6)
        assertEquals(0.0, requireNotNull(profile.slopeDegAt(17 * SPACING)).toDouble(), 1e-6)
        // Inside the covered stretch the real grade shows.
        assertEquals(climbDeg, requireNotNull(profile.slopeDegAt(10 * SPACING)).toDouble(), 0.1)
        // At the first valid sample the backward neighbour is a held copy of it, halving the rise.
        val edgeDeg = Math.toDegrees(atan(grade / 2))
        assertEquals(edgeDeg, requireNotNull(profile.slopeDegAt(7 * SPACING)).toDouble(), 0.1)
    }

    @Test
    fun `slope lookup reads the nearest sample and clamps beyond both ends`() {
        val points = wigglyPath(120)
        val profile = requireNotNull(SlopeSampler(::terrainWithVoids).sample(points, cumulativeMeters(points)))
        val last = profile.sampleCount - 1

        assertEquals(profile.slopeDegAt(0.0), profile.slopeDegAt(-50.0))
        assertEquals(profile.slopeDegAt(last * SPACING), profile.slopeDegAt(1e9))
        // 3 * 30 = 90 m: anything within half a spacing of it rounds to sample 3.
        assertEquals(profile.slopeDegAt(90.0), profile.slopeDegAt(76.0))
        assertEquals(profile.slopeDegAt(90.0), profile.slopeDegAt(104.0))
    }

    @Test
    fun `sampling is abandoned once the caller is no longer active`() {
        val points = wigglyPath(300)
        val cumulative = cumulativeMeters(points)
        var lookups = 0
        val sampler =
            SlopeSampler { lat, lon ->
                lookups++
                terrainWithVoids(lat, lon)
            }

        val profile = sampler.sample(points, cumulative) { lookups < 5 }

        assertNull(profile)
        assertEquals(5, lookups)
        assertNotNull(sampler.sample(points, cumulative))
    }

    private fun assertSameProfile(
        label: String,
        expected: SlopeSampler.Profile,
        actual: SlopeSampler.Profile,
    ) {
        assertEquals("$label: pointCount", expected.pointCount, actual.pointCount)
        assertEquals("$label: totalMeters", expected.totalMeters, actual.totalMeters, 0.0)
        assertEquals("$label: hasData", expected.hasData, actual.hasData)
        assertEquals("$label: sampleCount", expected.sampleCount, actual.sampleCount)
        for (k in 0 until expected.sampleCount) {
            // Exact equality: both ways must reach the very same floats, not merely close ones.
            assertEquals("$label: slope of sample $k", expected.slopeDegAt(k * SPACING), actual.slopeDegAt(k * SPACING))
        }
    }

    private fun expectedSampleCount(totalMeters: Double): Int = (totalMeters / SPACING).toInt() + 1

    private fun cumulativeMeters(points: List<LatLong>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + LatLongUtils.sphericalDistance(points[i - 1], points[i])
        }
        return out
    }

    /** A deterministic meandering path whose steps range from about 1 m to about 55 m. */
    private fun wigglyPath(pointCount: Int): List<LatLong> {
        val points = ArrayList<LatLong>(pointCount)
        var lat = START_LAT
        var lon = 121.0
        for (i in 0 until pointCount) {
            points.add(LatLong(lat, lon))
            lat += ((i * 37) % 50 + 1) * 1e-5
            lon += ((i * 13) % 7 - 3) * 2e-5
        }
        return points
    }

    /** [pointCount] evenly spaced points heading due north from [START_LAT] over [lengthMeters]. */
    private fun straightNorth(
        lengthMeters: Double,
        pointCount: Int,
    ): List<LatLong> {
        val endLat = START_LAT + lengthMeters / METERS_PER_DEGREE_LAT
        return (0 until pointCount).map { i -> LatLong(START_LAT + (endLat - START_LAT) * i / (pointCount - 1), 121.0) }
    }

    private fun metersNorthOf(
        fromLat: Double,
        lat: Double,
    ): Double = LatLongUtils.sphericalDistance(LatLong(fromLat, 121.0), LatLong(lat, 121.0))

    /**
     * Rolling terrain with two holes: a band right at the start of [wigglyPath] (so its first
     * samples have no elevation, exercising the backward hold and the "no data yet" state of a
     * growing path) and a band further on (exercising the forward hold).
     */
    private fun terrainWithVoids(
        lat: Double,
        lon: Double,
    ): Float? {
        val north = lat - START_LAT
        if (north < 0.0015 || north in 0.02..0.024) return null
        return (1000.0 + 300.0 * sin(lat * 4000.0) + 200.0 * cos(lon * 3000.0)).toFloat()
    }

    private companion object {
        const val SPACING = SlopeScale.SAMPLE_SPACING_M
        const val START_LAT = 24.0

        // Metres per degree of latitude on the sphere mapsforge's sphericalDistance uses.
        const val METERS_PER_DEGREE_LAT = 111_319.49
    }
}
