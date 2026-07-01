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
 * Process-wide owner of the platform [LocationManager] subscription. The map screen (foreground
 * marker) and [io.github.nexgus.jiudge.core.recording.RecordingService] both read the same
 * [fix] StateFlow, so a track's leading edge and the on-screen dot cannot drift apart the way two
 * independent listeners used to.
 *
 * Ownership is a strict lease: [acquire] throws when a lease is already outstanding, so the caller
 * must [Ownership.release] first. The activity guards the transition by releasing its Foreground
 * lease before dispatching a Recording start Intent, and reacquires when [RecordingController]
 * flips back to IDLE.
 */
object GpsSource {
    enum class Mode { Foreground, Recording }

    interface Ownership {
        val mode: Mode

        fun release()
    }

    private val _fix = MutableStateFlow<LocationFix?>(null)
    val fix: StateFlow<LocationFix?> = _fix.asStateFlow()

    // Whether the system-wide location service (the master toggle, not this app's permission) is on.
    private val _serviceEnabled = MutableStateFlow(false)
    val serviceEnabled: StateFlow<Boolean> = _serviceEnabled.asStateFlow()

    // Live GNSS reception, populated only while a fine-location subscription is active.
    private val _gnss = MutableStateFlow<GnssSummary?>(null)
    val gnss: StateFlow<GnssSummary?> = _gnss.asStateFlow()

    // Whether ACCESS_FINE_LOCATION is currently granted. Refreshed on every [acquire] and on
    // [refreshPermissionState], so the banner picks up a settings-page grant/revoke.
    private val _fineLocationGranted = MutableStateFlow(false)
    val fineLocationGranted: StateFlow<Boolean> = _fineLocationGranted.asStateFlow()

    private var appContext: Context? = null
    private var currentOwner: OwnershipImpl? = null
    private var lastLocation: Location? = null
    private var subscribed = false

    private val listener =
        object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)

            override fun onProviderEnabled(provider: String) {
                appContext?.let { _serviceEnabled.value = computeServiceEnabled(it) }
            }

            override fun onProviderDisabled(provider: String) {
                appContext?.let { _serviceEnabled.value = computeServiceEnabled(it) }
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
     * Take the subscription lease. Throws [IllegalStateException] when a lease is already
     * outstanding. Foreground mode subscribes to `gps + network` (or just `network` under a
     * coarse-only grant); Recording mode subscribes to `gps` only, so no network fix ever pollutes
     * a recorded track. Without any location permission the returned [Ownership] is real but the
     * underlying subscription is empty; the StateFlows stay at their last values.
     */
    fun acquire(
        context: Context,
        mode: Mode,
    ): Ownership {
        val existing = currentOwner
        if (existing != null) {
            throw IllegalStateException(
                "GpsSource is already owned by ${existing.mode}; call release() before re-acquiring.",
            )
        }
        val ctx = context.applicationContext
        appContext = ctx
        val owner = OwnershipImpl(mode)
        currentOwner = owner
        startSubscription(ctx, mode)
        return owner
    }

    /** True once the OS has granted fine or coarse location to this app. */
    fun hasPermission(context: Context): Boolean =
        hasFinePermission(context) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True only when precise (fine) location is granted - the prerequisite for satellite status. */
    fun hasFinePermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether the device's location service master switch is on (any provider usable). */
    fun isLocationServiceEnabled(context: Context): Boolean = computeServiceEnabled(context)

    /**
     * Resynchronises [fineLocationGranted] with the OS. Useful on `ON_RESUME` so a permission grant
     * or revocation made from the system settings page (where no `ActivityResultLauncher` fires) is
     * picked up immediately.
     */
    fun refreshPermissionState(context: Context) {
        _fineLocationGranted.value = hasFinePermission(context)
    }

    private fun computeServiceEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun releaseOwnership(owner: OwnershipImpl) {
        if (currentOwner !== owner) return
        stopSubscription()
        currentOwner = null
    }

    private class OwnershipImpl(
        override val mode: Mode,
    ) : Ownership {
        private var released = false

        override fun release() {
            if (released) return
            released = true
            releaseOwnership(this)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSubscription(
        context: Context,
        mode: Mode,
    ) {
        if (subscribed) stopSubscription()
        _fineLocationGranted.value = hasFinePermission(context)
        _serviceEnabled.value = computeServiceEnabled(context)
        if (!hasPermission(context)) return
        val fine = _fineLocationGranted.value
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers =
            when (mode) {
                Mode.Foreground -> {
                    val enabled = lm.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
                    // GPS_PROVIDER requires ACCESS_FINE_LOCATION; subscribing without it throws
                    // SecurityException at runtime. Skip it under a coarse-only grant and keep the
                    // network provider as an indoor fallback.
                    if (fine) enabled else enabled.filter { it != LocationManager.GPS_PROVIDER }
                }
                // Recording refuses network fixes: the recorded track must be a real movement trace,
                // and a coarse cell/WiFi position would poison the polyline with non-positions. If
                // fine is missing here the caller is expected to have bailed out before acquiring.
                Mode.Recording -> if (fine) listOf(LocationManager.GPS_PROVIDER) else emptyList()
            }
        if (providers.isEmpty()) return
        seedLastKnown(lm, providers)
        for (provider in providers) {
            lm.requestLocationUpdates(
                provider,
                MIN_TIME_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
        }
        if (fine) {
            lm.registerGnssStatusCallback(gnssCallback, Handler(Looper.getMainLooper()))
        }
        subscribed = true
    }

    private fun stopSubscription() {
        if (!subscribed) return
        val ctx = appContext
        if (ctx != null) {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(listener)
            lm.unregisterGnssStatusCallback(gnssCallback)
        }
        // Clear GNSS but keep _fix / lastLocation so a returning owner sees the last known position
        // immediately (per gps-source-refactor "release preserves fix" decision).
        _gnss.value = null
        subscribed = false
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnown(
        lm: LocationManager,
        providers: List<String>,
    ) {
        var best: Location? = null
        for (provider in providers) {
            val candidate = lm.getLastKnownLocation(provider) ?: continue
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

    /**
     * Two rules: drop a fix that is clearly older than the current one, and hold a still-recent GPS
     * fix against incoming network fixes so a briefly missing GPS cycle does not jump the marker to
     * a cell/WiFi position. No accuracy tie-break (see gps-source-refactor decision 4): under 1 Hz
     * updates the old tie-break froze the marker for seconds at a time when consecutive fixes
     * happened to report worse-than-current accuracy.
     */
    private fun isBetter(
        candidate: Location,
        current: Location?,
    ): Boolean {
        if (current == null) return true
        val ageDeltaMs = candidate.time - current.time
        if (ageDeltaMs < -SIGNIFICANT_TIME_MS) return false
        if (isGps(current) && !isGps(candidate) && ageDeltaMs < GPS_PREFERENCE_MS) return false
        return true
    }

    private fun isGps(location: Location): Boolean = location.provider == LocationManager.GPS_PROVIDER

    private const val MIN_TIME_MS = 1_000L
    private const val MIN_DISTANCE_M = 1f

    // Below ~0.5 m/s the device is effectively still; its reported bearing is unreliable.
    private const val MIN_BEARING_SPEED = 0.5f

    // A fix more than 5 s newer wins regardless of provider (via the ageDeltaMs stale check going
    // the other way); within the window the GPS-preference rule below decides.
    private const val SIGNIFICANT_TIME_MS = 5_000L

    // How long a recent GPS fix is held against incoming network fixes before they may take over.
    private const val GPS_PREFERENCE_MS = 20_000L
}
