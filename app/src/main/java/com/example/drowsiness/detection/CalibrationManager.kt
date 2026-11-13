package com.example.drowsiness.detection

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Robust, personalized baseline calibration for Eye Aspect Ratio (EAR) supporting
 * independent left and right eyes and diverse facial anatomies.
 *
 * # Calibration Procedure
 *
 * 1. Collects independent left and right eye EAR samples over [calibrationDurationMs] (default 3.5s).
 * 2. Requires frontal, stable face posture (rejects samples while driver is looking away or pitching down).
 * 3. Requires at least [minSamplesPerEye] valid observations (default 20).
 * 4. Uses robust percentile-trimmed estimation to exclude blinks (lower quantile) and wide-eyed flutters (upper quantile).
 * 5. Rejects unstable measurements if sample variance is abnormally high.
 * 6. Computes individualized thresholds per eye:
 *    - closedThreshold = baseline * closedFraction (default 0.65)
 *    - openThreshold   = baseline * openFraction   (default 0.80)
 * 7. Enforces a valid hysteresis gap between open and closed thresholds.
 * 8. Validates thresholds for numerical sanity without imposing a rigid 0.20 minimum baseline by default.
 * 9. Explicitly reports calibration success or failure with descriptive failure reasons.
 */
class CalibrationManager(
    val calibrationDurationMs: Long = 3_500L,
    val minSamplesPerEye: Int = 20,
    val closedFraction: Double = 0.65,
    val openFraction: Double = 0.80,
    val minHysteresisGap: Double = 0.02,
    val minPhysiologicalBaseline: Double = 0.11,
    val maxPhysiologicalBaseline: Double = 0.50,
    val maxSampleStdDevRatio: Double = 0.35,
    val pitchToleranceDegrees: Double = 15.0,
    val defaultClosedThreshold: Double = 0.18,
    val defaultOpenThreshold: Double = 0.22,
    val defaultBaseline: Double = 0.28,
    // Aliases / compatibility parameters
    val minSafeClosedThreshold: Double = 0.10,
    val maxSafeClosedThreshold: Double = 0.35,
    val minSafeOpenThreshold: Double = minPhysiologicalBaseline,
    val maxSafeOpenThreshold: Double = maxPhysiologicalBaseline,
    val minSamples: Int = minSamplesPerEye
) {
    var isCalibrating: Boolean = false
        private set

    var calibrationSucceeded: Boolean = false
        private set

    var failureReason: CalibrationFailureReason = CalibrationFailureReason.NONE
        private set

    // Independent baselines
    var baselineOpenEarLeft: Double? = null
        private set

    var baselineOpenEarRight: Double? = null
        private set

    // Independent thresholds
    var closedThresholdLeft: Double = defaultClosedThreshold
        private set

    var openThresholdLeft: Double = defaultOpenThreshold
        private set

    var closedThresholdRight: Double = defaultClosedThreshold
        private set

    var openThresholdRight: Double = defaultOpenThreshold
        private set

    // Combined/legacy properties for backward compatibility
    val closedThreshold: Double
        get() = (closedThresholdLeft + closedThresholdRight) / 2.0

    val openThreshold: Double
        get() = (openThresholdLeft + openThresholdRight) / 2.0

    val baselineOpenEar: Double?
        get() {
            val l = baselineOpenEarLeft
            val r = baselineOpenEarRight
            return when {
                l != null && r != null -> (l + r) / 2.0
                l != null -> l
                r != null -> r
                else -> null
            }
        }

    private var calibrationStartTimeMs: Long? = null
    private val leftSamples = mutableListOf<Double>()
    private val rightSamples = mutableListOf<Double>()

    private val requiredSamples: Int
        get() = max(minSamples, minSamplesPerEye)

    fun startCalibration(timestampMs: Long) {
        isCalibrating = true
        calibrationSucceeded = false
        failureReason = CalibrationFailureReason.NONE
        calibrationStartTimeMs = timestampMs
        leftSamples.clear()
        rightSamples.clear()
    }

    /**
     * Backward-compatible overload for single scalar EAR.
     */
    fun processSample(ear: Double?, timestampMs: Long): Int {
        return processSample(leftEar = ear, rightEar = ear, headPitchDegrees = null, timestampMs = timestampMs)
    }

    /**
     * Records a frame during calibration with independent eye metrics and head pose checks.
     *
     * @return Progress percent (0 to 100), or 100 if completed/not calibrating.
     */
    fun processSample(
        leftEar: Double?,
        rightEar: Double?,
        headPitchDegrees: Double?,
        timestampMs: Long
    ): Int {
        if (!isCalibrating) return 100
        val startTime = calibrationStartTimeMs ?: return 100

        // Check head pose: reject sample collection if head is pitching up/down excessively
        val isHeadFrontal = headPitchDegrees == null || kotlin.math.abs(headPitchDegrees) <= pitchToleranceDegrees

        if (isHeadFrontal) {
            if (leftEar != null && leftEar.isFinite() && leftEar > 0.02) {
                leftSamples.add(leftEar)
            }
            if (rightEar != null && rightEar.isFinite() && rightEar > 0.02) {
                rightSamples.add(rightEar)
            }
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

        if (leftSamples.size < requiredSamples || rightSamples.size < requiredSamples) {
            failureReason = CalibrationFailureReason.INSUFFICIENT_SAMPLES
            calibrationSucceeded = false
            applyDefaults()
            return
        }

        val leftEst = estimateBaselineAndThresholds(leftSamples)
        val rightEst = estimateBaselineAndThresholds(rightSamples)

        if (leftEst == null || rightEst == null) {
            calibrationSucceeded = false
            applyDefaults()
            return
        }

        baselineOpenEarLeft = leftEst.baseline
        closedThresholdLeft = leftEst.closedThreshold
        openThresholdLeft = leftEst.openThreshold

        baselineOpenEarRight = rightEst.baseline
        closedThresholdRight = rightEst.closedThreshold
        openThresholdRight = rightEst.openThreshold

        calibrationSucceeded = true
        failureReason = CalibrationFailureReason.NONE
    }

    private data class ThresholdsResult(
        val baseline: Double,
        val closedThreshold: Double,
        val openThreshold: Double
    )

    private fun estimateBaselineAndThresholds(samples: List<Double>): ThresholdsResult? {
        if (samples.isEmpty()) return null

        val sorted = samples.sorted()
        val total = sorted.size

        // Trim lowest 20% to exclude normal blinks and brief squints
        val trimLow = (total * 0.20).toInt()
        // Trim highest 10% to exclude wide-eyed flutters or landmark expansion glitches
        val trimHigh = (total * 0.90).toInt()

        if (trimHigh <= trimLow) {
            failureReason = CalibrationFailureReason.INSUFFICIENT_SAMPLES
            return null
        }

        val trimmed = sorted.subList(trimLow, trimHigh)
        val baseline = trimmed.average()

        val effectiveMinOpen = max(minPhysiologicalBaseline, minSafeOpenThreshold)
        val effectiveMaxOpen = min(maxPhysiologicalBaseline, maxSafeOpenThreshold)

        if (!baseline.isFinite() || baseline < effectiveMinOpen || baseline > effectiveMaxOpen) {
            failureReason = CalibrationFailureReason.INVALID_THRESHOLDS
            return null
        }

        // Check stability: standard deviation of trimmed samples
        val variance = trimmed.map { (it - baseline) * (it - baseline) }.average()
        val stdDev = sqrt(variance)
        if (stdDev / baseline > maxSampleStdDevRatio) {
            failureReason = CalibrationFailureReason.UNSTABLE_MEASUREMENTS
            return null
        }

        var closed = baseline * closedFraction
        var open = baseline * openFraction

        if (open - closed < minHysteresisGap) {
            open = closed + minHysteresisGap
        }

        // Validate threshold ordering and numerical soundness
        if (!closed.isFinite() || !open.isFinite() || closed <= 0.0 || open <= closed) {
            failureReason = CalibrationFailureReason.INVALID_THRESHOLDS
            return null
        }

        return ThresholdsResult(baseline, closed, open)
    }

    private fun applyDefaults() {
        baselineOpenEarLeft = null
        baselineOpenEarRight = null
        closedThresholdLeft = defaultClosedThreshold
        openThresholdLeft = defaultOpenThreshold
        closedThresholdRight = defaultClosedThreshold
        openThresholdRight = defaultOpenThreshold
    }

    fun updatePersonalizedThresholds(
        leftBaseline: Double,
        leftClosed: Double,
        leftOpen: Double,
        rightBaseline: Double,
        rightClosed: Double,
        rightOpen: Double
    ) {
        baselineOpenEarLeft = leftBaseline
        closedThresholdLeft = leftClosed
        openThresholdLeft = leftOpen

        baselineOpenEarRight = rightBaseline
        closedThresholdRight = rightClosed
        openThresholdRight = rightOpen
    }

    fun reset() {
        isCalibrating = false
        calibrationStartTimeMs = null
        leftSamples.clear()
        rightSamples.clear()
        calibrationSucceeded = false
        failureReason = CalibrationFailureReason.NONE
        applyDefaults()
    }
}
