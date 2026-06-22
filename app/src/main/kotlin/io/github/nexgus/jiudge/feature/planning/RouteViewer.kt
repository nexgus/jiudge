package io.github.nexgus.jiudge.feature.planning

import io.github.nexgus.jiudge.data.route.PlannedRoute
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer

/**
 * Displays a saved [PlannedRoute] read-only on the map (its polyline plus waypoint dots), using
 * the same styling as the live planner. v1 load is view-only; editing a loaded route is a v2
 * candidate (see CLAUDE.md scope discipline). Call [clear] to remove it.
 */
class RouteViewer(private val mapView: MapView) {
    private val layers = mutableListOf<Layer>()

    fun show(route: PlannedRoute) {
        clear()
        if (route.polyline.isNotEmpty()) {
            layers.add(mapView.addRoutePolyline(route.polyline))
        }
        route.waypoints.forEach { layers.add(mapView.addWaypointMarker(it)) }
        mapView.layerManager.redrawLayers()
    }

    fun clear() {
        layers.forEach { mapView.layerManager.layers.remove(it) }
        layers.clear()
        mapView.layerManager.redrawLayers()
    }
}
