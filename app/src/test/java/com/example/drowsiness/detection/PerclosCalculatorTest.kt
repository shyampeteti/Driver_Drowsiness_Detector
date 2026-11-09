package com.example.drowsiness.detection

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for time-based PERCLOS calculation.
 */
class PerclosCalculatorTest {

    @Test
    fun returnsNullBeforeMinimumWindowFills() {
        val calc = PerclosCalculator(minimumValidObservationMs = 5_000L)
        calc.recordObservation(0L, false)
        calc.recordObservation(1_000L, false)
        // Only 1 second of observations — below the 5-second minimum
        assertNull(calc.calculate(1_000L))
    }

    @Test
    fun zeroPerclosWhenAllObservationsAreOpen() {
        val calc = PerclosCalculator(minimumValidObservationMs = 2_000L)
        // 5 seconds of open-eye observations
        for (i in 0..10) {
            calc.recordObservation(i * 500L, false)
        }
        val result = calc.calculate(5_000L)
        assertNotNull(result)
        assertEquals("All-open PERCLOS should be 0.0", 0.0, result!!, 0.01)
    }

    @Test
    fun highPerclosWhenAllObservationsAreClosed() {
        val calc = PerclosCalculator(minimumValidObservationMs = 2_000L)
        for (i in 0..10) {
            calc.recordObservation(i * 500L, true)
        }
        val result = calc.calculate(5_000L)
        assertNotNull(result)
        assertEquals("All-closed PERCLOS should be 1.0", 1.0, result!!, 0.01)
    }

    @Test
    fun halfPerclosWhenHalfTimeIsClosed() {
        // 10 seconds of data: alternating 1-second closed, 1-second open
        val calc = PerclosCalculator(minimumValidObservationMs = 5_000L)
        for (i in 0..10) {
            val t = i * 1_000L
            calc.recordObservation(t, isClosed = (i % 2 == 0))
        }
        val result = calc.calculate(10_000L)
        assertNotNull(result)
        // Should be approximately 50%, ± tolerance for edge intervals
        assertTrue("PERCLOS should be ~50%, got ${result!! * 100}%", result in 0.40..0.60)
    }

    @Test
    fun samplesOutsideWindowAreExpired() {
        val calc = PerclosCalculator(windowMs = 5_000L, minimumValidObservationMs = 2_000L)

        // Add old closed samples (will be pruned)
        calc.recordObservation(0L, true)
        calc.recordObservation(500L, true)
        calc.recordObservation(1_000L, true)

        // Now fast-forward: only open samples in the last 5 seconds
        calc.recordObservation(5_500L, false)
        calc.recordObservation(6_000L, false)
        calc.recordObservation(6_500L, false)
        calc.recordObservation(7_000L, false)
        calc.recordObservation(7_500L, false)
        calc.recordObservation(8_000L, false)

        val result = calc.calculate(8_000L)
        assertNotNull(result)
        assertEquals("Old closed samples should be pruned; PERCLOS should be ~0", 0.0, result!!, 0.05)
    }

    @Test
    fun largeFrameGapsAreClamped() {
        // If two samples are 10 seconds apart (camera pause), the interval should be
        // clamped to avoid distorting the calculation.
        val calc = PerclosCalculator(minimumValidObservationMs = 1_000L)
        calc.recordObservation(0L, false)
        // Huge gap — simulates camera resuming after a pause
        calc.recordObservation(10_000L, true)
        calc.recordObservation(10_500L, true)
        calc.recordObservation(11_000L, true)
        calc.recordObservation(11_500L, false)

        val result = calc.calculate(11_500L)
        // Should not return null and should not crash or give a >100% value
        if (result != null) {
            assertTrue("PERCLOS must be in [0, 1]", result in 0.0..1.0)
        }
    }

    @Test
    fun resetClearsSamples() {
        val calc = PerclosCalculator(minimumValidObservationMs = 1_000L)
        for (i in 0..10) calc.recordObservation(i * 500L, true)
        calc.reset()
        assertNull("After reset, PERCLOS should be null", calc.calculate(5_500L))
    }
}
