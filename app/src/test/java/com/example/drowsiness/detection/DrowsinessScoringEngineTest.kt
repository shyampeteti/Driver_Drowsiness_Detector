package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for DrowsinessScoringEngine — verifying score correctness, event-based bonuses,
 * state transitions, and hysteresis.
 */
class DrowsinessScoringEngineTest {

    private fun makeEngine(
        drowsyThreshold: Double = 50.0,
        criticalThreshold: Double = 75.0,
        recoveryMs: Long = 1_500L
    ) = DrowsinessScoringEngine(
        drowsyScoreThreshold = drowsyThreshold,
        criticalScoreThreshold = criticalThreshold,
        recoveryConfirmationDurationMs = recoveryMs
    )

    @Test
    fun openEyesProduceNearZeroScore() {
        val engine = makeEngine()
        val result = engine.evaluate(
            closureDurationMs = 0L,
            perclos = 0.0,
            prolongedCount = 0,
            avgEpisodeDurationMs = 150.0,
            isYawning = false,
            isNodding = false,
            isEyeClosed = false,
            timestampMs = 0L
        )
        assertTrue("Open eyes should give near-zero score, got ${result.totalScore}", result.totalScore < 10.0)
        assertEquals(DetectionState.MONITORING, result.state)
    }

    @Test
    fun criticalClosureImmediatelyEscalatesToCritical() {
        val engine = makeEngine()
        val result = engine.evaluate(
            closureDurationMs = 2_100L,
            perclos = null,
            prolongedCount = 0,
            avgEpisodeDurationMs = 150.0,
            isYawning = false,
            isNodding = false,
            isEyeClosed = true,
            timestampMs = 2_100L
        )
        assertEquals(DetectionState.CRITICAL, result.state)
        assertTrue("Score should be >= 75 for 2.1s closure, got ${result.totalScore}", result.totalScore >= 75.0)
    }

    @Test
    fun prolongedClosureEscalatesToDrowsy() {
        val engine = makeEngine()
        val result = engine.evaluate(
            closureDurationMs = 1_100L,
            perclos = null,
            prolongedCount = 0,
            avgEpisodeDurationMs = 150.0,
            isYawning = false,
            isNodding = false,
            isEyeClosed = true,
            timestampMs = 1_100L
        )
        assertEquals(DetectionState.DROWSY, result.state)
    }

    @Test
    fun yawnBonusAppliedOncePerEvent() {
        val engine = makeEngine()

        // Apply 50 frames with yawnEventCount=1 (the same event, not new events)
        var lastResult: DrowsinessScoringEngine.ScoreBreakdown? = null
        for (i in 0..50) {
            lastResult = engine.evaluate(
                closureDurationMs = 0L,
                perclos = 0.0,
                prolongedCount = 0,
                avgEpisodeDurationMs = 150.0,
                isYawning = true,
                isNodding = false,
                isEyeClosed = false,
                timestampMs = i * 100L,
                yawnEventCount = 1,
                nodEventCount = 0
            )
        }
        // After 50 frames the bonus decays; score should not be near 100
        val finalScore = lastResult!!.totalScore
        assertTrue(
            "Score should not inflate from same yawn event repeated, got $finalScore",
            finalScore < 50.0
        )
    }

    @Test
    fun newYawnEventIncreasesScore() {
        val engine = makeEngine()

        // First yawn
        val r1 = engine.evaluate(0L, 0.0, 0, 150.0, true, false, false, 0L,
            yawnEventCount = 1, nodEventCount = 0)
        // Second yawn (new event)
        val r2 = engine.evaluate(0L, 0.0, 0, 150.0, true, false, false, 100L,
            yawnEventCount = 2, nodEventCount = 0)

        assertTrue("New yawn event should bump score above first", r2.totalScore >= r1.totalScore)
    }

    @Test
    fun recoveryRequiresSustainedOpenEyes() {
        val engine = makeEngine(recoveryMs = 1_000L)

        // Force critical state
        engine.evaluate(2_100L, null, 0, 150.0, false, false, true, 0L)

        // Single open-eye frame should NOT immediately drop to MONITORING
        val singleOpenFrame = engine.evaluate(0L, 0.0, 0, 150.0, false, false, false, 100L)
        assertEquals("Should remain in CRITICAL/DROWSY until sustained recovery", DetectionState.CRITICAL, singleOpenFrame.state)

        // After recovery duration expires, state should drop
        val recovered = engine.evaluate(0L, 0.0, 0, 150.0, false, false, false, 1_200L)
        assertNotEquals("After sustained recovery, should not still be CRITICAL", DetectionState.CRITICAL, recovered.state)
    }

    @Test
    fun perclosAloneDoesNotCauseCritical() {
        val engine = makeEngine(criticalThreshold = 75.0)
        // PERCLOS of 20% with no sustained closures
        val result = engine.evaluate(
            closureDurationMs = 0L,
            perclos = 0.20,
            prolongedCount = 0,
            avgEpisodeDurationMs = 150.0,
            isYawning = false,
            isNodding = false,
            isEyeClosed = false,
            timestampMs = 0L
        )
        assertNotEquals(
            "20% PERCLOS alone should not trigger CRITICAL",
            DetectionState.CRITICAL,
            result.state
        )
    }

    @Test
    fun scoreClamped0To100() {
        val engine = makeEngine()
        // Maximum possible inputs
        val result = engine.evaluate(
            closureDurationMs = 10_000L,
            perclos = 1.0,
            prolongedCount = 10,
            avgEpisodeDurationMs = 5_000.0,
            isYawning = true,
            isNodding = true,
            isEyeClosed = true,
            timestampMs = 0L
        )
        assertTrue("Score must not exceed 100", result.totalScore <= 100.0)
        assertTrue("Score must not be negative", result.totalScore >= 0.0)
    }

    @Test
    fun resetRestoresToInitialState() {
        val engine = makeEngine()
        // Force critical
        engine.evaluate(3_000L, 1.0, 5, 2_000.0, true, true, true, 0L)
        engine.reset()

        // After reset, should behave as fresh
        val result = engine.evaluate(0L, 0.0, 0, 150.0, false, false, false, 100L)
        assertEquals(DetectionState.MONITORING, result.state)
        assertTrue("Score should be near-zero after reset", result.totalScore < 10.0)
    }
}
