package com.example.drowsiness.detection

import java.util.ArrayDeque

/**
 * Calculates PERCLOS (Percentage of Eye Closure) over a rolling time window.
 *
 * Invalid observations (missing face) are excluded from the sample set
 * so face loss is not mistakenly treated as eye closure.
 */
class PerclosCalculator(
    private val windowMs: Long = 30_000L,
    private val minimumObservations: Int = 10
) {
    data class ObservationSample(val timeMs: Long, val isClosed: Boolean)

    private val samples = ArrayDeque<ObservationSample>()

    fun recordObservation(timestampMs: Long, isClosed: Boolean) {
        samples.addLast(ObservationSample(timestampMs, isClosed))
        prune(timestampMs)
    }

    fun calculate(timestampMs: Long): Double? {
        prune(timestampMs)
        if (samples.size < minimumObservations) return null
        val closedCount = samples.count { it.isClosed }
        return closedCount.toDouble() / samples.size
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
