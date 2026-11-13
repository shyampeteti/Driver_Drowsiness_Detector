package com.example.drowsiness.detection

import java.util.ArrayDeque

/**
 * Calculates PERCLOS (Percentage of Eye Closure) over a rolling time window.
 *
 * Uses interval-based time calculation rather than frame counts so the result
 * is accurate regardless of variable frame rates or dropped frames.
 *
 * Invalid observations (missing face, landmark loss, UNCERTAIN states) must NOT be passed here;
 * only confident observations are recorded.
 */
class PerclosCalculator(
    val windowMs: Long = 30_000L,
    val minimumValidObservationMs: Long = 5_000L,
    val maxIntervalMs: Long = 500L
) {
    /**
     * Each sample records the timestamp and eye closure state at that point.
     * PERCLOS is computed as the fraction of *time* (not frames) the eyes were closed.
     */
    data class ObservationSample(val timeMs: Long, val isClosed: Boolean, val connectsToNext: Boolean = true)

    private val samples = ArrayDeque<ObservationSample>()

    /**
     * Record a valid observation (face detected, landmarks valid, state confident).
     * Do NOT call this when face is missing or state is UNCERTAIN.
     */
    fun recordObservation(timestampMs: Long, isClosed: Boolean) {
        samples.addLast(ObservationSample(timestampMs, isClosed, connectsToNext = true))
        prune(timestampMs)
    }

    /** Break the interval across an unobserved period while retaining prior valid history. */
    fun breakContinuity() {
        val last = samples.pollLast() ?: return
        samples.addLast(last.copy(connectsToNext = false))
    }

    /**
     * Calculate PERCLOS as (closed time / total valid observation time).
     *
     * Uses consecutive sample pairs to compute intervals; the state of the
     * earlier sample determines whether the interval counts as closed.
     * Returns null until [minimumValidObservationMs] has accumulated in the window.
     */
    fun calculate(timestampMs: Long): Double? {
        prune(timestampMs)
        if (samples.size < 2) return null

        var totalTimeMs = 0L
        var closedTimeMs = 0L

        val list = samples.toList()
        for (i in 0 until list.size - 1) {
            val current = list[i]
            val next = list[i + 1]
            if (!current.connectsToNext) continue
            val intervalMs = next.timeMs - current.timeMs
            // A large gap is unknown time, not a shorter valid interval.
            if (intervalMs in 1L..maxIntervalMs) {
                totalTimeMs += intervalMs
                if (current.isClosed) {
                    closedTimeMs += intervalMs
                }
            }
        }

        if (totalTimeMs < minimumValidObservationMs) return null
        return closedTimeMs.toDouble() / totalTimeMs.toDouble()
    }

    private fun prune(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first.timeMs > windowMs) {
            samples.removeFirst()
        }
    }

    fun reset() {
        samples.clear()
    }
}
