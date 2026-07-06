package io.github.nexgus.jiudge.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nexgus.jiudge.core.elevation.SlopeScale
import io.github.nexgus.jiudge.core.stats.TraceStats
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Self-drawn elevation-profile chart (distance on X, DEM elevation on Y). Colours each polyline
 * segment by [SlopeScale.colorFor] so the chart always agrees with the map route line
 * ([io.github.nexgus.jiudge.feature.planning.PlannedRouteLayer]). Samples with a null elevation
 * break the line rather than plotting as zero (see [TraceStats.ProfileSample]).
 *
 * Falls back to a centered "no DEM data" message when the profile is empty or entirely null.
 */
@Composable
fun ElevationProfileChart(
    profile: List<TraceStats.ProfileSample>,
    modifier: Modifier = Modifier,
) {
    val hasData = profile.isNotEmpty() && profile.any { it.elevationM != null }
    if (!hasData) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "無 DEM 資料, 無法顯示高程剖面",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    val fillColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val labelStyle = remember(axisColor) { TextStyle(color = axisColor, fontSize = 11.sp) }

    Canvas(modifier = modifier.fillMaxSize()) {
        val maxDistanceM = profile.last().distanceM
        val elevations = profile.mapNotNull { it.elevationM }
        var minEle = elevations.min()
        var maxEle = elevations.max()
        // Expand a degenerate (flat or single-sample) elevation range so the chart still has a
        // visible vertical extent rather than collapsing to a single line.
        if (maxEle - minEle < 50f) {
            val mid = (maxEle + minEle) / 2f
            minEle = mid - 25f
            maxEle = mid + 25f
        }
        val maxDistanceKm = max(maxDistanceM / 1000.0, 0.001)

        // Reserve space for axis labels: left for elevation (Y), bottom for distance (X).
        val leftPad = 44.dp.toPx()
        val bottomPad = 24.dp.toPx()
        val topPad = 8.dp.toPx()
        val rightPad = 8.dp.toPx()
        val plotLeft = leftPad
        val plotTop = topPad
        val plotRight = size.width - rightPad
        val plotBottom = size.height - bottomPad
        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)

        fun xForDistance(distanceM: Double): Float = plotLeft + (distanceM / maxDistanceM).toFloat() * plotWidth

        fun yForElevation(elevationM: Float): Float {
            val t = (elevationM - minEle) / (maxEle - minEle)
            return plotBottom - t * plotHeight
        }

        // Grid lines + axis labels.
        val xStepKm = niceStep(maxDistanceKm)
        var xKm = 0.0
        while (xKm <= maxDistanceKm + 1e-6) {
            val x = xForDistance(xKm * 1000.0)
            drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
            val label = formatKmLabel(xKm)
            val measured = textMeasurer.measure(label, style = labelStyle)
            drawText(
                measured,
                topLeft = Offset(x - measured.size.width / 2f, plotBottom + 4.dp.toPx()),
            )
            xKm += xStepKm
        }

        val yStepM = niceStep((maxEle - minEle).toDouble())
        var yM = ceil(minEle / yStepM) * yStepM
        while (yM <= maxEle + 1e-6) {
            val y = yForElevation(yM.toFloat())
            drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1.dp.toPx())
            val label = "${yM.toInt()} m"
            val measured = textMeasurer.measure(label, style = labelStyle)
            drawText(
                measured,
                topLeft = Offset(plotLeft - measured.size.width - 4.dp.toPx(), y - measured.size.height / 2f),
            )
            yM += yStepM
        }

        // Filled area under the curve, drawn per contiguous (non-null) run so gaps stay unfilled.
        var runStart = -1
        for (i in profile.indices) {
            val has = profile[i].elevationM != null
            if (has && runStart == -1) {
                runStart = i
            }
            val runEnds = !has || i == profile.lastIndex
            if (runEnds && runStart != -1) {
                val end = if (has) i else i - 1
                if (end > runStart) {
                    val path =
                        androidx.compose.ui.graphics.Path().apply {
                            moveTo(xForDistance(profile[runStart].distanceM), plotBottom)
                            for (j in runStart..end) {
                                lineTo(xForDistance(profile[j].distanceM), yForElevation(profile[j].elevationM!!))
                            }
                            lineTo(xForDistance(profile[end].distanceM), plotBottom)
                            close()
                        }
                    drawPath(path, color = fillColor)
                }
                runStart = -1
            }
        }

        // Slope-coloured polyline, one segment per consecutive sample pair (gap on null elevation).
        val strokeWidth = 2.dp.toPx()
        for (i in 0 until profile.lastIndex) {
            val a = profile[i]
            val b = profile[i + 1]
            val ae = a.elevationM
            val be = b.elevationM
            if (ae == null || be == null) continue
            drawLine(
                color = Color(SlopeScale.colorFor(b.slopeDeg)),
                start = Offset(xForDistance(a.distanceM), yForElevation(ae)),
                end = Offset(xForDistance(b.distanceM), yForElevation(be)),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Picks a "nice" (1/2/5 * 10^n) step so the axis gets roughly 3-7 grid lines over [range]. */
private fun niceStep(range: Double): Double {
    if (range <= 0.0) return 1.0
    val roughStep = range / 5.0
    val magnitude = 10.0.pow(floor(kotlin.math.log10(roughStep)))
    val normalized = roughStep / magnitude
    val niceNormalized =
        when {
            normalized < 1.5 -> 1.0
            normalized < 3.0 -> 2.0
            normalized < 7.0 -> 5.0
            else -> 10.0
        }
    return niceNormalized * magnitude
}

/** Formats a kilometre axis tick, dropping the decimal when the step is a whole kilometre. */
private fun formatKmLabel(km: Double): String {
    val rounded = kotlin.math.round(km * 100) / 100
    return if (rounded == floor(rounded)) {
        "${rounded.toInt()} km"
    } else {
        String.format(java.util.Locale.getDefault(), "%.1f km", rounded)
    }
}
