package io.github.nexgus.jiudge.feature.recording

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Write-gating for track recording, implementing docs/gating.md §3. Rules applied in order to
 * every fix offered while recording:
 *
 * 0. Network admission - a network fix is considered at all only after the satellites have been
 *    silent for [NET_HOLDOFF_MS] (measured against the last GPS fix offered, accepted or not).
 *    A GPS fix re-arms the holdoff instantly, so satellite recovery never waits. This is what
 *    keeps a marginal-GPS situation (window seat, shallow indoors) from ping-ponging the track
 *    between GPS and WiFi positions.
 * 1. Accuracy gate - a fix whose reported accuracy exceeds its source's cap ([ACCURACY_MAX_M] for
 *    GPS, [ACCURACY_MAX_NET_M] for network - admit WiFi-grade, reject cell-tower-grade) is
 *    dropped. Null accuracy passes (rejecting it would brick recording on the few devices that
 *    omit it); the later rules still guard those fixes.
 * 2. Displacement / Doppler self-consistency - the speed implied by the displacement from the
 *    anchor (the last written point) must not exceed `max(K_DOP * dopplerSpeed, V_FLOOR)`. An
 *    unsupported displacement is held as the single pending fix and written only when the next fix
 *    corroborates it (the motion really continued from there); otherwise it was a spike and is
 *    dropped. Judging "does the motion continue" instead of "is the speed plausible for hiking"
 *    keeps recording correct on trains, shuttles, and gondolas.
 * 3. Adaptive spacing - a displacement below `clamp(K_ACC * accuracy, SPACING_MIN_M, source cap)`
 *    has not left the measurement's own uncertainty and is dropped. This is what keeps a
 *    stationary rest from writing a bird's nest. The cap follows the source ([SPACING_MAX_M] /
 *    [SPACING_MAX_NET_M]) because network drift scales with network accuracy.
 *
 * While the session has no anchor yet, rules 1-3 are replaced by the first-point bootstrap
 * (docs/gating.md §3.5): the gate holds the best-accuracy fix seen so far as the single
 * [provisional] point, commits it the moment a GPS fix reaches commit grade, or - when the user
 * starts moving before convergence - commits the best estimate as the track's starting point.
 * The provisional survives staleness and pause on purpose: it stays the best start estimate, and
 * a real relocation in between is honestly recorded as a jump on commit (same behaviour as the
 * tunnel-exit scenario).
 *
 * Pure state machine - no IO, no Android dependency - so the rules are unit-testable with
 * synthetic drift data (docs/gating.md §7). The caller owns session state gating (RECORDING vs
 * PAUSED) and file IO.
 */
class FixGate {
    /** One offered fix, reduced to the fields the rules need. */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val timeMs: Long,
        /** Horizontal accuracy radius in metres (68%), or null when the fix carries none. */
        val accuracyMeters: Float?,
        /** Doppler-derived ground speed in m/s, or null when the fix carries none. */
        val speedMps: Float?,
        /** True for a satellite fix; false for a network (cell/WiFi) fix. */
        val fromGps: Boolean = true,
    )

    // The anchor: the last written point. Null until the session's first accepted fix; a
    // continuation seeds it from the source track's last point via [reset].
    private var anchor: Fix? = null

    // The single fix held by rule 2 awaiting corroboration (docs/gating.md: 擱置點至多一個).
    private var pending: Fix? = null

    // Bootstrap state (docs/gating.md §3.5): the best-accuracy fix seen while the session has no
    // anchor yet. Deliberately kept across staleness and pause; cleared only by [reset] and by the
    // first point committing.
    private var bootstrapProvisional: Fix? = null

    // Rule 0's satellite-silence clock: the newest timeMs of any GPS fix offered this session,
    // accepted or not - "a satellite signal exists" is judged by fix presence, not quality.
    private var lastGpsTimeMs: Long? = null

    /**
     * The provisional first point currently held by the bootstrap, or null once the first point is
     * committed (or before any eligible fix arrived). Display-only for callers: it drives the
     * rubber-band anchor and the convergence readout, and is never written to the track by itself.
     */
    val provisional: Fix? get() = bootstrapProvisional

    /** Re-arms the gate for a new session. [anchor] is the continuation anchor; null for a fresh start. */
    fun reset(anchor: Fix? = null) {
        this.anchor = anchor
        pending = null
        bootstrapProvisional = null
        lastGpsTimeMs = null
    }

    /**
     * Drops the pending fix without touching the anchor or the bootstrap provisional. Call on
     * pause and when the fix stream goes stale (docs/gating.md rule 2: cleared on 暫停 / 結束 /
     * fixStale 轉 true; §3.5: the provisional survives both).
     */
    fun clearPending() {
        pending = null
    }

    /**
     * Offers one fix; returns the fixes to append to the track, in order. Empty = nothing to
     * write; two entries = a corroborated pending fix (or a movement-committed provisional)
     * followed by the current one.
     */
    fun offer(fix: Fix): List<Fix> {
        // Rule 0. A held-off network fix is ignored outright - it consumes no pending slot and
        // never touches the bootstrap. A GPS fix re-arms the silence clock before anything else,
        // so satellite recovery preempts network fixes from this very fix onward.
        if (fix.fromGps) {
            lastGpsTimeMs = max(lastGpsTimeMs ?: Long.MIN_VALUE, fix.timeMs)
        } else if (!networkAdmitted(fix.timeMs)) {
            return emptyList()
        }
        if (anchor == null) return bootstrap(fix)
        // Rule 1. A dropped fix does not consume the pending slot: the pending fix keeps waiting
        // for a usable witness.
        if (fix.accuracyMeters != null && fix.accuracyMeters > accuracyCapM(fix)) return emptyList()
        val pend = pending
        if (pend != null) {
            pending = null
            if (isConsistent(pend, fix)) {
                // Corroborated: the motion continued from the pending fix, so it was real. Write it
                // unconditionally (docs/gating.md rule 2), then evaluate the current fix with the
                // pending fix as the new anchor.
                anchor = pend
                return listOf(pend) + evaluate(fix)
            }
            // Not corroborated: the pending fix was a spike. Drop it and judge the current fix
            // against the original anchor.
        }
        return evaluate(fix)
    }

    // First-point bootstrap (docs/gating.md §3.5). Only reached with no anchor; [fix] has already
    // passed rule 0.
    private fun bootstrap(fix: Fix): List<Fix> {
        val acc = fix.accuracyMeters
        // Immediate commit: a commit-grade GPS fix is the first point, exactly as before the
        // bootstrap existed. Network fixes never commit directly - a still-converging satellite
        // must be able to displace them, otherwise the track would start on a biased network
        // position and jump once GPS comes up.
        if (fix.fromGps && (acc == null || acc <= ACCURACY_MAX_M)) {
            bootstrapProvisional = null
            return accept(fix)
        }
        // Provisional eligibility: null-accuracy network fixes are unrankable and dropped.
        if (acc == null || acc > BOOTSTRAP_ACC_MAX_M) return emptyList()
        val prov = bootstrapProvisional
        if (prov == null) {
            bootstrapProvisional = fix
            return emptyList()
        }
        // Movement commit: a displacement beyond the joint uncertainty, corroborated by rule 2's
        // consistency check (so a spike cannot trigger it), means the user set off - the best
        // start estimate becomes the track's first point and the current fix is judged normally.
        val provAcc = prov.accuracyMeters ?: SPACING_MIN_M.toFloat()
        val movementThresholdM = (K_ACC * max(provAcc, acc)).coerceIn(SPACING_MIN_M, BOOTSTRAP_ACC_MAX_M.toDouble())
        if (isConsistent(prov, fix) && distanceMeters(prov, fix) >= movementThresholdM) {
            bootstrapProvisional = null
            anchor = prov
            // The successor still owes rule 1 its own accuracy check - the bootstrap's laxer cap
            // qualified it to witness movement, not to be written.
            return listOf(prov) + if (acc > accuracyCapM(fix)) emptyList() else evaluate(fix)
        }
        // Replacement: equal-or-better accuracy takes over (ties prefer the newer measurement), so
        // the provisional's quality is monotonic while the user waits for convergence.
        if (acc <= provAcc) bootstrapProvisional = fix
        return emptyList()
    }

    // Rules 2 + 3 against the current anchor.
    private fun evaluate(fix: Fix): List<Fix> {
        val a = anchor ?: return accept(fix)
        // A fix not newer than the anchor is not a new measurement (duplicate or clock anomaly).
        if (fix.timeMs <= a.timeMs) return emptyList()
        if (!isConsistent(a, fix)) {
            pending = fix
            return emptyList()
        }
        if (distanceMeters(a, fix) < spacingThresholdM(fix)) return emptyList()
        return accept(fix)
    }

    private fun accept(fix: Fix): List<Fix> {
        anchor = fix
        return listOf(fix)
    }

    // Rule 0: has the satellite been silent long enough for a network fix at [timeMs] to enter?
    // No GPS fix this session (indoor start) counts as silence.
    private fun networkAdmitted(timeMs: Long): Boolean {
        val lastGps = lastGpsTimeMs ?: return true
        return timeMs - lastGps >= NET_HOLDOFF_MS
    }

    // Rule 2's formula: the displacement-implied speed must be supported by the Doppler speed.
    // Null Doppler (every network fix, some GPS fixes) degrades the threshold to V_FLOOR (the
    // two-beat corroboration cycle alone). A non-positive dt cannot support any displacement.
    private fun isConsistent(
        from: Fix,
        to: Fix,
    ): Boolean {
        val dtMs = to.timeMs - from.timeMs
        if (dtMs <= 0) return false
        val vDisp = distanceMeters(from, to) / (dtMs / 1000.0)
        val vDop = to.speedMps
        val limit = if (vDop != null) max(K_DOP * vDop, V_FLOOR) else V_FLOOR
        return vDisp <= limit
    }

    private fun accuracyCapM(fix: Fix): Float = if (fix.fromGps) ACCURACY_MAX_M else ACCURACY_MAX_NET_M

    private fun spacingThresholdM(fix: Fix): Double {
        val acc = fix.accuracyMeters ?: return SPACING_MIN_M
        val cap = if (fix.fromGps) SPACING_MAX_M else SPACING_MAX_NET_M
        return (K_ACC * acc).coerceIn(SPACING_MIN_M, cap)
    }

    /** Great-circle distance between two fixes in metres. */
    private fun distanceMeters(
        a: Fix,
        b: Fix,
    ): Double {
        val earthR = 6_371_000.0
        val phi1 = Math.toRadians(a.latitude)
        val phi2 = Math.toRadians(b.latitude)
        val dPhi = phi2 - phi1
        val dLambda = Math.toRadians(b.longitude - a.longitude)
        val sinDPhi = sin(dPhi / 2)
        val sinDLambda = sin(dLambda / 2)
        val h = sinDPhi * sinDPhi + cos(phi1) * cos(phi2) * sinDLambda * sinDLambda
        return 2 * earthR * asin(min(1.0, sqrt(h)))
    }

    private companion object {
        // docs/gating.md §4 - conservative defaults awaiting calibration from real-hike
        // recordings; update the doc's table when tuning these.
        const val ACCURACY_MAX_M = 30f
        const val ACCURACY_MAX_NET_M = 100f
        const val NET_HOLDOFF_MS = 30_000L
        const val K_DOP = 3.0
        const val V_FLOOR = 10.0
        const val K_ACC = 1.0
        const val SPACING_MIN_M = 5.0
        const val SPACING_MAX_M = 30.0
        const val SPACING_MAX_NET_M = 100.0
        const val BOOTSTRAP_ACC_MAX_M = 100f
    }
}
