package io.github.nexgus.jiudge.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A single GPS reading reduced to what the map overlay needs. */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy radius in metres (68% confidence), or null when the fix carries none. */
    val accuracyMeters: Float?,
    /** Direction of travel in degrees clockwise from north (true north), or null when not moving. */
    val bearingDeg: Float?,
    /**
     * Local magnetic declination in degrees: add this to a magnetic-north compass heading to get a
     * true-north heading, so the facing cone aligns with the true-north map. Taiwan is ~ -4 deg.
     */
    val declinationDeg: Float,
    /** True when this fix came from the GPS satellite provider (vs a coarse WiFi/cell network fix). */
    val fromGps: Boolean,
)

/**
 * Foreground-only current-location source. Subscribes to the platform [LocationManager] while the
 * map screen is visible and exposes the latest fix as a [StateFlow]; the caller is expected to
 * [start] on resume and [stop] on pause so no updates run in the background (route recording, which
 * needs a foreground service, is separate - see CLAUDE.md).
 *
 * No Google Play Services dependency: the bare platform API keeps the app fully offline and free of
 * Google libraries, consistent with the rest of the stack.
 */
class LocationProvider(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _fix = MutableStateFlow<LocationFix?>(null)
    val fix: StateFlow<LocationFix?> = _fix.asStateFlow()

    // Whether the system-wide location service (the master toggle, not this app's permission) is on.
    // Goes false when the user disables location; the caller then freezes the marker and warns.
    private val _serviceEnabled = MutableStateFlow(isLocationServiceEnabled())
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled.asStateFlow()

    private var lastLocation: Location? = null

    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)

            // The master switch flips every provider at once, so just re-read the overall state.
            override fun onProviderEnabled(provider: String) {
                _serviceEnabled.value = isLocationServiceEnabled()
            }

            override fun onProviderDisabled(provider: String) {
                _serviceEnabled.value = isLocationServiceEnabled()
            }
        }

    /** True once the OS has granted fine or coarse location to this app. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether the device's location service master switch is on (any provider usable). */
    fun isLocationServiceEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }

    /**
     * Begins receiving updates from every enabled provider (GPS for precision, network as a quick
     * indoor seed). Seeds [fix] with the freshest last-known location so the marker and recenter
     * button have something to show before the first live update arrives. No-op without permission.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        if (!hasPermission()) return
        _serviceEnabled.value = isLocationServiceEnabled()
        val providers = locationManager.getProviders(true)
        seedLastKnown(providers)
        for (provider in providers) {
            if (provider == LocationManager.PASSIVE_PROVIDER) continue
            locationManager.requestLocationUpdates(
                provider,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
        }
    }

    /** Stops all updates. Safe to call when not started. */
    fun stop() {
        locationManager.removeUpdates(listener)
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnown(providers: List<String>) {
        var best: Location? = null
        for (provider in providers) {
            val candidate = locationManager.getLastKnownLocation(provider) ?: continue
            if (isBetter(candidate, best)) best = candidate
        }
        if (best != null) onLocation(best)
    }

    private fun onLocation(location: Location) {
        if (!isBetter(location, lastLocation)) return
        lastLocation = location
        _fix.value =
            LocationFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                bearingDeg = movementBearing(location),
                declinationDeg = declinationAt(location),
                fromGps = isGps(location),
            )
    }

    /** Local magnetic declination from the world magnetic model; ~once per second is negligible cost. */
    private fun declinationAt(location: Location): Float =
        GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            if (location.hasAltitude()) location.altitude.toFloat() else 0f,
            location.time,
        ).declination

    /**
     * Trusts a fix's bearing only while actually moving: a stationary GPS reports a stale, noisy
     * bearing, so below [MIN_BEARING_SPEED] we report null and the overlay draws no direction arrow
     * (the compass, if present, supplies facing direction instead).
     */
    private fun movementBearing(location: Location): Float? =
        if (location.hasBearing() && location.hasSpeed() && location.speed >= MIN_BEARING_SPEED) {
            location.bearing
        } else {
            null
        }

    /** Prefers a clearly newer fix, falling back to better accuracy when readings are close in time. */
    private fun isBetter(
        candidate: Location,
        current: Location?,
    ): Boolean {
        if (current == null) return true
        val ageDeltaMs = candidate.time - current.time
        // Clearly older than what we already show: drop it.
        if (ageDeltaMs < -SIGNIFICANT_TIME_MS) return false
        // Prefer GPS: while the shown fix is a still-recent GPS reading, do not let a coarser
        // non-GPS (network) fix replace it - GPS briefly missing a cycle should not make the dot
        // jump to a cell/WiFi position. Once GPS has been silent past the hold window, fall through
        // so a stale GPS fix does not strand the marker forever.
        if (isGps(current) && !isGps(candidate) && ageDeltaMs < GPS_PREFERENCE_MS) return false
        // Clearly newer: take it on freshness.
        if (ageDeltaMs > SIGNIFICANT_TIME_MS) return true
        // Close in time: the more accurate reading wins.
        if (!candidate.hasAccuracy()) return false
        if (!current.hasAccuracy()) return true
        return candidate.accuracy <= current.accuracy
    }

    private fun isGps(location: Location): Boolean = location.provider == LocationManager.GPS_PROVIDER

    private companion object {
        const val MIN_TIME_MS = 1_000L
        const val MIN_DISTANCE_M = 1f

        // Below ~0.5 m/s the device is effectively still; its reported bearing is unreliable.
        const val MIN_BEARING_SPEED = 0.5f

        // A fix more than 5 s newer wins regardless of accuracy; within the window, accuracy decides.
        const val SIGNIFICANT_TIME_MS = 5_000L

        // How long a recent GPS fix is held against incoming network fixes before they may take over.
        const val GPS_PREFERENCE_MS = 20_000L
    }
}
