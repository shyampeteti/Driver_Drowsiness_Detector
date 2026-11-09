package com.example.drowsiness.detection

/**
 * Manages slow, safe baseline adaptation for individualized EAR thresholds.
 *
 * Requirements (Section 9):
 * - Adaptation only occurs when:
 *   1. Both eyes are confidently OPEN.
 *   2. Face landmarks are reliable and face is frontal (no excessive pitch).
 *   3. No blink or closure episode is active (closureDuration == 0).
 *   4. No drowsiness is suspected (detectionState is MONITORING, score < 25.0).
 *   5. Not nodding or yawning.
 *   6. Current observation is consistent with calibrated baseline (within ±20%).
 * - Uses very slow EMA (adaptationRate = 0.002).
 * - Imposes strict drift limits (maximum ±15% from calibrated baseline).
 * - Can be disabled completely via [enabled] flag.
 */
class AdaptiveBaselineTracker(
    val enabled: Boolean = true,
    val adaptationRate: Double = 0.002,
    val maxDriftFraction: Double = 0.15,
    val pitchStabilityLimit: Double = 10.0,
    val closedFraction: Double = 0.65,
    val openFraction: Double = 0.80,
    val minHysteresisGap: Double = 0.02
) {
    var calibratedBaselineLeft: Double? = null
        private set
    var calibratedBaselineRight: Double? = null
        private set

    var currentBaselineLeft: Double? = null
        private set
    var currentBaselineRight: Double? = null
        private set

    fun initialize(calibratedLeft: Double, calibratedRight: Double) {
        calibratedBaselineLeft = calibratedLeft
        calibratedBaselineRight = calibratedRight
        currentBaselineLeft = calibratedLeft
        currentBaselineRight = calibratedRight
    }

    /**
     * Attempts an adaptive update step.
     * @return true if an update was applied, false otherwise.
     */
    fun update(
        leftEar: Double?,
        rightEar: Double?,
        leftEyeState: EyeState,
        rightEyeState: EyeState,
        closureDurationMs: Long,
        drowsinessScore: Double,
        detectionState: DetectionState,
        headPitchDegrees: Double?,
        isYawning: Boolean,
        isNodding: Boolean
    ): Boolean {
        if (!enabled) return false
        val initLeft = calibratedBaselineLeft ?: return false
        val initRight = calibratedBaselineRight ?: return false
        val currLeft = currentBaselineLeft ?: return false
        val currRight = currentBaselineRight ?: return false

        // 1. Both eyes must be confidently OPEN
        if (leftEyeState != EyeState.OPEN || rightEyeState != EyeState.OPEN) return false

        // 2. Both raw observations must be valid and positive
        if (leftEar == null || !leftEar.isFinite() || leftEar <= 0.0 ||
            rightEar == null || !rightEar.isFinite() || rightEar <= 0.0) return false

        // 3. No ongoing closure or blink
        if (closureDurationMs > 0L) return false

        // 4. No suspected drowsiness (score must be low, state MONITORING)
        if (detectionState != DetectionState.MONITORING || drowsinessScore >= 25.0) return false

        // 5. Head pose must be stable, frontal, no nodding or yawning
        if (isNodding || isYawning) return false
        if (headPitchDegrees != null && kotlin.math.abs(headPitchDegrees) > pitchStabilityLimit) return false

        // 6. Observation must be consistent with current baseline (within ±20%)
        val leftDiff = kotlin.math.abs(leftEar - currLeft) / currLeft
        val rightDiff = kotlin.math.abs(rightEar - currRight) / currRight
        if (leftDiff > 0.20 || rightDiff > 0.20) return false

        // Apply slow EMA update
        var newLeft = (1.0 - adaptationRate) * currLeft + adaptationRate * leftEar
        var newRight = (1.0 - adaptationRate) * currRight + adaptationRate * rightEar

        // Strict clamp to ±maxDriftFraction from original calibrated baseline
        val minLeft = initLeft * (1.0 - maxDriftFraction)
        val maxLeft = initLeft * (1.0 + maxDriftFraction)
        newLeft = newLeft.coerceIn(minLeft, maxLeft)

        val minRight = initRight * (1.0 - maxDriftFraction)
        val maxRight = initRight * (1.0 + maxDriftFraction)
        newRight = newRight.coerceIn(minRight, maxRight)

        currentBaselineLeft = newLeft
        currentBaselineRight = newRight
        return true
    }

    fun getThresholdsLeft(): Pair<Double, Double>? {
        val base = currentBaselineLeft ?: return null
        val closed = base * closedFraction
        var open = base * openFraction
        if (open - closed < minHysteresisGap) {
            open = closed + minHysteresisGap
        }
        return Pair(closed, open)
    }

    fun getThresholdsRight(): Pair<Double, Double>? {
        val base = currentBaselineRight ?: return null
        val closed = base * closedFraction
        var open = base * openFraction
        if (open - closed < minHysteresisGap) {
            open = closed + minHysteresisGap
        }
        return Pair(closed, open)
    }

    fun reset() {
        calibratedBaselineLeft = null
        calibratedBaselineRight = null
        currentBaselineLeft = null
        currentBaselineRight = null
    }
}
