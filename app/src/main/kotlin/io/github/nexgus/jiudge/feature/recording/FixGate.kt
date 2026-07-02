package io.github.nexgus.jiudge.feature.recording

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Write-gating for track recording, implementing docs/gating.md §3. Three rules applied in order
 * to every fix offered while recording:
 *
 * 1. Accuracy gate - a fix whose reported accuracy exceeds [ACCURACY_MAX_M] is dropped. Null
 *    accuracy passes (rejecting it would brick recording on the few devices that omit it); the
 *    later rules still guard those fixes.
 * 2. Displacement / Doppler self-consistency - the speed implied by the displacement from the
 *    anchor (the last written point) must not exceed `max(K_DOP * dopplerSpeed, V_FLOOR)`. An
 *    unsupported displacement is held as the single pending fix and written only when the next fix
 *    corroborates it (the motion really continued from there); otherwise it was a spike and is
 *    dropped. Judging "does the motion continue" instead of "is the speed plausible for hiking"
 *    keeps recording correct on trains, shuttles, and gondolas.
 * 3. Adaptive spacing - a displacement below `clamp(K_ACC * accuracy, SPACING_MIN_M,
 *    SPACING_MAX_M)` has not left the measurement's own uncertainty and is dropped. This is what
 *    keeps a stationary rest from writing a bird's nest.
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
    )

    // The anchor: the last written point. Null until the session's first accepted fix; a
    // continuation seeds it from the source track's last point via [reset].
    private var anchor: Fix? = null

    // The single fix held by rule 2 awaiting corroboration (docs/gating.md: 擱置點至多一個).
    private var pending: Fix? = null

    /** Re-arms the gate for a new session. [anchor] is the continuation anchor; null for a fresh start. */
    fun reset(anchor: Fix? = null) {
        this.anchor = anchor
        pending = null
    }

    /**
     * Drops the pending fix without touching the anchor. Call on pause and when the fix stream
     * goes stale (docs/gating.md rule 2: cleared on 暫停 / 結束 / fixStale 轉 true).
     */
    fun clearPending() {
        pending = null
    }

    /**
     * Offers one fix; returns the fixes to append to the track, in order. Empty = nothing to
     * write; two entries = a corroborated pending fix followed by the current one.
     */
    fun offer(fix: Fix): List<Fix> {
        // Rule 1. A dropped fix does not consume the pending slot: the pending fix keeps waiting
        // for a usable witness.
        if (fix.accuracyMeters != null && fix.accuracyMeters > ACCURACY_MAX_M) return emptyList()
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

    // Rules 2 + 3 against the current anchor. The session's first fix has no anchor and only rule 1
    // applies (docs/gating.md §3 前置條件).
    private fun evaluate(fix: Fix): List<Fix> {
        val a = anchor ?: return accept(fix)
        // A fix not newer than the anchor is not a new measurement (duplicate or clock anomaly).
        if (fix.timeMs <= a.timeMs) return emptyList()
        if (!isConsistent(a, fix)) {
            pending = fix
            return emptyList()
        }
        if (distanceMeters(a, fix) < spacingThresholdM(fix.accuracyMeters)) return emptyList()
        return accept(fix)
    }

    private fun accept(fix: Fix): List<Fix> {
        anchor = fix
        return listOf(fix)
    }

    // Rule 2's formula: the displacement-implied speed must be supported by the Doppler speed.
    // Null Doppler degrades the threshold to V_FLOOR (the two-beat corroboration cycle alone). A
    // non-positive dt cannot support any displacement.
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

    private fun spacingThresholdM(accuracyMeters: Float?): Double =
        if (accuracyMeters == null) {
            SPACING_MIN_M
        } else {
            (K_ACC * accuracyMeters).coerceIn(SPACING_MIN_M, SPACING_MAX_M)
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
        const val K_DOP = 3.0
        const val V_FLOOR = 10.0
        const val K_ACC = 1.0
        const val SPACING_MIN_M = 5.0
        const val SPACING_MAX_M = 30.0
    }
}
