package io.github.nexgus.jiudge.feature.planning

import io.github.nexgus.jiudge.core.elevation.DemElevation
import io.github.nexgus.jiudge.data.route.joinRouteSegments
import org.mapsforge.core.graphics.Align
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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a planned/loaded route over the map as a continuous slope-coloured line with a dark casing
 * edge and periodic direction chevrons (OruxMaps-style), plus OruxMaps-style kilometre balloons and
 * start/end waypoint dots on top.
 *
 * Design (agreed with the user, see docs/.tmp/oruxmaps-route-distance-direction.png):
 * - **Continuous line + casing + chevrons.** The route is one continuous round-jointed line that
 *   follows every vertex, so it never breaks however sharply the route bends (the earlier discrete
 *   arrow glyphs cut corners and looked broken on tight curves). A dark casing under it gives the
 *   track a clean edge. Forward direction is shown by ">" chevrons laid over the line at a constant
 *   on-screen spacing; each chevron is a single dark colour with a faint white halo so it reads on
 *   any track colour (including the dark reds/blues) without a jarring switch. The line width is
 *   constant above [LINE_FULL_ZOOM] and tapers when zoomed out, and the casing and chevrons are sized
 *   as fixed fractions of it so the whole ribbon stays in proportion.
 * - **Slope colour, 5 discrete classes** (no gradient, for legibility): steep-up red, moderate-up
 *   orange, gentle/flat light green, moderate-down cyan, steep-down blue. Thresholds 6 deg / 16 deg
 *   follow the common hiking grade bands (<=5 flat, 6-15 moderate, 16+ steep). Slope is the signed
 *   grade along travel, from DEM elevations sampled every [SAMPLE_SPACING_M] m (trace_spec.md §8) -
 *   the 30 m sampling matches the DEM resolution and smooths out per-point noise. No DEM -> light green.
 * - **Kilometre markers**: a dot + the kilometre number, thinned to a "nice" interval so they never
 *   crowd at low zoom.
 * - **Waypoints**: start green, end red, intermediate orange. For an O-shaped route whose start and
 *   end coincide, a single half-green/half-red dot replaces the overlapping pair.
 *
 * Everything except colour scales with zoom (markers shrink as the map zooms out so they do not dwarf
 * the route). [density] is screen pixels per dp, keeping physical sizes constant across screens.
 * State is pushed from the UI thread via [update]; [draw] runs on the render thread off one immutable
 * [snapshot] (same discipline as [io.github.nexgus.jiudge.feature.map.CurrentLocationLayer]).
 */
class PlannedRouteLayer(
    private val density: Float,
    private val dem: DemElevation?,
) : Layer() {
    private data class KmMarker(
        val point: LatLong,
        val km: Int,
        // Two points straddling [point] along the track, used to derive the local screen tangent so the
        // balloon can offset perpendicular to the track (and thus never sit on top of it).
        val tangentBack: LatLong,
        val tangentFwd: LatLong,
    )

    /** Which straight edge of the balloon the tail sprouts from (the edge facing the track anchor). */
    private enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

    private data class Snapshot(
        val polyline: List<LatLong>,
        // Cumulative ground distance (m) at each polyline vertex; size == polyline.size.
        val cumulativeMeters: DoubleArray,
        // Signed slope (deg) sampled every SAMPLE_SPACING_M along the track, or null when no DEM data.
        val slopeDeg: FloatArray?,
        val waypoints: List<LatLong>,
        val isLoop: Boolean,
        val kmMarkers: List<KmMarker>,
        // Total ground distance (m) over the whole track, shown as the end-of-route total balloon.
        val totalMeters: Double,
        // Local track tangent just before the end point, to offset the total balloon clear of the line.
        val totalTangentBack: LatLong,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    private val factory = AndroidGraphicFactory.INSTANCE

    // Stroke widths are set per draw() from the zoom-dependent line width; the initial 1f is a placeholder.
    private val casingPaint = strokeRound(TRACK_EDGE_COLOR, 1f)
    private val linePaint = strokeRound(GENTLE_COLOR, 1f)

    // A single dark chevron with a faint white halo under it, so it reads on dark track colours too.
    private val chevronHalo = strokeRound(CHEVRON_HALO_COLOR, 1f)
    private val chevronPaint = strokeRound(CHEVRON_COLOR, 1f)
    private val balloonFill = fill(BALLOON_FILL_COLOR)
    private val totalBalloonFill = fill(TOTAL_BALLOON_FILL_COLOR)
    private val balloonStroke = stroke(BALLOON_STROKE_COLOR, BALLOON_STROKE_DP)
    private val labelFill = text(LABEL_COLOR, Style.FILL, 0f)
    private val waypointHalo = stroke(HALO_COLOR, WAYPOINT_HALO_DP)
    private val viaHalo = stroke(HALO_COLOR, MID_HALO_DP)
    private val startFill = fill(START_COLOR)
    private val endFill = fill(END_COLOR)
    private val midFill = fill(MID_COLOR)

    /**
     * Replaces the drawn route. [waypoints] are the user-placed points; [segments] is the routed
     * geometry per gap between them. Pre-computes the joined track, its cumulative distances, the
     * kilometre markers, and the DEM slope profile off the render thread. Pass empty lists to clear.
     */
    fun update(
        waypoints: List<LatLong>,
        segments: List<List<LatLong>>,
    ) {
        val polyline = joinRouteSegments(segments)
        val cumulative = cumulativeMeters(polyline)
        val total = cumulative.lastOrNull() ?: 0.0
        snapshot =
            Snapshot(
                polyline = polyline,
                cumulativeMeters = cumulative,
                slopeDeg = sampleSlopes(polyline, cumulative, total),
                waypoints = waypoints,
                isLoop = waypoints.size >= 2 && distanceMeters(waypoints.first(), waypoints.last()) <= LOOP_JOIN_METERS,
                kmMarkers = computeKmMarkers(polyline, cumulative, total),
                totalMeters = total,
                totalTangentBack =
                    if (polyline.size >= 2) {
                        interpolateAlong(polyline, cumulative, max(0.0, total - TANGENT_EPS_M))
                    } else {
                        polyline.firstOrNull() ?: LatLong(0.0, 0.0)
                    },
            )
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
        if (state.polyline.isEmpty() && state.waypoints.isEmpty()) return
        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)

        fun screenX(lon: Double) = MercatorProjection.longitudeToPixelX(lon, mapSize) - topLeftPoint.x

        fun screenY(lat: Double) = MercatorProjection.latitudeToPixelY(lat, mapSize) - topLeftPoint.y

        val scale = scaleForZoom(zoomLevel)
        // The line and its chevrons share one width that is constant above LINE_FULL_ZOOM and tapers
        // when zoomed out (so the line does not blanket the map); the chevrons size off this width.
        val lineWidth = (LINE_WIDTH_DP * density * lineScaleForZoom(zoomLevel)).coerceAtLeast(2f)
        drawTrackLine(canvas, state, lineWidth, ::screenX, ::screenY)
        drawDirectionChevrons(canvas, state, lineWidth, ::screenX, ::screenY)
        // Via points sit *under* the km balloons (a reference label must not be hidden by a minor via
        // dot), but the start/end markers stay on top - they are navigationally important.
        drawViaWaypoints(canvas, state, scale, zoomLevel, ::screenX, ::screenY)
        drawKmMarkers(canvas, state.kmMarkers, zoomLevel, mapSize, boundingBox, scale, ::screenX, ::screenY)
        drawEndpointWaypoints(canvas, state, scale, ::screenX, ::screenY)
        drawTotalMarker(canvas, state, zoomLevel, scale, ::screenX, ::screenY)
    }

    /**
     * The end-of-route total-distance balloon, anchored at the final track point and tinted differently
     * from the intermediate kilometre balloons so the total reads as distinct. Always shown (never
     * thinned like the km markers), but still hidden below [LABEL_MIN_ZOOM] where balloons crowd.
     */
    private fun drawTotalMarker(
        canvas: Canvas,
        state: Snapshot,
        zoomLevel: Byte,
        scale: Float,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        if (state.polyline.size < 2 || zoomLevel < LABEL_MIN_ZOOM || state.totalMeters < 1.0) return
        val end = state.polyline.last()
        val anchorX = screenX(end.longitude)
        val anchorY = screenY(end.latitude)
        val tangentX = anchorX - screenX(state.totalTangentBack.longitude)
        val tangentY = anchorY - screenY(state.totalTangentBack.latitude)
        labelFill.setTextSize(LABEL_TEXT_DP * density * scale)
        drawBalloon(canvas, anchorX, anchorY, tangentX, tangentY, formatKm(state.totalMeters / 1000.0), scale, totalBalloonFill)
    }

    /**
     * Draws the route as a continuous line: first a dark casing (a wider line under everything, so the
     * track reads with a dark edge), then each polyline segment stroked in its slope colour on top.
     * Round caps/joins make segments merge with no gap, so the line stays solid however sharply it
     * bends. The casing edge is a fixed fraction of [lineWidth], so it scales with zoom in proportion.
     */
    private fun drawTrackLine(
        canvas: Canvas,
        state: Snapshot,
        lineWidth: Float,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        val polyline = state.polyline
        if (polyline.size < 2) return

        // Pass 1: dark casing - one continuous wider line, giving the whole track a dark edge.
        casingPaint.setStrokeWidth(lineWidth + 2 * lineWidth * TRACK_EDGE_RATIO)
        val casing = factory.createPath()
        casing.moveTo(screenX(polyline[0].longitude).toFloat(), screenY(polyline[0].latitude).toFloat())
        for (i in 1 until polyline.size) {
            casing.lineTo(screenX(polyline[i].longitude).toFloat(), screenY(polyline[i].latitude).toFloat())
        }
        canvas.drawPath(casing, casingPaint)

        // Pass 2: slope-coloured line on top, one segment at a time.
        linePaint.setStrokeWidth(lineWidth)
        var prevX = screenX(polyline[0].longitude).toInt()
        var prevY = screenY(polyline[0].latitude).toInt()
        for (i in 1 until polyline.size) {
            val curX = screenX(polyline[i].longitude).toInt()
            val curY = screenY(polyline[i].latitude).toInt()
            val midGround = (state.cumulativeMeters[i - 1] + state.cumulativeMeters[i]) / 2.0
            linePaint.setColor(slopeColor(state.slopeDeg, midGround))
            canvas.drawLine(prevX, prevY, curX, curY, linePaint)
            prevX = curX
            prevY = curY
        }
    }

    /** Walks the projected track and lays a ">" chevron for direction; all sizes scale off [lineWidth]. */
    private fun drawDirectionChevrons(
        canvas: Canvas,
        state: Snapshot,
        lineWidth: Float,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        val polyline = state.polyline
        if (polyline.size < 2) return
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
        var carry = spacingPx / 2 // first chevron half a step in, clear of the start dot
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

    /** An open ">" chevron centred on ([px], [py]), opening backward, pointing along travel. */
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

    private fun drawKmMarkers(
        canvas: Canvas,
        markers: List<KmMarker>,
        zoomLevel: Byte,
        mapSize: Long,
        boundingBox: BoundingBox,
        scale: Float,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        // Below LABEL_MIN_ZOOM the balloons would crowd, so drop the kilometre markers entirely there.
        if (markers.isEmpty() || zoomLevel < LABEL_MIN_ZOOM) return
        // Keep only every Nth marker so on-screen spacing stays >= MARKER_MIN_SPACING_DP.
        val centerLat = (boundingBox.minLatitude + boundingBox.maxLatitude) / 2.0
        val metresPerPixel = MercatorProjection.calculateGroundResolution(centerLat, mapSize)
        val interval = niceKmInterval(MARKER_MIN_SPACING_DP * density * metresPerPixel / 1000.0)
        labelFill.setTextSize(LABEL_TEXT_DP * density * scale)
        for (marker in markers) {
            if (marker.km % interval != 0) continue
            val anchorX = screenX(marker.point.longitude)
            val anchorY = screenY(marker.point.latitude)
            // Local track direction in screen space (covers map rotation, since both ends are projected).
            val tangentX = screenX(marker.tangentFwd.longitude) - screenX(marker.tangentBack.longitude)
            val tangentY = screenY(marker.tangentFwd.latitude) - screenY(marker.tangentBack.latitude)
            drawBalloon(canvas, anchorX, anchorY, tangentX, tangentY, "${marker.km}", scale)
        }
    }

    /**
     * A rounded label balloon holding [text], with a tail tip pinned to the track point
     * ([anchorX], [anchorY]). The bubble body is pushed off the track *perpendicular* to the local
     * track direction ([tangentX], [tangentY] in screen space), so it never blankets the line - a
     * fixed "straight up" offset would lie right along a vertical track. Of the two perpendiculars the
     * one pointing more towards screen-up wins, so labels still tend to float above their anchor; the
     * tail then sprouts from whichever edge faces back towards the track.
     */
    private fun drawBalloon(
        canvas: Canvas,
        anchorX: Double,
        anchorY: Double,
        tangentX: Double,
        tangentY: Double,
        text: String,
        scale: Float,
        fillPaint: Paint = balloonFill,
    ) {
        val pad = BALLOON_PADDING_DP * density * scale
        val radius = (BALLOON_RADIUS_DP * density * scale).toDouble()
        val tail = (BALLOON_TAIL_DP * density * scale).toDouble()
        val tailHalf = (BALLOON_TAIL_HALF_DP * density * scale).toDouble()
        val bubbleHeight = labelFill.getTextHeight(text) + 2 * pad
        val halfWidth = max(labelFill.getTextWidth(text) / 2.0 + pad, radius + tailHalf + 1.0)

        // Unit perpendicular to the track, preferring the one that points upward on screen (y < 0).
        val len = hypot(tangentX, tangentY)
        val tnx = if (len > 0.0) tangentX / len else 1.0
        val tny = if (len > 0.0) tangentY / len else 0.0
        var offsetX = -tny
        var offsetY = tnx
        if (offsetY > 0.0) {
            offsetX = -offsetX
            offsetY = -offsetY
        }

        // Push the bubble centre out far enough that its near edge clears the track by [tail]; the
        // half-extent towards the anchor is the vertical half-height or the horizontal half-width,
        // whichever axis the offset runs along.
        val halfExtent = if (abs(offsetY) >= abs(offsetX)) bubbleHeight / 2.0 else halfWidth
        val centerDist = tail + halfExtent
        val cx = anchorX + offsetX * centerDist
        val cy = anchorY + offsetY * centerDist
        val left = cx - halfWidth
        val right = cx + halfWidth
        val top = cy - bubbleHeight / 2.0
        val bottom = cy + bubbleHeight / 2.0

        // The tail leaves the edge facing the anchor, i.e. along the reverse of the offset direction.
        val edge = tailEdge(-offsetX, -offsetY)
        val path = buildBalloonPath(anchorX, anchorY, top, bottom, left, right, radius, tailHalf, edge)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, balloonStroke)
        // Baseline that visually centres the digits within the bubble.
        val baseline = (cy + labelFill.getTextHeight(text) * 0.34).toInt()
        canvas.drawText(text, cx.toInt(), baseline, labelFill)
    }

    /** The balloon edge whose outward normal best matches direction ([dx], [dy]) from bubble to anchor. */
    private fun tailEdge(
        dx: Double,
        dy: Double,
    ): Edge =
        if (abs(dx) >= abs(dy)) {
            if (dx >= 0) Edge.RIGHT else Edge.LEFT
        } else {
            if (dy >= 0) Edge.BOTTOM else Edge.TOP
        }

    /**
     * Rounded-rectangle outline walked clockwise from the top-left corner, with the triangular tail
     * spliced into whichever [edge] faces the track. The tail tip lands on ([tipX], [tipY]) and its
     * base ([tailHalf] either side) is clamped to the straight part of that edge, between the corner
     * arcs, so it never overruns a rounded corner.
     */
    private fun buildBalloonPath(
        tipX: Double,
        tipY: Double,
        top: Double,
        bottom: Double,
        left: Double,
        right: Double,
        r: Double,
        tailHalf: Double,
        edge: Edge,
    ) = factory.createPath().apply {
        moveTo((left + r).toFloat(), top.toFloat())
        if (edge == Edge.TOP) {
            val bx = tipX.coerceIn(left + r + tailHalf, right - r - tailHalf)
            lineTo((bx - tailHalf).toFloat(), top.toFloat())
            lineTo(tipX.toFloat(), tipY.toFloat())
            lineTo((bx + tailHalf).toFloat(), top.toFloat())
        }
        lineTo((right - r).toFloat(), top.toFloat())
        appendArc(right - r, top + r, r, -Math.PI / 2, 0.0)
        if (edge == Edge.RIGHT) {
            val by = tipY.coerceIn(top + r + tailHalf, bottom - r - tailHalf)
            lineTo(right.toFloat(), (by - tailHalf).toFloat())
            lineTo(tipX.toFloat(), tipY.toFloat())
            lineTo(right.toFloat(), (by + tailHalf).toFloat())
        }
        lineTo(right.toFloat(), (bottom - r).toFloat())
        appendArc(right - r, bottom - r, r, 0.0, Math.PI / 2)
        if (edge == Edge.BOTTOM) {
            val bx = tipX.coerceIn(left + r + tailHalf, right - r - tailHalf)
            lineTo((bx + tailHalf).toFloat(), bottom.toFloat())
            lineTo(tipX.toFloat(), tipY.toFloat()) // tail tip on the route point
            lineTo((bx - tailHalf).toFloat(), bottom.toFloat())
        }
        lineTo((left + r).toFloat(), bottom.toFloat())
        appendArc(left + r, bottom - r, r, Math.PI / 2, Math.PI)
        if (edge == Edge.LEFT) {
            val by = tipY.coerceIn(top + r + tailHalf, bottom - r - tailHalf)
            lineTo(left.toFloat(), (by + tailHalf).toFloat())
            lineTo(tipX.toFloat(), tipY.toFloat())
            lineTo(left.toFloat(), (by - tailHalf).toFloat())
        }
        lineTo(left.toFloat(), (top + r).toFloat())
        appendArc(left + r, top + r, r, Math.PI, Math.PI * 1.5)
        close()
    }

    /** Appends a quarter-ish arc (sampled) to this path, sweeping [from]..[to] around ([cx], [cy]). */
    private fun org.mapsforge.core.graphics.Path.appendArc(
        cx: Double,
        cy: Double,
        r: Double,
        from: Double,
        to: Double,
    ) {
        val steps = 4
        for (i in 1..steps) {
            val a = from + (to - from) * i / steps
            lineTo((cx + r * cos(a)).toFloat(), (cy + r * sin(a)).toFloat())
        }
    }

    /**
     * Intermediate via-points (everything between the first and last placed waypoint). Drawn small and
     * with a thin halo so dense runs do not blob, and *under* the km balloons (see [draw]) so a via dot
     * never hides a reference label. Hidden when zoomed out, where even thin halos would merge.
     */
    private fun drawViaWaypoints(
        canvas: Canvas,
        state: Snapshot,
        scale: Float,
        zoomLevel: Byte,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        val waypoints = state.waypoints
        if (waypoints.size <= 2 || zoomLevel < MID_WAYPOINT_MIN_ZOOM) return
        val midRadius = (MID_RADIUS_DP * density * scale).toInt().coerceAtLeast(2)
        for (i in 1 until waypoints.lastIndex) {
            val cx = screenX(waypoints[i].longitude).toInt()
            val cy = screenY(waypoints[i].latitude).toInt()
            canvas.drawCircle(cx, cy, midRadius, midFill)
            canvas.drawCircle(cx, cy, midRadius, viaHalo)
        }
    }

    /** Start (green) / end (red) markers - drawn on top of everything, as they anchor the route. */
    private fun drawEndpointWaypoints(
        canvas: Canvas,
        state: Snapshot,
        scale: Float,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        val waypoints = state.waypoints
        if (waypoints.isEmpty()) return
        val endRadius = (WAYPOINT_RADIUS_DP * density * scale).toInt().coerceAtLeast(3)

        val start = waypoints.first()
        val sx = screenX(start.longitude).toInt()
        val sy = screenY(start.latitude).toInt()
        if (state.isLoop) {
            // Start == end: one dot, left half green (start) / right half red (end).
            drawHalfDisc(canvas, sx, sy, endRadius, left = true, paint = startFill)
            drawHalfDisc(canvas, sx, sy, endRadius, left = false, paint = endFill)
            canvas.drawCircle(sx, sy, endRadius, waypointHalo)
        } else {
            canvas.drawCircle(sx, sy, endRadius, startFill)
            canvas.drawCircle(sx, sy, endRadius, waypointHalo)
            if (waypoints.size >= 2) {
                val end = waypoints.last()
                val ex = screenX(end.longitude).toInt()
                val ey = screenY(end.latitude).toInt()
                canvas.drawCircle(ex, ey, endRadius, endFill)
                canvas.drawCircle(ex, ey, endRadius, waypointHalo)
            }
        }
    }

    /** Fills one half (left or right of the vertical diameter) of a circle - the O-route start/end dot. */
    private fun drawHalfDisc(
        canvas: Canvas,
        cx: Int,
        cy: Int,
        radius: Int,
        left: Boolean,
        paint: Paint,
    ) {
        val path = factory.createPath()
        val steps = 16
        for (i in 0..steps) {
            val theta = Math.PI * i / steps // 0..PI, top -> bottom
            val r = radius.toDouble()
            val x = if (left) cx - r * sin(theta) else cx + r * sin(theta)
            val y = cy - r * cos(theta)
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close() // closes bottom -> top along the diameter, filling the half disc
        canvas.drawPath(path, paint)
    }

    /** Maps signed slope (deg, + uphill) at ground distance [meters] to one of the five class colours. */
    private fun slopeColor(
        slopeDeg: FloatArray?,
        meters: Double,
    ): Int {
        if (slopeDeg == null || slopeDeg.isEmpty()) return GENTLE_COLOR
        val idx = (meters / SAMPLE_SPACING_M).roundToInt().coerceIn(0, slopeDeg.size - 1)
        val s = slopeDeg[idx]
        return when {
            s >= STEEP_DEG -> STEEP_UP_COLOR
            s >= MODERATE_DEG -> MODERATE_UP_COLOR
            s > -MODERATE_DEG -> GENTLE_COLOR
            s > -STEEP_DEG -> MODERATE_DOWN_COLOR
            else -> STEEP_DOWN_COLOR
        }
    }

    /** Per-vertex cumulative ground distance in metres. */
    private fun cumulativeMeters(polyline: List<LatLong>): DoubleArray {
        val out = DoubleArray(polyline.size)
        for (i in 1 until polyline.size) {
            out[i] = out[i - 1] + distanceMeters(polyline[i - 1], polyline[i])
        }
        return out
    }

    /**
     * Samples DEM elevation every [SAMPLE_SPACING_M] along the track and returns the signed slope
     * (deg) at each sample via central difference. Null when there is no DEM or the route is too short;
     * gaps in DEM coverage are held from the nearest valid sample so one void does not blank the route.
     */
    private fun sampleSlopes(
        polyline: List<LatLong>,
        cumulative: DoubleArray,
        total: Double,
    ): FloatArray? {
        val source = dem ?: return null
        if (polyline.size < 2 || total < SAMPLE_SPACING_M) return null
        val n = (total / SAMPLE_SPACING_M).toInt()
        val elevations = arrayOfNulls<Float>(n + 1)
        var anyValid = false
        for (k in 0..n) {
            val point = interpolateAlong(polyline, cumulative, min(k * SAMPLE_SPACING_M, total))
            val e = source.elevationAt(point.latitude, point.longitude)
            if (e != null) anyValid = true
            elevations[k] = e
        }
        if (!anyValid) return null
        // Hold gaps forward then backward so every sample has a value.
        var hold: Float? = null
        for (k in 0..n) {
            if (elevations[k] == null) elevations[k] = hold else hold = elevations[k]
        }
        hold = null
        for (k in n downTo 0) {
            if (elevations[k] == null) elevations[k] = hold else hold = elevations[k]
        }
        val slope = FloatArray(n + 1)
        for (k in 0..n) {
            val lo = max(0, k - 1)
            val hi = min(n, k + 1)
            val run = (hi - lo) * SAMPLE_SPACING_M
            val rise = (elevations[hi]!! - elevations[lo]!!).toDouble()
            slope[k] = if (run > 0.0) Math.toDegrees(atan2(rise, run)).toFloat() else 0f
        }
        return slope
    }

    /** Emits one marker at each whole kilometre, interpolated along the track. */
    private fun computeKmMarkers(
        polyline: List<LatLong>,
        cumulative: DoubleArray,
        total: Double,
    ): List<KmMarker> {
        if (polyline.size < 2 || total < 1000.0) return emptyList()
        val markers = mutableListOf<KmMarker>()
        var km = 1
        while (km * 1000.0 <= total) {
            val d = km * 1000.0
            markers.add(
                KmMarker(
                    point = interpolateAlong(polyline, cumulative, d),
                    km = km,
                    tangentBack = interpolateAlong(polyline, cumulative, max(0.0, d - TANGENT_EPS_M)),
                    tangentFwd = interpolateAlong(polyline, cumulative, min(total, d + TANGENT_EPS_M)),
                ),
            )
            km++
        }
        return markers
    }

    /** Point at cumulative ground distance [meters] along the track. */
    private fun interpolateAlong(
        polyline: List<LatLong>,
        cumulative: DoubleArray,
        meters: Double,
    ): LatLong {
        if (meters <= 0.0) return polyline.first()
        if (meters >= cumulative.last()) return polyline.last()
        // Linear scan is fine: called O(distance / spacing) times, each from the start.
        var i = 1
        while (i < cumulative.size && cumulative[i] < meters) i++
        val segStart = cumulative[i - 1]
        val segLen = cumulative[i] - segStart
        val t = if (segLen > 0.0) (meters - segStart) / segLen else 0.0
        val a = polyline[i - 1]
        val b = polyline[i]
        return LatLong(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }

    /** Linear ramp from [SCALE_MIN] to [SCALE_MAX] across [SCALE_MIN_ZOOM]..[SCALE_MAX_ZOOM]. */
    private fun scaleForZoom(zoomLevel: Byte): Float {
        val span = (SCALE_MAX_ZOOM - SCALE_MIN_ZOOM).toFloat()
        val t = ((zoomLevel - SCALE_MIN_ZOOM) / span).coerceIn(0f, 1f)
        return SCALE_MIN + t * (SCALE_MAX - SCALE_MIN)
    }

    /**
     * Track-line width scale: full (1.0) at/above [LINE_FULL_ZOOM], tapering to [LINE_MIN_SCALE] when
     * zoomed out, so the line keeps a constant on-screen width once zoomed in but thins out far away
     * instead of blanketing the map.
     */
    private fun lineScaleForZoom(zoomLevel: Byte): Float {
        val span = (LINE_FULL_ZOOM - LINE_TAPER_MIN_ZOOM).toFloat()
        val t = ((zoomLevel - LINE_TAPER_MIN_ZOOM) / span).coerceIn(0f, 1f)
        return LINE_MIN_SCALE + t * (1f - LINE_MIN_SCALE)
    }

    private fun fill(color: Int): Paint =
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

    /** A round-capped, round-joined stroke - for the continuous track line and the chevrons. */
    private fun strokeRound(
        color: Int,
        widthDp: Float,
    ): Paint =
        stroke(color, widthDp).apply {
            setStrokeCap(Cap.ROUND)
            setStrokeJoin(Join.ROUND)
        }

    private fun text(
        color: Int,
        style: Style,
        strokeWidthDp: Float,
    ): Paint =
        factory.createPaint().apply {
            setColor(color)
            setStyle(style)
            if (style == Style.STROKE) setStrokeWidth(strokeWidthDp * density)
            setTextAlign(Align.CENTER)
        }

    private companion object {
        // Five slope classes (signed grade along travel): warm uphill, cool downhill, grey gentle.
        val STEEP_UP_COLOR = 0xFFC62828.toInt() // red, >= STEEP_DEG up
        val MODERATE_UP_COLOR = 0xFFF57C00.toInt() // orange
        val GENTLE_COLOR = 0xFF9CCC65.toInt() // light green, |slope| < MODERATE_DEG (also the no-DEM fallback)
        val MODERATE_DOWN_COLOR = 0xFF29B6F6.toInt() // cyan
        val STEEP_DOWN_COLOR = 0xFF1565C0.toInt() // blue, >= STEEP_DEG down

        val HALO_COLOR = 0xFFFFFFFF.toInt() // white outline for waypoints (legibility over varied terrain)

        val CHEVRON_COLOR = 0xFF2B2B2B.toInt() // single dark direction chevron
        val CHEVRON_HALO_COLOR = 0x99FFFFFF.toInt() // faint white halo so the chevron reads on dark track colours
        val TRACK_EDGE_COLOR = 0xFF2B2B2B.toInt() // near-black casing edge under the coloured track line
        val BALLOON_FILL_COLOR = 0xFFFFD54F.toInt() // amber kilometre balloon (high contrast over green terrain)
        val TOTAL_BALLOON_FILL_COLOR = 0xFF4DD0E1.toInt() // cyan end-of-route total balloon (distinct from the amber km ones)
        val BALLOON_STROKE_COLOR = 0xFF202124.toInt() // dark balloon outline
        val LABEL_COLOR = 0xFF202124.toInt()
        val START_COLOR = 0xFF2E7D32.toInt() // green
        val END_COLOR = 0xFFD32F2F.toInt() // red
        val MID_COLOR = 0xFFFB8C00.toInt() // orange via-points

        const val MODERATE_DEG = 4f // >= moderate grade (gentle/green band is -4..+4 deg)
        const val STEEP_DEG = 16f // >= steep grade (hiking convention; user's "feels steep" 30 deg sits deep in here)

        // DEM sampling step for slope: ~30 m matches the 30 m DEM and smooths per-point noise.
        const val SAMPLE_SPACING_M = 30.0

        // Continuous slope-coloured track line. Width = LINE_WIDTH_DP * density * lineScaleForZoom.
        const val LINE_WIDTH_DP = 5f
        const val TRACK_EDGE_RATIO = 0.22f // dark casing edge each side, as a fraction of the line width
        const val LINE_FULL_ZOOM = 14 // at/above this zoom the line keeps a constant on-screen width
        const val LINE_TAPER_MIN_ZOOM = 10 // at/below this zoom the line is thinnest (LINE_MIN_SCALE)
        const val LINE_MIN_SCALE = 0.5f

        // ">" direction chevrons, sized as fixed multiples of the line width so they stay in proportion
        // at every zoom. Shorter arms than before (HALF_WIDTH_RATIO).
        const val CHEVRON_LENGTH_RATIO = 1.0f // back-to-tip extent
        const val CHEVRON_HALF_WIDTH_RATIO = 0.4f // half-span across the arms (kept within the track width)
        const val CHEVRON_STROKE_RATIO = 0.5f // chevron line thickness
        const val CHEVRON_HALO_EXTRA_RATIO = 0.35f // extra halo width under the chevron (fraction of line width)
        const val CHEVRON_SPACING_RATIO = 5f // gap between chevrons along the track

        const val LABEL_TEXT_DP = 15f
        const val BALLOON_PADDING_DP = 6f
        const val BALLOON_RADIUS_DP = 5f
        const val BALLOON_TAIL_DP = 7f // height of the tail from the bubble down to the route point
        const val BALLOON_TAIL_HALF_DP = 5f // half-width of the tail base
        const val BALLOON_STROKE_DP = 2.2f

        // Half-span (m) each side of a km point used to read the local track direction for the balloon
        // offset; long enough to ignore per-vertex jitter, short enough to track real bends.
        const val TANGENT_EPS_M = 20.0

        const val WAYPOINT_RADIUS_DP = 7f
        const val MID_RADIUS_DP = 3.2f // via points: small, so dense runs do not blob
        const val WAYPOINT_HALO_DP = 3f
        const val MID_HALO_DP = 1.8f // thinner halo for via points than for start/end

        // Marker size ramps with zoom: full size at/above SCALE_MAX_ZOOM, SCALE_MIN fraction at/below
        // SCALE_MIN_ZOOM. Keeps markers from dwarfing the route when zoomed out, without vanishing.
        const val SCALE_MIN_ZOOM = 11
        const val SCALE_MAX_ZOOM = 17
        const val SCALE_MIN = 0.5f
        const val SCALE_MAX = 1.0f

        const val LABEL_MIN_ZOOM = 13 // below this, draw kilometre dots without numbers
        const val MID_WAYPOINT_MIN_ZOOM = 14 // below this, hide via-points (their halos would merge)
        const val MARKER_MIN_SPACING_DP = 48f // minimum on-screen gap between drawn kilometre markers
        const val LOOP_JOIN_METERS = 30.0 // start/end closer than this -> treat as an O-shaped loop
    }
}

/** Formats a kilometre distance with one decimal place (e.g. 12.34 -> "12.3"), no unit suffix. */
private fun formatKm(km: Double): String {
    val tenths = (km * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}"
}

/** Smallest "nice" kilometre interval (1/2/5/10...) that is at least [rawKm]. */
private fun niceKmInterval(rawKm: Double): Int {
    for (step in intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1000)) {
        if (step >= rawKm) return step
    }
    return 1000
}

/** Haversine great-circle distance in metres - good enough for distance marks and loop detection. */
private fun distanceMeters(
    a: LatLong,
    b: LatLong,
): Double {
    val earthRadius = 6_371_000.0
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * earthRadius * atan2(Math.sqrt(h), Math.sqrt(1 - h))
}
