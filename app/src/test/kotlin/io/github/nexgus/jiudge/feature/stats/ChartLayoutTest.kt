package io.github.nexgus.jiudge.feature.stats

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import io.github.nexgus.jiudge.core.stats.TraceStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the elevation-range rules documented on [ChartLayout.compute]: the degenerate-range floor
 * and the vertical-exaggeration cap. Density is fixed at 1 so dp values equal px and the default
 * paddings (left 44, right 8, top 8, bottom 24) yield a 948x468 plot on a 1000x500 canvas.
 */
class ChartLayoutTest {
    private val density = Density(1f)
    private val canvas = Size(1000f, 500f)

    private fun sample(
        distanceM: Double,
        elevationM: Float?,
    ) = TraceStats.ProfileSample(distanceM = distanceM, elevationM = elevationM, slopeDeg = 0f, epochMs = null)

    private fun compute(profile: List<TraceStats.ProfileSample>): ChartLayout =
        ChartLayout.compute(
            canvasSize = canvas,
            minDistanceM = 0.0,
            maxDistanceM = profile.last().distanceM,
            profile = profile,
            density = density,
        )

    @Test
    fun `long low-relief trace is clamped to the exaggeration cap`() {
        // 37.2 km with only 227 m of relief: unbounded, the exaggeration would be ~80x here.
        val layout = compute(listOf(sample(0.0, 10f), sample(18_600.0, 237f), sample(37_200.0, 10f)))

        assertEquals(ChartLayout.MAX_VERTICAL_EXAGGERATION, layout.verticalExaggeration, 0.01f)
        // The range grows upward only: the curve stays seated on its true minimum.
        assertEquals(10f, layout.minEle, 0.001f)
        assertTrue(layout.maxEle > 237f)
    }

    @Test
    fun `mountain trace below the cap keeps its tight elevation fit`() {
        val layout = compute(listOf(sample(0.0, 100f), sample(10_000.0, 900f)))

        assertEquals(100f, layout.minEle, 0.001f)
        assertEquals(900f, layout.maxEle, 0.001f)
        assertTrue(layout.verticalExaggeration < ChartLayout.MAX_VERTICAL_EXAGGERATION)
    }

    @Test
    fun `degenerate range expands to the 50 m floor around its midpoint`() {
        val layout = compute(listOf(sample(0.0, 100f), sample(1_000.0, 100f)))

        assertEquals(75f, layout.minEle, 0.001f)
        assertEquals(125f, layout.maxEle, 0.001f)
    }
}
