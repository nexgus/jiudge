package io.github.nexgus.jiudge.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
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
    /** GPS altitude above the WGS84 ellipsoid in metres, or null when the fix carries none. */
    val altitudeMeters: Double?,
    /** Vertical accuracy of [altitudeMeters] in metres (68% confidence), or null when unavailable. */
    val verticalAccuracyMeters: Float?,
    /** Ground speed in metres per second, or null when the fix carries none. */
    val speedMps: Float?,
    /** The platform provider that produced this fix (e.g. `gps`, `network`), or null when unknown. */
    val provider: String?,
    /** UTC time of the fix in epoch milliseconds. */
    val timeMs: Long,
)

/**
 * A snapshot of GNSS satellite reception, independent of any single fix. Populated only while a
 * fine-location subscription is active (the satellite callback needs `ACCESS_FINE_LOCATION`); a
 * coarse-only grant leaves this null.
 */
data class GnssSummary(
    /** Number of satellites currently tracked (in view), across all constellations. */
    val satellitesInView: Int,
    /** Number of those satellites actually used in the position fix. */
    val satellitesUsedInFix: Int,
    /** Mean carrier-to-noise density (C/N0, dB-Hz) over the satellites used in the fix, or null when none. */
    val averageCn0DbHz: Float?,
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

    // Live GNSS reception, fed by the satellite-status callback. Null until the first status arrives,
    // and reset to null on stop; only populated when a fine-location subscription is active.
    private val _gnss = MutableStateFlow<GnssSummary?>(null)
    val gnss: StateFlow<GnssSummary?> = _gnss.asStateFlow()

    // Whether ACCESS_FINE_LOCATION is currently granted. Refreshed on every start() so the UI can
    // observe a permission upgrade made from the system settings page without further plumbing.
    private val _fineLocationGranted = MutableStateFlow(hasFinePermission())
    val fineLocationGranted: StateFlow<Boolean> = _fineLocationGranted.asStateFlow()

    private var lastLocation: Location? = null
    private var started = false

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

    // Summarises each satellite-status update into counts + mean C/N0 of the satellites used in the
    // fix. Registered only with fine location; harmless to keep around when no callback is firing.
    private val gnssCallback =
        object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                val inView = status.satelliteCount
                var used = 0
                var cn0Sum = 0f
                for (i in 0 until inView) {
                    if (status.usedInFix(i)) {
                        used++
                        cn0Sum += status.getCn0DbHz(i)
                    }
                }
                _gnss.value =
                    GnssSummary(
                        satellitesInView = inView,
                        satellitesUsedInFix = used,
                        averageCn0DbHz = if (used > 0) cn0Sum / used else null,
                    )
            }
        }

    /**
     * Resynchronises [fineLocationGranted] with the OS. Useful on `ON_RESUME` so a permission grant
     * or revocation made from the system settings page (where no `ActivityResultLauncher` fires) is
     * picked up immediately.
     */
    fun refreshPermissionState() {
        _fineLocationGranted.value = hasFinePermission()
    }

    /** True once the OS has granted fine or coarse location to this app. */
    fun hasPermission(): Boolean =
        hasFinePermission() ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True only when precise (fine) location is granted - the prerequisite for satellite status. */
    fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
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
     *
     * Idempotent: calling [start] again rechecks permissions and resubscribes. The caller may invoke
     * it on every `ON_RESUME` so that a permission change made in the system settings page (e.g. a
     * coarse-to-fine upgrade) takes effect on return.
     */
    @SuppressLint("MissingPermission")
    fun start() {
        // Release any prior subscription first so a re-entry on ON_RESUME does not double-register
        // the GNSS callback or leave a stale listener bound to old permission state.
        if (started) stop()
        // Always reflect the current grant in the StateFlow, even when there is nothing to subscribe
        // to: a permission revoked from the system settings page lands here through the early
        // return below, and the banner must still update.
        refreshPermissionState()
        if (!hasPermission()) return
        _serviceEnabled.value = isLocationServiceEnabled()
        val fine = _fineLocationGranted.value
        val providers = locationManager.getProviders(true)
        seedLastKnown(providers, fine)
        for (provider in providers) {
            if (provider == LocationManager.PASSIVE_PROVIDER) continue
            // GPS_PROVIDER requires ACCESS_FINE_LOCATION; subscribing without it throws
            // SecurityException at runtime (the @SuppressLint annotation only silences the lint
            // check). Skip it under a coarse-only grant and keep the network provider.
            if (provider == LocationManager.GPS_PROVIDER && !fine) continue
            locationManager.requestLocationUpdates(
                provider,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
        }
        // Satellite status needs fine location; with a coarse-only grant we skip it and leave gnss null.
        if (fine) {
            locationManager.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
        }
        started = true
    }

    /** Stops all updates. Safe to call when not started. */
    fun stop() {
        locationManager.removeUpdates(listener)
        locationManager.unregisterGnssStatusCallback(gnssCallback)
        _gnss.value = null
        started = false
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnown(
        providers: List<String>,
        fine: Boolean,
    ) {
        var best: Location? = null
        for (provider in providers) {
            // Same fine-permission gate as the live subscription: getLastKnownLocation("gps")
            // throws SecurityException under a coarse-only grant.
            if (provider == LocationManager.GPS_PROVIDER && !fine) continue
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
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                verticalAccuracyMeters =
                    if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
                speedMps = if (location.hasSpeed()) location.speed else null,
                provider = location.provider,
                timeMs = location.time,
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
