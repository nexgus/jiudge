package io.github.nexgus.jiudge.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nexgus.jiudge.core.elevation.SlopeScale
import io.github.nexgus.jiudge.core.stats.TraceStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/**
 * Self-drawn elevation-profile chart (distance on X, DEM elevation on Y), interactive per
 * docs/stats.md "互動: 縮放 / 平移 / 十字游標":
 * - Two-finger pinch/pan zooms and pans the X axis (Y auto-fits the visible range); one finger never
 *   pans, only scrubs the crosshair (tap or drag) - see [ChartViewport].
 * - The crosshair anchors to a profile sample by distance so it survives zoom/pan; an info panel
 *   shows distance/elevation/slope/wall-time for the anchored sample.
 *
 * The actual drawing (grid, filled curve, slope-coloured line, optional title) lives in
 * [drawProfileChart] so this on-screen, interactive view and the offscreen PNG export
 * ([renderProfileChartBitmap]) share one code path - the export never draws the crosshair and always
 * renders the full extent, regardless of the current viewport here.
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

    val totalM = profile.last().distanceM
    var viewport by remember(profile) { mutableStateOf(ChartViewport.full(totalM)) }
    // Anchored crosshair sample index into [profile], or null while hidden. Anchoring by index (not
    // by pixel) is what makes the crosshair stick to its sample across zoom/pan (D3).
    var crosshairIndex by remember(profile) { mutableStateOf<Int?>(null) }

    val textMeasurer = rememberTextMeasurer()
    val colors = rememberChartColors()
    val density = LocalDensity.current

    Box(modifier = modifier) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(profile) {
                        val plotLeft = with(density) { PLOT_LEFT_PAD_DP.dp.toPx() }

                        fun scrubAt(x: Float) {
                            val plotRight = size.width - with(density) { PLOT_RIGHT_PAD_DP.dp.toPx() }
                            val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                            val fraction = ((x - plotLeft) / plotWidth).coerceIn(0f, 1f)
                            val targetM = viewport.startM + fraction * viewport.spanM
                            crosshairIndex = nearestSampleIndex(profile, targetM)
                        }
                        // Hand-rolled gesture loop rather than detectTransformGestures/detectDragGestures:
                        // D2/D3 require single-finger input to ONLY scrub the crosshair (never pan), while
                        // two-or-more fingers pinch-zoom and pan - a distinction the stock detectors don't
                        // make (detectTransformGestures treats a lone finger as a pan).
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            // The first finger scrubs immediately, but if the gesture turns out to
                            // be a pinch, the pre-gesture anchor is restored (D3: zoom/pan must not
                            // move an anchored crosshair).
                            val indexBeforeGesture = crosshairIndex
                            scrubAt(down.position.x)
                            var multiTouch = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.isEmpty()) break
                                if (pressed.size > 1) {
                                    if (!multiTouch) {
                                        multiTouch = true
                                        crosshairIndex = indexBeforeGesture
                                    }
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    if (viewport.spanM > 0.0 && (zoom != 1f || pan != Offset.Zero)) {
                                        val plotRight = size.width - with(density) { PLOT_RIGHT_PAD_DP.dp.toPx() }
                                        val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
                                        val focalM = viewport.startM + ((centroid.x - plotLeft) / plotWidth) * viewport.spanM
                                        val panM = (pan.x / plotWidth) * viewport.spanM
                                        viewport = viewport.applyGesture(zoom, panM, focalM, totalM)
                                    }
                                    event.changes.forEach { it.consume() }
                                } else if (!multiTouch) {
                                    val change = pressed.first()
                                    if (change.positionChanged()) scrubAt(change.position.x)
                                    change.consume()
                                }
                                // All pointers lifted - end the gesture.
                                if (event.changes.all { it.changedToUp() }) break
                            }
                        }
                    },
        ) {
            // `this` (the DrawScope) is the Density: the paddings must resolve with the same
            // density the gesture math above uses, or touch positions and drawn geometry disagree.
            val layout = ChartLayout.compute(size, viewport.startM, viewport.endM, profile, density = this)
            drawProfileChart(this, profile, layout, colors, textMeasurer, title = null)
            crosshairIndex?.let { idx ->
                val sample = profile.getOrNull(idx)
                if (sample != null && sample.distanceM in layout.minDistanceM..layout.maxDistanceM) {
                    drawCrosshair(this, sample, layout, colors)
                }
            }
        }

        crosshairIndex?.let { idx ->
            profile.getOrNull(idx)?.let { sample ->
                CrosshairInfoPanel(
                    sample = sample,
                    onClose = { crosshairIndex = null },
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
        }
    }
}

/** Horizontal padding reserved for the Y-axis labels, in dp - shared between gesture math and drawing. */
private const val PLOT_LEFT_PAD_DP = 44f
private const val PLOT_RIGHT_PAD_DP = 8f

/** Index of the profile sample whose distance is closest to [targetM]. */
private fun nearestSampleIndex(
    profile: List<TraceStats.ProfileSample>,
    targetM: Double,
): Int? {
    if (profile.isEmpty()) return null
    var bestIndex = 0
    var bestDelta = Double.MAX_VALUE
    for (i in profile.indices) {
        val delta = abs(profile[i].distanceM - targetM)
        if (delta < bestDelta) {
            bestDelta = delta
            bestIndex = i
        }
    }
    return bestIndex
}

/** Chart colours, resolved once from the current theme so both on-screen and offscreen draws agree. */
data class ChartColors(
    val axis: Color,
    val grid: Color,
    val fill: Color,
    val crosshair: Color,
    val background: Color,
)

@Composable
fun rememberChartColors(): ChartColors {
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val backgroundColor = MaterialTheme.colorScheme.surface
    return remember(axisColor, backgroundColor) {
        ChartColors(
            axis = axisColor,
            grid = axisColor.copy(alpha = 0.2f),
            fill = axisColor.copy(alpha = 0.18f),
            crosshair = axisColor,
            background = backgroundColor,
        )
    }
}

/**
 * Pixel-space layout for one chart draw: the visible distance/elevation range and the plot
 * rectangle within the canvas. Y auto-fits the elevation of the samples visible in
 * `[minDistanceM, maxDistanceM]` (docs/stats.md), expanding a degenerate range below 50 m so the
 * chart keeps a visible vertical extent.
 */
data class ChartLayout(
    val minDistanceM: Double,
    val maxDistanceM: Double,
    val minEle: Float,
    val maxEle: Float,
    val plotLeft: Float,
    val plotTop: Float,
    val plotRight: Float,
    val plotBottom: Float,
) {
    val plotWidth: Float get() = (plotRight - plotLeft).coerceAtLeast(1f)
    val plotHeight: Float get() = (plotBottom - plotTop).coerceAtLeast(1f)

    fun xForDistance(distanceM: Double): Float {
        val span = (maxDistanceM - minDistanceM).coerceAtLeast(1e-6)
        return plotLeft + ((distanceM - minDistanceM) / span).toFloat() * plotWidth
    }

    fun yForElevation(elevationM: Float): Float {
        val t = (elevationM - minEle) / (maxEle - minEle)
        return plotBottom - t * plotHeight
    }

    companion object {
        fun compute(
            canvasSize: Size,
            minDistanceM: Double,
            maxDistanceM: Double,
            profile: List<TraceStats.ProfileSample>,
            leftPadDp: Float = PLOT_LEFT_PAD_DP,
            rightPadDp: Float = PLOT_RIGHT_PAD_DP,
            bottomPadDp: Float = 24f,
            topPadDp: Float = 8f,
            density: Density,
        ): ChartLayout {
            val visible = profile.filter { it.distanceM in minDistanceM..maxDistanceM }
            val elevations = visible.mapNotNull { it.elevationM }.ifEmpty { profile.mapNotNull { it.elevationM } }
            var minEle = elevations.minOrNull() ?: 0f
            var maxEle = elevations.maxOrNull() ?: 0f
            if (maxEle - minEle < 50f) {
                val mid = (maxEle + minEle) / 2f
                minEle = mid - 25f
                maxEle = mid + 25f
            }
            val leftPad = with(density) { leftPadDp.dp.toPx() }
            val rightPad = with(density) { rightPadDp.dp.toPx() }
            val bottomPad = with(density) { bottomPadDp.dp.toPx() }
            val topPad = with(density) { topPadDp.dp.toPx() }
            return ChartLayout(
                minDistanceM = minDistanceM,
                maxDistanceM = max(maxDistanceM, minDistanceM + 1e-6),
                minEle = minEle,
                maxEle = maxEle,
                plotLeft = leftPad,
                plotTop = topPad,
                plotRight = canvasSize.width - rightPad,
                plotBottom = canvasSize.height - bottomPad,
            )
        }
    }
}

/**
 * Shared draw routine used by both the interactive on-screen [Canvas] and the offscreen PNG export
 * ([renderProfileChartBitmap]) - grid lines + axis labels, filled area under the curve, slope-coloured
 * polyline, and an optional [title] drawn at the top (used only by the export). Never draws the
 * crosshair - that is layered separately on-screen only (D3/D4).
 */
fun drawProfileChart(
    drawScope: DrawScope,
    profile: List<TraceStats.ProfileSample>,
    layout: ChartLayout,
    colors: ChartColors,
    textMeasurer: TextMeasurer,
    title: String?,
) {
    with(drawScope) {
        val labelStyle = TextStyle(color = colors.axis, fontSize = 11.sp)

        if (title != null) {
            val measured = textMeasurer.measure(title, style = TextStyle(color = colors.axis, fontSize = 18.sp))
            drawText(measured, topLeft = Offset((size.width - measured.size.width) / 2f, 8.dp.toPx()))
        }

        val maxDistanceKm = max((layout.maxDistanceM - layout.minDistanceM) / 1000.0, 0.001)
        val minDistanceKm = layout.minDistanceM / 1000.0

        // Grid lines + axis labels.
        val xStepKm = niceStep(maxDistanceKm)
        val decimals = decimalsForStep(xStepKm)
        var xKm = ceil(minDistanceKm / xStepKm) * xStepKm
        val maxKm = layout.maxDistanceM / 1000.0
        var lastLabel: String? = null
        while (xKm <= maxKm + 1e-9) {
            val x = layout.xForDistance(xKm * 1000.0)
            drawLine(colors.grid, Offset(x, layout.plotTop), Offset(x, layout.plotBottom), strokeWidth = 1.dp.toPx())
            val label = formatKmLabel(xKm, decimals)
            if (label != lastLabel) {
                val measured = textMeasurer.measure(label, style = labelStyle)
                drawText(
                    measured,
                    topLeft = Offset(x - measured.size.width / 2f, layout.plotBottom + 4.dp.toPx()),
                )
                lastLabel = label
            }
            xKm += xStepKm
        }

        val yStepM = niceStep((layout.maxEle - layout.minEle).toDouble())
        var yM = ceil(layout.minEle / yStepM) * yStepM
        while (yM <= layout.maxEle + 1e-6) {
            val y = layout.yForElevation(yM.toFloat())
            drawLine(colors.grid, Offset(layout.plotLeft, y), Offset(layout.plotRight, y), strokeWidth = 1.dp.toPx())
            val label = "${yM.toInt()} m"
            val measured = textMeasurer.measure(label, style = labelStyle)
            drawText(
                measured,
                topLeft = Offset(layout.plotLeft - measured.size.width - 4.dp.toPx(), y - measured.size.height / 2f),
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
                        Path().apply {
                            moveTo(layout.xForDistance(profile[runStart].distanceM), layout.plotBottom)
                            for (j in runStart..end) {
                                lineTo(layout.xForDistance(profile[j].distanceM), layout.yForElevation(profile[j].elevationM!!))
                            }
                            lineTo(layout.xForDistance(profile[end].distanceM), layout.plotBottom)
                            close()
                        }
                    drawPath(path, color = colors.fill)
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
                start = Offset(layout.xForDistance(a.distanceM), layout.yForElevation(ae)),
                end = Offset(layout.xForDistance(b.distanceM), layout.yForElevation(be)),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Draws the crosshair (vertical + horizontal line, dot) for [sample] on top of the chart. */
private fun drawCrosshair(
    drawScope: DrawScope,
    sample: TraceStats.ProfileSample,
    layout: ChartLayout,
    colors: ChartColors,
) {
    with(drawScope) {
        val x = layout.xForDistance(sample.distanceM)
        drawLine(colors.crosshair.copy(alpha = 0.6f), Offset(x, layout.plotTop), Offset(x, layout.plotBottom), strokeWidth = 1.dp.toPx())
        val elevation = sample.elevationM
        if (elevation != null) {
            val y = layout.yForElevation(elevation)
            drawLine(
                colors.crosshair.copy(alpha = 0.6f),
                Offset(layout.plotLeft, y),
                Offset(layout.plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(colors.crosshair, radius = 4.dp.toPx(), center = Offset(x, y))
        }
    }
}

/** Small rounded translucent panel showing 距離/海拔/坡度/時間 for the crosshair-anchored sample. */
@Composable
private fun CrosshairInfoPanel(
    sample: TraceStats.ProfileSample,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = crosshairInfoText(sample),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "關閉十字游標",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** Builds the "距離 ... 海拔 ... 坡度 ... [時間 ...]" line for the crosshair info panel (docs/stats.md). */
private fun crosshairInfoText(sample: TraceStats.ProfileSample): String {
    val distance = formatCrosshairDistance(sample.distanceM)
    val elevation = sample.elevationM?.let { "${it.roundToInt()} m" } ?: "-"
    val slope =
        sample.elevationM?.let {
            val percent = (tan(Math.toRadians(sample.slopeDeg.toDouble())) * 100).roundToInt()
            if (percent > 0) "+$percent%" else "$percent%"
        } ?: "-"
    val parts = mutableListOf("距離 $distance", "海拔 $elevation", "坡度 $slope")
    sample.epochMs?.let { epochMs ->
        parts += "時間 ${formatWallTime(epochMs)}"
    }
    return parts.joinToString("  ")
}

/** Distance for the crosshair panel: metres below 1 km, else kilometres to two decimals. */
private fun formatCrosshairDistance(meters: Double): String =
    if (meters >= 1000.0) {
        String.format(Locale.getDefault(), "%.2f 公里", meters / 1000.0)
    } else {
        String.format(Locale.getDefault(), "%.0f 公尺", meters)
    }

/** Local wall time HH:mm:ss from an epoch-millis timestamp. */
private fun formatWallTime(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMs))

/** Picks a "nice" (1/2/5 * 10^n) step so the axis gets roughly 3-7 grid lines over [range]. */
private fun niceStep(range: Double): Double {
    if (range <= 0.0) return 1.0
    val roughStep = range / 5.0
    val magnitude = 10.0.pow(floor(log10(roughStep)))
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

/** Decimal places needed so adjacent km-axis ticks at this step never render identical text. */
private fun decimalsForStep(stepKm: Double): Int =
    when {
        stepKm >= 1.0 -> 0
        stepKm >= 0.1 -> 1
        stepKm >= 0.01 -> 2
        else -> 3
    }

/** Formats a kilometre axis tick at [decimals] decimal places (0 renders as a bare integer). */
private fun formatKmLabel(
    km: Double,
    decimals: Int,
): String =
    if (decimals == 0) {
        "${km.roundToInt()} km"
    } else {
        String.format(Locale.getDefault(), "%.${decimals}f km", km)
    }

/**
 * Offscreen render of the full-extent chart (no crosshair, no zoom) at a fixed size for PNG export
 * (D4): opaque background using [colors].background, [title] drawn at the top, otherwise identical
 * drawing to the on-screen chart via [drawProfileChart].
 */
fun renderProfileChartBitmap(
    profile: List<TraceStats.ProfileSample>,
    colors: ChartColors,
    textMeasurer: TextMeasurer,
    title: String,
    density: Density,
    widthPx: Int = EXPORT_WIDTH_PX,
    heightPx: Int = EXPORT_HEIGHT_PX,
): ImageBitmap {
    val bitmap = ImageBitmap(widthPx, heightPx)
    val canvasDrawScope = CanvasDrawScope()
    val size = Size(widthPx.toFloat(), heightPx.toFloat())
    canvasDrawScope.draw(
        density = density,
        layoutDirection = LayoutDirection.Ltr,
        canvas = ComposeCanvas(bitmap),
        size = size,
    ) {
        drawRect(color = colors.background, size = size)
        val totalM = profile.lastOrNull()?.distanceM ?: 0.0
        val layout =
            ChartLayout.compute(
                canvasSize = size,
                minDistanceM = 0.0,
                maxDistanceM = totalM,
                profile = profile,
                topPadDp = 40f,
                density = density,
            )
        drawProfileChart(this, profile, layout, colors, textMeasurer, title = title)
    }
    return bitmap
}

const val EXPORT_WIDTH_PX = 2048
const val EXPORT_HEIGHT_PX = 1152
