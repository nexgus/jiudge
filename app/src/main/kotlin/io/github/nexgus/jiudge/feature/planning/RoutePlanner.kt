package io.github.nexgus.jiudge.feature.planning

import androidx.compose.runtime.mutableStateListOf
import io.github.nexgus.jiudge.core.elevation.DemElevation
import io.github.nexgus.jiudge.core.routing.BRouterEngine
import io.github.nexgus.jiudge.core.routing.RoutingException
import io.github.nexgus.jiudge.core.routing.ToughPathDetector
import io.github.nexgus.jiudge.data.route.PlannedRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.map.android.view.MapView

/**
 * Drives an in-progress route plan over a mapsforge [MapView]: holds the placed waypoints, asks
 * [BRouterEngine] for the shortest on-trail path between consecutive waypoints, and renders them
 * through a single [PlannedRouteLayer]. New waypoints come from the map center (the on-screen
 * crosshair). Active only while planning mode is on; call [clear] when leaving it.
 *
 * Waypoints are Compose snapshot state so the bottom-bar button states recompose as they change.
 * [density] feeds the zoom-aware overlay; [dem] feeds its slope colouring (null -> grey route).
 * [toughDetector] (null when the basemap is unavailable) lets a leg whose endpoint sits on a
 * RudyMap "艱難路線" be routed through it instead of detoured around (the strict profile blocks it).
 */
class RoutePlanner(
    private val mapView: MapView,
    private val engine: BRouterEngine,
    private val density: Float,
    private val dem: DemElevation?,
    private val toughDetector: ToughPathDetector? = null,
) {
    // Waypoints rather than bare LatLongs: a plan loaded for editing carries each point's optional
    // name (spec §5.1), and re-saving must not silently drop it.
    private val _waypoints = mutableStateListOf<PlannedRoute.Waypoint>()
    val waypoints: List<PlannedRoute.Waypoint> get() = _waypoints

    // segments[i] is the geometry from waypoint[i] to waypoint[i+1]. Snapshot state (like the
    // waypoints) so the bottom bar's "-" enablement recomposes off canRemoveLast. The whole overlay
    // is re-pushed to the layer on every change; rebuilding the marker set is cheap.
    private val segments = mutableStateListOf<PlannedRoute.Segment>()
    private var layer: PlannedRouteLayer? = null

    // The single spelling of the imported-geometry protection: an imported segment cannot be
    // recomputed once dropped or re-snapped, so both "-" and the endpoint re-snap key off this.
    private val lastSegmentImported: Boolean
        get() = segments.lastOrNull()?.imported == true

    /**
     * Whether "-" may act: false when the leg it would delete is imported GPX geometry, which cannot
     * be recomputed once dropped (a routed leg can always be re-added via "+"). Also false with
     * nothing to remove at all.
     */
    val canRemoveLast: Boolean
        get() = _waypoints.isNotEmpty() && !lastSegmentImported

    /**
     * Adds the map-center point as the next waypoint. From the second on, routes from the previous
     * waypoint and draws the returned path. Returns null on success, or an error message (e.g.
     * BRouter could not reach the point) - in which case nothing is added.
     *
     * BRouter snaps both endpoints of the returned track to the nearest routable node, so the
     * placed point is re-set to the matched endpoint (`path.first()`/`path.last()`) rather than the
     * raw map-center tap. This keeps the waypoint dot on the route even when the user tapped while
     * zoomed out, where the tap can land well off the trail. The previous waypoint is re-snapped on
     * `path.first()` too, which also fixes the very first waypoint (placed before any routing).
     *
     * The A* search runs on [Dispatchers.Default]; call this from a coroutine off the main thread.
     */
    suspend fun addWaypointAtCenter(): String? {
        val center = mapView.model.mapViewPosition.center
        if (_waypoints.isEmpty()) {
            _waypoints.add(PlannedRoute.Waypoint(center))
            pushOverlay()
            return null
        }
        val from = _waypoints.last().point
        val path =
            try {
                withContext(Dispatchers.Default) {
                    // Allow tough paths on this leg only when an endpoint actually sits on one, so the
                    // route heads straight to a waypoint placed there rather than detouring around it.
                    val allowTough =
                        toughDetector?.let { it.isOnToughPath(from) || it.isOnToughPath(center) } ?: false
                    engine.route(from, center, allowTough)
                }
            } catch (e: RoutingException) {
                return e.message ?: "routing failed"
            }
        // Move both endpoints onto BRouter's snapped positions (the track is never empty on success)
        // - except a waypoint that ends an imported segment: that one must stay on the imported
        // geometry, so only the new endpoint snaps (the tiny gap to the snapped route start is
        // bridged by the flattened polyline).
        if (!lastSegmentImported) {
            _waypoints[_waypoints.lastIndex] = _waypoints.last().copy(point = path.first())
        }
        segments.add(PlannedRoute.Segment(path))
        _waypoints.add(PlannedRoute.Waypoint(path.last()))
        pushOverlay()
        return null
    }

    /**
     * Removes the most recently added waypoint and the segment leading into it. No-op when that
     * segment is imported GPX geometry (see [canRemoveLast]; the UI disables "-" then).
     */
    fun removeLastWaypoint() {
        if (!canRemoveLast) return
        _waypoints.removeAt(_waypoints.lastIndex)
        if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
        pushOverlay()
    }

    fun toPlannedRoute(
        name: String,
        createdAtEpochMs: Long,
    ): PlannedRoute = PlannedRoute(name, createdAtEpochMs, _waypoints.toList(), segments.toList())

    /**
     * Replaces the current plan with a saved [route] and draws it, leaving it fully editable
     * (waypoints and their segments restored so "-" can drop them one at a time). Recenters on the
     * first waypoint. No routing happens here, so it works even when BRouter data is absent.
     */
    fun loadFrom(route: PlannedRoute) {
        clear()
        _waypoints.addAll(route.waypoints)
        segments.addAll(route.segments)
        pushOverlay()
        route.waypoints.firstOrNull()?.let { mapView.model.mapViewPosition.center = it.point }
    }

    /** Removes the overlay layer and resets state. */
    fun clear() {
        segments.clear()
        _waypoints.clear()
        layer?.let { mapView.layerManager.layers.remove(it) }
        layer = null
        mapView.layerManager.redrawLayers()
    }

    /** Pushes the current waypoints + segments to the (lazily added) overlay layer. */
    private fun pushOverlay() {
        val overlay =
            layer ?: PlannedRouteLayer(density, dem).also {
                layer = it
                mapView.layerManager.layers.add(it)
            }
        overlay.update(_waypoints.map { it.point }, segments.map { it.points })
        mapView.layerManager.redrawLayers()
    }
}
