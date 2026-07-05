package io.github.nexgus.jiudge.feature.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        gps: Boolean = true,
    ) = FixGate.Fix(
        latitude = baseLat + northMeters / 111_320.0,
        longitude = baseLon,
        timeMs = tSec * 1000,
        accuracyMeters = acc,
        speedMps = v,
        fromGps = gps,
    )

    private fun norths(fixes: List<FixGate.Fix>): List<Long> = fixes.map { Math.round((it.latitude - baseLat) * 111_320.0) }

    // --- Rule 1: accuracy gate ---

    @Test
    fun `first fix passes with good accuracy`() {
        val gate = FixGate()
        assertEquals(listOf(0L), norths(gate.offer(fix(0.0, 0, acc = 10f))))
    }

    @Test
    fun `first fix with accuracy above 30 m is not written but becomes the provisional`() {
        val gate = FixGate()
        assertTrue(gate.offer(fix(0.0, 0, acc = 31f)).isEmpty())
        assertNotNull(gate.provisional)
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

    // --- §3.5: first-point bootstrap ---

    @Test
    fun `provisional is replaced by equal or better accuracy only`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0, acc = 50f))
        // Better accuracy takes over.
        gate.offer(fix(2.0, 1, acc = 40f))
        assertEquals(40f, gate.provisional?.accuracyMeters)
        // Worse accuracy does not.
        gate.offer(fix(3.0, 2, acc = 45f))
        assertEquals(40f, gate.provisional?.accuracyMeters)
        // Equal accuracy prefers the newer measurement.
        val newer = fix(4.0, 3, acc = 40f)
        gate.offer(newer)
        assertEquals(newer.timeMs, gate.provisional?.timeMs)
    }

    @Test
    fun `commit-grade gps fix commits immediately and discards the provisional`() {
        val gate = FixGate()
        gate.offer(fix(10.0, 0, acc = 50f))
        assertEquals(listOf(0L), norths(gate.offer(fix(0.0, 5, acc = 25f))))
        assertNull(gate.provisional)
    }

    @Test
    fun `movement commits the provisional as the starting point`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0, acc = 40f))
        // 50 m in 30 s (1.7 m/s, consistent) beyond max(40, 35) m of joint uncertainty: the user
        // set off. The provisional is the start point; the successor (acc 35 > 30) still fails
        // rule 1 and is not written.
        assertEquals(listOf(0L), norths(gate.offer(fix(50.0, 30, acc = 35f))))
        assertNull(gate.provisional)
        // The anchor is now the committed start: a commit-grade fix beyond spacing extends it.
        assertEquals(listOf(70L), norths(gate.offer(fix(70.0, 60, acc = 10f))))
    }

    @Test
    fun `inconsistent spike neither commits nor replaces the provisional`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0, acc = 50f))
        // 500 m in 1 s with vDop 1: far beyond the consistency limit - not movement, and its worse
        // accuracy cannot take the provisional slot either.
        assertTrue(gate.offer(fix(500.0, 1, acc = 60f)).isEmpty())
        assertEquals(50f, gate.provisional?.accuracyMeters)
    }

    @Test
    fun `provisional survives clearPending`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0, acc = 50f))
        gate.clearPending()
        assertNotNull(gate.provisional)
    }

    @Test
    fun `network fix never commits directly but can bootstrap via movement`() {
        val gate = FixGate()
        // Indoor start: no gps this session, so network is admitted at once - but even WiFi-grade
        // accuracy only earns the provisional slot, never an immediate commit.
        assertTrue(gate.offer(fix(0.0, 0, acc = 20f, v = null, gps = false)).isEmpty())
        assertNotNull(gate.provisional)
        // Walking off (60 m in 60 s) commits the provisional and writes the successor too.
        assertEquals(
            listOf(0L, 60L),
            norths(gate.offer(fix(60.0, 60, acc = 20f, v = null, gps = false))),
        )
    }

    // --- Rule 0 + network branches of rules 1 and 3 ---

    @Test
    fun `network fix is held off while satellites are alive`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        // 10 s after the last gps fix: rejected outright, however far it is.
        assertTrue(gate.offer(fix(50.0, 10, acc = 30f, v = null, gps = false)).isEmpty())
        // 31 s of satellite silence: admitted, consistent (1.6 m/s), beyond spacing - written.
        assertEquals(listOf(50L), norths(gate.offer(fix(50.0, 31, acc = 30f, v = null, gps = false))))
    }

    @Test
    fun `network accuracy above 100 m is dropped`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        // Cell-tower-grade accuracy: rejected by rule 1's network cap despite passing the holdoff.
        assertTrue(gate.offer(fix(200.0, 40, acc = 150f, v = null, gps = false)).isEmpty())
        // WiFi-grade accuracy passes (spacing threshold = 80 m <= 90 m displacement).
        assertEquals(listOf(90L), norths(gate.offer(fix(90.0, 41, acc = 80f, v = null, gps = false))))
    }

    @Test
    fun `network spacing threshold scales with network accuracy beyond 30 m`() {
        val gate = FixGate()
        // Continuation-style anchor, no gps this session: network admitted immediately.
        gate.reset(anchor = FixGate.Fix(baseLat, baseLon, 0, accuracyMeters = null, speedMps = null))
        // 50 m of drift is within acc-80's own uncertainty (threshold clamps to 80 m, not 30 m).
        assertTrue(gate.offer(fix(50.0, 40, acc = 80f, v = null, gps = false)).isEmpty())
        // 90 m has genuinely left it.
        assertEquals(listOf(90L), norths(gate.offer(fix(90.0, 80, acc = 80f, v = null, gps = false))))
    }

    @Test
    fun `returning gps preempts network from its very first fix`() {
        val gate = FixGate()
        gate.offer(fix(0.0, 0))
        assertEquals(listOf(50L), norths(gate.offer(fix(50.0, 31, acc = 30f, v = null, gps = false))))
        // The first satellite fix back is judged normally (15 m/s with matching Doppler)...
        assertEquals(listOf(70L), norths(gate.offer(fix(70.0, 32, acc = 10f, v = 15f))))
        // ...and instantly re-arms the holdoff: the next network fix is rejected, however far.
        assertTrue(gate.offer(fix(120.0, 33, acc = 30f, v = null, gps = false)).isEmpty())
    }
}
