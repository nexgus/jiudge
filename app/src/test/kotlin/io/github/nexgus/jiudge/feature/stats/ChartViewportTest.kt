package io.github.nexgus.jiudge.feature.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the clamping/anchoring rules documented on [ChartViewport.applyGesture]. */
class ChartViewportTest {
    @Test
    fun `full extent matches total distance`() {
        val viewport = ChartViewport.full(5000.0)

        assertEquals(0.0, viewport.startM, 0.0)
        assertEquals(5000.0, viewport.endM, 0.0)
    }

    @Test
    fun `zooming in around focal point keeps focal fraction stable and shrinks span`() {
        val viewport = ChartViewport.full(10000.0)

        val zoomed = viewport.applyGesture(zoomFactor = 2f, panM = 0.0, focalM = 2000.0, totalM = 10000.0)

        assertEquals(5000.0, zoomed.spanM, 0.001)
        // Focal point was at fraction 0.2 of the original span; it should stay at fraction 0.2 of
        // the new span too.
        val focalFraction = (2000.0 - zoomed.startM) / zoomed.spanM
        assertEquals(0.2, focalFraction, 0.01)
    }

    @Test
    fun `zoom never shrinks span below minimum`() {
        val viewport = ChartViewport.full(10000.0)

        val zoomed = viewport.applyGesture(zoomFactor = 1000f, panM = 0.0, focalM = 5000.0, totalM = 10000.0)

        assertEquals(ChartViewport.MIN_SPAN_M, zoomed.spanM, 0.001)
    }

    @Test
    fun `zoom out never exceeds total extent`() {
        val viewport = ChartViewport(4000.0, 6000.0)

        val zoomedOut = viewport.applyGesture(zoomFactor = 0.01f, panM = 0.0, focalM = 5000.0, totalM = 10000.0)

        assertEquals(0.0, zoomedOut.startM, 0.001)
        assertEquals(10000.0, zoomedOut.endM, 0.001)
    }

    @Test
    fun `pan shifts viewport but clamps at the start of the trace`() {
        val viewport = ChartViewport(1000.0, 3000.0)

        // Pan far to the right (panM negative shifts view start forward per gesture convention used
        // here - see below for the opposite direction) then far to the left past 0.
        val pannedLeft = viewport.applyGesture(zoomFactor = 1f, panM = 5000.0, focalM = 2000.0, totalM = 10000.0)

        assertEquals(0.0, pannedLeft.startM, 0.001)
        assertEquals(2000.0, pannedLeft.spanM, 0.001)
    }

    @Test
    fun `pan clamps at the end of the trace`() {
        val viewport = ChartViewport(7000.0, 9000.0)

        val pannedRight = viewport.applyGesture(zoomFactor = 1f, panM = -5000.0, focalM = 8000.0, totalM = 10000.0)

        assertEquals(10000.0, pannedRight.endM, 0.001)
        assertEquals(2000.0, pannedRight.spanM, 0.001)
    }

    @Test
    fun `trace shorter than minimum span pins to full extent`() {
        val viewport = ChartViewport.full(150.0)

        val zoomed = viewport.applyGesture(zoomFactor = 5f, panM = 0.0, focalM = 75.0, totalM = 150.0)

        assertEquals(0.0, zoomed.startM, 0.001)
        assertEquals(150.0, zoomed.endM, 0.001)
    }

    @Test
    fun `zero total distance yields a degenerate viewport without crashing`() {
        val viewport = ChartViewport.full(0.0)

        val result = viewport.applyGesture(zoomFactor = 2f, panM = 10.0, focalM = 0.0, totalM = 0.0)

        assertEquals(0.0, result.startM, 0.0)
        assertEquals(0.0, result.endM, 0.0)
        assertTrue(result.spanM == 0.0)
    }
}
