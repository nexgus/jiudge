package io.github.nexgus.jiudge.feature.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the docs/gating.md §3 rules with synthetic drift data. Coordinates are built by
 * offsetting north from a base point, so "metres" in the helpers are ground-truth distances.
 */
class FixGateTest {
    private val baseLat = 23.5
    private val baseLon = 121.0

    // 1 degree of latitude is ~111.32 km; small northward offsets are effectively exact.
    private fun fix(
        northMeters: Double,
        tSec: Long,
        acc: Float? = 5f,
        v: Float? = 1f,
    ) = FixGate.Fix(
        latitude = baseLat + northMeters / 111_320.0,
        longitude = baseLon,
        timeMs = tSec * 1000,
        accuracyMeters = acc,
        speedMps = v,
    )

    private fun norths(fixes: List<FixGate.Fix>): List<Long> = fixes.map { Math.round((it.latitude - baseLat) * 111_320.0) }

    // --- Rule 1: accuracy gate ---

    @Test
    fun `first fix passes with good accuracy`() {
        val gate = FixGate()
        assertEquals(listOf(0L), norths(gate.offer(fix(0.0, 0, acc = 10f))))
    }

    @Test
    fun `first fix with accuracy above 30 m is dropped`() {
        val gate = FixGate()
        assertTrue(gate.offer(fix(0.0, 0, acc = 31f)).isEmpty())
    }

    @Test
    fun `null accuracy passes rule 1`() {
        val gate = FixGate()
        assertEquals(listOf(0L), norths(gate.offer(fix(0.0, 0, acc = null))))
    }

    // --- Rule 3: adaptive spacing ---

    @Test
    fun `stationary drift within the spacing threshold is dropped`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        // acc 5 -> threshold clamps up to SPACING_MIN_M = 5; 3 m of drift never leaves it.
        assertTrue(gate.offer(fix(3.0, 1, v = 0.1f)).isEmpty())
        assertTrue(gate.offer(fix(-2.0, 2, v = 0.1f)).isEmpty())
    }

    @Test
    fun `movement beyond the spacing threshold is written`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        assertEquals(listOf(6L), norths(gate.offer(fix(6.0, 6))))
    }

    @Test
    fun `spacing threshold scales with accuracy`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0, acc = 20f))
        // acc 20 -> threshold 20 m: 15 m is drift, 25 m (measured from the anchor) is movement.
        assertTrue(gate.offer(fix(15.0, 15, acc = 20f)).isEmpty())
        assertEquals(listOf(25L), norths(gate.offer(fix(25.0, 25, acc = 20f))))
    }

    @Test
    fun `spacing threshold clamps at the 5 m floor`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0, acc = 2f))
        assertTrue(gate.offer(fix(3.0, 3, acc = 2f)).isEmpty())
        assertEquals(listOf(6L), norths(gate.offer(fix(6.0, 6, acc = 2f))))
    }

    @Test
    fun `null accuracy uses the 5 m floor as spacing threshold`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0, acc = null))
        assertTrue(gate.offer(fix(3.0, 3, acc = null)).isEmpty())
        assertEquals(listOf(6L), norths(gate.offer(fix(6.0, 6, acc = null))))
    }

    @Test
    fun `fix not newer than the anchor is dropped`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 10))
        assertTrue(gate.offer(fix(20.0, 10)).isEmpty())
        assertTrue(gate.offer(fix(20.0, 9)).isEmpty())
    }

    // --- Rule 2: displacement / Doppler self-consistency ---

    @Test
    fun `train speed with matching doppler is written directly`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0, v = 30f))
        // 30 m in 1 s with vDop 30: limit = max(3 * 30, 10) = 90 -> consistent, no pending cycle.
        assertEquals(listOf(30L), norths(gate.offer(fix(30.0, 1, v = 30f))))
        assertEquals(listOf(60L), norths(gate.offer(fix(60.0, 2, v = 30f))))
    }

    @Test
    fun `stationary spike is held and dropped when the next fix returns to the anchor`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        // 50 m in 1 s with vDop 0.5: limit = max(1.5, 10) = 10 < 50 -> pending, nothing written.
        assertTrue(gate.offer(fix(50.0, 1, v = 0.5f)).isEmpty())
        // Next fix is back at the anchor: corroboration fails (49 m in 1 s from the pending fix),
        // the spike is gone, and the fix itself is drift within spacing.
        assertTrue(gate.offer(fix(1.0, 2, v = 0.5f)).isEmpty())
        // The anchor never moved: a real 6 m step from it is written.
        assertEquals(listOf(6L), norths(gate.offer(fix(6.0, 8))))
    }

    @Test
    fun `pending fix corroborated by continuing motion writes both fixes`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        // 12 m in 1 s with null doppler: limit = V_FLOOR = 10 < 12 -> pending.
        assertTrue(gate.offer(fix(12.0, 1, v = null)).isEmpty())
        // 8 m/s onward from the pending fix -> corroborated: pending written, then the current fix
        // is evaluated against it (8 m >= 5 m spacing) and written too.
        assertEquals(listOf(12L, 20L), norths(gate.offer(fix(20.0, 2, v = null))))
    }

    @Test
    fun `corroborated pending fix is written even when its successor is then spacing-dropped`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        assertTrue(gate.offer(fix(12.0, 1, v = null)).isEmpty())
        // 2 m onward corroborates the pending fix but is itself within spacing of it.
        assertEquals(listOf(12L), norths(gate.offer(fix(14.0, 2, v = null))))
    }

    @Test
    fun `rule-1-dropped fix does not consume the pending slot`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        assertTrue(gate.offer(fix(12.0, 1, v = null)).isEmpty())
        // A garbage-accuracy fix is dropped by rule 1; the pending fix keeps waiting.
        assertTrue(gate.offer(fix(60.0, 2, acc = 50f)).isEmpty())
        // The next usable fix still corroborates the pending one.
        assertEquals(listOf(12L, 20L), norths(gate.offer(fix(20.0, 3, v = null))))
    }

    @Test
    fun `clearPending drops the held fix but keeps the anchor`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        assertTrue(gate.offer(fix(12.0, 1, v = null)).isEmpty())
        gate.clearPending()
        // Continuing motion can no longer corroborate the dropped fix; judged against the original
        // anchor instead (20 m in 20 s = 1 m/s, beyond spacing) and written alone.
        assertEquals(listOf(20L), norths(gate.offer(fix(20.0, 20, v = null))))
    }

    // --- Session lifecycle ---

    @Test
    fun `continuation anchor spacing-gates the first fixes`() {
        val gate = FixGate()
        gate.reset(anchor = FixGate.Fix(baseLat, baseLon, 0, accuracyMeters = null, speedMps = null))
        // Not treated as a first point: 3 m from the seeded anchor is drift.
        assertTrue(gate.offer(fix(3.0, 1000)).isEmpty())
        assertEquals(listOf(6L), norths(gate.offer(fix(6.0, 1006))))
    }

    @Test
    fun `reset forgets anchor and pending`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        assertTrue(gate.offer(fix(12.0, 1, v = null)).isEmpty())
        gate.reset()
        // A fresh session: the first fix passes on rule 1 alone.
        assertEquals(listOf(1L), norths(gate.offer(fix(1.0, 2))))
    }
}
