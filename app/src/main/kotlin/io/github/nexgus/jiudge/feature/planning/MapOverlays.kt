package io.github.nexgus.jiudge.feature.planning

import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.map.android.view.MapView

// The route/waypoint overlay drawing now lives in [PlannedRouteLayer] (a single zoom-aware custom
// layer shared by [RoutePlanner] and [RouteViewer]). This file keeps only viewport framing.

// Leave a margin around the trace when fitting it to the viewport, so the route does not sit
// flush against the screen edges; achieved by fitting against a shrunken viewport.
private const val FIT_PADDING_FRACTION = 0.85

// Used when the viewport has no measured size yet, or the route is a single point with no extent
// to fit. Matches RudyMapView's INITIAL_ZOOM.
private const val FIT_FALLBACK_ZOOM: Byte = 15
private const val FIT_MIN_ZOOM: Byte = 0

// Matches RudyMapView's configured zoom-level max.
private const val FIT_MAX_ZOOM: Byte = 22

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
