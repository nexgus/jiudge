package io.github.nexgus.jiudge.feature.recording

import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Join
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * Draws a live recorded track as a series of red ">" chevrons laid back-to-back along the path - no
 * continuous line, no casing, just the directional marks at high density. Designed to read as a
 * "track" purely from the marks' density and direction, distinct from a planned route's continuous
 * slope-coloured ribbon (see [io.github.nexgus.jiudge.feature.planning.PlannedRouteLayer]).
 *
 * The chevron geometry (length, half-width, stroke, halo) is borrowed from the planned-route layer so
 * both share the same visual vocabulary; only the colour (red) and the on-screen spacing differ
 * (much tighter here so the chevrons read as a trail). Width follows the same zoom taper as the
 * planned-route line, so the recording overlay matches the planned overlay in scale at every zoom.
 *
 * State is pushed from the UI thread via [update]; [draw] runs on the render thread off one
 * immutable [snapshot]. The same discipline as the planned-route layer.
 */
class RecordedTrackLayer(
    private val density: Float,
) : Layer() {
    @Volatile
    private var snapshot: List<LatLong> = emptyList()

    private val factory = AndroidGraphicFactory.INSTANCE
    private val chevronHalo = strokeRound(CHEVRON_HALO_COLOR, 1f)
    private val chevronPaint = strokeRound(CHEVRON_COLOR, 1f)

    /** Replaces the rendered polyline; the chevron walker is rebuilt on the next draw. */
    fun update(points: List<LatLong>) {
        snapshot = points
        requestRedraw()
    }

    /** Removes the rendered polyline; the layer stays mounted, ready for the next update. */
    fun clear() {
        snapshot = emptyList()
        requestRedraw()
    }

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeftPoint: Point,
        rotation: Rotation,
    ) {
        val polyline = snapshot
        if (polyline.size < 2) return
        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
        val visualZoom = visualZoom(mapSize)

        fun screenX(lon: Double) = MercatorProjection.longitudeToPixelX(lon, mapSize) - topLeftPoint.x

        fun screenY(lat: Double) = MercatorProjection.latitudeToPixelY(lat, mapSize) - topLeftPoint.y

        val lineWidth = (LINE_WIDTH_DP * density * lineScaleForZoom(visualZoom)).coerceAtLeast(2f)
        drawDirectionChevrons(canvas, polyline, lineWidth, ::screenX, ::screenY)
    }

    /** Walks the projected track and lays a ">" chevron pointing along travel; sizes scale off [lineWidth]. */
    private fun drawDirectionChevrons(
        canvas: Canvas,
        polyline: List<LatLong>,
        lineWidth: Float,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        val headLen = (lineWidth * CHEVRON_LENGTH_RATIO).toDouble()
        val halfWidth = (lineWidth * CHEVRON_HALF_WIDTH_RATIO).toDouble()
        val spacingPx = (lineWidth * CHEVRON_SPACING_RATIO).toDouble()
        val chevronStroke = (lineWidth * CHEVRON_STROKE_RATIO).coerceAtLeast(1f)
        chevronPaint.setStrokeWidth(chevronStroke)
        chevronHalo.setStrokeWidth(chevronStroke + lineWidth * CHEVRON_HALO_EXTRA_RATIO)
        val margin = headLen + halfWidth
        val width = canvas.width
        val height = canvas.height

        var prevX = screenX(polyline[0].longitude)
        var prevY = screenY(polyline[0].latitude)
        var carry = spacingPx / 2 // first chevron half a step in
        for (i in 1 until polyline.size) {
            val curX = screenX(polyline[i].longitude)
            val curY = screenY(polyline[i].latitude)
            val dx = curX - prevX
            val dy = curY - prevY
            val segLen = hypot(dx, dy)
            if (segLen > 0.0) {
                val dirRad = atan2(dy, dx)
                var along = carry
                while (along <= segLen) {
                    val f = along / segLen
                    val px = prevX + dx * f
                    val py = prevY + dy * f
                    if (px >= -margin && px <= width + margin && py >= -margin && py <= height + margin) {
                        drawChevron(canvas, px, py, dirRad, headLen, halfWidth)
                    }
                    along += spacingPx
                }
                carry = along - segLen
            }
            prevX = curX
            prevY = curY
        }
    }

    /** An open ">" chevron centred on ([px], [py]), pointing along travel. */
    private fun drawChevron(
        canvas: Canvas,
        px: Double,
        py: Double,
        dirRad: Double,
        headLen: Double,
        halfWidth: Double,
    ) {
        val fx = cos(dirRad)
        val fy = sin(dirRad)
        val rx = -fy // right-hand perpendicular
        val ry = fx
        val tipX = px + fx * headLen / 2
        val tipY = py + fy * headLen / 2
        val backX = px - fx * headLen / 2
        val backY = py - fy * headLen / 2
        val path = factory.createPath()
        path.moveTo((backX - rx * halfWidth).toFloat(), (backY - ry * halfWidth).toFloat())
        path.lineTo(tipX.toFloat(), tipY.toFloat())
        path.lineTo((backX + rx * halfWidth).toFloat(), (backY + ry * halfWidth).toFloat())
        canvas.drawPath(path, chevronHalo)
        canvas.drawPath(path, chevronPaint)
    }

    /**
     * Track-line width scale: matches [io.github.nexgus.jiudge.feature.planning.PlannedRouteLayer] so
     * recorded and planned overlays read at the same physical scale at every zoom.
     */
    private fun lineScaleForZoom(visualZoom: Double): Float {
        val span = (LINE_FULL_ZOOM - LINE_TAPER_MIN_ZOOM).toDouble()
        val t = ((visualZoom - LINE_TAPER_MIN_ZOOM) / span).coerceIn(0.0, 1.0)
        return (LINE_MIN_SCALE + t * (1f - LINE_MIN_SCALE)).toFloat()
    }

    /** Visual zoom inferred from `mapSize`: `log2(mapSize / 256)`. */
    private fun visualZoom(mapSize: Long): Double = ln(mapSize.toDouble() / BASE_TILE_SIZE) / LOG_2

    private fun strokeRound(
        color: Int,
        widthDp: Float,
    ): Paint =
        factory.createPaint().apply {
            setColor(color)
            setStrokeWidth(widthDp * density)
            setStyle(Style.STROKE)
            setStrokeCap(Cap.ROUND)
            setStrokeJoin(Join.ROUND)
        }

    private companion object {
        // Red chevron with a faint white halo so it reads on both light and dark terrain.
        val CHEVRON_COLOR = 0xFFD32F2F.toInt()
        val CHEVRON_HALO_COLOR = 0x99FFFFFF.toInt()

        // Width basis shared with PlannedRouteLayer.LINE_WIDTH_DP so the two overlays read at the
        // same physical scale; the chevron is sized off this width via the ratios below.
        const val LINE_WIDTH_DP = 5f
        const val LINE_FULL_ZOOM = 14
        const val LINE_TAPER_MIN_ZOOM = 10
        const val LINE_MIN_SCALE = 0.5f

        // Chevron geometry: matches PlannedRouteLayer's chevron so the two overlays share a visual
        // vocabulary. Only the on-screen spacing differs - tighter here so density alone reads as a
        // trail (the recording overlay has no underlying continuous line).
        const val CHEVRON_LENGTH_RATIO = 1.0f
        const val CHEVRON_HALF_WIDTH_RATIO = 0.4f
        const val CHEVRON_STROKE_RATIO = 0.5f
        const val CHEVRON_HALO_EXTRA_RATIO = 0.35f

        // The whole point of this layer: chevrons nearly touch so the trail reads from density alone.
        // Tune this on-device; lower values pack the marks tighter at the cost of more draw work.
        const val CHEVRON_SPACING_RATIO = 1.2f

        const val BASE_TILE_SIZE = 256
        val LOG_2 = ln(2.0)
    }
}
