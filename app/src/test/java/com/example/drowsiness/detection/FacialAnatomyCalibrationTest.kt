package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests Section 13.A & Section 13.C:
 * - Individualized calibration for diverse facial anatomies (Persons A through F).
 * - Naturally low EAR (narrow eyes: 0.16, 0.19) must NOT be rejected or clamped to 0.20.
 * - Outlier/blink rejection during calibration.
 * - Head pose gating (frontal requirement).
 * - Stability gating (unstable/noisy measurements rejected).
 * - Recalibration and failure reporting.
 */
class FacialAnatomyCalibrationTest {

    data class PersonProfile(
        val name: String,
        val leftEar: Double,
        val rightEar: Double
    )

    private val testPersons = listOf(
        PersonProfile("A", 0.16, 0.17),
        PersonProfile("B", 0.19, 0.20),
        PersonProfile("C", 0.23, 0.24),
        PersonProfile("D", 0.28, 0.29),
        PersonProfile("E", 0.33, 0.32),
        PersonProfile("F", 0.36, 0.35)
    )

    // ─────────────────────────── Section 13.A Tests ───────────────────────────

    @Test
    fun testAllDiverseFacialAnatomiesSuccessfullyCalibrate() {
        for (person in testPersons) {
            val cal = CalibrationManager(calibrationDurationMs = 1_000L, minSamplesPerEye = 20)
            cal.startCalibration(0L)

            // Simulate 25 frames of steady open eyes for this person
            for (i in 0..24) {
                cal.processSample(
                    leftEar = person.leftEar,
                    rightEar = person.rightEar,
                    headPitchDegrees = 0.0,
                    timestampMs = i * 40L
                )
            }
            val progress = cal.processSample(person.leftEar, person.rightEar, 0.0, 1_050L)

            assertEquals(100, progress)
            assertTrue("Person ${person.name} with baselines (${person.leftEar}, ${person.rightEar}) must calibrate successfully", cal.calibrationSucceeded)
            assertEquals(CalibrationFailureReason.NONE, cal.failureReason)

            assertNotNull(cal.baselineOpenEarLeft)
            assertNotNull(cal.baselineOpenEarRight)
            assertEquals("Left baseline should match person's resting EAR", person.leftEar, cal.baselineOpenEarLeft!!, 0.015)
            assertEquals("Right baseline should match person's resting EAR", person.rightEar, cal.baselineOpenEarRight!!, 0.015)

            // Verify personalized thresholds
            assertTrue("Closed threshold left must be strictly less than open threshold left", cal.closedThresholdLeft < cal.openThresholdLeft)
            assertTrue("Closed threshold right must be strictly less than open threshold right", cal.closedThresholdRight < cal.openThresholdRight)
            assertTrue("Person's resting open left EAR must be >= open threshold", person.leftEar >= cal.openThresholdLeft)
            assertTrue("Person's resting open right EAR must be >= open threshold", person.rightEar >= cal.openThresholdRight)
        }
    }

    @Test
    fun testNarrowEyedDriverDoesNotTriggerFalseDrowsinessAfterCalibration() {
        val personA = PersonProfile("A", 0.16, 0.17)
        val detector = DrowsinessDetector()
        detector.start(enableCalibration = true, nowMs = 0L)

        // Feed calibration frames
        for (i in 0..30) {
            detector.observe(
                leftEar = personA.leftEar,
                rightEar = personA.rightEar,
                faceDetected = true,
                mouthAspectRatio = 0.20,
                headPitchDegrees = 0.0,
                timestampMs = i * 120L
            )
        }
        val calEndSnap = detector.observe(
            leftEar = personA.leftEar,
            rightEar = personA.rightEar,
            faceDetected = true,
            timestampMs = 3_600L
        )

        assertFalse("Calibration should be complete", calEndSnap.isCalibrating)
        assertTrue("Calibration should have succeeded", detector.calibrationManager.calibrationSucceeded)

        // Drive for 10 seconds with natural narrow eyes open at 0.16 / 0.17
        var lastSnapshot: DetectionSnapshot? = null
        for (i in 1..100) {
            lastSnapshot = detector.observe(
                leftEar = personA.leftEar,
                rightEar = personA.rightEar,
                faceDetected = true,
                timestampMs = 3_600L + i * 100L
            )
        }

        val snap = lastSnapshot!!
        assertEquals("State must be MONITORING, not DROWSY or CRITICAL", DetectionState.MONITORING, snap.state)
        assertEquals("Eye state must be classified as OPEN", EyeState.OPEN, snap.eyeState)
        assertEquals("Alert level must be NONE", AlertLevel.NONE, snap.alertLevel)
        assertFalse("No alarm should be requested for narrow open eyes", snap.alarmRequested)
        assertTrue("Drowsiness score must remain minimal (< 15.0)", snap.drowsinessScore < 15.0)
    }

    // ─────────────────────────── Section 13.C Tests ───────────────────────────

    @Test
    fun testCalibrationRejectsInsufficientSamplesWithoutFalseSuccess() {
        val cal = CalibrationManager(calibrationDurationMs = 1_000L, minSamplesPerEye = 25)
        cal.startCalibration(0L)

        // Provide only 10 samples (fewer than required 25)
        for (i in 0 until 10) {
            cal.processSample(0.28, 0.28, 0.0, i * 40L)
        }
        cal.processSample(0.28, 0.28, 0.0, 1_050L)

        assertFalse("Calibration must fail when insufficient samples are collected", cal.calibrationSucceeded)
        assertEquals(CalibrationFailureReason.INSUFFICIENT_SAMPLES, cal.failureReason)
        assertNull(cal.baselineOpenEarLeft)
        assertNull(cal.baselineOpenEarRight)
    }

    @Test
    fun testCalibrationGatingRejectsDownwardPitchingHead() {
        val cal = CalibrationManager(calibrationDurationMs = 1_000L, minSamplesPerEye = 20, pitchToleranceDegrees = 15.0)
        cal.startCalibration(0L)

        // Driver is looking down (pitch = -25.0°) during calibration
        for (i in 0..25) {
            cal.processSample(0.28, 0.28, headPitchDegrees = -25.0, timestampMs = i * 40L)
        }
        cal.processSample(0.28, 0.28, headPitchDegrees = -25.0, timestampMs = 1_050L)

        assertFalse("Calibration must fail if face is not frontal", cal.calibrationSucceeded)
        assertEquals(CalibrationFailureReason.INSUFFICIENT_SAMPLES, cal.failureReason)
    }

    @Test
    fun testBlinksDuringCalibrationAreRejectedByTrimmedEstimator() {
        val cal = CalibrationManager(calibrationDurationMs = 2_000L, minSamplesPerEye = 30)
        cal.startCalibration(0L)

        // 40 frames total: 32 open frames at 0.30, and 8 blink frames at 0.08
        val samples = mutableListOf<Double>()
        for (i in 0 until 32) samples.add(0.30)
        for (i in 0 until 8) samples.add(0.08)
        samples.shuffle()

        for (i in samples.indices) {
            cal.processSample(samples[i], samples[i], 0.0, i * 50L)
        }
        cal.processSample(0.30, 0.30, 0.0, 2_050L)

        assertTrue(cal.calibrationSucceeded)
        assertNotNull(cal.baselineOpenEarLeft)
        assertEquals("Blinks must not depress the open baseline below resting level", 0.30, cal.baselineOpenEarLeft!!, 0.015)
    }

    @Test
    fun testUnstableMeasurementsRejectCalibration() {
        val cal = CalibrationManager(
            calibrationDurationMs = 1_000L,
            minSamplesPerEye = 20,
            maxSampleStdDevRatio = 0.20
        )
        cal.startCalibration(0L)

        // Wildly fluctuating EAR measurements (e.g. erratic lighting or tracking glitches)
        val erraticValues = listOf(0.12, 0.45, 0.15, 0.42, 0.18, 0.40, 0.13, 0.44, 0.16, 0.39,
                                   0.14, 0.43, 0.17, 0.38, 0.19, 0.41, 0.11, 0.46, 0.20, 0.37, 0.15, 0.42)
        for (i in erraticValues.indices) {
            cal.processSample(erraticValues[i], erraticValues[i], 0.0, i * 45L)
        }
        cal.processSample(0.25, 0.25, 0.0, 1_050L)

        assertFalse("Unstable measurements must cause calibration to fail", cal.calibrationSucceeded)
        assertEquals(CalibrationFailureReason.UNSTABLE_MEASUREMENTS, cal.failureReason)
    }

    @Test
    fun testRecalibrationAfterPreviousSuccess() {
        val cal = CalibrationManager(calibrationDurationMs = 1_000L, minSamplesPerEye = 20)

        // First calibration with Person C (0.23, 0.24)
        cal.startCalibration(0L)
        for (i in 0..25) {
            cal.processSample(0.23, 0.24, 0.0, i * 40L)
        }
        cal.processSample(0.23, 0.24, 0.0, 1_050L)
        assertTrue(cal.calibrationSucceeded)
        assertEquals(0.23, cal.baselineOpenEarLeft!!, 0.015)

        // Recalibration with Person E (0.33, 0.32)
        cal.startCalibration(2_000L)
        for (i in 0..25) {
            cal.processSample(0.33, 0.32, 0.0, 2_000L + i * 40L)
        }
        cal.processSample(0.33, 0.32, 0.0, 3_050L)
        assertTrue(cal.calibrationSucceeded)
        assertEquals("Baseline should be updated to new person after recalibration", 0.33, cal.baselineOpenEarLeft!!, 0.015)
        assertEquals(0.32, cal.baselineOpenEarRight!!, 0.015)
    }

    @Test
    fun testAsymmetricLeftRightBaselinesSupported() {
        val cal = CalibrationManager(calibrationDurationMs = 1_000L, minSamplesPerEye = 20)
        cal.startCalibration(0L)

        // Asymmetric anatomy: Left eye resting 0.18, Right eye resting 0.28
        for (i in 0..25) {
            cal.processSample(0.18, 0.28, 0.0, i * 40L)
        }
        cal.processSample(0.18, 0.28, 0.0, 1_050L)

        assertTrue(cal.calibrationSucceeded)
        assertEquals(0.18, cal.baselineOpenEarLeft!!, 0.015)
        assertEquals(0.28, cal.baselineOpenEarRight!!, 0.015)

        assertTrue(cal.closedThresholdLeft < cal.closedThresholdRight)
        assertTrue(cal.openThresholdLeft < cal.openThresholdRight)
    }
}
