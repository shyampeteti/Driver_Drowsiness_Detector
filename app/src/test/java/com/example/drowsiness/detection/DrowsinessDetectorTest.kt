package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

class DrowsinessDetectorTest {

    private fun createDetector(
        closedThreshold: Double = 0.20,
        openThreshold: Double = 0.24,
        warningCooldownMs: Long = 1_000L,
        criticalCooldownMs: Long = 1_000L
    ) = DrowsinessDetector(
        initialClosedThreshold = closedThreshold,
        initialOpenThreshold = openThreshold,
        warningCooldownMs = warningCooldownMs,
        criticalCooldownMs = criticalCooldownMs
    )

    @Test
    fun briefNormalBlinkDoesNotTriggerAlarm() {
        val detector = createDetector()
        detector.start()

        detector.observe(0.30, 0L)
        detector.observe(0.12, 100L) // blink start
        val reopened = detector.observe(0.30, 220L) // blink ended within ~120ms

        assertFalse("Brief blink should not trigger alarm", reopened.alarmRequested)
        assertEquals(AlertLevel.NONE, reopened.alertLevel)
        assertEquals(DetectionState.MONITORING, reopened.state)
    }

    @Test
    fun prolongedEyeClosureTriggersDrowsyWarning() {
        val detector = createDetector()
        detector.start()

        detector.observe(0.10, 0L)
        detector.observe(0.10, 500L)
        val snapshot = detector.observe(0.10, 1_100L) // > 1000ms

        assertEquals(DetectionState.DROWSY, snapshot.state)
        assertEquals(AlertLevel.WARNING, snapshot.alertLevel)
        assertTrue(snapshot.alarmRequested)
        assertTrue(snapshot.closureDurationMs >= 1000L)
    }

    @Test
    fun sustainedEyeClosureOver2SecondsEscalatesToCritical() {
        val detector = createDetector()
        detector.start()

        detector.observe(0.10, 0L)
        detector.observe(0.10, 1_000L)
        val criticalSnapshot = detector.observe(0.10, 2_100L) // > 2000ms

        assertEquals(DetectionState.CRITICAL, criticalSnapshot.state)
        assertEquals(AlertLevel.CRITICAL, criticalSnapshot.alertLevel)
        assertTrue(criticalSnapshot.alarmRequested)
        assertEquals(DurationCategory.CRITICAL_SUSTAINED, criticalSnapshot.durationCategory)
    }

    @Test
    fun durationCategoriesAreCorrectlyMapped() {
        assertEquals(DurationCategory.NORMAL_BLINK, DurationCategory.fromDuration(150L))
        assertEquals(DurationCategory.MONITOR, DurationCategory.fromDuration(350L))
        assertEquals(DurationCategory.POTENTIAL_DROWSINESS, DurationCategory.fromDuration(750L))
        assertEquals(DurationCategory.SERIOUS_WARNING, DurationCategory.fromDuration(1500L))
        assertEquals(DurationCategory.CRITICAL_SUSTAINED, DurationCategory.fromDuration(2500L))
    }

    @Test
    fun antiFlickerPreventsPrematureResetOnMicroReopening() {
        val tracker = ClosureEpisodeTracker(recoveryConfirmationMs = 250L)

        // Close eyes for 800ms
        tracker.update(isClosed = true, timestampMs = 0L)
        val (dur1, _) = tracker.update(isClosed = true, timestampMs = 800L)
        assertEquals(800L, dur1)

        // Micro reopening for 100ms (< 250ms recovery confirmation)
        val (dur2, episode) = tracker.update(isClosed = false, timestampMs = 900L)
        assertNull("Episode should not be concluded during micro-reopening", episode)
        assertEquals("Duration should be preserved during flicker window", 800L, dur2)

        // Continuous open for >= 250ms concludes the episode
        val (dur3, concluded) = tracker.update(isClosed = false, timestampMs = 1_160L)
        assertNotNull("Episode should conclude after sustained open confirmation", concluded)
        assertEquals(0L, dur3)
        assertEquals(800L, concluded?.durationMs)
    }

    @Test
    fun repeatedProlongedClosuresAreDetected() {
        val tracker = ClosureEpisodeTracker(recoveryConfirmationMs = 200L, prolongedEpisodeThresholdMs = 500L)

        // First prolonged closure: 600ms
        tracker.update(isClosed = true, timestampMs = 0L)
        tracker.update(isClosed = true, timestampMs = 600L)
        tracker.update(isClosed = false, timestampMs = 700L)
        tracker.update(isClosed = false, timestampMs = 950L) // completed

        assertEquals(1, tracker.countProlongedClosures(1_000L))

        // Second prolonged closure: 700ms
        tracker.update(isClosed = true, timestampMs = 2_000L)
        tracker.update(isClosed = true, timestampMs = 2_700L)
        tracker.update(isClosed = false, timestampMs = 2_800L)
        tracker.update(isClosed = false, timestampMs = 3_100L) // completed

        assertEquals(2, tracker.countProlongedClosures(3_200L))
        assertTrue(tracker.hasRepeatedProlongedClosures(3_200L, minCount = 2))
    }

    @Test
    fun warningCooldownPreventsRepeatingAlarmEveryFrame() {
        val detector = createDetector(warningCooldownMs = 2_000L)
        detector.start()

        detector.observe(0.10, 0L)
        val firstAlert = detector.observe(0.10, 1_100L)
        assertTrue(firstAlert.alarmRequested)

        // Next frame shortly after (within cooldown)
        val cooledSnapshot = detector.observe(0.10, 1_500L)
        assertEquals(DetectionState.DROWSY, cooledSnapshot.state)
        assertFalse("Should be suppressed by cooldown", cooledSnapshot.alarmRequested)

        // Frame after cooldown expires
        val nextAlert = detector.observe(0.10, 3_200L)
        assertTrue("Alarm should trigger again after cooldown", nextAlert.alarmRequested)
    }

    @Test
    fun missingFaceDoesNotCountAsClosedOrSafe() {
        val detector = createDetector()
        detector.start()

        detector.observe(0.10, 0L)
        val faceLost = detector.faceLost(500L)

        assertEquals(DetectionState.FACE_NOT_DETECTED, faceLost.state)
        assertEquals(EyeState.UNCERTAIN, faceLost.eyeState)
        assertFalse(faceLost.alarmRequested)

        // Recovers when face reappears
        val recovered = detector.observe(0.30, 800L)
        assertEquals(DetectionState.MONITORING, recovered.state)
        assertEquals(EyeState.OPEN, recovered.eyeState)
    }

    @Test
    fun yawningDetectionCalculatesMouthAspectRatioAndTriggersBonus() {
        val leftCorner = Point2D(0.0, 0.0)
        val rightCorner = Point2D(10.0, 0.0)
        val upperCenter = Point2D(5.0, 4.0)
        val lowerCenter = Point2D(5.0, -4.0)
        val upperLeft = Point2D(3.0, 3.0)
        val lowerLeft = Point2D(3.0, -3.0)
        val upperRight = Point2D(7.0, 3.0)
        val lowerRight = Point2D(7.0, -3.0)

        val mar = MouthAspectRatioCalculator.calculate(
            leftCorner, rightCorner,
            upperCenter, lowerCenter,
            upperLeft, lowerLeft,
            upperRight, lowerRight
        )

        assertNotNull(mar)
        assertTrue(mar!! > 0.70) // large mouth opening

        val tracker = YawnTracker(yawnThreshold = 0.65, minSustainedDurationMs = 1_000L)
        val r1 = tracker.update(mar, 0L)
        assertFalse(r1.isYawning)

        val r2 = tracker.update(mar, 1_100L)
        assertTrue("Sustained mouth opening should be classified as a yawn", r2.isYawning)
        assertEquals(1, r2.totalCount)
    }

    @Test
    fun calibrationAdjustsThresholdsWithinSafeBounds() {
        val cal = CalibrationManager(
            calibrationDurationMs = 1_000L,
            minSafeClosedThreshold = 0.16,
            maxSafeClosedThreshold = 0.25,
            minSafeOpenThreshold = 0.22,
            maxSafeOpenThreshold = 0.32
        )

        cal.startCalibration(0L)
        for (i in 0..20) {
            cal.processSample(0.32, i * 50L)
        }
        val progress = cal.processSample(0.32, 1_050L)

        assertEquals(100, progress)
        assertFalse(cal.isCalibrating)
        assertNotNull(cal.baselineOpenEar)
        assertTrue(cal.closedThreshold in 0.16..0.25)
        assertTrue(cal.openThreshold in 0.22..0.32)
        assertTrue(cal.openThreshold > cal.closedThreshold)
    }

    @Test
    fun stopAndFailExplicitStates() {
        val detector = createDetector()
        detector.start()
        val stopped = detector.stop()
        assertEquals(DetectionState.STOPPED, stopped.state)

        detector.start()
        val failed = detector.fail()
        assertEquals(DetectionState.CAMERA_ERROR, failed.state)
    }
}
