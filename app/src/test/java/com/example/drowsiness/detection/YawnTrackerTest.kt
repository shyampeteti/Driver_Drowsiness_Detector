package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for YawnTracker — hysteresis, tongue-out guard, cooldown, and event counting.
 */
class YawnTrackerTest {

    private fun makeTracker(
        yawnThreshold: Double = 0.65,
        reopenThreshold: Double = 0.50,
        minSustainedMs: Long = 1_500L,
        cooldownMs: Long = 5_000L,
        minVerticalRatio: Double = 0.15
    ) = YawnTracker(
        yawnThreshold = yawnThreshold,
        yawnReopeningThreshold = reopenThreshold,
        minSustainedDurationMs = minSustainedMs,
        cooldownMs = cooldownMs,
        minVerticalToHorizontalRatio = minVerticalRatio
    )

    // ─── Basic detection ──────────────────────────────────────────────────────

    @Test
    fun noYawnForNullMar() {
        val t = makeTracker()
        val r = t.update(null, 0L)
        assertFalse(r.isYawning)
        assertEquals(0, r.totalCount)
    }

    @Test
    fun noYawnIfBelowThreshold() {
        val t = makeTracker()
        // Keep MAR below both thresholds
        for (i in 0..20) {
            val r = t.update(0.40, i * 100L)
            assertFalse("MAR below reopening threshold should not be a yawn", r.isYawning)
        }
        assertEquals(0, t.totalYawnCount)
    }

    @Test
    fun yawnDetectedAfterMinimumSustainedDuration() {
        val t = makeTracker(minSustainedMs = 1_000L)
        // Frame 1: candidate starts
        val r1 = t.update(0.70, 0L)
        assertFalse("Not yet sustained", r1.isYawning)

        // Frame 2: 1100 ms later — should confirm
        val r2 = t.update(0.70, 1_100L)
        assertTrue("Should detect yawn after sustained duration", r2.isYawning)
        assertEquals(1, r2.totalCount)
    }

    // ─── Hysteresis ───────────────────────────────────────────────────────────

    @Test
    fun candidateResetsWhenMouthClosesEnough() {
        val t = makeTracker()

        // Start a candidate
        t.update(0.70, 0L)
        t.update(0.70, 500L)

        // Mouth partially closes — still above reopening threshold (0.50), candidate continues
        t.update(0.55, 600L)
        // Drops below reopening threshold → candidate resets
        t.update(0.40, 700L)

        // Start fresh candidate — must sustain again from scratch
        t.update(0.70, 800L)
        val r = t.update(0.70, 900L)
        assertFalse("New candidate should not be a yawn yet (< minSustainedMs)", r.isYawning)
    }

    // ─── Cooldown ─────────────────────────────────────────────────────────────

    @Test
    fun cooldownPreventsDoubleCountingContinuousYawn() {
        val t = makeTracker(minSustainedMs = 500L, cooldownMs = 3_000L)

        // Confirm yawn at t=600
        for (i in 0..6) t.update(0.80, i * 100L)

        // Keep mouth open continuously — should NOT add a second yawn during cooldown
        for (i in 7..30) t.update(0.80, i * 100L)

        assertEquals("Only one yawn should be counted during cooldown", 1, t.totalYawnCount)
    }

    @Test
    fun secondYawnCountedAfterCooldownExpires() {
        val t = makeTracker(minSustainedMs = 500L, cooldownMs = 2_000L)

        // First yawn at t=600
        t.update(0.80, 0L)
        t.update(0.80, 600L)
        assertEquals(1, t.totalYawnCount)

        // Mouth closes
        t.update(0.30, 700L)

        // After cooldown, new yawn
        t.update(0.80, 3_000L)
        t.update(0.80, 3_600L)
        assertEquals("Second yawn should be counted after cooldown", 2, t.totalYawnCount)
    }

    // ─── Tongue-protrusion guard ──────────────────────────────────────────────

    @Test
    fun tongueProtrusionRejectedByVerticalRatioGuard() {
        val t = makeTracker(minVerticalRatio = 0.15)

        // Tongue-out: high MAR but tiny vertical center opening
        val tongueMetrics = MouthAspectRatioCalculator.MouthMetrics(
            mar = 0.72,
            horizontalPx = 60.0,
            verticalCenterPx = 7.0  // 7/60 = 0.117 < 0.15 → rejected
        )

        t.update(mar = 0.72, timestampMs = 0L, mouthMetrics = tongueMetrics)
        val r = t.update(mar = 0.72, timestampMs = 2_000L, mouthMetrics = tongueMetrics)

        assertFalse("Tongue protrusion should not be classified as a yawn", r.isYawning)
        assertEquals(0, r.totalCount)
    }

    @Test
    fun genuineYawnPassesVerticalRatioGuard() {
        val t = makeTracker(minSustainedMs = 500L, minVerticalRatio = 0.15)

        // Genuine yawn: large vertical opening
        val yawnMetrics = MouthAspectRatioCalculator.MouthMetrics(
            mar = 0.80,
            horizontalPx = 60.0,
            verticalCenterPx = 24.0  // 24/60 = 0.40 >> 0.15
        )

        t.update(0.80, 0L, yawnMetrics)
        val r = t.update(0.80, 600L, yawnMetrics)

        assertTrue("Genuine yawn should be detected", r.isYawning)
        assertEquals(1, r.totalCount)
    }

    @Test
    fun guardDisabledWhenRatioIsZero() {
        val t = makeTracker(minSustainedMs = 500L, minVerticalRatio = 0.0)

        // Even with tiny vertical opening, guard is disabled so it should count
        val metrics = MouthAspectRatioCalculator.MouthMetrics(
            mar = 0.80,
            horizontalPx = 60.0,
            verticalCenterPx = 5.0
        )

        t.update(0.80, 0L, metrics)
        val r = t.update(0.80, 600L, metrics)
        assertTrue("With guard disabled, yawn should still be detected", r.isYawning)
    }

    // ─── Reset ────────────────────────────────────────────────────────────────

    @Test
    fun resetClearsAllState() {
        val t = makeTracker(minSustainedMs = 500L, cooldownMs = 1_000L)
        t.update(0.80, 0L)
        t.update(0.80, 600L)
        assertEquals(1, t.totalYawnCount)

        t.reset()
        assertEquals(0, t.totalYawnCount)
        // After reset, new candidate must build up from scratch
        val r = t.update(0.80, 700L)
        assertFalse("After reset, first frame should not be a yawn", r.isYawning)
    }
}
