package com.example.drowsiness.detection

import kotlin.math.max
import kotlin.math.min

/**
 * Personalized baseline calibration for Eye Aspect Ratio (EAR).
 *
 * # Calibration Procedure
 *
 * **Phase 1 – Open-eye baseline (3–4 seconds)**
 * The driver looks naturally ahead. The mean EAR is recorded as [baselineOpenEar].
 *
 * **Phase 2 – Closed-eye reference (not measured in-app)**
 * We estimate the closed-eye EAR as a fixed fraction of the open baseline,
 * since asking the driver to close their eyes at startup is impractical.
 *
 * **Threshold derivation**
 * ```
 * closedThreshold = baseline * closedFraction  (default 0.65 of open baseline)
 * openThreshold   = baseline * openFraction    (default 0.80 of open baseline)
 * ```
 * Both values are safely clamped to avoid pathological configurations.
 *
 * **Rejection criteria**
 * - Fewer than [minSamples] valid EAR samples collected → fall back to defaults.
 * - Computed baseline below [minSafeOpenThreshold] → reject (eyes already closed/drowsy).
 * - openThreshold − closedThreshold < [minHysteresisGap] → widen automatically.
 *
 * # Default thresholds when calibration is unavailable
 *
 * The defaults (closed=0.18, open=0.22) are intentionally conservative.
 * Many people have open-eye EAR in the range 0.25–0.35 (wider eyes → higher EAR).
 * Using too-high defaults causes false OPEN → CLOSED transitions for people with
 * naturally low EAR, and also causes the opposite for people with high EAR.
 * Calibration resolves this by adapting to the individual.
 */
class CalibrationManager(
    val calibrationDurationMs: Long = 3_500L,
    private val defaultClosedThreshold: Double = 0.18,
    private val defaultOpenThreshold: Double = 0.22,
    private val minSafeClosedThreshold: Double = 0.14,
    private val maxSafeClosedThreshold: Double = 0.26,
    private val minSafeOpenThreshold: Double = 0.20,
    private val maxSafeOpenThreshold: Double = 0.36,
    /** EAR below this during calibration is treated as a blink and excluded. */
    private val blinkExclusionThreshold: Double = 0.15,
    /** Minimum number of valid EAR samples required to accept calibration. */
    private val minSamples: Int = 20,
    /**
     * Fraction of open-eye baseline used as the closed-eye threshold.
     * Default 0.65 means: if open EAR is 0.30, closed threshold = 0.195.
     */
    private val closedFraction: Double = 0.65,
    /**
     * Fraction of open-eye baseline used as the re-opening (open) threshold.
     * Default 0.80 means: if open EAR is 0.30, open threshold = 0.24.
     */
    private val openFraction: Double = 0.80,
    /** Minimum gap between closed and open thresholds for valid hysteresis. */
    private val minHysteresisGap: Double = 0.03
) {
    var isCalibrating: Boolean = false
        private set

    var closedThreshold: Double = defaultClosedThreshold
        private set

    var openThreshold: Double = defaultOpenThreshold
        private set

    var baselineOpenEar: Double? = null
        private set

    /** Whether the last calibration completed successfully. */
    var calibrationSucceeded: Boolean = false
        private set

    private var calibrationStartTimeMs: Long? = null
    private val calibrationSamples = mutableListOf<Double>()

    fun startCalibration(timestampMs: Long) {
        isCalibrating = true
        calibrationSucceeded = false
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

        // Accept samples only when eyes are likely open (above blink exclusion threshold)
        if (ear != null && ear.isFinite() && ear > blinkExclusionThreshold) {
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

        if (calibrationSamples.size >= minSamples) {
            // Use the median of the top 60% of samples to exclude blink artifacts
            val sorted = calibrationSamples.sorted()
            val trimStart = (sorted.size * 0.20).toInt()  // drop lowest 20%
            val trimmed = sorted.drop(trimStart)
            val avg = trimmed.average()

            if (avg >= minSafeOpenThreshold) {
                baselineOpenEar = avg

                val rawClosed = avg * closedFraction
                val rawOpen   = avg * openFraction

                closedThreshold = min(maxSafeClosedThreshold, max(minSafeClosedThreshold, rawClosed))
                openThreshold   = min(maxSafeOpenThreshold,   max(minSafeOpenThreshold,   rawOpen))

                // Enforce minimum hysteresis gap
                if (openThreshold - closedThreshold < minHysteresisGap) {
                    openThreshold = closedThreshold + minHysteresisGap
                    openThreshold = min(maxSafeOpenThreshold, openThreshold)
                }

                calibrationSucceeded = true
                return
            }
        }

        // Fall back to safe defaults
        closedThreshold = defaultClosedThreshold
        openThreshold   = defaultOpenThreshold
        calibrationSucceeded = false
    }

    fun reset() {
        isCalibrating = false
        calibrationStartTimeMs = null
        calibrationSamples.clear()
        closedThreshold = defaultClosedThreshold
        openThreshold   = defaultOpenThreshold
        baselineOpenEar = null
        calibrationSucceeded = false
    }
}
