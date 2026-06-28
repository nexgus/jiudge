package io.github.nexgus.jiudge.feature.map

import android.content.Context
import io.github.nexgus.jiudge.core.location.LocationFix
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Rotation
import org.mapsforge.map.android.view.MapView

/** Map orientation. NORTH_UP keeps north pinned; TRACK_UP rotates so the compass facing is up. */
enum class MapBearingMode { NORTH_UP, TRACK_UP }

/**
 * Persists [MapBearingMode] across app launches in a dedicated SharedPreferences file. The default
 * stays NORTH_UP - it is what the existing render theme is built for, and matches v1 spec wording.
 */
object MapBearingPrefs {
    private const val PREFS_NAME = "map_view_prefs"
    private const val KEY_BEARING_MODE = "bearing_mode"

    fun load(context: Context): MapBearingMode {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_BEARING_MODE, null) ?: return MapBearingMode.NORTH_UP
        return runCatching { MapBearingMode.valueOf(raw) }.getOrDefault(MapBearingMode.NORTH_UP)
    }

    fun save(
        context: Context,
        mode: MapBearingMode,
    ) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BEARING_MODE, mode.name)
            .apply()
    }
}

/**
 * Safe-zone "soft follow" geometry. The current-location marker is left alone while it sits inside a
 * rectangle inset from the viewport by [SAFE_ZONE_INSET_FRACTION]; when an incoming GPS fix would push
 * it outside that inset (but it is still inside the viewport), the map is recentred on the fix so the
 * marker returns to the viewport centre. Once the marker is already outside the viewport ("free" mode)
 * the follow is silent: only the explicit recentre button brings it back.
 *
 * The follow caller decides between an animated pan and an instant jump using [JUMP_THRESHOLD_METERS]
 * - a tunnel-exit GPS jump should not crawl across the screen.
 */
object MapFollow {
    /** Each viewport edge is treated as this fraction of the viewport's extent away from the centre. */
    const val SAFE_ZONE_INSET_FRACTION = 0.15f

    /** Movement beyond this between consecutive fixes is treated as a GPS jump (skip the animation). */
    const val JUMP_THRESHOLD_METERS = 100.0

    /** Outcome of [evaluate] for a single GPS fix against the current map viewport. */
    sealed interface Action {
        /** Marker is in the safe zone (or off-viewport): do nothing. */
        object None : Action

        /** Marker is in the viewport but outside the safe zone: pan the map so the fix is centred. */
        data class Push(
            val target: LatLong,
            val animate: Boolean,
        ) : Action
    }

    /**
     * Decides whether [fix] should trigger a recenter. Returns [Action.None] when the marker is inside
     * the safe zone or outside the viewport entirely, [Action.Push] otherwise. [lastFix] is consulted
     * only to detect a GPS jump (so animation is skipped); null means "no prior reference".
     */
    fun evaluate(
        mapView: MapView,
        fix: LocationFix,
        lastFix: LocationFix?,
    ): Action {
        val width = mapView.width
        val height = mapView.height
        if (width <= 0 || height <= 0) return Action.None
        val point = mapView.mapViewProjection.toPixels(LatLong(fix.latitude, fix.longitude)) ?: return Action.None
        val inViewport = point.x in 0.0..width.toDouble() && point.y in 0.0..height.toDouble()
        if (!inViewport) return Action.None
        val insetX = width * SAFE_ZONE_INSET_FRACTION
        val insetY = height * SAFE_ZONE_INSET_FRACTION
        val inSafeZone =
            point.x in insetX..(width - insetX) &&
                point.y in insetY..(height - insetY)
        if (inSafeZone) return Action.None
        val animate = lastFix == null || haversineMeters(lastFix, fix) <= JUMP_THRESHOLD_METERS
        return Action.Push(LatLong(fix.latitude, fix.longitude), animate)
    }

    /**
     * True when the projection of [fix] lands inside the [mapView]'s pixel rectangle. Used to drive
     * the "recentre" button's lit state (lit only when the marker has actually drifted off-screen, so
     * the user knows the map is no longer tracking them). False before the map is laid out or when no
     * fix has arrived yet.
     */
    fun isMarkerInViewport(
        mapView: MapView,
        fix: LocationFix?,
    ): Boolean {
        if (fix == null) return false
        val width = mapView.width
        val height = mapView.height
        if (width <= 0 || height <= 0) return false
        val point = mapView.mapViewProjection.toPixels(LatLong(fix.latitude, fix.longitude)) ?: return false
        return point.x in 0.0..width.toDouble() && point.y in 0.0..height.toDouble()
    }

    /** Great-circle distance between two fixes in metres. */
    private fun haversineMeters(
        a: LocationFix,
        b: LocationFix,
    ): Double {
        val earthR = 6_371_000.0
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val sinDLat = Math.sin(dLat / 2)
        val sinDLng = Math.sin(dLng / 2)
        val h = sinDLat * sinDLat + Math.cos(lat1) * Math.cos(lat2) * sinDLng * sinDLng
        return 2 * earthR * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }
}

/**
 * Map-bearing application. Computes the [Rotation] for a given [MapBearingMode] + compass heading and
 * pushes it to [MapView]. TRACK_UP rotates the map so the user's compass facing points to the top of
 * the screen; NORTH_UP clears any prior rotation. The pivot is the current viewport centre, so the
 * map rotates in place rather than swinging the marker around.
 */
fun applyMapBearing(
    mapView: MapView,
    mode: MapBearingMode,
    headingDeg: Float?,
) {
    val width = mapView.width
    val height = mapView.height
    val rotation =
        when (mode) {
            MapBearingMode.NORTH_UP -> Rotation.NULL_ROTATION
            MapBearingMode.TRACK_UP -> {
                if (headingDeg == null || width <= 0 || height <= 0) return
                // mapsforge rotation degrees rotate the map clockwise; the compass heading is the
                // direction the user faces (also clockwise from north). To put facing at the top we
                // need to rotate the map counter-clockwise by that heading, i.e. negate it.
                Rotation(-headingDeg, width / 2f, height / 2f)
            }
        }
    mapView.model.mapViewPosition.rotation = rotation
}
