package io.github.nexgus.jiudge.feature.path

import android.util.Log
import io.github.nexgus.jiudge.core.elevation.SlopeSampler
import io.github.nexgus.jiudge.core.elevation.SlopeScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.mapsforge.core.graphics.Align
import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Join
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Path
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.Layer
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Every tunable number of the path overlays, in one place, so the look can be adjusted after a
 * field check by editing values here only - no drawing logic depends on a constant defined elsewhere.
 */
internal object PathAppearance {
    // Kind colours: which sort of path a line is. They colour the line itself while slope display
    // is off, and its outline while slope display is on.
    const val RECORDING_COLOR: Int = 0xFFD32F2F.toInt() // red: the track being recorded
    const val TRACK_COLOR: Int = 0xFF1976D2.toInt() // blue: an opened (saved) track
    const val PLAN_COLOR: Int = 0xFF2E7D32.toInt() // green: a planned route, opened or being edited

    // Line width = LINE_WIDTH_DP * density * a zoom factor: 1.0 at/above LINE_FULL_ZOOM, tapering
    // linearly to LINE_MIN_SCALE at/below LINE_TAPER_MIN_ZOOM so a zoomed-out line does not blanket
    // the map. Never thinner than LINE_MIN_WIDTH_PX.
    const val LINE_WIDTH_DP = 5f
    const val LINE_FULL_ZOOM = 14
    const val LINE_TAPER_MIN_ZOOM = 10
    const val LINE_MIN_SCALE = 0.5f
    const val LINE_MIN_WIDTH_PX = 2f

    // Outline: an edge of OUTLINE_RATIO x the line width on each side. White while slope display is
    // off; the kind colour while it is on. Line and outline are both fully opaque.
    const val OUTLINE_RATIO = 0.3f
    const val OUTLINE_COLOR: Int = 0xFFFFFFFF.toInt()

    // ">" direction chevrons: one dark colour with a faint white halo so it reads on every line
    // colour. All sizes are multiples of the line width, so they stay in proportion at every zoom.
    const val CHEVRON_COLOR: Int = 0xFF2B2B2B.toInt()
    const val CHEVRON_HALO_COLOR: Int = 0x99FFFFFF.toInt()
    const val CHEVRON_LENGTH_RATIO = 1.0f // back-to-tip extent
    const val CHEVRON_HALF_WIDTH_RATIO = 0.4f // half-span across the arms (kept within the line width)
    const val CHEVRON_STROKE_RATIO = 0.5f // chevron line thickness
    const val CHEVRON_HALO_EXTRA_RATIO = 0.35f // extra halo width under the chevron
    const val CHEVRON_SPACING_RATIO = 5f // gap between chevrons along the line

    // Rubber band of a track being recorded: a thin dashed link from the last written point to the
    // current-location marker, in the recording colour.
    const val RUBBER_WIDTH_RATIO = 0.4f
    const val RUBBER_DASH_ON_DP = 4f
    const val RUBBER_DASH_OFF_DP = 3f

    // Start / end / via markers.
    const val START_COLOR: Int = 0xFF2E7D32.toInt() // green
    const val END_COLOR: Int = 0xFFD32F2F.toInt() // red
    const val VIA_COLOR: Int = 0xFFFB8C00.toInt() // orange
    const val MARKER_HALO_COLOR: Int = 0xFFFFFFFF.toInt() // white ring, for legibility over varied terrain
    const val ENDPOINT_RADIUS_DP = 7f
    const val ENDPOINT_HALO_DP = 3f
    const val ENDPOINT_MIN_RADIUS_PX = 3
    const val VIA_RADIUS_DP = 3.2f // small, so dense runs do not blob
    const val VIA_HALO_DP = 1.8f
    const val VIA_MIN_RADIUS_PX = 2
    const val VIA_MIN_ZOOM = 14 // below this, via points are hidden (their halos would merge)

    // Distance labels: amber balloons for whole kilometres, a cyan one for the total at the end.
    const val KM_BALLOON_COLOR: Int = 0xFFFFD54F.toInt()
    const val TOTAL_BALLOON_COLOR: Int = 0xFF4DD0E1.toInt()
    const val BALLOON_STROKE_COLOR: Int = 0xFF202124.toInt()
    const val LABEL_COLOR: Int = 0xFF202124.toInt()
    const val LABEL_TEXT_DP = 15f
    const val BALLOON_PADDING_DP = 6f
    const val BALLOON_RADIUS_DP = 5f
    const val BALLOON_TAIL_DP = 7f // length of the tail from the bubble to the path point
    const val BALLOON_TAIL_HALF_DP = 5f // half-width of the tail base
    const val BALLOON_STROKE_DP = 2.2f
    const val KM_MARKER_MIN_SPACING_DP = 48f // minimum on-screen gap between drawn kilometre labels
    const val TOTAL_LABEL_MIN_METERS = 1.0 // a path shorter than this gets no total label

    // Marker size ramps with zoom: full size at/above MARKER_SCALE_MAX_ZOOM, MARKER_SCALE_MIN of it
    // at/below MARKER_SCALE_MIN_ZOOM. Keeps markers from dwarfing the line when zoomed out.
    const val MARKER_SCALE_MIN_ZOOM = 11
    const val MARKER_SCALE_MAX_ZOOM = 17
    const val MARKER_SCALE_MIN = 0.5f
    const val MARKER_SCALE_MAX = 1.0f
}

/**
 * What a [PathLayer] draws, which fixes its kind colour and which markers it carries.
 *
 * Every style has the direction chevrons and the start marker. [TRACK] and [PLAN] also have the end
 * marker and, subject to [PathDisplayOptions.showDistanceMarkers], the kilometre labels and the
 * total label. Only [PLAN] has via points. [RECORDING] has neither an end marker nor distance
 * labels: it has no end yet - the current-location marker stands in for it, linked by the rubber
 * band - and distance labels on it could be mistaken for those of a reference path shown alongside.
 */
enum class PathStyle(
    /** The colour that tells this kind of path apart: the line when slope display is off, its outline when on. */
    val kindColor: Int,
    internal val showsEndMarker: Boolean,
    internal val showsViaWaypoints: Boolean,
    internal val showsDistanceMarkers: Boolean,
    internal val showsRubberBand: Boolean,
) {
    /** The track currently being recorded (including one stopped but not yet saved or discarded). */
    RECORDING(
        kindColor = PathAppearance.RECORDING_COLOR,
        showsEndMarker = false,
        showsViaWaypoints = false,
        showsDistanceMarkers = false,
        showsRubberBand = true,
    ),

    /** An opened saved track. */
    TRACK(
        kindColor = PathAppearance.TRACK_COLOR,
        showsEndMarker = true,
        showsViaWaypoints = false,
        showsDistanceMarkers = true,
        showsRubberBand = false,
    ),

    /** A planned route, opened or being edited. */
    PLAN(
        kindColor = PathAppearance.PLAN_COLOR,
        showsEndMarker = true,
        showsViaWaypoints = true,
        showsDistanceMarkers = true,
        showsRubberBand = false,
    ),
}

/**
 * The two user-switchable display options, shared by every path on the map. [showSlope] colours the
 * line by slope and moves the kind colour to the outline; [showDistanceMarkers] shows the kilometre
 * labels and the total label. Start, end and via markers and the chevrons are not affected by either.
 */
data class PathDisplayOptions(
    val showSlope: Boolean,
    val showDistanceMarkers: Boolean,
)

/**
 * Draws one path over the map: a solid line with an outline and periodic ">" direction chevrons,
 * plus the markers its [style] carries (see [PathStyle]).
 *
 * Line colours. With slope display off the line is the kind colour on a white outline. With it on,
 * the line is coloured by slope (five classes, see [SlopeScale]) and the outline takes the kind
 * colour, so the kind stays readable. Slope needs DEM sampling, which runs on [samplingScope] off
 * the main and render threads; until a stretch has been sampled it keeps the slope-off look. Nothing
 * is sampled while slope display is off. When the path only grows (a recording, or a waypoint added
 * while planning) the stretch already sampled is kept and only the appended part is sampled. A
 * sampled path with no elevation data at all is drawn in the gentle-slope colour.
 *
 * Markers. Start is a green dot, end a red dot, merged into one half-green / half-red dot when the
 * two coincide; via points are small orange dots. Kilometre labels are thinned to a "nice" interval
 * so they never crowd at low zoom, and a differently tinted label at the end shows the total.
 * Everything except colour scales with zoom.
 *
 * Recording extras ([PathStyle.RECORDING] only): a dashed rubber band from the last written point to
 * [setLiveTip], so the trail stays visually connected to the current-location marker while the fix
 * gate holds points back; before any point is written the band starts at [setProvisionalStart].
 *
 * Threading. The setters are called from the main thread, [draw] runs on mapsforge's render thread,
 * and sampling results arrive from [samplingScope]. All three meet in one immutable [snapshot] that
 * is replaced whole: writers build the next one under [lock], [draw] reads the reference once. A
 * sampling result is applied only if the path it was computed for has not been replaced meanwhile
 * (see [lineage]); a result for a path that has since only grown is still valid for its stretch.
 *
 * [density] is screen pixels per dp, keeping physical sizes constant across screens.
 */
class PathLayer(
    val style: PathStyle,
    private val density: Float,
    private val sampler: SlopeSampler,
    private val samplingScope: CoroutineScope,
    options: PathDisplayOptions,
) : Layer() {
    /** Which straight edge of a label balloon the tail sprouts from (the edge facing the path point). */
    private enum class Edge { TOP, BOTTOM, LEFT, RIGHT }

    private data class Snapshot(
        val geometry: PathGeometry,
        val waypoints: List<LatLong>,
        val start: LatLong?,
        val end: LatLong?,
        // Start and end coincide: one merged dot instead of two overlapping ones.
        val isLoop: Boolean,
        val kmMarkers: List<PathGeometry.KmMarker>,
        // Slope samples of the first `profile.pointCount` points of the geometry, or null when
        // nothing has been sampled (slope display off, or sampling not finished yet).
        val profile: SlopeSampler.Profile?,
        val options: PathDisplayOptions,
        val liveTip: LatLong?,
        val provisionalStart: LatLong?,
    )

    /** What one sampling pass works from, captured under [lock]. */
    private class SamplingPass(
        val geometry: PathGeometry,
        val base: SlopeSampler.Profile?,
        val lineage: Long,
    )

    private val lock = Any()

    @Volatile
    private var snapshot =
        Snapshot(
            geometry = PathGeometry.EMPTY,
            waypoints = emptyList(),
            start = null,
            end = null,
            isLoop = false,
            kmMarkers = emptyList(),
            profile = null,
            options = options,
            liveTip = null,
            provisionalStart = null,
        )

    // Identifies the path a sampling result belongs to. Bumped (under [lock]) whenever the path is
    // replaced rather than extended, slope display is switched off, or the layer leaves the map, so
    // a result computed for anything older is recognised as stale and dropped. Volatile because the
    // sampling thread polls it to abandon a pass early.
    @Volatile
    private var lineage = 0L

    // The coroutine sampling this layer's path, if any. Guarded by [lock].
    private var samplingJob: Job? = null

    // Lineage whose sampling threw; not retried until the path or the options change. Guarded by [lock].
    private var failedLineage = -1L

    // True once the layer has left the map; no sampling runs for it. Guarded by [lock].
    private var released = false

    private val factory = AndroidGraphicFactory.INSTANCE

    // Stroke widths and most colours are set per draw(); the values given here are placeholders.
    private val outlinePaint = strokeRound(PathAppearance.OUTLINE_COLOR)
    private val linePaint = strokeRound(style.kindColor)
    private val chevronHalo = strokeRound(PathAppearance.CHEVRON_HALO_COLOR)
    private val chevronPaint = strokeRound(PathAppearance.CHEVRON_COLOR)
    private val rubberPaint =
        strokeRound(style.kindColor).apply {
            setDashPathEffect(
                floatArrayOf(PathAppearance.RUBBER_DASH_ON_DP * density, PathAppearance.RUBBER_DASH_OFF_DP * density),
            )
        }
    private val balloonFill = fill(PathAppearance.KM_BALLOON_COLOR)
    private val totalBalloonFill = fill(PathAppearance.TOTAL_BALLOON_COLOR)
    private val balloonStroke = stroke(PathAppearance.BALLOON_STROKE_COLOR, PathAppearance.BALLOON_STROKE_DP)
    private val labelFill =
        fill(PathAppearance.LABEL_COLOR).apply {
            setTextAlign(Align.CENTER)
        }
    private val endpointHalo = stroke(PathAppearance.MARKER_HALO_COLOR, PathAppearance.ENDPOINT_HALO_DP)
    private val viaHalo = stroke(PathAppearance.MARKER_HALO_COLOR, PathAppearance.VIA_HALO_DP)
    private val startFill = fill(PathAppearance.START_COLOR)
    private val endFill = fill(PathAppearance.END_COLOR)
    private val viaFill = fill(PathAppearance.VIA_COLOR)

    // Per-frame scratch state, touched by the render thread only: the projected vertices and one
    // reusable path object.
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private val scratchPath: Path = factory.createPath()

    /**
     * Replaces the drawn path. [points] is the whole line in travel order; [waypoints] are the
     * user-placed points of a plan (empty for a track), whose first and last mark start and end.
     * Without waypoints the start and end markers sit on the first and last of [points]. A path of
     * fewer than two points has no line; a single point still gets its start marker. Pass empty
     * lists to clear. Neither list may be mutated afterwards.
     */
    fun setPath(
        points: List<LatLong>,
        waypoints: List<LatLong> = emptyList(),
    ) {
        synchronized(lock) {
            val current = snapshot
            val extends = current.geometry.isPrefixOf(points)
            val geometry = if (extends) current.geometry.extendedTo(points) else PathGeometry.of(points)
            if (!extends) lineage++
            val start = waypoints.firstOrNull() ?: points.firstOrNull()
            val end =
                when {
                    !style.showsEndMarker -> null
                    waypoints.size >= 2 -> waypoints.last()
                    waypoints.isEmpty() && points.size >= 2 -> points.last()
                    else -> null
                }
            snapshot =
                current.copy(
                    geometry = geometry,
                    waypoints = waypoints,
                    start = start,
                    end = end,
                    isLoop = start != null && end != null && PathGeometry.isLoop(start, end),
                    kmMarkers = if (style.showsDistanceMarkers) geometry.kmMarkers() else emptyList(),
                    profile = if (extends) current.profile else null,
                )
            ensureSamplingLocked()
        }
        requestRedraw()
    }

    /** Applies the display options. Switching slope display off also abandons any sampling in flight. */
    fun setDisplayOptions(options: PathDisplayOptions) {
        synchronized(lock) {
            val current = snapshot
            if (current.options == options) return
            if (!options.showSlope) lineage++
            snapshot = current.copy(options = options, profile = if (options.showSlope) current.profile else null)
            ensureSamplingLocked()
        }
        requestRedraw()
    }

    /**
     * Moves (or with null, hides) the marker-side end of the rubber band. Only drawn by a
     * [PathStyle.RECORDING] layer.
     */
    fun setLiveTip(tip: LatLong?) {
        synchronized(lock) { snapshot = snapshot.copy(liveTip = tip) }
        requestRedraw()
    }

    /**
     * Moves (or with null, drops) the rubber band's fixed end used while no point has been written
     * yet. Ignored once the path has a point. Only drawn by a [PathStyle.RECORDING] layer.
     */
    fun setProvisionalStart(start: LatLong?) {
        synchronized(lock) { snapshot = snapshot.copy(provisionalStart = start) }
        requestRedraw()
    }

    override fun onAdd() {
        super.onAdd()
        synchronized(lock) {
            released = false
            ensureSamplingLocked()
        }
    }

    override fun onRemove() {
        release()
        super.onRemove()
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    /** Stops sampling for good (until the layer is added to a map again) and invalidates any result in flight. */
    private fun release() {
        synchronized(lock) {
            released = true
            lineage++
            samplingJob?.cancel()
            samplingJob = null
        }
    }

    private fun needsSamplingLocked(): Boolean {
        val current = snapshot
        return !released &&
            current.options.showSlope &&
            lineage != failedLineage &&
            (current.profile?.pointCount ?: 0) != current.geometry.size
    }

    /** Starts the sampling coroutine if there is unsampled path to colour and none is running. Call under [lock]. */
    private fun ensureSamplingLocked() {
        if (!needsSamplingLocked()) return
        if (samplingJob?.isActive == true) return
        samplingJob = samplingScope.launch { runSampling() }
    }

    /**
     * Samples until the profile covers the whole current path, one pass per path state: a pass that
     * finishes after the path has grown publishes its (still valid, shorter) result and the next
     * pass continues from it. Deciding to stop and clearing [samplingJob] happen in one critical
     * section, so a concurrent [ensureSamplingLocked] either is seen by this loop or starts a new one.
     */
    private suspend fun runSampling() {
        val job = coroutineContext[Job]
        while (true) {
            val pass =
                synchronized(lock) {
                    if (!needsSamplingLocked()) {
                        samplingJob = null
                        return
                    }
                    val current = snapshot
                    SamplingPass(current.geometry, current.profile, lineage)
                }
            val isActive = { job?.isActive != false && lineage == pass.lineage }
            val points = pass.geometry.points
            val cumulative = pass.geometry.cumulativeMeters
            val result =
                try {
                    if (pass.base != null) {
                        sampler.extend(pass.base, points, cumulative, isActive)
                    } else {
                        sampler.sample(points, cumulative, isActive)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "slope sampling failed", e)
                    synchronized(lock) { if (lineage == pass.lineage) failedLineage = pass.lineage }
                    null
                }
            val published =
                synchronized(lock) {
                    val current = snapshot
                    if (result != null && lineage == pass.lineage && !released && current.options.showSlope) {
                        snapshot = current.copy(profile = result)
                        true
                    } else {
                        false
                    }
                }
            if (published) requestRedraw()
            // Cancellation point between passes (the passes themselves poll isActive).
            yield()
        }
    }

    override fun draw(
        boundingBox: BoundingBox,
        zoomLevel: Byte,
        canvas: Canvas,
        topLeftPoint: Point,
        rotation: Rotation,
    ) {
        val state = snapshot
        val geometry = state.geometry
        val tip = if (style.showsRubberBand) state.liveTip else null
        // The band's fixed end: the last written point, or - before anything is written - the
        // provisional first point.
        val bandAnchor = if (style.showsRubberBand) geometry.points.lastOrNull() ?: state.provisionalStart else null
        val hasBand = tip != null && bandAnchor != null
        if (geometry.size == 0 && state.start == null && !hasBand) return

        val mapSize = MercatorProjection.getMapSize(zoomLevel, displayModel.tileSize)
        // mapsforge bakes the device density and any FRACTIONAL_ZOOM scale into `displayModel.tileSize`,
        // so `zoomLevel` is the byte stored on the model (the floor) and lags behind what the user
        // actually sees - e.g. on a 420 dpi device a freshly loaded route renders at byte zoom 12
        // even though the visual scale corresponds to ~13.5. Use the visual zoom for every threshold
        // and scale ramp.
        val visualZoom = visualZoom(mapSize)

        fun screenX(lon: Double) = MercatorProjection.longitudeToPixelX(lon, mapSize) - topLeftPoint.x

        fun screenY(lat: Double) = MercatorProjection.latitudeToPixelY(lat, mapSize) - topLeftPoint.y

        val markerScale = markerScaleForZoom(visualZoom)
        val lineWidth =
            (PathAppearance.LINE_WIDTH_DP * density * lineScaleForZoom(visualZoom))
                .coerceAtLeast(PathAppearance.LINE_MIN_WIDTH_PX)
        val showDistance = style.showsDistanceMarkers && state.options.showDistanceMarkers

        if (geometry.size >= 2) {
            project(geometry, mapSize, topLeftPoint)
            drawLine(canvas, state, lineWidth)
            drawDirectionChevrons(canvas, geometry, mapSize, topLeftPoint, lineWidth)
        }
        if (tip != null && bandAnchor != null) {
            drawRubberBand(canvas, bandAnchor, tip, lineWidth, ::screenX, ::screenY)
        }
        // Via points sit *under* the km labels (a reference label must not be hidden by a minor via
        // dot), but the start/end markers stay on top - they are navigationally important.
        if (style.showsViaWaypoints) drawViaWaypoints(canvas, state.waypoints, markerScale, visualZoom, ::screenX, ::screenY)
        if (showDistance) drawKmMarkers(canvas, state.kmMarkers, mapSize, boundingBox, markerScale, ::screenX, ::screenY)
        drawEndpoints(canvas, state, markerScale, ::screenX, ::screenY)
        if (showDistance) drawTotalMarker(canvas, geometry, markerScale, ::screenX, ::screenY)
    }

    /** Projects every vertex of [geometry] into canvas pixels, into [xs] / [ys]. */
    private fun project(
        geometry: PathGeometry,
        mapSize: Long,
        topLeftPoint: Point,
    ) {
        val count = geometry.size
        if (xs.size < count) {
            // Headroom so a path growing one point at a time does not reallocate every frame.
            val capacity = count + count / 2 + 16
            xs = FloatArray(capacity)
            ys = FloatArray(capacity)
        }
        val size = mapSize.toDouble()
        val unitX = geometry.unitX
        val unitY = geometry.unitY
        for (i in 0 until count) {
            xs[i] = (unitX[i] * size - topLeftPoint.x).toFloat()
            ys[i] = (unitY[i] * size - topLeftPoint.y).toFloat()
        }
    }

    /**
     * Draws the line: first the outline (a wider stroke underneath, giving the line its edge), then
     * the line itself on top. Each pass strokes runs of consecutive same-coloured segments as one
     * round-jointed path, so the line stays solid however sharply it bends, and skips segments that
     * lie wholly beyond one edge of the canvas.
     *
     * A segment whose slope has been sampled takes the slope-on look (slope-coloured line, kind-
     * coloured outline); any other segment takes the slope-off look (kind-coloured line, white
     * outline). With slope display off no segment counts as sampled.
     */
    private fun drawLine(
        canvas: Canvas,
        state: Snapshot,
        lineWidth: Float,
    ) {
        val geometry = state.geometry
        val count = geometry.size
        val profile = state.profile
        // Segment s joins vertex s to s + 1 and is sampled once both of its ends are covered.
        val sampledSegments = if (profile != null) profile.pointCount - 1 else 0
        val kindColor = style.kindColor
        val outlineWidth = lineWidth * (1f + 2f * PathAppearance.OUTLINE_RATIO)
        val margin = outlineWidth / 2f + 1f
        val left = -margin
        val top = -margin
        val right = canvas.width + margin
        val bottom = canvas.height + margin

        outlinePaint.setStrokeWidth(outlineWidth)
        PathGeometry.forEachLineRun(
            xs,
            ys,
            count,
            left,
            top,
            right,
            bottom,
            colorOfSegment = { segment -> if (segment < sampledSegments) kindColor else PathAppearance.OUTLINE_COLOR },
        ) { first, last, color -> strokeRun(canvas, first, last, color, outlinePaint) }

        linePaint.setStrokeWidth(lineWidth)
        val cumulative = geometry.cumulativeMeters
        PathGeometry.forEachLineRun(
            xs,
            ys,
            count,
            left,
            top,
            right,
            bottom,
            colorOfSegment = { segment ->
                if (segment < sampledSegments) {
                    slopeColor(profile, (cumulative[segment] + cumulative[segment + 1]) / 2.0)
                } else {
                    kindColor
                }
            },
        ) { first, last, color -> strokeRun(canvas, first, last, color, linePaint) }
    }

    /** Strokes projected vertices [first]..[last] as one path in [color]. */
    private fun strokeRun(
        canvas: Canvas,
        first: Int,
        last: Int,
        color: Int,
        paint: Paint,
    ) {
        paint.setColor(color)
        val path = scratchPath
        path.clear()
        path.moveTo(xs[first], ys[first])
        for (i in first + 1..last) path.lineTo(xs[i], ys[i])
        canvas.drawPath(path, paint)
    }

    /** Slope-class colour at ground distance [meters]; the gentle colour when the profile has no elevation data. */
    private fun slopeColor(
        profile: SlopeSampler.Profile?,
        meters: Double,
    ): Int = profile?.slopeDegAt(meters)?.let { SlopeScale.colorFor(it) } ?: SlopeScale.GENTLE_COLOR

    /**
     * Walks the line and lays a ">" chevron every fixed on-screen distance; all sizes scale off
     * [lineWidth]. The walk measures in double precision straight from the geometry rather than
     * from the float vertices in [xs] / [ys], so the spacing phase - and with it every chevron's
     * place on the line - does not creep as the map is panned. A segment wholly beyond one edge of
     * the canvas only advances that phase.
     */
    private fun drawDirectionChevrons(
        canvas: Canvas,
        geometry: PathGeometry,
        mapSize: Long,
        topLeftPoint: Point,
        lineWidth: Float,
    ) {
        val headLen = (lineWidth * PathAppearance.CHEVRON_LENGTH_RATIO).toDouble()
        val halfWidth = (lineWidth * PathAppearance.CHEVRON_HALF_WIDTH_RATIO).toDouble()
        val spacingPx = (lineWidth * PathAppearance.CHEVRON_SPACING_RATIO).toDouble()
        val chevronStroke = (lineWidth * PathAppearance.CHEVRON_STROKE_RATIO).coerceAtLeast(1f)
        chevronPaint.setStrokeWidth(chevronStroke)
        chevronHalo.setStrokeWidth(chevronStroke + lineWidth * PathAppearance.CHEVRON_HALO_EXTRA_RATIO)
        val margin = headLen + halfWidth
        val minEdge = -margin
        val maxX = canvas.width + margin
        val maxY = canvas.height + margin
        val size = mapSize.toDouble()
        val unitX = geometry.unitX
        val unitY = geometry.unitY

        var prevX = unitX[0] * size - topLeftPoint.x
        var prevY = unitY[0] * size - topLeftPoint.y
        var carry = spacingPx / 2 // first chevron half a step in, clear of the start dot
        for (i in 1 until geometry.size) {
            val curX = unitX[i] * size - topLeftPoint.x
            val curY = unitY[i] * size - topLeftPoint.y
            val dx = curX - prevX
            val dy = curY - prevY
            val segLen = hypot(dx, dy)
            if (segLen > 0.0) {
                if (carry > segLen) {
                    carry -= segLen
                } else if (isBeyondOneEdge(prevX, prevY, curX, curY, minEdge, maxX, maxY)) {
                    // No chevron on this segment can show: step over them without drawing.
                    val steps = floor((segLen - carry) / spacingPx) + 1
                    carry += steps * spacingPx - segLen
                } else {
                    val dirRad = atan2(dy, dx)
                    var along = carry
                    while (along <= segLen) {
                        val f = along / segLen
                        val px = prevX + dx * f
                        val py = prevY + dy * f
                        if (px >= minEdge && px <= maxX && py >= minEdge && py <= maxY) {
                            drawChevron(canvas, px, py, dirRad, headLen, halfWidth)
                        }
                        along += spacingPx
                    }
                    carry = along - segLen
                }
            }
            prevX = curX
            prevY = curY
        }
    }

    /** [PathGeometry.isSegmentOffscreen] for double coordinates, against an area whose left and top edge are both [minEdge]. */
    private fun isBeyondOneEdge(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        minEdge: Double,
        maxX: Double,
        maxY: Double,
    ): Boolean =
        PathGeometry.isSegmentOffscreen(
            x0.toFloat(),
            y0.toFloat(),
            x1.toFloat(),
            y1.toFloat(),
            minEdge.toFloat(),
            minEdge.toFloat(),
            maxX.toFloat(),
            maxY.toFloat(),
        )

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
        val path = scratchPath
        path.clear()
        path.moveTo((backX - rx * halfWidth).toFloat(), (backY - ry * halfWidth).toFloat())
        path.lineTo(tipX.toFloat(), tipY.toFloat())
        path.lineTo((backX + rx * halfWidth).toFloat(), (backY + ry * halfWidth).toFloat())
        canvas.drawPath(path, chevronHalo)
        canvas.drawPath(path, chevronPaint)
    }

    /**
     * The rubber band: a thin dashed line from the last written point to the current-location
     * marker, so the trail reads as connected to the marker even while the fix gate holds fixes
     * back. Dashed and chevron-free so it cannot be mistaken for recorded track.
     */
    private fun drawRubberBand(
        canvas: Canvas,
        anchor: LatLong,
        tip: LatLong,
        lineWidth: Float,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        rubberPaint.setStrokeWidth((lineWidth * PathAppearance.RUBBER_WIDTH_RATIO).coerceAtLeast(1f))
        val path = scratchPath
        path.clear()
        path.moveTo(screenX(anchor.longitude).toFloat(), screenY(anchor.latitude).toFloat())
        path.lineTo(screenX(tip.longitude).toFloat(), screenY(tip.latitude).toFloat())
        canvas.drawPath(path, rubberPaint)
    }

    private fun drawKmMarkers(
        canvas: Canvas,
        markers: List<PathGeometry.KmMarker>,
        mapSize: Long,
        boundingBox: BoundingBox,
        scale: Float,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        if (markers.isEmpty()) return
        // Crowding is handled entirely by the "nice" interval - at low zoom it is large, so most (or
        // all) km labels are dropped automatically. The end-of-path total label still shows there,
        // so the path is never completely unlabelled.
        val centerLat = (boundingBox.minLatitude + boundingBox.maxLatitude) / 2.0
        val metresPerPixel = MercatorProjection.calculateGroundResolution(centerLat, mapSize)
        val interval =
            PathGeometry.niceKmInterval(PathAppearance.KM_MARKER_MIN_SPACING_DP * density * metresPerPixel / 1000.0)
        labelFill.setTextSize(PathAppearance.LABEL_TEXT_DP * density * scale)
        for (marker in markers) {
            if (marker.km % interval != 0) continue
            val anchorX = screenX(marker.point.longitude)
            val anchorY = screenY(marker.point.latitude)
            // Local direction of travel in screen space (covers map rotation, since both ends are projected).
            val tangentX = screenX(marker.tangentFwd.longitude) - screenX(marker.tangentBack.longitude)
            val tangentY = screenY(marker.tangentFwd.latitude) - screenY(marker.tangentBack.latitude)
            drawBalloon(canvas, anchorX, anchorY, tangentX, tangentY, "${marker.km}", scale, balloonFill)
        }
    }

    /**
     * The end-of-path total-distance label, anchored at the final point and tinted differently from
     * the kilometre labels so the total reads as distinct. It is a single label, so it never crowds,
     * and at low zoom it is often the only readable distance cue.
     */
    private fun drawTotalMarker(
        canvas: Canvas,
        geometry: PathGeometry,
        scale: Float,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        if (geometry.size < 2 || geometry.totalMeters < PathAppearance.TOTAL_LABEL_MIN_METERS) return
        val end = geometry.points.last()
        val back = geometry.endTangentBack()
        val anchorX = screenX(end.longitude)
        val anchorY = screenY(end.latitude)
        val tangentX = anchorX - screenX(back.longitude)
        val tangentY = anchorY - screenY(back.latitude)
        labelFill.setTextSize(PathAppearance.LABEL_TEXT_DP * density * scale)
        val text = PathGeometry.formatKm(geometry.totalMeters / 1000.0)
        drawBalloon(canvas, anchorX, anchorY, tangentX, tangentY, text, scale, totalBalloonFill)
    }

    /**
     * A rounded label balloon holding [text], with a tail tip pinned to the path point
     * ([anchorX], [anchorY]). The bubble body is pushed off the path *perpendicular* to the local
     * direction of travel ([tangentX], [tangentY] in screen space), so it never blankets the line - a
     * fixed "straight up" offset would lie right along a vertical line. Of the two perpendiculars the
     * one pointing more towards screen-up wins, so labels still tend to float above their anchor; the
     * tail then sprouts from whichever edge faces back towards the path.
     */
    private fun drawBalloon(
        canvas: Canvas,
        anchorX: Double,
        anchorY: Double,
        tangentX: Double,
        tangentY: Double,
        text: String,
        scale: Float,
        fillPaint: Paint,
    ) {
        val pad = PathAppearance.BALLOON_PADDING_DP * density * scale
        val radius = (PathAppearance.BALLOON_RADIUS_DP * density * scale).toDouble()
        val tail = (PathAppearance.BALLOON_TAIL_DP * density * scale).toDouble()
        val tailHalf = (PathAppearance.BALLOON_TAIL_HALF_DP * density * scale).toDouble()
        val bubbleHeight = labelFill.getTextHeight(text) + 2 * pad
        val halfWidth = max(labelFill.getTextWidth(text) / 2.0 + pad, radius + tailHalf + 1.0)

        // Unit perpendicular to the path, preferring the one that points upward on screen (y < 0).
        val len = hypot(tangentX, tangentY)
        val tnx = if (len > 0.0) tangentX / len else 1.0
        val tny = if (len > 0.0) tangentY / len else 0.0
        var offsetX = -tny
        var offsetY = tnx
        if (offsetY > 0.0) {
            offsetX = -offsetX
            offsetY = -offsetY
        }

        // Push the bubble centre out far enough that its near edge clears the path by [tail]; the
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
     * spliced into whichever [edge] faces the path. The tail tip lands on ([tipX], [tipY]) and its
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
    ) = scratchPath.apply {
        clear()
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
            lineTo(tipX.toFloat(), tipY.toFloat()) // tail tip on the path point
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
    private fun Path.appendArc(
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
     * with a thin halo so dense runs do not blob, and *under* the km labels (see [draw]) so a via dot
     * never hides a reference label. Hidden when zoomed out, where even thin halos would merge.
     */
    private fun drawViaWaypoints(
        canvas: Canvas,
        waypoints: List<LatLong>,
        scale: Float,
        visualZoom: Double,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        if (waypoints.size <= 2 || visualZoom < PathAppearance.VIA_MIN_ZOOM) return
        val radius = (PathAppearance.VIA_RADIUS_DP * density * scale).toInt().coerceAtLeast(PathAppearance.VIA_MIN_RADIUS_PX)
        for (i in 1 until waypoints.lastIndex) {
            val cx = screenX(waypoints[i].longitude).toInt()
            val cy = screenY(waypoints[i].latitude).toInt()
            canvas.drawCircle(cx, cy, radius, viaFill)
            canvas.drawCircle(cx, cy, radius, viaHalo)
        }
    }

    /** Start (green) / end (red) markers - drawn on top of the line and labels, as they anchor the path. */
    private fun drawEndpoints(
        canvas: Canvas,
        state: Snapshot,
        scale: Float,
        screenX: (Double) -> Double,
        screenY: (Double) -> Double,
    ) {
        val start = state.start ?: return
        val radius =
            (PathAppearance.ENDPOINT_RADIUS_DP * density * scale).toInt().coerceAtLeast(PathAppearance.ENDPOINT_MIN_RADIUS_PX)
        val sx = screenX(start.longitude).toInt()
        val sy = screenY(start.latitude).toInt()
        if (state.isLoop) {
            // Start == end: one dot, left half green (start) / right half red (end).
            drawHalfDisc(canvas, sx, sy, radius, left = true, paint = startFill)
            drawHalfDisc(canvas, sx, sy, radius, left = false, paint = endFill)
            canvas.drawCircle(sx, sy, radius, endpointHalo)
            return
        }
        canvas.drawCircle(sx, sy, radius, startFill)
        canvas.drawCircle(sx, sy, radius, endpointHalo)
        val end = state.end ?: return
        val ex = screenX(end.longitude).toInt()
        val ey = screenY(end.latitude).toInt()
        canvas.drawCircle(ex, ey, radius, endFill)
        canvas.drawCircle(ex, ey, radius, endpointHalo)
    }

    /** Fills one half (left or right of the vertical diameter) of a circle - the merged start/end dot of a loop. */
    private fun drawHalfDisc(
        canvas: Canvas,
        cx: Int,
        cy: Int,
        radius: Int,
        left: Boolean,
        paint: Paint,
    ) {
        val path = scratchPath
        path.clear()
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

    /** Marker size factor: a linear ramp across the marker-scale zoom range of [PathAppearance]. */
    private fun markerScaleForZoom(visualZoom: Double): Float {
        val span = (PathAppearance.MARKER_SCALE_MAX_ZOOM - PathAppearance.MARKER_SCALE_MIN_ZOOM).toDouble()
        val t = ((visualZoom - PathAppearance.MARKER_SCALE_MIN_ZOOM) / span).coerceIn(0.0, 1.0)
        return (PathAppearance.MARKER_SCALE_MIN + t * (PathAppearance.MARKER_SCALE_MAX - PathAppearance.MARKER_SCALE_MIN)).toFloat()
    }

    /**
     * Line width factor: full (1.0) at/above [PathAppearance.LINE_FULL_ZOOM], tapering linearly to
     * [PathAppearance.LINE_MIN_SCALE] at/below [PathAppearance.LINE_TAPER_MIN_ZOOM], so the line keeps
     * a constant on-screen width once zoomed in but thins out far away instead of blanketing the map.
     */
    private fun lineScaleForZoom(visualZoom: Double): Float {
        val span = (PathAppearance.LINE_FULL_ZOOM - PathAppearance.LINE_TAPER_MIN_ZOOM).toDouble()
        val t = ((visualZoom - PathAppearance.LINE_TAPER_MIN_ZOOM) / span).coerceIn(0.0, 1.0)
        return (PathAppearance.LINE_MIN_SCALE + t * (1f - PathAppearance.LINE_MIN_SCALE)).toFloat()
    }

    /**
     * Visual zoom inferred from `mapSize`: `log2(mapSize / 256)`. mapsforge folds device density and
     * the FRACTIONAL_ZOOM scale into `displayModel.tileSize`, so the byte `zoomLevel` no longer maps
     * cleanly to what the user sees - the visual zoom does.
     */
    private fun visualZoom(mapSize: Long): Double = ln(mapSize.toDouble() / BASE_TILE_SIZE) / LOG_2

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

    /** A round-capped, round-joined stroke - for the line, its outline, the chevrons and the rubber band. */
    private fun strokeRound(color: Int): Paint =
        stroke(color, 1f).apply {
            setStrokeCap(Cap.ROUND)
            setStrokeJoin(Join.ROUND)
        }

    private companion object {
        const val TAG = "PathLayer"

        // mapsforge's untouched base tile size before density and FRACTIONAL_ZOOM scaling, used to
        // recover a visual zoom from `mapSize` independent of the device DPI.
        const val BASE_TILE_SIZE = 256
        val LOG_2 = ln(2.0)
    }
}
