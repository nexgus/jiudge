package io.github.nexgus.jiudge.feature.map

import io.github.nexgus.jiudge.core.location.LocationFix
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the current position over the map, Google-Maps-style: a coloured dot, a translucent accuracy
 * circle (radius = the fix's 68% accuracy, so it tracks a fixed ground distance across zoom), and a
 * direction indicator. The colour encodes the fix source - green for a true GPS fix, blue for a
 * coarse WiFi/cell network fix - applied uniformly to the dot, the accuracy circle, and the cone.
 *
 * Direction prefers the compass facing beam: a soft gradient cone (approximated by stacked
 * translucent fans, dense near the dot and fading outward) whose half-spread equals the compass
 * heading error, so a well-calibrated compass draws a tight beam and a noisy one a wide fan. With no
 * compass it falls back to a movement arrow, and to nothing when standing still.
 *
 * [density] is screen pixels per dp, so marker sizes stay constant in physical size across screens.
 * State is pushed from the UI thread via [update]; [draw] runs on the render thread and reads a
 * single immutable [snapshot] reference, so the two never see a half-updated marker.
 */
class CurrentLocationLayer(
    private val density: Float,
    /** Invoked when the marker itself is long-pressed; null disables the gesture. */
    private val onMarkerLongPress: (() -> Unit)? = null,
) : Layer() {
    private enum class DirectionMode { CONE, ARROW, NONE }

    private data class Snapshot(
        val fix: LocationFix,
        val mode: DirectionMode,
        val directionDeg: Float,
        val coneHalfAngleDeg: Float,
        val showAccuracy: Boolean,
        val frozen: Boolean,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    // The marker's screen position from the most recent draw, used to hit-test long presses against
    // what is actually on screen, plus the projection inputs that produced it so a long-press point
    // (given as a LatLong) can be re-projected into the same pixel frame. Null until first drawn.
    @Volatile
    private var lastScreenX: Double? = null

    @Volatile
    private var lastScreenY: Double? = null

    @Volatile
    private var lastMapSize: Long = 0

    @Volatile
    private var lastTopLeftX: Double = 0.0

    @Volatile
    private var lastTopLeftY: Double = 0.0

    private val factory = AndroidGraphicFactory.INSTANCE

    // Colour is chosen per draw from the fix source, so the fills below are created blank and have
    // their colour set each frame; only the stale-grey and the white borders are fixed.
    private val dotFill = fill()
    private val dotStroke = stroke(0xFFFFFFFF.toInt(), 1.5f)
    private val frozenFill = solid(0xFF9E9E9E.toInt()) // grey: the fix is stale (location service off)
    private val frozenStroke = stroke(0xFFFFFFFF.toInt(), 1.5f)
    private val accuracyFill = fill()
    private val accuracyStroke = stroke(0xFFFFFFFF.toInt(), 1.5f)
    private val coneFill = fill()
    private val arrowFill = fill()
    private val arrowStroke = stroke(0xFFFFFFFF.toInt(), 2f)

    /**
     * Replaces the drawn marker. Resolves which direction indicator to show: the compass cone when
     * a heading is available, else the GPS movement arrow, else a plain dot. [headingAccuracyDeg] is
     * the compass error half-angle (the cone's half-spread); when null a default spread is used. Pass
     * a null [fix] to clear the marker (e.g. before a fix arrives).
     */
    fun update(
        fix: LocationFix?,
        headingDeg: Float?,
        headingAccuracyDeg: Float?,
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
                Snapshot(fix, DirectionMode.NONE, 0f, 0f, showAccuracy = false, frozen = true)
            } else if (hasCompass && headingDeg != null) {
                val half =
                    (headingAccuracyDeg ?: DEFAULT_CONE_HALF_ANGLE_DEG)
                        .coerceIn(MIN_CONE_HALF_ANGLE_DEG, MAX_CONE_HALF_ANGLE_DEG)
                Snapshot(fix, DirectionMode.CONE, headingDeg, half, showAccuracy, frozen = false)
            } else if (fix.bearingDeg != null) {
                Snapshot(fix, DirectionMode.ARROW, fix.bearingDeg, 0f, showAccuracy, frozen = false)
            } else {
                Snapshot(fix, DirectionMode.NONE, 0f, 0f, showAccuracy, frozen = false)
            }
        requestRedraw()
    }

    /**
     * Fires [onMarkerLongPress] when the press lands on the drawn marker. mapsforge passes the screen
     * point arguments as null (see the identify layer's note), so hit-testing goes through the
     * non-null [tapLatLong]: it is re-projected with the same map size / top-left recorded by [draw]
     * into the dot's pixel frame, then compared against the dot's last on-screen position. Params are
     * declared platform-nullable; a null tap point or an undrawn marker means "not handled".
     */
    override fun onLongPress(
        tapLatLong: LatLong?,
        layerXYPosition: Point?,
        tapXYPosition: Point?,
    ): Boolean {
        if (onMarkerLongPress == null || snapshot == null) return false
        val tap = tapLatLong ?: return false
        val x = lastScreenX ?: return false
        val y = lastScreenY ?: return false
        if (lastMapSize <= 0) return false
        val tapX = MercatorProjection.longitudeToPixelX(tap.longitude, lastMapSize) - lastTopLeftX
        val tapY = MercatorProjection.latitudeToPixelY(tap.latitude, lastMapSize) - lastTopLeftY
        val dx = tapX - x
        val dy = tapY - y
        val touchRadius = TOUCH_RADIUS_DP * density
        if (dx * dx + dy * dy > touchRadius * touchRadius) return false
        onMarkerLongPress.invoke()
        return true
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
        val baseRgb = if (fix.fromGps) GPS_RGB else NETWORK_RGB
        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
        val cx = MercatorProjection.longitudeToPixelX(fix.longitude, mapSize) - topLeftPoint.x
        val cy = MercatorProjection.latitudeToPixelY(fix.latitude, mapSize) - topLeftPoint.y
        lastScreenX = cx
        lastScreenY = cy
        lastMapSize = mapSize
        lastTopLeftX = topLeftPoint.x
        lastTopLeftY = topLeftPoint.y

        if (state.showAccuracy && fix.accuracyMeters != null) {
            val metresPerPixel = MercatorProjection.calculateGroundResolution(fix.latitude, mapSize)
            val radius = (fix.accuracyMeters / metresPerPixel).toInt()
            if (radius > 0) {
                accuracyFill.setColor((ACCURACY_FILL_ALPHA shl 24) or baseRgb)
                accuracyStroke.setColor((ACCURACY_STROKE_ALPHA shl 24) or baseRgb)
                canvas.drawCircle(cx.toInt(), cy.toInt(), radius, accuracyFill)
                canvas.drawCircle(cx.toInt(), cy.toInt(), radius, accuracyStroke)
            }
        }

        when (state.mode) {
            DirectionMode.CONE -> drawCone(canvas, cx, cy, state.directionDeg, state.coneHalfAngleDeg, baseRgb)
            DirectionMode.ARROW -> drawArrow(canvas, cx, cy, state.directionDeg, baseRgb)
            DirectionMode.NONE -> Unit
        }

        val dotRadius = (DOT_RADIUS_DP * density).toInt()
        val fill =
            if (state.frozen) {
                frozenFill
            } else {
                dotFill.also { it.setColor(0xFF000000.toInt() or baseRgb) }
            }
        val border = if (state.frozen) frozenStroke else dotStroke
        canvas.drawCircle(cx.toInt(), cy.toInt(), dotRadius, fill)
        canvas.drawCircle(cx.toInt(), cy.toInt(), dotRadius, border)
    }

    /**
     * A soft beam spreading from the dot toward [headingDeg]: where the phone (and user) is facing.
     * mapsforge's Paint has no gradient, so the fade is faked by stacking concentric fans of the same
     * translucent colour with decreasing radius - the near field gets painted by every layer and the
     * far edge by only the outermost, giving a dense-to-faint falloff. [halfAngleDeg] (the compass
     * error) sets the spread, so the beam tightens as the heading estimate sharpens.
     */
    private fun drawCone(
        canvas: Canvas,
        cx: Double,
        cy: Double,
        headingDeg: Float,
        halfAngleDeg: Float,
        baseRgb: Int,
    ) {
        coneFill.setColor((CONE_LAYER_ALPHA shl 24) or baseRgb)
        val maxRadius = CONE_RADIUS_DP * density
        val step = (2 * halfAngleDeg) / CONE_SEGMENTS
        var layer = CONE_LAYERS
        while (layer >= 1) {
            val radius = maxRadius * layer / CONE_LAYERS
            val path = factory.createPath()
            path.moveTo(cx.toFloat(), cy.toFloat())
            var angle = headingDeg - halfAngleDeg
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
            layer--
        }
    }

    /** A solid arrowhead pointing along the GPS movement [headingDeg], used when no compass exists. */
    private fun drawArrow(
        canvas: Canvas,
        cx: Double,
        cy: Double,
        headingDeg: Float,
        baseRgb: Int,
    ) {
        arrowFill.setColor(0xFF000000.toInt() or baseRgb)
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

    private fun fill(): Paint =
        factory.createPaint().apply {
            setStyle(Style.FILL)
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

        // Long-press hit radius around the dot centre; generous enough to forgive a fingertip's miss.
        const val TOUCH_RADIUS_DP = 24f

        const val CONE_RADIUS_DP = 64f
        const val CONE_SEGMENTS = 10

        // Stacked fans faking a gradient: more layers + lower per-layer alpha = smoother falloff.
        const val CONE_LAYERS = 8
        const val CONE_LAYER_ALPHA = 0x2E

        // The cone half-spread tracks the compass error, clamped so it stays a readable pointer.
        const val MIN_CONE_HALF_ANGLE_DEG = 15f
        const val MAX_CONE_HALF_ANGLE_DEG = 45f
        const val DEFAULT_CONE_HALF_ANGLE_DEG = 22f

        const val ARROW_LENGTH_DP = 26f
        const val ARROW_HALF_WIDTH_DP = 11f

        // Fix-source colours (RGB, no alpha): green = true GPS, blue = coarse WiFi/cell network fix.
        const val GPS_RGB = 0x34A853
        const val NETWORK_RGB = 0x2962FF

        const val ACCURACY_FILL_ALPHA = 0x22
        const val ACCURACY_STROKE_ALPHA = 0x55
    }
}
