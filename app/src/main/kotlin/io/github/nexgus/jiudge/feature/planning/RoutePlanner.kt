package io.github.nexgus.jiudge.feature.planning

import androidx.compose.runtime.mutableStateListOf
import io.github.nexgus.jiudge.core.elevation.DemElevation
import io.github.nexgus.jiudge.core.routing.BRouterEngine
import io.github.nexgus.jiudge.core.routing.RoutingException
import io.github.nexgus.jiudge.core.routing.ToughPathDetector
import io.github.nexgus.jiudge.data.route.PlannedRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
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
    private val _waypoints = mutableStateListOf<LatLong>()
    val waypoints: List<LatLong> get() = _waypoints

    // segments[i] is the routed geometry from waypoint[i] to waypoint[i+1]. The whole overlay is
    // re-pushed to the layer on every change; rebuilding the marker set is cheap.
    private val segments = mutableListOf<List<LatLong>>()
    private var layer: PlannedRouteLayer? = null

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
            _waypoints.add(center)
            pushOverlay()
            return null
        }
        val from = _waypoints.last()
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
        segments.add(path)
        // Move both endpoints onto BRouter's snapped positions (the track is never empty on success).
        _waypoints[_waypoints.lastIndex] = path.first()
        _waypoints.add(path.last())
        pushOverlay()
        return null
    }

    /** Removes the most recently added waypoint and the segment leading into it. */
    fun removeLastWaypoint() {
        if (_waypoints.isEmpty()) return
        _waypoints.removeAt(_waypoints.lastIndex)
        if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
        pushOverlay()
    }

    fun toPlannedRoute(
        name: String,
        createdAtEpochMs: Long,
    ): PlannedRoute = PlannedRoute(name, createdAtEpochMs, _waypoints.toList(), segments.map { it.toList() })

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
        route.waypoints.firstOrNull()?.let { mapView.model.mapViewPosition.center = it }
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
        overlay.update(_waypoints.toList(), segments.map { it.toList() })
        mapView.layerManager.redrawLayers()
    }
}
