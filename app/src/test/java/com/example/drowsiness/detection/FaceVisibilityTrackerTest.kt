package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

class FaceVisibilityTrackerTest {
    private fun lockedTracker(): FaceVisibilityTracker = FaceVisibilityTracker().also {
        it.observe(true, 0L)
        it.observe(true, 250L)
        assertTrue(it.observe(true, 500L).isFaceLocked)
    }

    @Test fun startupMissingDoesNotLockOrAlert() {
        val tracker = FaceVisibilityTracker()
        val result = tracker.observe(false, 100L)
        assertFalse(result.isFaceLocked)
        assertFalse(result.isFaceLossCritical)
        assertEquals(0, result.faceLossIncidentCount)
    }

    @Test fun initialLockRequiresThreeObservationsAndFiveHundredMs() {
        val tracker = FaceVisibilityTracker()
        assertFalse(tracker.observe(true, 0L).isFaceLocked)
        assertFalse(tracker.observe(true, 500L).isFaceLocked)
        assertTrue(tracker.observe(true, 501L).isFaceLocked)
    }

    @Test fun insufficientSpanAndInterruptedCandidateDoNotLock() {
        val tracker = FaceVisibilityTracker()
        tracker.observe(true, 0L); tracker.observe(true, 200L); tracker.observe(true, 300L)
        assertFalse(tracker.observe(false, 400L).isFaceLocked)
        tracker.observe(true, 500L); tracker.observe(true, 700L)
        assertFalse(tracker.observe(true, 900L).isFaceLocked)
    }

    @Test fun lossActivatesAtBoundaryOnceAndRecoveryNeedsStableSequence() {
        val tracker = lockedTracker()
        tracker.observe(false, 600L)
        assertFalse(tracker.observe(false, 2_099L).isFaceLossCritical)
        val activated = tracker.observe(false, 2_100L)
        assertTrue(activated.faceLossIncidentActivated)
        assertEquals(1, activated.faceLossIncidentCount)
        assertFalse(tracker.observe(true, 2_200L).faceLossRecovered)
        tracker.observe(true, 2_450L)
        val recovered = tracker.observe(true, 2_700L)
        assertTrue(recovered.faceLossRecovered)
        assertFalse(recovered.isFaceLossCritical)
    }

    @Test fun interruptedRecoveryResetsAndShortLossDoesNotActivate() {
        val tracker = lockedTracker()
        tracker.observe(false, 600L)
        val short = tracker.observe(true, 2_000L)
        assertFalse(short.isFaceLossCritical)
        tracker.observe(true, 2_250L)
        tracker.observe(false, 2_300L)
        tracker.observe(true, 2_400L); tracker.observe(true, 2_650L)
        assertFalse(tracker.observe(true, 2_900L).isFaceLocked.not())
    }

    @Test fun repeatedLossAfterRecoveryCreatesOneNewIncident() {
        val tracker = lockedTracker()
        tracker.observe(false, 600L); tracker.observe(false, 2_100L)
        tracker.observe(true, 2_200L); tracker.observe(true, 2_450L); tracker.observe(true, 2_700L)
        tracker.observe(false, 2_800L)
        val second = tracker.observe(false, 4_300L)
        assertTrue(second.faceLossIncidentActivated)
        assertEquals(2, second.faceLossIncidentCount)
        assertFalse(tracker.observe(false, 5_000L).faceLossIncidentActivated)
    }

    @Test fun invalidAndOutOfOrderTimestampsAreIgnored() {
        val tracker = FaceVisibilityTracker()
        assertFalse(tracker.observe(true, -1L).isFaceLocked)
        tracker.observe(true, 0L)
        assertFalse(tracker.observe(true, 0L).isFaceLocked)
        assertFalse(tracker.observe(true, 500L).isFaceLocked)
        assertFalse(tracker.observe(true, 400L).isFaceLocked)
        assertTrue(tracker.observe(true, 501L).isFaceLocked)
    }

    @Test fun returnFrameAtGraceBoundaryActivatesIncident() {
        val tracker = lockedTracker()
        tracker.observe(false, 600L)
        val returning = tracker.observe(true, 2_100L)
        assertTrue(returning.faceLossIncidentActivated)
        assertTrue(returning.isFaceLossCritical)
        assertTrue(returning.isReacquiring)
        assertEquals(1, returning.faceLossIncidentCount)
    }

    @Test fun excessiveLockAndRecoveryGapsRestartTheirSequences() {
        val lockTracker = FaceVisibilityTracker(maxObservationGapMs = 600L)
        lockTracker.observe(true, 0L)
        lockTracker.observe(true, 250L)
        assertFalse(lockTracker.observe(true, 1_000L).isFaceLocked)
        assertFalse(lockTracker.observe(true, 1_250L).isFaceLocked)
        assertTrue(lockTracker.observe(true, 1_500L).isFaceLocked)

        val tracker = lockedTracker()
        tracker.observe(false, 600L)
        tracker.observe(false, 2_100L)
        tracker.observe(true, 2_200L)
        tracker.observe(true, 2_450L)
        val afterGap = tracker.observe(true, 3_200L)
        assertTrue(afterGap.isFaceLossCritical)
        assertFalse(afterGap.faceLossRecovered)
        tracker.observe(true, 3_450L)
        assertTrue(tracker.observe(true, 3_700L).faceLossRecovered)
    }
}
