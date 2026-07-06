package io.github.nexgus.jiudge.core.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mapsforge.core.model.LatLong

/**
 * Covers the derivation rules documented on [TraceStatsCalculator.compute]: Vincenty distance
 * summation, the 30 m-spaced elevation profile (with gap fill and central-difference slope),
 * hysteresis-gated ascent/descent, and the moving/stopped time split.
 */
class TraceStatsCalculatorTest {
    @Test
    fun `distance between two points close to theoretical value`() {
        // 0.01 degrees of latitude is ~1105-1113 m depending on latitude.
        val points = listOf(LatLong(24.0, 121.0), LatLong(24.01, 121.0))

        val stats = TraceStatsCalculator.compute(points, timesMs = null, elevationAt = null)

        assertTrue("distance was ${stats.distanceM}", stats.distanceM in 1105.0..1113.0)
    }

    @Test
    fun `monotonic climb over 3km gains about 100m with no descent`() {
        // Elevation is a linear function of latitude: climbs steadily from 1000m to 1100m over the
        // ~3km line below (0.027 degrees of latitude is close to 3000m).
        val start = 24.0
        val end = 24.027
        val points = listOf(LatLong(start, 121.0), LatLong(end, 121.0))
        val elevationAt: (Double, Double) -> Float? = { lat, _ ->
            val t = ((lat - start) / (end - start)).coerceIn(0.0, 1.0)
            (1000.0 + t * 100.0).toFloat()
        }

        val stats = TraceStatsCalculator.compute(points, timesMs = null, elevationAt = elevationAt)

        val ascent = requireNotNull(stats.ascentM)
        assertTrue("ascent was $ascent", ascent in 85.0..100.0)
        assertTrue("descent was ${stats.descentM}", (stats.descentM ?: 0.0) <= 0.001)
        val minElevation = requireNotNull(stats.minElevationM)
        val maxElevation = requireNotNull(stats.maxElevationM)
        assertTrue("min was $minElevation", minElevation in 999f..1005f)
        assertTrue("max was $maxElevation", maxElevation in 1095f..1101f)
    }

    @Test
    fun `flat trace with jitter under hysteresis threshold has zero ascent`() {
        val start = 24.0
        val end = 24.027
        val points = listOf(LatLong(start, 121.0), LatLong(end, 121.0))
        // Deterministic +/-3m jitter (well under the 10m hysteresis) around a flat 1000m base.
        val elevationAt: (Double, Double) -> Float? = { lat, _ ->
            val t = (lat - start) / (end - start)
            val phase = (t * 37.0) % (2 * Math.PI)
            (1000.0 + 3.0 * kotlin.math.sin(phase)).toFloat()
        }

        val stats = TraceStatsCalculator.compute(points, timesMs = null, elevationAt = elevationAt)

        assertEquals(0.0, stats.ascentM ?: -1.0, 0.0)
    }

    @Test
    fun `null elevation lookup yields null elevation stats and empty profile`() {
        val points = listOf(LatLong(24.0, 121.0), LatLong(24.027, 121.0))

        val stats = TraceStatsCalculator.compute(points, timesMs = null, elevationAt = null)

        assertNull(stats.ascentM)
        assertNull(stats.descentM)
        assertNull(stats.minElevationM)
        assertNull(stats.maxElevationM)
        assertTrue(stats.profile.isEmpty())
    }

    @Test
    fun `elevation lookup always returning null yields null-elevation profile samples`() {
        val points = listOf(LatLong(24.0, 121.0), LatLong(24.027, 121.0))

        val stats = TraceStatsCalculator.compute(points, timesMs = null) { _, _ -> null }

        assertTrue(stats.profile.isNotEmpty())
        assertTrue(stats.profile.all { it.elevationM == null })
        assertNull(stats.ascentM)
        assertNull(stats.descentM)
        assertNull(stats.minElevationM)
        assertNull(stats.maxElevationM)
    }

    @Test
    fun `time stats split moving and stopped spans correctly`() {
        // 4 points, 10s apart. Span 1: ~5m in 10s (0.5 m/s, moving). Span 2: ~1m in 10s (0.1 m/s,
        // stopped). Span 3: ~6m in 10s (0.6 m/s, moving).
        val base = 24.0
        val points =
            listOf(
                LatLong(base, 121.0),
                LatLong(base + 0.000045, 121.0), // ~5.0m
                LatLong(base + 0.000054, 121.0), // +~1.0m
                LatLong(base + 0.000108, 121.0), // +~6.0m
            )
        val t0 = 1_000_000L
        val timesMs = listOf(t0, t0 + 10_000, t0 + 20_000, t0 + 30_000)

        val stats = TraceStatsCalculator.compute(points, timesMs, elevationAt = null)

        val time = requireNotNull(stats.time)
        assertEquals(30_000L, time.totalMs)
        assertEquals(20_000L, time.movingMs)
        assertEquals(10_000L, time.stoppedMs)
        assertEquals(stats.distanceM / 30.0, time.avgSpeedMps, 0.01)
        val movingAvg = requireNotNull(time.movingAvgSpeedMps)
        assertEquals(stats.distanceM / 20.0, movingAvg, 0.01)
        val maxSpeed = requireNotNull(time.maxSpeedMps)
        assertTrue("maxSpeed was $maxSpeed", maxSpeed in 0.55..0.65)
    }

    @Test
    fun `null timesMs yields null time stats`() {
        val points = listOf(LatLong(24.0, 121.0), LatLong(24.01, 121.0))

        val stats = TraceStatsCalculator.compute(points, timesMs = null, elevationAt = null)

        assertNull(stats.time)
    }

    @Test
    fun `mismatched timesMs length is treated as absent`() {
        val points = listOf(LatLong(24.0, 121.0), LatLong(24.01, 121.0))

        val stats = TraceStatsCalculator.compute(points, timesMs = listOf(1L, 2L, 3L), elevationAt = null)

        assertNull(stats.time)
    }

    @Test
    fun `profile samples interpolate epochMs from point timestamps by cumulative distance`() {
        // 3km line, elevation irrelevant here (constant so profile is non-empty); 2 points 100s apart.
        val start = 24.0
        val end = 24.027
        val points = listOf(LatLong(start, 121.0), LatLong(end, 121.0))
        val t0 = 1_000_000L
        val t1 = t0 + 100_000L
        val timesMs = listOf(t0, t1)

        val stats = TraceStatsCalculator.compute(points, timesMs) { _, _ -> 1000f }

        assertTrue(stats.profile.size >= 2)
        // Exact ends.
        assertEquals(t0, stats.profile.first().epochMs)
        assertEquals(t1, stats.profile.last().epochMs)
        // A mid-segment sample's epoch should land strictly between the two endpoints and track its
        // fractional distance along the line.
        val mid = stats.profile[stats.profile.size / 2]
        val expectedFraction = mid.distanceM / stats.distanceM
        val expectedEpoch = t0 + (expectedFraction * (t1 - t0)).toLong()
        assertTrue(
            "epoch ${mid.epochMs} not close to expected $expectedEpoch",
            kotlin.math.abs((mid.epochMs ?: 0L) - expectedEpoch) <= 1000L,
        )
        assertTrue((mid.epochMs ?: 0L) in t0..t1)
    }

    @Test
    fun `profile samples have null epochMs when timesMs absent`() {
        val points = listOf(LatLong(24.0, 121.0), LatLong(24.027, 121.0))

        val stats = TraceStatsCalculator.compute(points, timesMs = null) { _, _ -> 1000f }

        assertTrue(stats.profile.isNotEmpty())
        assertTrue(stats.profile.all { it.epochMs == null })
    }

    @Test
    fun `profile samples have null epochMs when timesMs length mismatches points`() {
        val points = listOf(LatLong(24.0, 121.0), LatLong(24.027, 121.0))

        val stats = TraceStatsCalculator.compute(points, timesMs = listOf(1L, 2L, 3L)) { _, _ -> 1000f }

        assertTrue(stats.profile.isNotEmpty())
        assertTrue(stats.profile.all { it.epochMs == null })
    }

    @Test
    fun `fewer than 2 points yields zeroed empty stats`() {
        val empty = TraceStatsCalculator.compute(emptyList(), timesMs = null, elevationAt = null)
        val one = TraceStatsCalculator.compute(listOf(LatLong(24.0, 121.0)), timesMs = null, elevationAt = null)

        for (stats in listOf(empty, one)) {
            assertEquals(0.0, stats.distanceM, 0.0)
            assertTrue(stats.profile.isEmpty())
            assertNull(stats.ascentM)
            assertNull(stats.descentM)
            assertNull(stats.minElevationM)
            assertNull(stats.maxElevationM)
            assertNull(stats.time)
        }
    }
}
