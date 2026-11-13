package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests Section 13.E and Section 9:
 * - Multi-indicator scoring (blinks, closures, PERCLOS, yawns, head nodding).
 * - Calibration isolation (zero alarms, zero score during calibration).
 * - Recovery hysteresis (sustained open eyes needed).
 * - Adaptive baseline updater (safe gates, drift limits, never adapts on closed eyes).
 */
class DrowsinessScoringAndAdaptiveBaselineTest {

    @Test
    fun testCalibrationPeriodNeverTriggersAlarmsOrScoring() {
        val detector = DrowsinessDetector()
        // Start with calibration active
        val initSnap = detector.start(enableCalibration = true, nowMs = 0L)
        assertTrue(initSnap.isCalibrating)
        assertEquals(DetectionState.CALIBRATING, initSnap.state)

        // Even if samples during calibration have low values (e.g. narrow eyes 0.15),
        // no drowsiness alert must be triggered!
        for (i in 0..25) {
            val snap = detector.observe(0.15, i * 100L)
            assertTrue(snap.isCalibrating)
            assertEquals("DetectionState must be CALIBRATING throughout", DetectionState.CALIBRATING, snap.state)
            assertFalse("Alarm must never trigger during calibration", snap.alarmRequested)
            assertEquals(AlertLevel.NONE, snap.alertLevel)
            assertEquals(0.0, snap.drowsinessScore, 0.001)
            assertNull("PERCLOS should not be calculated during calibration", snap.perclos)
        }
    }

    @Test
    fun testFrequentNormalBlinkingMaintainsSafeScore() {
        val scoring = DrowsinessScoringEngine()

        // 30 normal blinks (150ms each) over 60 seconds
        var scoreAtEnd = 0.0
        for (i in 0..30) {
            val res = scoring.evaluate(
                closureDurationMs = 150L, // blink in progress
                perclos = 0.06,           // normal waking PERCLOS (~6%)
                prolongedCount = 0,
                avgEpisodeDurationMs = 150.0,
                isYawning = false,
                isNodding = false,
                isEyeClosed = false,
                timestampMs = i * 2000L
            )
            scoreAtEnd = res.totalScore
        }

        assertTrue("Frequent normal blinks must maintain low score (< 20.0), got $scoreAtEnd", scoreAtEnd < 20.0)
    }

    @Test
    fun testMultipleIndicatorsTogetherEscalateState() {
        val scoring = DrowsinessScoringEngine()

        // High PERCLOS (35%) + 2 prolonged closures + yawn event
        val res = scoring.evaluate(
            closureDurationMs = 600L,
            perclos = 0.35,
            prolongedCount = 2,
            avgEpisodeDurationMs = 500.0,
            isYawning = true,
            isNodding = false,
            isEyeClosed = true,
            timestampMs = 5_000L,
            yawnEventCount = 1,
            nodEventCount = 0
        )

        assertTrue("Multiple indicators together must raise score significantly (>= 50.0), got ${res.totalScore}", res.totalScore >= 50.0)
        assertEquals(DetectionState.DROWSY, res.state)
    }

    @Test
    fun testRecoveryRequiresSustainedOpenEyes() {
        val scoring = DrowsinessScoringEngine(recoveryConfirmationDurationMs = 1_500L)

        // Force critical from 2200ms closure
        val crit = scoring.evaluate(2200L, 0.40, 2, 800.0, false, false, true, 2200L)
        assertEquals(DetectionState.CRITICAL, crit.state)

        // Driver opens eyes at 2300L (open for 100ms)
        val open100 = scoring.evaluate(0L, 0.20, 0, 150.0, false, false, false, 2300L)
        assertEquals("Single open frame must not immediately clear CRITICAL", DetectionState.CRITICAL, open100.state)

        // Driver keeps eyes open for 1000ms (elapsed 1000ms < 1500ms recovery)
        val open1000 = scoring.evaluate(0L, 0.20, 0, 150.0, false, false, false, 3200L)
        assertEquals("Still within recovery window, must hold state", DetectionState.CRITICAL, open1000.state)

        // Eyes remain open past 1500ms recovery duration (2300L + 1600L = 3900L)
        val recovered = scoring.evaluate(0L, 0.20, 0, 150.0, false, false, false, 3900L)
        assertNotEquals("State must downgrade after sustained open-eye recovery", DetectionState.CRITICAL, recovered.state)
    }

    // ─────────────────────────── Section 9: Adaptive Baseline Tests ───────────────────────────

    @Test
    fun testAdaptiveBaselineAdaptsSlowlyOnConfirmedOpenEyes() {
        val tracker = AdaptiveBaselineTracker(enabled = true, adaptationRate = 0.05, maxDriftFraction = 0.15)
        tracker.initialize(calibratedLeft = 0.25, calibratedRight = 0.25)

        // Driver drives attentively with eyes slightly wider (0.27)
        var updated = false
        for (i in 0..10) {
            updated = tracker.update(
                leftEar = 0.27,
                rightEar = 0.27,
                leftEyeState = EyeState.OPEN,
                rightEyeState = EyeState.OPEN,
                closureDurationMs = 0L,
                drowsinessScore = 5.0,
                detectionState = DetectionState.MONITORING,
                headPitchDegrees = 0.0,
                isYawning = false,
                isNodding = false
            )
        }

        assertTrue("Update should succeed under clean conditions", updated)
        assertTrue("Baseline should adapt towards higher resting EAR", tracker.currentBaselineLeft!! > 0.25)
        assertTrue("Baseline should not exceed max drift clamp (0.25 * 1.15 = 0.2875)", tracker.currentBaselineLeft!! <= 0.2875)
    }

    @Test
    fun testAdaptiveBaselineNeverLearnsClosedEyesOrDrowsiness() {
        val tracker = AdaptiveBaselineTracker(enabled = true)
        tracker.initialize(calibratedLeft = 0.25, calibratedRight = 0.25)

        // Case 1: Eyes are CLOSED
        val resClosed = tracker.update(
            leftEar = 0.08, rightEar = 0.08,
            leftEyeState = EyeState.CLOSED, rightEyeState = EyeState.CLOSED,
            closureDurationMs = 1200L, drowsinessScore = 65.0,
            detectionState = DetectionState.DROWSY, headPitchDegrees = 0.0,
            isYawning = false, isNodding = false
        )
        assertFalse("Must never adapt when eyes are closed", resClosed)
        assertEquals(0.25, tracker.currentBaselineLeft!!, 0.001)

        // Case 2: Driver is drowsy (score >= 25.0)
        val resDrowsy = tracker.update(
            leftEar = 0.22, rightEar = 0.22,
            leftEyeState = EyeState.OPEN, rightEyeState = EyeState.OPEN,
            closureDurationMs = 0L, drowsinessScore = 45.0,
            detectionState = DetectionState.DROWSY, headPitchDegrees = 0.0,
            isYawning = false, isNodding = false
        )
        assertFalse("Must never adapt when drowsiness is suspected", resDrowsy)
        assertEquals(0.25, tracker.currentBaselineLeft!!, 0.001)

        // Case 3: Driver is yawning or nodding
        val resYawn = tracker.update(
            leftEar = 0.24, rightEar = 0.24,
            leftEyeState = EyeState.OPEN, rightEyeState = EyeState.OPEN,
            closureDurationMs = 0L, drowsinessScore = 10.0,
            detectionState = DetectionState.MONITORING, headPitchDegrees = -22.0,
            isYawning = true, isNodding = true
        )
        assertFalse("Must never adapt during yawns or nods", resYawn)
        assertEquals(0.25, tracker.currentBaselineLeft!!, 0.001)
    }

    @Test
    fun testAdaptiveBaselineCanBeDisabled() {
        val tracker = AdaptiveBaselineTracker(enabled = false)
        tracker.initialize(calibratedLeft = 0.25, calibratedRight = 0.25)

        val updated = tracker.update(
            leftEar = 0.28, rightEar = 0.28,
            leftEyeState = EyeState.OPEN, rightEyeState = EyeState.OPEN,
            closureDurationMs = 0L, drowsinessScore = 0.0,
            detectionState = DetectionState.MONITORING, headPitchDegrees = 0.0,
            isYawning = false, isNodding = false
        )
        assertFalse("Disabled tracker should not update", updated)
        assertEquals(0.25, tracker.currentBaselineLeft!!, 0.001)
    }
}
