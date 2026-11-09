package com.example.drowsiness.detection

import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent driver drowsiness detection coordinator.
 *
 * Combines independent eye tracking, duration-weighted closure classification,
 * rolling PERCLOS calculation, modular yawning and head pose monitoring,
 * and adaptive multi-indicator scoring.
 *
 * # Key design decisions
 *
 * ## EAR smoothing
 * A gentle exponential moving average (alpha=0.5) reduces single-frame noise
 * without introducing significant lag. The previous implementation used alpha=0.65
 * which was too aggressive — a single noisy low-EAR frame would drag the smoothed
 * value below the closed threshold.
 *
 * ## Eye state hysteresis
 * Two separate thresholds (closedThreshold < openThreshold) prevent rapid
 * oscillation. The eye is only classified CLOSED when EAR drops below
 * [closedThreshold]; it is classified OPEN only when EAR rises above
 * [openThreshold]. Between the two it retains its previous state.
 *
 * ## Closure duration display
 * [DetectionSnapshot.closureDurationMs] shows the **current ongoing** closure
 * duration if eyes are closed, or the **last completed** closure duration once
 * eyes reopen. It never shows 0 ms if a closure just ended.
 *
 * ## PERCLOS
 * Only valid (face-detected) frames are passed to [PerclosCalculator].
 * Face loss frames are excluded from both numerator and denominator.
 */
class DrowsinessDetector(
    val initialClosedThreshold: Double = 0.18,
    val initialOpenThreshold: Double = 0.22,
    val observationGapResetMs: Long = 600L,
    val warningCooldownMs: Long = 4_000L,
    val criticalCooldownMs: Long = 2_500L,
    /** EMA smoothing factor: 0.0 = no smoothing (raw), 1.0 = no update (stuck).
     *  Lower values = smoother but laggier; higher = more responsive but noisier. */
    val earEmaAlpha: Double = 0.50
) {
    // Sub-components
    val calibrationManager = CalibrationManager(
        defaultClosedThreshold = initialClosedThreshold,
        defaultOpenThreshold = initialOpenThreshold
    )
    val episodeTracker = ClosureEpisodeTracker()
    val perclosCalculator = PerclosCalculator()
    val scoringEngine = DrowsinessScoringEngine()
    val yawnTracker = YawnTracker()
    val headPoseEstimator = HeadPoseEstimator()

    // Operational State
    private var detectionState = DetectionState.STOPPED
    private var eyeState = EyeState.UNCERTAIN
    private var smoothedEar: Double? = null
    private var lastValidObservationMs: Long? = null
    private var lastWarningAlertMs: Long = Long.MIN_VALUE
    private var lastCriticalAlertMs: Long = Long.MIN_VALUE

    /**
     * Last completed closure duration in ms.
     * Retained until the next closure begins, so the UI always shows meaningful data
     * rather than 0 ms after eyes reopen.
     */
    private var lastCompletedClosureDurationMs: Long = 0L

    // Event Counters
    var warningEventsCount: Int = 0
        private set
    var criticalEventsCount: Int = 0
        private set

    fun start(enableCalibration: Boolean = false, nowMs: Long = System.currentTimeMillis()): DetectionSnapshot {
        resetInternalState()
        detectionState = DetectionState.MONITORING

        if (enableCalibration) {
            calibrationManager.startCalibration(nowMs)
        }

        return snapshot(
            alarmRequested = false,
            alertLevel = AlertLevel.NONE,
            message = if (enableCalibration) "Calibrating baseline EAR… Please look ahead" else "Monitoring active — looking for driver face"
        )
    }

    /**
     * Convenience overload for single combined EAR observations.
     */
    fun observe(ear: Double?, timestampMs: Long, faceDetected: Boolean = true): DetectionSnapshot {
        return observe(
            leftEar = ear,
            rightEar = ear,
            faceDetected = faceDetected,
            mouthAspectRatio = null,
            mouthMetrics = null,
            headPitchDegrees = null,
            timestampMs = timestampMs
        )
    }

    /**
     * Process an observation frame.
     *
     * @param leftEar Left eye EAR (or null if unavailable)
     * @param rightEar Right eye EAR (or null if unavailable)
     * @param faceDetected Whether a face was detected in this frame
     * @param mouthAspectRatio Calculated MAR scalar for yawning detection
     * @param mouthMetrics Full mouth geometry metrics for improved yawn detection
     * @param headPitchDegrees Estimated pitch degrees for nodding detection
     * @param timestampMs Monotonic timestamp in milliseconds
     */
    fun observe(
        leftEar: Double?,
        rightEar: Double?,
        faceDetected: Boolean,
        mouthAspectRatio: Double? = null,
        mouthMetrics: MouthAspectRatioCalculator.MouthMetrics? = null,
        headPitchDegrees: Double? = null,
        timestampMs: Long
    ): DetectionSnapshot {
        if (detectionState == DetectionState.STOPPED ||
            detectionState == DetectionState.CAMERA_ERROR ||
            detectionState == DetectionState.INITIALIZING
        ) {
            return snapshot(
                alarmRequested = false,
                alertLevel = AlertLevel.NONE,
                message = if (detectionState == DetectionState.CAMERA_ERROR) "Camera or model error" else "Monitoring stopped"
            )
        }

        // Handle missing face
        if (!faceDetected || (leftEar == null && rightEar == null)) {
            handleFaceLost(timestampMs)
            return snapshot(
                alarmRequested = false,
                alertLevel = AlertLevel.NONE,
                message = "Face not detected — ensure front camera has clear view"
            )
        }

        // Check for observation time gap (e.g. app paused, camera briefly lost)
        // If timestamps go backwards or gap exceeds observationGapResetMs, reset smoothing.
        val prevTime = lastValidObservationMs
        if (prevTime != null && (timestampMs <= prevTime || timestampMs - prevTime > observationGapResetMs)) {
            smoothedEar = null
        }
        lastValidObservationMs = timestampMs

        // Independent eye analysis & combined EAR
        val validLeft  = leftEar?.takeIf  { it.isFinite() && it >= 0.0 }
        val validRight = rightEar?.takeIf { it.isFinite() && it >= 0.0 }

        val rawEar = when {
            validLeft != null && validRight != null -> {
                // If one eye is significantly more closed than the other (> 0.08 difference),
                // use the lower EAR to detect asymmetric closure or partial ptosis.
                val diff = kotlin.math.abs(validLeft - validRight)
                if (diff > 0.08) min(validLeft, validRight) else (validLeft + validRight) / 2.0
            }
            validLeft  != null -> validLeft
            validRight != null -> validRight
            else               -> null
        }

        if (rawEar == null) {
            handleFaceLost(timestampMs)
            return snapshot(alarmRequested = false, alertLevel = AlertLevel.NONE, message = "Landmark tracking lost")
        }

        // Temporal smoothing (Exponential Moving Average)
        // alpha=0.50 balances responsiveness vs noise rejection.
        // The previous value of 0.65 was too aggressive — a single noisy frame
        // could drop the smooth EAR below the closed threshold.
        val currentSmoothedEar = smoothedEar?.let { earEmaAlpha * rawEar + (1.0 - earEmaAlpha) * it } ?: rawEar
        smoothedEar = currentSmoothedEar

        // Handle calibration if active
        val calProgress = calibrationManager.processSample(currentSmoothedEar, timestampMs)
        val closedThreshold = calibrationManager.closedThreshold
        val openThreshold   = calibrationManager.openThreshold

        // Determine eye state with hysteresis
        val prevEyeState = eyeState
        eyeState = when {
            currentSmoothedEar <= closedThreshold -> EyeState.CLOSED
            currentSmoothedEar >= openThreshold   -> EyeState.OPEN
            // In the hysteresis zone: retain previous state to prevent oscillation
            else -> if (prevEyeState != EyeState.UNCERTAIN) prevEyeState else EyeState.OPEN
        }

        val isClosed = eyeState == EyeState.CLOSED

        // Update duration tracking and rolling episodes
        val (closureDurationMs, concludedEpisode) = episodeTracker.update(isClosed, timestampMs)

        // Track last completed closure so the UI shows meaningful data after eyes reopen
        if (concludedEpisode != null && concludedEpisode.durationMs > 0L) {
            lastCompletedClosureDurationMs = concludedEpisode.durationMs
        }
        if (isClosed && closureDurationMs > 0L) {
            // While eyes are closed, always show the live duration
            lastCompletedClosureDurationMs = closureDurationMs
        }

        // Only record valid observations in PERCLOS (not face-loss frames)
        perclosCalculator.recordObservation(timestampMs, isClosed)

        // Update modular indicators
        val yawnResult = yawnTracker.update(
            mar = mouthAspectRatio,
            timestampMs = timestampMs,
            mouthMetrics = mouthMetrics
        )
        val isHeadDown = headPitchDegrees != null && headPitchDegrees < -18.0

        // Adaptive scoring (event-based)
        val prolongedCount = episodeTracker.countProlongedClosures(timestampMs)
        val avgEpisodeDuration = episodeTracker.averageEpisodeDurationMs(timestampMs)
        val rollingPerclos = perclosCalculator.calculate(timestampMs)

        val scoreResult = scoringEngine.evaluate(
            closureDurationMs = closureDurationMs,
            perclos = rollingPerclos,
            prolongedCount = prolongedCount,
            avgEpisodeDurationMs = avgEpisodeDuration,
            isYawning = yawnResult.isYawning,
            isNodding = isHeadDown,
            isEyeClosed = isClosed,
            timestampMs = timestampMs,
            yawnEventCount = yawnTracker.totalYawnCount,
            nodEventCount = headPoseEstimator.totalNodCount
        )

        detectionState = scoreResult.state

        // Determine Alert Escalation and Cooldowns
        var alarmRequested = false
        var alertLevel = AlertLevel.NONE

        when (detectionState) {
            DetectionState.CRITICAL -> {
                alertLevel = AlertLevel.CRITICAL
                val canTriggerCritical = lastCriticalAlertMs == Long.MIN_VALUE ||
                        (timestampMs - lastCriticalAlertMs >= criticalCooldownMs)
                if (canTriggerCritical) {
                    alarmRequested = true
                    lastCriticalAlertMs = timestampMs
                    criticalEventsCount++
                }
            }
            DetectionState.DROWSY -> {
                alertLevel = AlertLevel.WARNING
                val canTriggerWarning = lastWarningAlertMs == Long.MIN_VALUE ||
                        (timestampMs - lastWarningAlertMs >= warningCooldownMs)
                if (canTriggerWarning) {
                    alarmRequested = true
                    lastWarningAlertMs = timestampMs
                    warningEventsCount++
                }
            }
            else -> {
                alertLevel = AlertLevel.NONE
            }
        }

        val displayedClosureDurationMs = lastCompletedClosureDurationMs
        val durationCategory = DurationCategory.fromDuration(displayedClosureDurationMs)
        val statusMessage = buildStatusMessage(detectionState, durationCategory, yawnResult.isYawning)

        return snapshot(
            alarmRequested = alarmRequested,
            alertLevel = alertLevel,
            message = statusMessage,
            ear = currentSmoothedEar,
            leftEar = validLeft,
            rightEar = validRight,
            closureDurationMs = displayedClosureDurationMs,
            durationCategory = durationCategory,
            perclos = rollingPerclos,
            drowsinessScore = scoreResult.totalScore,
            isYawning = yawnResult.isYawning,
            mouthAspectRatio = mouthAspectRatio,
            yawnCount = yawnTracker.totalYawnCount,
            headPitchDegrees = headPitchDegrees,
            isHeadNodding = isHeadDown,
            isCalibrating = calibrationManager.isCalibrating,
            calibrationProgressPercent = calProgress
        )
    }

    fun faceLost(timestampMs: Long): DetectionSnapshot {
        handleFaceLost(timestampMs)
        return snapshot(
            alarmRequested = false,
            alertLevel = AlertLevel.NONE,
            message = "Face not detected — adjust device angle"
        )
    }

    private fun handleFaceLost(timestampMs: Long) {
        // Missing face is NOT counted as eye closure — do not update episodeTracker or perclos.
        detectionState = DetectionState.FACE_NOT_DETECTED
        eyeState = EyeState.UNCERTAIN
        smoothedEar = null
    }

    fun startCalibration(timestampMs: Long) {
        calibrationManager.startCalibration(timestampMs)
    }

    fun fail(): DetectionSnapshot {
        detectionState = DetectionState.CAMERA_ERROR
        eyeState = EyeState.UNCERTAIN
        smoothedEar = null
        return snapshot(false, AlertLevel.NONE, "Camera or vision model error")
    }

    fun stop(): DetectionSnapshot {
        detectionState = DetectionState.STOPPED
        resetInternalState()
        return snapshot(false, AlertLevel.NONE, "Monitoring stopped")
    }

    private fun resetInternalState() {
        eyeState = EyeState.UNCERTAIN
        smoothedEar = null
        lastValidObservationMs = null
        lastWarningAlertMs = Long.MIN_VALUE
        lastCriticalAlertMs = Long.MIN_VALUE
        lastCompletedClosureDurationMs = 0L
        episodeTracker.reset()
        perclosCalculator.reset()
        scoringEngine.reset()
        yawnTracker.reset()
        headPoseEstimator.reset()
    }

    private fun buildStatusMessage(
        state: DetectionState,
        durationCategory: DurationCategory,
        isYawning: Boolean
    ): String = when (state) {
        DetectionState.CRITICAL -> "CRITICAL: Possible driver drowsiness. Stop safely and take a break."
        DetectionState.DROWSY -> if (isYawning) "Signs of drowsiness detected (Yawning). Stay alert." else "Signs of drowsiness detected."
        DetectionState.FACE_NOT_DETECTED -> "Face not detected — adjust camera position"
        DetectionState.INITIALIZING -> "Initializing vision system…"
        DetectionState.CAMERA_ERROR -> "Camera or vision error"
        DetectionState.STOPPED -> "Monitoring stopped"
        DetectionState.MONITORING -> when (durationCategory) {
            DurationCategory.MONITOR -> "Eye closure monitored"
            else -> "Driver attentive — system active"
        }
    }

    private fun snapshot(
        alarmRequested: Boolean,
        alertLevel: AlertLevel,
        message: String,
        ear: Double? = smoothedEar,
        leftEar: Double? = null,
        rightEar: Double? = null,
        closureDurationMs: Long = lastCompletedClosureDurationMs,
        durationCategory: DurationCategory = DurationCategory.fromDuration(lastCompletedClosureDurationMs),
        perclos: Double? = null,
        drowsinessScore: Double = 0.0,
        isYawning: Boolean = false,
        mouthAspectRatio: Double? = null,
        yawnCount: Int = yawnTracker.totalYawnCount,
        headPitchDegrees: Double? = null,
        isHeadNodding: Boolean = false,
        isCalibrating: Boolean = calibrationManager.isCalibrating,
        calibrationProgressPercent: Int = 100
    ): DetectionSnapshot = DetectionSnapshot(
        state = detectionState,
        eyeState = eyeState,
        ear = ear,
        leftEar = leftEar,
        rightEar = rightEar,
        closureDurationMs = closureDurationMs,
        durationCategory = durationCategory,
        perclos = perclos,
        drowsinessScore = drowsinessScore,
        alertLevel = alertLevel,
        alarmRequested = alarmRequested,
        statusMessage = message,
        warningEventsCount = warningEventsCount,
        criticalEventsCount = criticalEventsCount,
        isYawning = isYawning,
        mouthAspectRatio = mouthAspectRatio,
        yawnCount = yawnCount,
        headPitchDegrees = headPitchDegrees,
        isHeadNodding = isHeadNodding,
        isCalibrating = isCalibrating,
        calibrationProgressPercent = calibrationProgressPercent
    )
}
