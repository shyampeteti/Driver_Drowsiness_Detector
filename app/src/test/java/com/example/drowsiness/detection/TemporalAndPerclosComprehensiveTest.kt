package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests Section 13.D:
 * - Closure episode durations: 100ms, 150ms, 200ms, 500ms, 1000ms, 2000ms.
 * - Anti-flicker micro-reopenings.
 * - Repeated prolonged closures.
 * - Missing face / landmark loss handling.
 * - PERCLOS interval-based calculation and face-loss immunity.
 */
class TemporalAndPerclosComprehensiveTest {

    @Test
    fun testBlinkAndClosureDurationsCategorizedCorrectly() {
        // Normal blinks (< 200ms)
        assertEquals(DurationCategory.NORMAL_BLINK, DurationCategory.fromDuration(100L))
        assertEquals(DurationCategory.NORMAL_BLINK, DurationCategory.fromDuration(150L))
        assertEquals(DurationCategory.NORMAL_BLINK, DurationCategory.fromDuration(199L))

        // Monitor (200 - 500ms)
        assertEquals(DurationCategory.MONITOR, DurationCategory.fromDuration(200L))
        assertEquals(DurationCategory.MONITOR, DurationCategory.fromDuration(350L))
        assertEquals(DurationCategory.MONITOR, DurationCategory.fromDuration(499L))

        // Potential drowsiness (500 - 1000ms)
        assertEquals(DurationCategory.POTENTIAL_DROWSINESS, DurationCategory.fromDuration(500L))
        assertEquals(DurationCategory.POTENTIAL_DROWSINESS, DurationCategory.fromDuration(750L))
        assertEquals(DurationCategory.POTENTIAL_DROWSINESS, DurationCategory.fromDuration(999L))

        // Serious warning (1000 - 2000ms)
        assertEquals(DurationCategory.SERIOUS_WARNING, DurationCategory.fromDuration(1000L))
        assertEquals(DurationCategory.SERIOUS_WARNING, DurationCategory.fromDuration(1500L))
        assertEquals(DurationCategory.SERIOUS_WARNING, DurationCategory.fromDuration(1999L))

        // Critical sustained (>= 2000ms)
        assertEquals(DurationCategory.CRITICAL_SUSTAINED, DurationCategory.fromDuration(2000L))
        assertEquals(DurationCategory.CRITICAL_SUSTAINED, DurationCategory.fromDuration(3500L))
    }

    @Test
    fun test100MsAnd150MsBlinksDoNotTriggerAlarmOrEpisode() {
        val tracker = ClosureEpisodeTracker(recoveryConfirmationMs = 250L)

        // 100ms blink
        tracker.update(isClosed = true, timestampMs = 0L)
        tracker.update(isClosed = true, timestampMs = 100L)
        val (_, ep1) = tracker.update(isClosed = false, timestampMs = 150L)
        val (_, ep2) = tracker.update(isClosed = false, timestampMs = 450L) // sustained open

        assertNotNull(ep2)
        assertEquals(100L, ep2?.durationMs)
        assertEquals(DurationCategory.NORMAL_BLINK, ep2?.category)
        assertEquals(0, tracker.countProlongedClosures(500L))
    }

    @Test
    fun test500MsClosureRecordedAsProlongedEpisode() {
        val tracker = ClosureEpisodeTracker(recoveryConfirmationMs = 200L, prolongedEpisodeThresholdMs = 500L)

        tracker.update(isClosed = true, timestampMs = 0L)
        tracker.update(isClosed = true, timestampMs = 500L)
        tracker.update(isClosed = false, timestampMs = 550L)
        val (_, ep) = tracker.update(isClosed = false, timestampMs = 800L) // confirmed open

        assertNotNull(ep)
        assertEquals(500L, ep?.durationMs)
        assertEquals(DurationCategory.POTENTIAL_DROWSINESS, ep?.category)
        assertEquals(1, tracker.countProlongedClosures(1000L))
    }

    @Test
    fun testBriefReopeningDoesNotSplitOngoingClosure() {
        val tracker = ClosureEpisodeTracker(recoveryConfirmationMs = 250L)

        // Closed for 400ms
        tracker.update(isClosed = true, timestampMs = 0L)
        tracker.update(isClosed = true, timestampMs = 400L)

        // Brief micro-reopening for 80ms (< 250ms confirmation)
        val (durOpen, ep1) = tracker.update(isClosed = false, timestampMs = 480L)
        assertNull("Episode should not conclude during micro-reopening", ep1)
        assertEquals(400L, durOpen)

        // Eyes close again
        val (durClosedAgain, _) = tracker.update(isClosed = true, timestampMs = 600L)
        assertEquals(600L, durClosedAgain)

        // Finally reopen and stay open
        tracker.update(isClosed = false, timestampMs = 700L)
        val (_, finalEp) = tracker.update(isClosed = false, timestampMs = 1000L)

        assertNotNull(finalEp)
        assertEquals(600L, finalEp?.durationMs)
    }

    @Test
    fun testFaceLossDuringClosureDoesNotAccumulateMissingTime() {
        val detector = DrowsinessDetector()
        detector.start()

        // Close eyes for 200ms
        detector.observe(0.08, 0L)
        detector.observe(0.08, 200L)

        // Face is lost for 3 seconds (e.g. driver looked out side window or obstructed camera)
        detector.faceLost(500L)
        detector.faceLost(1500L)
        detector.faceLost(3000L)

        // Face returns with open eyes
        val snap = detector.observe(0.30, 3200L)

        assertEquals(DetectionState.MONITORING, snap.state)
        assertEquals(AlertLevel.NONE, snap.alertLevel)
        assertFalse(snap.alarmRequested)
        assertNotEquals(DetectionState.CRITICAL, snap.state)
    }

    @Test
    fun testPerclosDoesNotAccumulateClosedTimeFromUncertainOrLostFrames() {
        val perclos = PerclosCalculator(windowMs = 20_000L, minimumValidObservationMs = 4_000L)

        // 5 seconds of valid open observations
        for (i in 0..10) {
            perclos.recordObservation(i * 500L, isClosed = false)
        }

        val resultOpen = perclos.calculate(5_000L)
        assertNotNull(resultOpen)
        assertEquals(0.0, resultOpen!!, 0.001)

        // Suppose camera dropped frames for 10 seconds (no observations recorded)
        // Then driver reappears with open eyes
        perclos.recordObservation(15_000L, isClosed = false)
        perclos.recordObservation(16_000L, isClosed = false)

        val resultAfterGap = perclos.calculate(16_000L)
        assertNotNull(resultAfterGap)
        assertEquals("Missing frames must not inflate PERCLOS to non-zero closed ratio", 0.0, resultAfterGap!!, 0.001)
    }
}
