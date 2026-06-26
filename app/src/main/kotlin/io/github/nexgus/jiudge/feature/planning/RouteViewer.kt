package io.github.nexgus.jiudge.feature.planning

import io.github.nexgus.jiudge.core.elevation.DemElevation
import io.github.nexgus.jiudge.data.route.PlannedRoute
import org.mapsforge.map.android.view.MapView

/**
 * Displays a saved [PlannedRoute] read-only on the map through a [PlannedRouteLayer], using the same
 * zoom-aware styling as the live planner. v1 load is view-only; editing a loaded route is a v2
 * candidate (see CLAUDE.md scope discipline). Call [clear] to remove it.
 *
 * [density] feeds the overlay so markers stay a constant physical size; [dem] feeds slope colouring.
 */
class RouteViewer(
    private val mapView: MapView,
    private val density: Float,
    private val dem: DemElevation?,
) {
    private var layer: PlannedRouteLayer? = null

    fun show(route: PlannedRoute) {
        val overlay =
            layer ?: PlannedRouteLayer(density, dem).also {
                layer = it
                mapView.layerManager.layers.add(it)
            }
        overlay.update(route.waypoints, route.segments)
        mapView.layerManager.redrawLayers()
    }

    fun clear() {
        layer?.let { mapView.layerManager.layers.remove(it) }
        layer = null
        mapView.layerManager.redrawLayers()
    }
}
