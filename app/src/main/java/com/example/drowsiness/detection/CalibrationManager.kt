package com.example.drowsiness.detection

import kotlin.math.max
import kotlin.math.min

/**
 * Personalized baseline calibration for Eye Aspect Ratio (EAR).
 *
 * Runs for a short period (e.g. 3.5 seconds) while driver looks at the road.
 * Strictly bounded to prevent learning an already-drowsy state as normal.
 */
class CalibrationManager(
    val calibrationDurationMs: Long = 3_500L,
    private val defaultClosedThreshold: Double = 0.20,
    private val defaultOpenThreshold: Double = 0.24,
    private val minSafeClosedThreshold: Double = 0.16,
    private val maxSafeClosedThreshold: Double = 0.25,
    private val minSafeOpenThreshold: Double = 0.22,
    private val maxSafeOpenThreshold: Double = 0.32
) {
    var isCalibrating: Boolean = false
        private set

    var closedThreshold: Double = defaultClosedThreshold
        private set

    var openThreshold: Double = defaultOpenThreshold
        private set

    var baselineOpenEar: Double? = null
        private set

    private var calibrationStartTimeMs: Long? = null
    private val calibrationSamples = mutableListOf<Double>()

    fun startCalibration(timestampMs: Long) {
        isCalibrating = true
        calibrationStartTimeMs = timestampMs
        calibrationSamples.clear()
    }

    /**
     * Records a frame during calibration.
     * @return progress percent (0 to 100), or 100 if completed/not calibrating.
     */
    fun processSample(ear: Double?, timestampMs: Long): Int {
        if (!isCalibrating) return 100
        val startTime = calibrationStartTimeMs ?: return 100

        if (ear != null && ear.isFinite() && ear > 0.10) {
            calibrationSamples.add(ear)
        }

        val elapsed = timestampMs - startTime
        if (elapsed >= calibrationDurationMs) {
            finishCalibration()
            return 100
        }

        return min(99, ((elapsed.toDouble() / calibrationDurationMs) * 100).toInt())
    }

    private fun finishCalibration() {
        isCalibrating = false
        if (calibrationSamples.size >= 15) {
            val avg = calibrationSamples.average()
            // Reject calibration if baseline is dangerously low (eyes already closed/drowsy)
            if (avg >= minSafeOpenThreshold) {
                baselineOpenEar = avg
                // Set closed threshold to ~70% of open baseline, safely bounded
                closedThreshold = min(maxSafeClosedThreshold, max(minSafeClosedThreshold, avg * 0.70))
                // Set open threshold to ~85% of open baseline, safely bounded
                openThreshold = min(maxSafeOpenThreshold, max(minSafeOpenThreshold, avg * 0.85))
                if (openThreshold <= closedThreshold) {
                    openThreshold = closedThreshold + 0.04
                }
                return
            }
        }
        // Fallback to safe defaults if calibration was noisy or rejected
        closedThreshold = defaultClosedThreshold
        openThreshold = defaultOpenThreshold
    }

    fun reset() {
        isCalibrating = false
        calibrationStartTimeMs = null
        calibrationSamples.clear()
        closedThreshold = defaultClosedThreshold
        openThreshold = defaultOpenThreshold
        baselineOpenEar = null
    }
}
