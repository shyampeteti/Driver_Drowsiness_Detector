package com.example.drowsiness.detection

/**
 * Independent eye state classification and bilateral fusion strategy.
 *
 * Implements explicit if-else cases (Cases A through H):
 * - CASE A: Invalid landmarks -> UNCERTAIN
 * - CASE B: Invalid EAR -> UNCERTAIN
 * - CASE C: EAR <= personalized closed threshold -> CLOSED
 * - CASE D: EAR >= personalized open threshold -> OPEN
 * - CASE E: EAR between thresholds -> Retain previous confident state (hysteresis)
 * - CASE F: Face tracking lost -> UNCERTAIN
 * - CASE G: Calibration in progress -> Handled at detector level (CALIBRATING state)
 * - CASE H: Abrupt measurement changes / tracking noise -> Damped with temporal EMA
 */
object EyeClassifier {

    data class EyeResult(
        val state: EyeState,
        val smoothedEar: Double?,
        val confidence: Double
    )

    data class BilateralFusionResult(
        val combinedState: EyeState,
        val combinedEar: Double?,
        val leftResult: EyeResult,
        val rightResult: EyeResult,
        val isAsymmetric: Boolean
    )

    /**
     * Classifies a single eye.
     *
     * @param rawEar Current frame EAR for this eye
     * @param prevSmoothedEar Previous smoothed EAR value
     * @param prevEyeState Previous classified state of this eye
     * @param closedThreshold Personalized closed threshold for this eye
     * @param openThreshold Personalized open threshold for this eye
     * @param emaAlpha Smoothing factor (default 0.50)
     * @param faceDetected Whether face tracking was confirmed
     */
    fun classifySingleEye(
        rawEar: Double?,
        prevSmoothedEar: Double?,
        prevEyeState: EyeState,
        closedThreshold: Double,
        openThreshold: Double,
        emaAlpha: Double = 0.50,
        faceDetected: Boolean = true
    ): EyeResult {
        // CASE F: Face tracking lost
        if (!faceDetected) {
            return EyeResult(state = EyeState.UNCERTAIN, smoothedEar = null, confidence = 0.0)
        }

        // CASE A & CASE B: Invalid landmarks or invalid EAR
        if (rawEar == null || !rawEar.isFinite() || rawEar < 0.0) {
            return EyeResult(state = EyeState.UNCERTAIN, smoothedEar = prevSmoothedEar, confidence = 0.0)
        }

        // CASE H: Smooth out single-frame jitter and abrupt drops
        val currentSmoothed = if (prevSmoothedEar != null) {
            emaAlpha * rawEar + (1.0 - emaAlpha) * prevSmoothedEar
        } else {
            rawEar
        }

        val earToEvaluate = currentSmoothed

        // Classification
        val newState = when {
            // CASE C: EAR <= personalized closed threshold
            earToEvaluate <= closedThreshold -> EyeState.CLOSED

            // CASE D: EAR >= personalized open threshold
            earToEvaluate >= openThreshold -> EyeState.OPEN

            // CASE E: Between thresholds (hysteresis zone)
            else -> {
                if (prevEyeState != EyeState.UNCERTAIN) prevEyeState else EyeState.OPEN
            }
        }

        // Confidence evaluation
        val confidence = when (newState) {
            EyeState.CLOSED -> if (closedThreshold > 0.0) {
                (0.70 + 0.30 * (1.0 - (earToEvaluate / closedThreshold).coerceIn(0.0, 1.0))).coerceIn(0.5, 1.0)
            } else 0.8
            EyeState.OPEN -> if (openThreshold > 0.0) {
                (0.70 + 0.30 * ((earToEvaluate - openThreshold) / openThreshold).coerceAtLeast(0.0)).coerceIn(0.5, 1.0)
            } else 0.8
            EyeState.UNCERTAIN -> 0.0
        }

        return EyeResult(
            state = newState,
            smoothedEar = currentSmoothed,
            confidence = confidence
        )
    }

    /**
     * Sensible bilateral fusion strategy across both eyes.
     *
     * Rules:
     * - Both eyes OPEN -> OPEN
     * - Both eyes CLOSED -> CLOSED (strong evidence of eye closure)
     * - One CLOSED, one OPEN -> Asymmetric closure (e.g. winking, unilateral squint, ptosis);
     *   a single unreliable eye must NOT trigger drowsiness alerts; combined is OPEN.
     * - One UNCERTAIN, one OPEN -> OPEN (reliable open eye visible)
     * - One UNCERTAIN, one CLOSED -> UNCERTAIN (cannot confirm bilateral closure; do not inflate alarms)
     * - Both UNCERTAIN -> UNCERTAIN
     */
    fun fuse(
        leftResult: EyeResult,
        rightResult: EyeResult
    ): BilateralFusionResult {
        val leftState = leftResult.state
        val rightState = rightResult.state

        val leftEar = leftResult.smoothedEar
        val rightEar = rightResult.smoothedEar

        val combinedEar = when {
            leftEar != null && rightEar != null -> (leftEar + rightEar) / 2.0
            leftEar != null -> leftEar
            rightEar != null -> rightEar
            else -> null
        }

        val isAsymmetric = (leftState == EyeState.OPEN && rightState == EyeState.CLOSED) ||
                (leftState == EyeState.CLOSED && rightState == EyeState.OPEN)

        val combinedState = when {
            // Both eyes open
            leftState == EyeState.OPEN && rightState == EyeState.OPEN -> EyeState.OPEN

            // Both eyes closed
            leftState == EyeState.CLOSED && rightState == EyeState.CLOSED -> EyeState.CLOSED

            // Asymmetric closure: one eye open, one eye closed
            leftState == EyeState.OPEN && rightState == EyeState.CLOSED -> EyeState.OPEN
            leftState == EyeState.CLOSED && rightState == EyeState.OPEN -> EyeState.OPEN

            // One eye uncertain, other open
            leftState == EyeState.OPEN && rightState == EyeState.UNCERTAIN -> EyeState.OPEN
            leftState == EyeState.UNCERTAIN && rightState == EyeState.OPEN -> EyeState.OPEN

            // One eye uncertain, other closed -> do not assume bilateral closure
            leftState == EyeState.CLOSED && rightState == EyeState.UNCERTAIN -> EyeState.UNCERTAIN
            leftState == EyeState.UNCERTAIN && rightState == EyeState.CLOSED -> EyeState.UNCERTAIN

            // Both uncertain
            else -> EyeState.UNCERTAIN
        }

        return BilateralFusionResult(
            combinedState = combinedState,
            combinedEar = combinedEar,
            leftResult = leftResult,
            rightResult = rightResult,
            isAsymmetric = isAsymmetric
        )
    }
}
