package io.github.nexgus.jiudge.feature.stats

/**
 * Visible distance range `[startM, endM]` of the elevation-profile chart's X axis (docs/stats.md
 * "互動: 縮放 / 平移"). Pure math extracted out of [ElevationProfileChart] so pinch-zoom/pan clamping
 * can be unit-tested without a Compose runtime.
 *
 * Zoom is anchored at a focal distance (the pinch centroid): the same ground distance stays under
 * the fingers as the span shrinks/grows. Pan simply shifts both ends together. Both operations clamp
 * the result to `[0, totalM]` and to [MIN_SPAN_M] so the viewport can never invert or leave the trace.
 */
data class ChartViewport(
    val startM: Double,
    val endM: Double,
) {
    val spanM: Double get() = endM - startM

    companion object {
        /** Minimum visible span in metres - below this the chart is effectively not zoomable. */
        const val MIN_SPAN_M = 300.0

        /** Full-extent viewport for a trace of [totalM] metres. */
        fun full(totalM: Double): ChartViewport = ChartViewport(0.0, totalM.coerceAtLeast(0.0))
    }

    /**
     * Applies a pinch/pan gesture step: [zoomFactor] scales the span around [focalM] (>1 zooms in,
     * <1 zooms out), then [panM] shifts the result. Clamped to `[0, totalM]` and to at least
     * `min(MIN_SPAN_M, totalM)` wide (so a trace shorter than [MIN_SPAN_M] simply pins to full extent).
     */
    fun applyGesture(
        zoomFactor: Float,
        panM: Double,
        focalM: Double,
        totalM: Double,
    ): ChartViewport {
        if (totalM <= 0.0) return ChartViewport(0.0, 0.0)
        val minSpan = MIN_SPAN_M.coerceAtMost(totalM)
        val currentSpan = spanM
        val newSpan = (currentSpan / zoomFactor).coerceIn(minSpan, totalM)

        // Keep the focal distance at the same fractional position within the span, then apply the
        // pan on top, matching how detectTransformGestures reports centroid movement.
        val focalFraction = if (currentSpan > 0.0) ((focalM - startM) / currentSpan).coerceIn(0.0, 1.0) else 0.5
        var newStart = focalM - focalFraction * newSpan - panM
        var newEnd = newStart + newSpan

        if (newStart < 0.0) {
            newEnd -= newStart
            newStart = 0.0
        }
        if (newEnd > totalM) {
            newStart -= (newEnd - totalM)
            newEnd = totalM
        }
        newStart = newStart.coerceAtLeast(0.0)

        return ChartViewport(newStart, newEnd)
    }
}
