package io.github.nexgus.jiudge.feature.stats

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import io.github.nexgus.jiudge.core.stats.TraceStats
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the crosshair info-panel corner choice ([chooseInfoPanelCorner], docs/stats.md): top-left
 * by default, yielding to top-right, bottom-left, then bottom-right when the visible curve runs
 * under the panel, and falling back to the least-occluded corner when all four are occluded.
 *
 * Density is fixed at 1 so dp equal px; on the 1000x500 canvas the default paddings yield a plot of
 * x 44..992, y 8..476. With a 600x40 panel and an 8 px margin inside the plot rectangle the corner
 * rectangles (before the 4 px clearance inflation) are x 52..652 / x 384..984 crossed with
 * y 16..56 / y 428..468.
 */
class InfoPanelCornerTest {
    private val density = Density(1f)
    private val canvas = Size(1000f, 500f)
    private val panel = IntSize(600, 40)

    private fun sample(
        distanceM: Double,
        elevationM: Float,
    ) = TraceStats.ProfileSample(distanceM = distanceM, elevationM = elevationM, slopeDeg = 0f, epochMs = null)

    private fun choose(profile: List<TraceStats.ProfileSample>): Alignment {
        val layout =
            ChartLayout.compute(
                canvasSize = canvas,
                minDistanceM = 0.0,
                maxDistanceM = profile.last().distanceM,
                profile = profile,
                density = density,
            )
        return chooseInfoPanelCorner(
            profile = profile,
            layout = layout,
            panelSize = panel,
            marginPx = 8f,
            clearancePx = 4f,
        )
    }

    @Test
    fun `free top-left corner is kept as the default`() {
        // Gentle diagonal from the bottom-left up to mid-height: only the bottom-left is occluded.
        val corner = choose(listOf(sample(0.0, 0f), sample(10_000.0, 100f)))

        assertEquals(Alignment.TopStart, corner)
    }

    @Test
    fun `left peak pushes the panel to the top-right`() {
        // A near-start peak reaches the top of the plot inside the top-left rect; the right half of
        // the trace stays low, so the top-right corner is the first free candidate.
        val corner = choose(listOf(sample(0.0, 0f), sample(1_000.0, 300f), sample(2_000.0, 0f), sample(10_000.0, 50f)))

        assertEquals(Alignment.TopEnd, corner)
    }

    @Test
    fun `mid-peak trace starting at its minimum falls through to the bottom-right`() {
        // The regression shape from the field report: a mid-trace summit occludes both top corners
        // (the wide panel rects both reach the middle), and the trace starts at its overall minimum
        // so the curve sits on the plot floor inside the bottom-left rect. Only the bottom-right,
        // where the trace ends well above the floor, is free.
        val corner = choose(listOf(sample(0.0, 0f), sample(5_000.0, 300f), sample(10_000.0, 120f)))

        assertEquals(Alignment.BottomEnd, corner)
    }

    @Test
    fun `all corners occluded picks the one with the fewest crossing segments`() {
        // High start at 270 m - inside the top rects' elevation band (the 285 m maximum itself maps
        // to the plot's very top edge, above the inset panel) - giving 2 segments through top-left,
        // a floor-level dip spanning both bottom rects (3 through bottom-left, 2 through
        // bottom-right), and a single final rise through the top-right: the least-occluded corner.
        val corner =
            choose(
                listOf(
                    sample(0.0, 270f),
                    sample(1_000.0, 270f),
                    sample(3_000.0, 10f),
                    sample(4_000.0, 10f),
                    sample(10_000.0, 285f),
                ),
            )

        assertEquals(Alignment.TopEnd, corner)
    }
}
