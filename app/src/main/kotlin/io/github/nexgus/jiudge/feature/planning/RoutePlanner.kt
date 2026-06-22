package io.github.nexgus.jiudge.feature.planning

import androidx.compose.runtime.mutableStateListOf
import io.github.nexgus.jiudge.core.routing.BRouterEngine
import io.github.nexgus.jiudge.core.routing.RoutingException
import io.github.nexgus.jiudge.data.route.PlannedRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer

/**
 * Drives an in-progress route plan over a mapsforge [MapView]: holds the placed waypoints, asks
 * [BRouterEngine] for the shortest on-trail path between consecutive waypoints, and maintains the
 * marker / polyline overlay layers. New waypoints come from the map center (the on-screen
 * crosshair). Active only while planning mode is on; call [clear] when leaving it.
 *
 * Waypoints are Compose snapshot state so the bottom-bar button states recompose as they change.
 */
class RoutePlanner(
    private val mapView: MapView,
    private val engine: BRouterEngine,
) {
    private val _waypoints = mutableStateListOf<LatLong>()
    val waypoints: List<LatLong> get() = _waypoints

    // segments[i] is the routed geometry from waypoint[i] to waypoint[i+1]; parallel layer lists
    // let "+"/"-" touch only the tail without rebuilding the whole overlay.
    private val segments = mutableListOf<List<LatLong>>()
    private val markerLayers = mutableListOf<Layer>()
    private val segmentLayers = mutableListOf<Layer>()

    /**
     * Adds the map-center point as the next waypoint. From the second on, routes from the previous
     * waypoint and draws the returned path. Returns null on success, or an error message (e.g.
     * BRouter could not reach the point) - in which case nothing is added.
     *
     * The A* search runs on [Dispatchers.Default]; call this from a coroutine off the main thread.
     */
    suspend fun addWaypointAtCenter(): String? {
        val center = mapView.model.mapViewPosition.center
        if (_waypoints.isNotEmpty()) {
            val path = try {
                withContext(Dispatchers.Default) { engine.route(_waypoints.last(), center) }
            } catch (e: RoutingException) {
                return e.message ?: "routing failed"
            }
            segments.add(path)
            segmentLayers.add(mapView.addRoutePolyline(path))
        }
        _waypoints.add(center)
        markerLayers.add(mapView.addWaypointMarker(center))
        mapView.layerManager.redrawLayers()
        return null
    }

    /** Removes the most recently added waypoint and the segment leading into it. */
    fun removeLastWaypoint() {
        if (_waypoints.isEmpty()) return
        _waypoints.removeAt(_waypoints.lastIndex)
        mapView.layerManager.layers.remove(markerLayers.removeAt(markerLayers.lastIndex))
        if (segments.isNotEmpty()) {
            segments.removeAt(segments.lastIndex)
            mapView.layerManager.layers.remove(segmentLayers.removeAt(segmentLayers.lastIndex))
        }
        mapView.layerManager.redrawLayers()
    }

    fun toPlannedRoute(name: String, createdAtEpochMs: Long): PlannedRoute =
        PlannedRoute(name, createdAtEpochMs, _waypoints.toList(), segments.map { it.toList() })

    /**
     * Replaces the current plan with a saved [route] and draws it, leaving it fully editable
     * (waypoints and their segments restored so "-" can drop them one at a time). Recenters on the
     * first waypoint. No routing happens here, so it works even when BRouter data is absent.
     */
    fun loadFrom(route: PlannedRoute) {
        clear()
        route.waypoints.forEachIndexed { i, wp ->
            // The segment leading into this waypoint (skip for the first, guard against short files).
            if (i > 0 && i - 1 < route.segments.size) {
                val seg = route.segments[i - 1]
                segments.add(seg)
                segmentLayers.add(mapView.addRoutePolyline(seg))
            }
            _waypoints.add(wp)
            markerLayers.add(mapView.addWaypointMarker(wp))
        }
        route.waypoints.firstOrNull()?.let { mapView.model.mapViewPosition.center = it }
        mapView.layerManager.redrawLayers()
    }

    /** Removes every overlay layer and resets state. */
    fun clear() {
        (markerLayers + segmentLayers).forEach { mapView.layerManager.layers.remove(it) }
        markerLayers.clear()
        segmentLayers.clear()
        segments.clear()
        _waypoints.clear()
        mapView.layerManager.redrawLayers()
    }
}
