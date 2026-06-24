package io.github.nexgus.jiudge.feature.map

import io.github.nexgus.jiudge.core.location.LocationFix
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the current GPS position over the map, OruxMaps-style: a blue dot, a translucent accuracy
 * circle (radius = the fix's 68% accuracy, so it grows/shrinks with zoom as it tracks a fixed
 * ground distance), and a direction indicator. Direction prefers the compass view cone; with no
 * compass it falls back to a movement arrow, and to nothing when standing still.
 *
 * [density] is screen pixels per dp, so marker sizes stay constant in physical size across screens.
 * State is pushed from the UI thread via [update]; [draw] runs on the render thread and reads a
 * single immutable [snapshot] reference, so the two never see a half-updated marker.
 */
class CurrentLocationLayer(
    private val density: Float,
) : Layer() {
    private enum class DirectionMode { CONE, ARROW, NONE }

    private data class Snapshot(
        val fix: LocationFix,
        val mode: DirectionMode,
        val directionDeg: Float,
        val showAccuracy: Boolean,
        val frozen: Boolean,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    private val factory = AndroidGraphicFactory.INSTANCE

    private val dotFill = solid(0xFF2962FF.toInt())
    private val dotStroke = stroke(0xFFFFFFFF.toInt(), 3f)
    private val frozenFill = solid(0xFF9E9E9E.toInt()) // grey: the fix is stale (location service off)
    private val frozenStroke = stroke(0xFFFFFFFF.toInt(), 3f)
    private val accuracyFill = solid(0x222962FF)
    private val accuracyStroke = stroke(0x552962FF, 1.5f)
    private val coneFill = solid(0xAAFFC107.toInt())
    private val arrowFill = solid(0xFFE53935.toInt())
    private val arrowStroke = stroke(0xFFFFFFFF.toInt(), 2f)

    /**
     * Replaces the drawn marker. Resolves which direction indicator to show: the compass cone when
     * a heading is available, else the GPS movement arrow, else a plain dot. Pass a null [fix] to
     * clear the marker (e.g. before a fix arrives).
     */
    fun update(
        fix: LocationFix?,
        headingDeg: Float?,
        hasCompass: Boolean,
        showAccuracy: Boolean,
        frozen: Boolean,
    ) {
        snapshot =
            if (fix == null) {
                null
            } else if (frozen) {
                // Location service off mid-session: keep the last point in place but grey, with no
                // direction or accuracy, to signal it is stale and no longer updating.
                Snapshot(fix, DirectionMode.NONE, 0f, showAccuracy = false, frozen = true)
            } else if (hasCompass && headingDeg != null) {
                Snapshot(fix, DirectionMode.CONE, headingDeg, showAccuracy, frozen = false)
            } else if (fix.bearingDeg != null) {
                Snapshot(fix, DirectionMode.ARROW, fix.bearingDeg, showAccuracy, frozen = false)
            } else {
                Snapshot(fix, DirectionMode.NONE, 0f, showAccuracy, frozen = false)
            }
        requestRedraw()
    }

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeftPoint: Point,
        rotation: Rotation,
    ) {
        val state = snapshot ?: return
        val fix = state.fix
        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
        val cx = MercatorProjection.longitudeToPixelX(fix.longitude, mapSize) - topLeftPoint.x
        val cy = MercatorProjection.latitudeToPixelY(fix.latitude, mapSize) - topLeftPoint.y

        if (state.showAccuracy && fix.accuracyMeters != null) {
            val metresPerPixel = MercatorProjection.calculateGroundResolution(fix.latitude, mapSize)
            val radius = (fix.accuracyMeters / metresPerPixel).toInt()
            if (radius > 0) {
                canvas.drawCircle(cx.toInt(), cy.toInt(), radius, accuracyFill)
                canvas.drawCircle(cx.toInt(), cy.toInt(), radius, accuracyStroke)
            }
        }

        when (state.mode) {
            DirectionMode.CONE -> drawCone(canvas, cx, cy, state.directionDeg)
            DirectionMode.ARROW -> drawArrow(canvas, cx, cy, state.directionDeg)
            DirectionMode.NONE -> Unit
        }

        val dotRadius = (DOT_RADIUS_DP * density).toInt()
        val fill = if (state.frozen) frozenFill else dotFill
        val border = if (state.frozen) frozenStroke else dotStroke
        canvas.drawCircle(cx.toInt(), cy.toInt(), dotRadius, fill)
        canvas.drawCircle(cx.toInt(), cy.toInt(), dotRadius, border)
    }

    /** A fan spreading from the dot toward [headingDeg]: where the phone (and user) is facing. */
    private fun drawCone(
        canvas: Canvas,
        cx: Double,
        cy: Double,
        headingDeg: Float,
    ) {
        val radius = CONE_RADIUS_DP * density
        val path = factory.createPath()
        path.moveTo(cx.toFloat(), cy.toFloat())
        var angle = headingDeg - CONE_HALF_ANGLE_DEG
        val step = (2 * CONE_HALF_ANGLE_DEG) / CONE_SEGMENTS
        var i = 0
        while (i <= CONE_SEGMENTS) {
            val rad = Math.toRadians(angle.toDouble())
            path.lineTo(
                (cx + radius * sin(rad)).toFloat(),
                (cy - radius * cos(rad)).toFloat(),
            )
            angle += step
            i++
        }
        path.close()
        canvas.drawPath(path, coneFill)
    }

    /** A red arrowhead pointing along the GPS movement [headingDeg], used when no compass exists. */
    private fun drawArrow(
        canvas: Canvas,
        cx: Double,
        cy: Double,
        headingDeg: Float,
    ) {
        val length = ARROW_LENGTH_DP * density
        val halfWidth = ARROW_HALF_WIDTH_DP * density
        val rad = Math.toRadians(headingDeg.toDouble())
        val fx = sin(rad)
        val fy = -cos(rad)
        // Perpendicular (right-hand) direction for the two base corners.
        val rx = -fy
        val ry = fx
        val tipX = cx + fx * length
        val tipY = cy + fy * length
        val leftX = cx - rx * halfWidth
        val leftY = cy - ry * halfWidth
        val rightX = cx + rx * halfWidth
        val rightY = cy + ry * halfWidth
        val path = factory.createPath()
        path.moveTo(tipX.toFloat(), tipY.toFloat())
        path.lineTo(leftX.toFloat(), leftY.toFloat())
        path.lineTo(rightX.toFloat(), rightY.toFloat())
        path.close()
        canvas.drawPath(path, arrowFill)
        canvas.drawPath(path, arrowStroke)
    }

    private fun solid(color: Int): Paint =
        factory.createPaint().apply {
            setColor(color)
            setStyle(Style.FILL)
        }

    private fun stroke(
        color: Int,
        widthDp: Float,
    ): Paint =
        factory.createPaint().apply {
            setColor(color)
            setStrokeWidth(widthDp * density)
            setStyle(Style.STROKE)
        }

    private companion object {
        const val DOT_RADIUS_DP = 7f
        const val CONE_RADIUS_DP = 48f
        const val CONE_HALF_ANGLE_DEG = 30f
        const val CONE_SEGMENTS = 6
        const val ARROW_LENGTH_DP = 26f
        const val ARROW_HALF_WIDTH_DP = 11f
    }
}
