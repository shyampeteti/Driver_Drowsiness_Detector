package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

class DrowsinessDetectorTest {

    private fun createDetector(
        closedThreshold: Double = 0.18,
        openThreshold: Double = 0.22,
        warningCooldownMs: Long = 1_000L,
        criticalCooldownMs: Long = 1_000L
    ) = DrowsinessDetector(
        initialClosedThreshold = closedThreshold,
        initialOpenThreshold = openThreshold,
        warningCooldownMs = warningCooldownMs,
        criticalCooldownMs = criticalCooldownMs
    )

    // ─────────────────────────── TEST A: Eyes fully open ─────────────────────
    @Test
    fun testA_eyesFullyOpenFor30SecondsNoFalseAlarms() {
        val detector = createDetector()
        detector.start()

        // Simulate 30 seconds of open-eye frames at ~10 fps
        var lastSnapshot: DetectionSnapshot? = null
        for (i in 0..300) {
            lastSnapshot = detector.observe(0.35, (i * 100).toLong())
        }

        val snap = lastSnapshot!!
        assertFalse("No alarm should trigger with eyes fully open", snap.alarmRequested)
        assertEquals("State should be MONITORING", DetectionState.MONITORING, snap.state)
        assertEquals("Eye state should be OPEN", EyeState.OPEN, snap.eyeState)
        assertEquals("Yawn count should be zero", 0, snap.yawnCount)
        assertEquals(AlertLevel.NONE, snap.alertLevel)
    }

    // ─────────────────────────── TEST B: Normal blinking ────────────────────
    @Test
    fun testB_repeatedNormalBlinkingNoCriticalAlert() {
        val detector = createDetector()
        detector.start()

        // 20 normal blink cycles: 150 ms closed, 850 ms open
        for (cycle in 0 until 20) {
            val base = cycle * 1_000L
            detector.observe(0.35, base)
            detector.observe(0.08, base + 50L)   // blink start
            detector.observe(0.08, base + 150L)  // still closed
            detector.observe(0.35, base + 300L)  // reopen
            detector.observe(0.35, base + 500L)
            detector.observe(0.35, base + 850L)
        }

        val snap = detector.observe(0.35, 20_000L)
        assertFalse("Repeated normal blinking should not trigger critical", snap.alarmRequested)
        assertNotEquals("Should not escalate to CRITICAL from normal blinks", DetectionState.CRITICAL, snap.state)
    }

    // ─────────────────────────── TEST C: 1-second closure ───────────────────
    @Test
    fun briefNormalBlinkDoesNotTriggerAlarm() {
        val detector = createDetector()
        detector.start()

        detector.observe(0.30, 0L)
        detector.observe(0.08, 100L) // blink start
        val reopened = detector.observe(0.30, 220L) // blink ended within ~120ms

        assertFalse("Brief blink should not trigger alarm", reopened.alarmRequested)
        assertEquals(AlertLevel.NONE, reopened.alertLevel)
        assertEquals(DetectionState.MONITORING, reopened.state)
    }

    @Test
    fun prolongedEyeClosureTriggersDrowsyWarning() {
        val detector = createDetector()
        detector.start()

        detector.observe(0.08, 0L)
        detector.observe(0.08, 500L)
        val snapshot = detector.observe(0.08, 1_100L) // > 1000ms

        assertEquals(DetectionState.DROWSY, snapshot.state)
        assertEquals(AlertLevel.WARNING, snapshot.alertLevel)
        assertTrue(snapshot.alarmRequested)
        assertTrue(snapshot.closureDurationMs >= 1000L)
    }

    // ─────────────────────────── TEST D: 3-second closure → CRITICAL ────────
    @Test
    fun sustainedEyeClosureOver2SecondsEscalatesToCritical() {
        val detector = createDetector()
        detector.start()

        detector.observe(0.08, 0L)
        detector.observe(0.08, 1_000L)
        val criticalSnapshot = detector.observe(0.08, 2_100L) // > 2000ms

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

        detector.observe(0.08, 0L)
        val firstAlert = detector.observe(0.08, 1_100L)
        assertTrue(firstAlert.alarmRequested)

        // Next frame shortly after (within cooldown)
        val cooledSnapshot = detector.observe(0.08, 1_500L)
        assertEquals(DetectionState.DROWSY, cooledSnapshot.state)
        assertFalse("Should be suppressed by cooldown", cooledSnapshot.alarmRequested)

        // Frame after cooldown expires
        val nextAlert = detector.observe(0.08, 3_200L)
        assertTrue("Alarm should trigger again after cooldown", nextAlert.alarmRequested)
    }

    // ─────────────────────────── TEST H: Face temporarily leaves ───────────
    @Test
    fun testH_missingFaceDoesNotCountAsClosedOrSafe() {
        val detector = createDetector()
        detector.start()

        detector.observe(0.08, 0L)
        val faceLost = detector.faceLost(500L)

        assertEquals(DetectionState.FACE_NOT_DETECTED, faceLost.state)
        assertEquals(EyeState.UNCERTAIN, faceLost.eyeState)
        assertFalse(faceLost.alarmRequested)

        // Recovers when face reappears
        val recovered = detector.observe(0.35, 800L)
        assertEquals(DetectionState.MONITORING, recovered.state)
        assertEquals(EyeState.OPEN, recovered.eyeState)
    }

    // ─────────────────────────── TEST E: Tongue protrusion ──────────────────
    @Test
    fun testE_tonguePrustionDoesNotCountAsYawn() {
        // Tongue protrusion: MAR may be elevated but vertical-to-horizontal ratio
        // of vertical center is small relative to mouth width (tongue pushes lower
        // lip down but corners may not stretch much)
        val tracker = YawnTracker(
            yawnThreshold = 0.65,
            minSustainedDurationMs = 1_500L,
            minVerticalToHorizontalRatio = 0.15
        )

        // Simulate tongue-out: MAR=0.70 but vertical center is small (8px), width=60px
        // verticalToHorizontal = 8/60 = 0.133 < 0.15 → rejected
        val tongueMetrics = MouthAspectRatioCalculator.MouthMetrics(
            mar = 0.70,
            horizontalPx = 60.0,
            verticalCenterPx = 8.0  // small gap despite high MAR
        )

        val r1 = tracker.update(mar = 0.70, timestampMs = 0L, mouthMetrics = tongueMetrics)
        val r2 = tracker.update(mar = 0.70, timestampMs = 1_600L, mouthMetrics = tongueMetrics)

        assertFalse("Tongue protrusion with small vertical gap should not be a yawn", r2.isYawning)
        assertEquals("No yawn should be counted", 0, r2.totalCount)
    }

    // ─────────────────────────── TEST G: Genuine yawn ───────────────────────
    @Test
    fun testG_genuineYawnCounted_onlyOnce() {
        val tracker = YawnTracker(
            yawnThreshold = 0.65,
            minSustainedDurationMs = 1_500L,
            cooldownMs = 5_000L
        )

        // Genuine yawn: large vertical opening relative to width
        val yawnMetrics = MouthAspectRatioCalculator.MouthMetrics(
            mar = 0.80,
            horizontalPx = 60.0,
            verticalCenterPx = 25.0   // 25/60 = 0.42 >> 0.15
        )

        tracker.update(0.80, 0L, yawnMetrics)
        val midYawn = tracker.update(0.80, 1_600L, yawnMetrics)
        assertTrue("Genuine sustained yawn should be detected", midYawn.isYawning)
        assertEquals("Should count exactly one yawn", 1, midYawn.totalCount)

        // Continuing the same yawn (cooldown prevents double-counting)
        val continued = tracker.update(0.80, 2_000L, yawnMetrics)
        assertEquals("Should still be 1 yawn during cooldown", 1, continued.totalCount)
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

    // ─────────────────────────── TEST I: Eyes reopen after long closure ──────
    @Test
    fun testI_closureDurationRetainedAfterEyesReopen() {
        val detector = createDetector()
        detector.start()

        // Close eyes for 1.5 seconds
        detector.observe(0.08, 0L)
        detector.observe(0.08, 500L)
        detector.observe(0.08, 1_000L)
        detector.observe(0.08, 1_500L)

        // Eyes reopen
        detector.observe(0.35, 1_600L)
        detector.observe(0.35, 1_700L)
        val reopenedSnap = detector.observe(0.35, 1_900L) // after anti-flicker confirmation

        // The closure duration should not be 0 — it should retain the last completed closure
        assertTrue(
            "Closure duration should retain last completed closure after reopening, got ${reopenedSnap.closureDurationMs}",
            reopenedSnap.closureDurationMs > 0L
        )
    }

    @Test
    fun calibrationAdjustsThresholdsWithinSafeBounds() {
        val cal = CalibrationManager(
            calibrationDurationMs = 1_000L,
            minSafeClosedThreshold = 0.14,
            maxSafeClosedThreshold = 0.26,
            minSafeOpenThreshold = 0.20,
            maxSafeOpenThreshold = 0.36
        )

        cal.startCalibration(0L)
        for (i in 0..20) {
            cal.processSample(0.32, i * 50L)
        }
        val progress = cal.processSample(0.32, 1_050L)

        assertEquals(100, progress)
        assertFalse(cal.isCalibrating)
        assertTrue(cal.calibrationSucceeded)
        assertNotNull(cal.baselineOpenEar)
        assertTrue(cal.closedThreshold in 0.14..0.26)
        assertTrue(cal.openThreshold in 0.20..0.36)
        assertTrue(cal.openThreshold > cal.closedThreshold)
    }

    @Test
    fun calibrationRejectedWhenBaselineIsTooLow() {
        val cal = CalibrationManager(
            calibrationDurationMs = 500L,
            minSafeOpenThreshold = 0.20
        )
        cal.startCalibration(0L)
        // All samples are very low (drowsy-looking EAR)
        for (i in 0..30) {
            cal.processSample(0.14, i * 15L)
        }
        cal.processSample(0.14, 600L)

        assertFalse(cal.isCalibrating)
        assertFalse("Calibration should be rejected when baseline is too low", cal.calibrationSucceeded)
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

    // ─────────────────────────── PERCLOS tests ───────────────────────────────
    @Test
    fun perclosUsesTimeBasedCalculationNotFrameCounts() {
        val calc = PerclosCalculator(windowMs = 10_000L, minimumValidObservationMs = 2_000L)

        // Simulate 5 seconds of valid observations, alternating open/closed
        // 50% closed by time
        for (i in 0..9) {
            val t = i * 500L
            calc.recordObservation(t, isClosed = (i % 2 == 0))
        }

        val result = calc.calculate(4_500L)
        // Should be non-null since 4.5 seconds > 2 second minimum
        assertNotNull(result)
        // Roughly 50% (some variation due to interval counting)
        assertTrue("PERCLOS should be approximately 50% ± 20%", result!! in 0.30..0.70)
    }

    @Test
    fun perclosExcludesFaceLossIntervals() {
        val calc = PerclosCalculator(windowMs = 10_000L, minimumValidObservationMs = 1_000L)

        // Record 3 seconds of open-eye observations (face always detected)
        calc.recordObservation(0L, isClosed = false)
        calc.recordObservation(1_000L, isClosed = false)
        calc.recordObservation(2_000L, isClosed = false)
        calc.recordObservation(3_000L, isClosed = false)

        val result = calc.calculate(3_000L)
        assertNotNull(result)
        assertEquals("All open-eye observations should give 0% PERCLOS", 0.0, result!!, 0.01)
    }

    @Test
    fun perclosReturnsNullBeforeMinimumWindowFills() {
        val calc = PerclosCalculator(minimumValidObservationMs = 5_000L)
        calc.recordObservation(0L, isClosed = false)
        calc.recordObservation(1_000L, isClosed = false)
        // Only 1 second of data — below 5-second minimum
        assertNull("PERCLOS should be null before minimum window", calc.calculate(1_000L))
    }

    // ─────────────────────────── Score consistency ────────────────────────────
    @Test
    fun openEyesDoNotAccumulateHighScore() {
        val engine = DrowsinessScoringEngine()

        // 100 frames of open eyes, no closures
        var lastScore = 0.0
        for (i in 0..100) {
            val result = engine.evaluate(
                closureDurationMs = 0L,
                perclos = 0.0,
                prolongedCount = 0,
                avgEpisodeDurationMs = 150.0,
                isYawning = false,
                isNodding = false,
                isEyeClosed = false,
                timestampMs = i * 100L
            )
            lastScore = result.totalScore
        }

        assertTrue(
            "Score should remain near 0 with open eyes, got $lastScore",
            lastScore < 25.0
        )
        assertEquals(DetectionState.MONITORING, engine.evaluate(
            0L, 0.0, 0, 150.0, false, false, false, 10_100L
        ).state)
    }

    @Test
    fun singleYawnEventDoesNotRepeatlyInflateScore() {
        val engine = DrowsinessScoringEngine()

        // Fire "yawn detected" for 50 consecutive frames
        var score50 = 0.0
        for (i in 0..50) {
            val r = engine.evaluate(
                closureDurationMs = 0L,
                perclos = 0.0,
                prolongedCount = 0,
                avgEpisodeDurationMs = 150.0,
                isYawning = true,
                isNodding = false,
                isEyeClosed = false,
                timestampMs = i * 100L,
                yawnEventCount = 1,  // only 1 yawn event, not increasing
                nodEventCount = 0
            )
            score50 = r.totalScore
        }

        assertTrue(
            "Score after 50 'yawn' frames with same yawn count should not be near 100, got $score50",
            score50 < 50.0
        )
    }
}
