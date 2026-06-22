package io.github.nexgus.jiudge.feature.planning

import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
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

/** Adds a routed-path polyline layer and returns it (caller owns removal). */
internal fun MapView.addRoutePolyline(points: List<LatLong>): Polyline {
    val paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
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
    val fill = AndroidGraphicFactory.INSTANCE.createPaint().apply {
        setColor(WAYPOINT_FILL)
        setStyle(Style.FILL)
    }
    val stroke = AndroidGraphicFactory.INSTANCE.createPaint().apply {
        setColor(WAYPOINT_STROKE)
        setStrokeWidth(WAYPOINT_STROKE_WIDTH)
        setStyle(Style.STROKE)
    }
    val circle = FixedPixelCircle(point, WAYPOINT_RADIUS, fill, stroke)
    layerManager.layers.add(circle)
    return circle
}
