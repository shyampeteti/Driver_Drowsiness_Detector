package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

class DrowsinessDetectorTest {
    private fun detector() = DrowsinessDetector(
        closedThreshold = 0.20, openThreshold = 0.25,
        prolongedClosureMs = 1_000, observationGapResetMs = 400,
        perclosWindowMs = 2_000, minimumPerclosObservations = 3,
        perclosAlertThreshold = 0.95, alarmCooldownMs = 500
    )

    @Test fun briefBlinkDoesNotAlertAndReopeningRecovers() {
        val d = detector(); d.start()
        d.observe(0.30, 0)
        d.observe(0.10, 100)
        val reopened = d.observe(0.30, 250)
        assertFalse(reopened.alarmRequested)
        assertEquals(DetectionState.MONITORING, reopened.state)
        assertEquals(EyeState.OPEN, reopened.eyeState)
    }

    @Test fun prolongedClosureRequestsAlarmOnceThenHonoursCooldown() {
        val d = detector(); d.start()
        d.observe(0.10, 0)
        d.observe(0.10, 500)
        val alert = d.observe(0.10, 1_000)
        assertEquals(DetectionState.DROWSINESS_DETECTED, alert.state)
        assertTrue(alert.alarmRequested)
        assertFalse(d.observe(0.10, 1_100).alarmRequested)
        assertTrue(d.observe(0.10, 1_600).alarmRequested)
    }

    @Test fun longGapDoesNotCountAsContinuousClosure() {
        val d = detector(); d.start()
        d.observe(0.10, 100)
        val afterGap = d.observe(0.10, 1_000)
        assertEquals(0L, afterGap.closureDurationMs)
        assertFalse(afterGap.alarmRequested)
    }

    @Test fun invalidFaceDoesNotBecomeOpenOrClosedAndCanRecover() {
        val d = detector(); d.start()
        d.observe(0.10, 100)
        val missing = d.faceLost(700)
        assertEquals(DetectionState.FACE_NOT_DETECTED, missing.state)
        assertEquals(EyeState.UNCERTAIN, missing.eyeState)
        val recovered = d.observe(0.30, 750)
        assertEquals(DetectionState.MONITORING, recovered.state)
        assertEquals(EyeState.OPEN, recovered.eyeState)
    }

    @Test fun rollingClosureRatioExcludesInvalidObservations() {
        val d = detector(); d.start()
        d.observe(0.10, 0)
        d.observe(0.30, 100)
        d.faceLost(200)
        val result = d.observe(0.10, 250)
        assertEquals(2.0 / 3.0, result.perclos!!, 1e-9)
    }

    @Test fun expiredSamplesAreRemovedFromWindow() {
        val d = detector(); d.start()
        d.observe(0.10, 0)
        d.observe(0.10, 100)
        d.observe(0.30, 200)
        val recent = d.observe(0.30, 2_500)
        assertNull(recent.perclos)
    }

    @Test fun stopClearsStateAndFailureIsExplicit() {
        val d = detector(); d.start()
        assertEquals(DetectionState.STOPPED, d.stop().state)
        d.start()
        assertEquals(DetectionState.CAMERA_ERROR, d.fail().state)
    }

    @Test fun thresholdBandUsesHysteresis() {
        val d = detector(); d.start()
        assertEquals(EyeState.OPEN, d.observe(0.30, 0).eyeState)
        assertEquals(EyeState.OPEN, d.observe(0.22, 100).eyeState)
        assertEquals(EyeState.CLOSED, d.observe(0.19, 200).eyeState)
        assertEquals(EyeState.CLOSED, d.observe(0.22, 300).eyeState)
    }
}
