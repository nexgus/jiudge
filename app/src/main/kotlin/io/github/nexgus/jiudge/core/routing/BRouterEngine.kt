package io.github.nexgus.jiudge.core.routing

import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import org.mapsforge.core.model.LatLong
import java.io.File

/**
 * Thin in-process wrapper over BRouter's `brouter-core` (`org.btools:brouter-core`).
 *
 * BRouter is normally run as a standalone app / HTTP server; here the engine is driven
 * directly in-process - the same path `brouter-routing-app`'s `BRouterWorker` takes - so route
 * planning needs no separate process, service, or platform channel. This matches the project's
 * "both JVM engines (mapsforge rendering, routing) run in-process" architecture (see CLAUDE.md).
 *
 * Routing data lives under [brouterDir] (the app's `brouter/` data folder, see `AppPaths`) using
 * BRouter's standard folder names:
 *   `<brouterDir>/segments4/`   the `.rd5` routing tiles (Taiwan: `E120_N20`, `E120_N25`)
 *   `<brouterDir>/profiles2/`   the `.brf` routing profile + BRouter's `lookups.dat` table
 *
 * BRouter encodes coordinates as integers: `i = (deg + offset) * 1e6`, offset 180 (lon) / 90
 * (lat). The routed polyline comes back as `getFoundTrack().nodes` (`OsmPathElement`s).
 */
class BRouterEngine(
    brouterDir: File,
    profileName: String = DEFAULT_PROFILE,
) {
    private val profilesDir = File(brouterDir, "profiles2")
    private val segmentDir = File(brouterDir, "segments4")
    private val profileFile = File(profilesDir, "$profileName.brf")

    // BRouter reads the tag-lookup table next to the profiles; routing fails without it.
    private val lookupsFile = File(profilesDir, "lookups.dat")

    /** True only when the segment folder, the profile, and BRouter's `lookups.dat` are present. */
    fun isReady(): Boolean = segmentDir.isDirectory && profileFile.isFile && lookupsFile.isFile

    /**
     * Computes the shortest on-trail route from [from] to [to] and returns its geometry as an
     * ordered list of [LatLong] (both endpoints included, snapped to the routable network).
     *
     * Runs the A* search synchronously - call off the main thread. Always throws [RoutingException]
     * on failure (no path, waypoint off-network, missing/corrupt data) so callers never have to
     * handle BRouter's raw exception types - and a data problem surfaces as a message, not a crash.
     */
    fun route(
        from: LatLong,
        to: LatLong,
    ): List<LatLong> {
        require(isReady()) { "BRouter data missing under ${segmentDir.parent}" }

        val rc = RoutingContext().apply { localFunction = profileFile.absolutePath }
        val waypoints = listOf(from.toWaypoint("from"), to.toWaypoint("to"))

        val track =
            try {
                val engine = RoutingEngine(null, null, segmentDir, waypoints, rc)
                engine.doRun(0L) // 0 = no internal time limit; bound externally if needed
                engine.errorMessage?.let { throw RoutingException(it) }
                engine.foundTrack ?: throw RoutingException("BRouter returned no track")
            } catch (e: RoutingException) {
                throw e
            } catch (e: Exception) {
                // BRouter throws plain RuntimeExceptions (e.g. on bad data); normalise them.
                throw RoutingException(e.message ?: e.javaClass.simpleName)
            }

        return track.nodes.map { LatLong(decode(it.iLat, LAT_OFFSET), decode(it.iLon, LON_OFFSET)) }
    }

    private fun LatLong.toWaypoint(name: String): OsmNodeNamed =
        OsmNodeNamed().also {
            it.name = name
            it.ilon = encode(longitude, LON_OFFSET)
            it.ilat = encode(latitude, LAT_OFFSET)
        }

    companion object {
        /** Bundled hiking profile name (drops the `.brf` suffix). */
        const val DEFAULT_PROFILE = "hiking-mountain"

        private const val SCALE = 1_000_000.0
        private const val LON_OFFSET = 180.0
        private const val LAT_OFFSET = 90.0

        private fun encode(
            deg: Double,
            offset: Double,
        ): Int = ((deg + offset) * SCALE + 0.5).toInt()

        private fun decode(
            i: Int,
            offset: Double,
        ): Double = i / SCALE - offset
    }
}

/** Raised when BRouter cannot produce a route (bad waypoint, no path, missing data). */
class RoutingException(
    message: String,
) : Exception(message)
