package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests Section 13.B:
 * - Single-eye classification across Cases A through H.
 * - Hysteresis behavior (retention of previous state).
 * - Bilateral fusion cases (both open, both closed, asymmetric winks/squints, one eye uncertain).
 * - Sudden drops and tracking jitter.
 */
class EyeClassificationAndBilateralFusionTest {

    private val closedThreshold = 0.13
    private val openThreshold = 0.16

    // ─────────────────────────── Single-Eye Cases A - H ───────────────────────────

    @Test
    fun testCaseAAndB_invalidLandmarksAndInvalidEarReturnUncertain() {
        // CASE A / B: null EAR
        val resNull = EyeClassifier.classifySingleEye(
            rawEar = null,
            prevSmoothedEar = 0.15,
            prevEyeState = EyeState.OPEN,
            closedThreshold = closedThreshold,
            openThreshold = openThreshold
        )
        assertEquals(EyeState.UNCERTAIN, resNull.state)
        assertEquals(0.0, resNull.confidence, 0.001)

        // Invalid NaN
        val resNaN = EyeClassifier.classifySingleEye(
            rawEar = Double.NaN,
            prevSmoothedEar = 0.15,
            prevEyeState = EyeState.OPEN,
            closedThreshold = closedThreshold,
            openThreshold = openThreshold
        )
        assertEquals(EyeState.UNCERTAIN, resNaN.state)

        // Invalid negative
        val resNeg = EyeClassifier.classifySingleEye(
            rawEar = -0.05,
            prevSmoothedEar = 0.15,
            prevEyeState = EyeState.OPEN,
            closedThreshold = closedThreshold,
            openThreshold = openThreshold
        )
        assertEquals(EyeState.UNCERTAIN, resNeg.state)
    }

    @Test
    fun testCaseC_earBelowOrEqualClosedThresholdClassifiesClosed() {
        val res = EyeClassifier.classifySingleEye(
            rawEar = 0.08,
            prevSmoothedEar = null,
            prevEyeState = EyeState.UNCERTAIN,
            closedThreshold = closedThreshold,
            openThreshold = openThreshold
        )
        assertEquals(EyeState.CLOSED, res.state)
        assertTrue("Confidence should be high for clear closure", res.confidence >= 0.70)
    }

    @Test
    fun testCaseD_earAboveOrEqualOpenThresholdClassifiesOpen() {
        val res = EyeClassifier.classifySingleEye(
            rawEar = 0.20,
            prevSmoothedEar = null,
            prevEyeState = EyeState.UNCERTAIN,
            closedThreshold = closedThreshold,
            openThreshold = openThreshold
        )
        assertEquals(EyeState.OPEN, res.state)
        assertTrue("Confidence should be high for clear opening", res.confidence >= 0.70)
    }

    @Test
    fun testCaseE_betweenThresholdsRetainsPreviousState() {
        // In hysteresis zone: 0.145 is between 0.13 and 0.16
        val prevOpen = EyeClassifier.classifySingleEye(
            rawEar = 0.145,
            prevSmoothedEar = 0.145,
            prevEyeState = EyeState.OPEN,
            closedThreshold = closedThreshold,
            openThreshold = openThreshold
        )
        assertEquals("Should retain OPEN in hysteresis zone", EyeState.OPEN, prevOpen.state)

        val prevClosed = EyeClassifier.classifySingleEye(
            rawEar = 0.145,
            prevSmoothedEar = 0.145,
            prevEyeState = EyeState.CLOSED,
            closedThreshold = closedThreshold,
            openThreshold = openThreshold
        )
        assertEquals("Should retain CLOSED in hysteresis zone", EyeState.CLOSED, prevClosed.state)
    }

    @Test
    fun testCaseF_faceTrackingLostReturnsUncertain() {
        val res = EyeClassifier.classifySingleEye(
            rawEar = 0.25,
            prevSmoothedEar = 0.25,
            prevEyeState = EyeState.OPEN,
            closedThreshold = closedThreshold,
            openThreshold = openThreshold,
            faceDetected = false
        )
        assertEquals("Lost face must yield UNCERTAIN", EyeState.UNCERTAIN, res.state)
        assertNull(res.smoothedEar)
        assertEquals(0.0, res.confidence, 0.001)
    }

    @Test
    fun testCaseH_singleFrameAbruptDropIsDampedByEma() {
        // Previous stable open eye at 0.25. One noisy frame drops to 0.05.
        // With EMA alpha = 0.50: smoothed = 0.50 * 0.05 + 0.50 * 0.25 = 0.150
        // Since 0.150 is between 0.13 (closed) and 0.16 (open), it stays in hysteresis
        // and retains OPEN rather than false-triggering CLOSED on a single glitch!
        val res = EyeClassifier.classifySingleEye(
            rawEar = 0.05,
            prevSmoothedEar = 0.25,
            prevEyeState = EyeState.OPEN,
            closedThreshold = 0.13,
            openThreshold = 0.16,
            emaAlpha = 0.50
        )
        assertEquals("Single-frame noise must not immediately flip open state to closed", EyeState.OPEN, res.state)
    }

    // ─────────────────────────── Bilateral Fusion Tests ───────────────────────────

    @Test
    fun testBilateralFusionBothOpen() {
        val left = EyeClassifier.EyeResult(EyeState.OPEN, 0.25, 0.9)
        val right = EyeClassifier.EyeResult(EyeState.OPEN, 0.26, 0.9)
        val fusion = EyeClassifier.fuse(left, right)

        assertEquals(EyeState.OPEN, fusion.combinedState)
        assertEquals(0.255, fusion.combinedEar!!, 0.001)
        assertFalse(fusion.isAsymmetric)
    }

    @Test
    fun testBilateralFusionBothClosed() {
        val left = EyeClassifier.EyeResult(EyeState.CLOSED, 0.08, 0.9)
        val right = EyeClassifier.EyeResult(EyeState.CLOSED, 0.09, 0.9)
        val fusion = EyeClassifier.fuse(left, right)

        assertEquals(EyeState.CLOSED, fusion.combinedState)
        assertEquals(0.085, fusion.combinedEar!!, 0.001)
        assertFalse(fusion.isAsymmetric)
    }

    @Test
    fun testBilateralFusionAsymmetricClosureDoesNotTriggerCombinedClosed() {
        // Driver is winking with left eye closed, right eye fully open
        val left = EyeClassifier.EyeResult(EyeState.CLOSED, 0.08, 0.9)
        val right = EyeClassifier.EyeResult(EyeState.OPEN, 0.25, 0.9)
        val fusion = EyeClassifier.fuse(left, right)

        // Asymmetric closure must NOT be treated as bilateral acute closure
        assertEquals("Combined state must remain OPEN when one eye is clearly open", EyeState.OPEN, fusion.combinedState)
        assertTrue(fusion.isAsymmetric)
    }

    @Test
    fun testBilateralFusionOneEyeUncertainOtherOpen() {
        val left = EyeClassifier.EyeResult(EyeState.UNCERTAIN, null, 0.0)
        val right = EyeClassifier.EyeResult(EyeState.OPEN, 0.26, 0.9)
        val fusion = EyeClassifier.fuse(left, right)

        assertEquals("When one eye is uncertain but the other is open, driver is classified OPEN", EyeState.OPEN, fusion.combinedState)
        assertEquals(0.26, fusion.combinedEar!!, 0.001)
    }

    @Test
    fun testBilateralFusionOneEyeUncertainOtherClosedDoesNotTriggerDefiniteClosed() {
        val left = EyeClassifier.EyeResult(EyeState.CLOSED, 0.08, 0.8)
        val right = EyeClassifier.EyeResult(EyeState.UNCERTAIN, null, 0.0)
        val fusion = EyeClassifier.fuse(left, right)

        // Without confirmation from the second eye, cannot confirm bilateral closure
        assertEquals(EyeState.UNCERTAIN, fusion.combinedState)
    }

    @Test
    fun testBilateralFusionBothUncertain() {
        val left = EyeClassifier.EyeResult(EyeState.UNCERTAIN, null, 0.0)
        val right = EyeClassifier.EyeResult(EyeState.UNCERTAIN, null, 0.0)
        val fusion = EyeClassifier.fuse(left, right)

        assertEquals(EyeState.UNCERTAIN, fusion.combinedState)
        assertNull(fusion.combinedEar)
    }
}
