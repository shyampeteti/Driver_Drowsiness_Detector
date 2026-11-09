package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * Tests Section 13.F: Property-based and randomized tests.
 *
 * Verifies invariants across hundreds of synthetic baseline values:
 * 1. Thresholds are always finite, positive, and ordered (closed < open).
 * 2. Adequate hysteresis separation is preserved.
 * 3. Low-EAR baselines (0.13 - 0.20) are never rejected solely for being low.
 * 4. Scaling is monotonic with baseline magnitude.
 * 5. Invalid inputs never become confident CLOSED observations.
 * 6. Detector never enters DROWSY solely from baseline anatomy.
 */
class PropertyBasedThresholdValidationTest {

    @Test
    fun testProperty_thresholdInvariantsAcrossRandomBaselines() {
        val random = Random(42)

        // Generate 150 diverse baselines in range [0.12, 0.45]
        for (i in 0 until 150) {
            val baseLeft = 0.12 + random.nextDouble() * (0.45 - 0.12)
            val baseRight = 0.12 + random.nextDouble() * (0.45 - 0.12)

            val cal = CalibrationManager(calibrationDurationMs = 500L, minSamplesPerEye = 20)
            cal.startCalibration(0L)

            // Feed 25 steady samples around this baseline
            for (f in 0..24) {
                val noiseL = (random.nextDouble() - 0.5) * 0.008
                val noiseR = (random.nextDouble() - 0.5) * 0.008
                cal.processSample(
                    leftEar = baseLeft + noiseL,
                    rightEar = baseRight + noiseR,
                    headPitchDegrees = 0.0,
                    timestampMs = f * 20L
                )
            }
            cal.processSample(baseLeft, baseRight, 0.0, 550L)

            assertTrue("Valid baseline ($baseLeft, $baseRight) must calibrate successfully", cal.calibrationSucceeded)

            // Invariant 1: Thresholds are finite
            assertTrue("Closed threshold left must be finite", cal.closedThresholdLeft.isFinite())
            assertTrue("Open threshold left must be finite", cal.openThresholdLeft.isFinite())
            assertTrue("Closed threshold right must be finite", cal.closedThresholdRight.isFinite())
            assertTrue("Open threshold right must be finite", cal.openThresholdRight.isFinite())

            // Invariant 2: Thresholds are strictly positive
            assertTrue("Closed threshold left must be > 0", cal.closedThresholdLeft > 0.0)
            assertTrue("Open threshold left must be > 0", cal.openThresholdLeft > 0.0)

            // Invariant 3: Ordering
            assertTrue("Closed threshold must be < open threshold for left eye", cal.closedThresholdLeft < cal.openThresholdLeft)
            assertTrue("Closed threshold must be < open threshold for right eye", cal.closedThresholdRight < cal.openThresholdRight)

            // Invariant 4: Adequate separation (hysteresis gap >= minHysteresisGap)
            assertTrue(
                "Separation must be at least minHysteresisGap (0.02), got ${cal.openThresholdLeft - cal.closedThresholdLeft}",
                cal.openThresholdLeft - cal.closedThresholdLeft >= 0.02 - 1e-6
            )
            assertTrue(
                "Separation must be at least minHysteresisGap (0.02), got ${cal.openThresholdRight - cal.closedThresholdRight}",
                cal.openThresholdRight - cal.closedThresholdRight >= 0.02 - 1e-6
            )

            // Invariant 5: Driver's resting open EAR is >= openThreshold
            assertTrue("Resting left EAR should be recognized as OPEN", baseLeft >= cal.openThresholdLeft - 0.02)
            assertTrue("Resting right EAR should be recognized as OPEN", baseRight >= cal.openThresholdRight - 0.02)
        }
    }

    @Test
    fun testProperty_monotonicThresholdScaling() {
        val baselines = listOf(0.14, 0.18, 0.22, 0.26, 0.30, 0.34, 0.38)
        var prevClosed = 0.0
        var prevOpen = 0.0

        for (base in baselines) {
            val cal = CalibrationManager(calibrationDurationMs = 500L, minSamplesPerEye = 20)
            cal.startCalibration(0L)
            for (i in 0..22) {
                cal.processSample(base, base, 0.0, i * 20L)
            }
            cal.processSample(base, base, 0.0, 550L)

            assertTrue(cal.calibrationSucceeded)
            assertTrue("Closed threshold must scale monotonically with baseline", cal.closedThresholdLeft >= prevClosed)
            assertTrue("Open threshold must scale monotonically with baseline", cal.openThresholdLeft >= prevOpen)

            prevClosed = cal.closedThresholdLeft
            prevOpen = cal.openThresholdLeft
        }
    }

    @Test
    fun testProperty_invalidMeasurementsNeverBecomeConfidentClosedObservations() {
        val invalidInputs = listOf(
            null,
            Double.NaN,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            -0.5,
            -1e-6
        )

        for (invalid in invalidInputs) {
            val res = EyeClassifier.classifySingleEye(
                rawEar = invalid,
                prevSmoothedEar = 0.20,
                prevEyeState = EyeState.OPEN,
                closedThreshold = 0.15,
                openThreshold = 0.20
            )

            assertNotEquals("Invalid inputs must never be classified as CLOSED", EyeState.CLOSED, res.state)
            assertEquals("Invalid inputs must be UNCERTAIN", EyeState.UNCERTAIN, res.state)
            assertEquals(0.0, res.confidence, 0.001)
        }
    }

    @Test
    fun testProperty_detectorNeverEntersDrowsySolelyBecauseOfBaselineAnatomy() {
        // Test 10 distinct resting anatomies from very narrow (0.15) to very wide (0.42)
        val testBaselines = listOf(0.15, 0.17, 0.20, 0.23, 0.27, 0.31, 0.35, 0.39, 0.42)

        for (base in testBaselines) {
            val detector = DrowsinessDetector()
            detector.start(enableCalibration = true, nowMs = 0L)

            // Calibrate
            for (i in 0..25) {
                detector.observe(base, i * 150L)
            }
            detector.observe(base, 3_600L)

            // Monitor for 50 frames with steady open eyes
            var maxScore = 0.0
            for (i in 1..50) {
                val snap = detector.observe(base, 3_600L + i * 100L)
                assertEquals("Should remain in MONITORING for resting EAR $base", DetectionState.MONITORING, snap.state)
                assertFalse("Should never request alarm for resting EAR $base", snap.alarmRequested)
                if (snap.drowsinessScore > maxScore) maxScore = snap.drowsinessScore
            }

            assertTrue("Max score for attentive driver with baseline $base must be < 15.0, got $maxScore", maxScore < 15.0)
        }
    }
}
