package io.github.nexgus.jiudge.feature.planning

import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.FixedPixelCircle
import org.mapsforge.map.layer.overlay.Polyline

// Shared overlay styling for both the in-progress plan ([RoutePlanner]) and a loaded route
// ([RouteViewer]) so planned and saved routes look identical on the map. Colours are chosen to
// stand out from RudyMap's red/orange trail rendering: a vivid blue route, orange waypoints.
private val ROUTE_COLOR = 0xFF2962FF.toInt() // vivid blue
private val WAYPOINT_FILL = 0xFFFF6D00.toInt() // orange
private val WAYPOINT_STROKE = 0xFFFFFFFF.toInt() // white halo
private const val ROUTE_STROKE_WIDTH = 8f
private const val WAYPOINT_RADIUS = 9f
private const val WAYPOINT_STROKE_WIDTH = 3f

// Leave a margin around the trace when fitting it to the viewport, so the route does not sit
// flush against the screen edges; achieved by fitting against a shrunken viewport.
private const val FIT_PADDING_FRACTION = 0.85

// Used when the viewport has no measured size yet, or the route is a single point with no extent
// to fit. Matches RudyMapView's INITIAL_ZOOM.
private const val FIT_FALLBACK_ZOOM: Byte = 15
private const val FIT_MIN_ZOOM: Byte = 0

// Matches RudyMapView's configured zoom-level max.
private const val FIT_MAX_ZOOM: Byte = 22

/** Adds a routed-path polyline layer and returns it (caller owns removal). */
internal fun MapView.addRoutePolyline(points: List<LatLong>): Polyline {
    val paint =
        AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(ROUTE_COLOR)
            setStrokeWidth(ROUTE_STROKE_WIDTH)
            setStyle(Style.STROKE)
        }
    val line = Polyline(paint, AndroidGraphicFactory.INSTANCE)
    line.latLongs.addAll(points)
    layerManager.layers.add(line)
    return line
}

/** Adds a filled waypoint dot layer and returns it (caller owns removal). */
internal fun MapView.addWaypointMarker(point: LatLong): FixedPixelCircle {
    val fill =
        AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(WAYPOINT_FILL)
            setStyle(Style.FILL)
        }
    val stroke =
        AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(WAYPOINT_STROKE)
            setStrokeWidth(WAYPOINT_STROKE_WIDTH)
            setStyle(Style.STROKE)
        }
    val circle = FixedPixelCircle(point, WAYPOINT_RADIUS, fill, stroke)
    layerManager.layers.add(circle)
    return circle
}

/**
 * Pans and zooms the map so the whole [points] set fits within the current viewport, roughly
 * centred. No-op on empty input. Falls back to [FIT_FALLBACK_ZOOM] when the view has not been
 * measured yet or the route is a single point (no extent to fit). Used only when loading a saved
 * route from file - not on save or cancel - so opening a route always frames the entire trace.
 */
internal fun MapView.fitToRoute(points: List<LatLong>) {
    if (points.isEmpty()) return
    val bbox = BoundingBox(points)
    val dim = dimension
    val zoom =
        when {
            dim == null || dim.width <= 0 || dim.height <= 0 -> FIT_FALLBACK_ZOOM
            points.size == 1 -> FIT_FALLBACK_ZOOM
            else -> {
                val padded =
                    Dimension(
                        (dim.width * FIT_PADDING_FRACTION).toInt().coerceAtLeast(1),
                        (dim.height * FIT_PADDING_FRACTION).toInt().coerceAtLeast(1),
                    )
                LatLongUtils.zoomForBounds(padded, bbox, model.displayModel.tileSize)
            }
        }.coerceIn(FIT_MIN_ZOOM, FIT_MAX_ZOOM)
    model.mapViewPosition.mapPosition = MapPosition(bbox.centerPoint, zoom)
}
