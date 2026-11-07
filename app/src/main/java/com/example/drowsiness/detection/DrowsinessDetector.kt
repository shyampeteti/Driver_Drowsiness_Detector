package com.example.drowsiness.detection

import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent driver drowsiness detection coordinator.
 *
 * Combines independent eye tracking, duration-weighted closure classification,
 * rolling PERCLOS calculation, modular yawning and head pose monitoring,
 * and adaptive multi-indicator scoring.
 */
class DrowsinessDetector(
    val initialClosedThreshold: Double = 0.20,
    val initialOpenThreshold: Double = 0.24,
    val observationGapResetMs: Long = 600L,
    val warningCooldownMs: Long = 4_000L,
    val criticalCooldownMs: Long = 2_500L
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
     * @param mouthAspectRatio Calculated MAR for yawning detection
     * @param headPitchDegrees Estimated pitch degrees for nodding detection
     * @param timestampMs Monotonic timestamp in milliseconds
     */
    fun observe(
        leftEar: Double?,
        rightEar: Double?,
        faceDetected: Boolean,
        mouthAspectRatio: Double? = null,
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

        // Check for observation time gap (e.g. app paused or frame dropped)
        val prevTime = lastValidObservationMs
        if (prevTime != null && (timestampMs <= prevTime || timestampMs - prevTime > observationGapResetMs)) {
            smoothedEar = null
        }
        lastValidObservationMs = timestampMs

        // Independent eye analysis & combined EAR
        val validLeft = leftEar?.takeIf { it.isFinite() && it >= 0.0 }
        val validRight = rightEar?.takeIf { it.isFinite() && it >= 0.0 }

        val rawEar = when {
            validLeft != null && validRight != null -> {
                // If one eye is significantly closed while the other is open,
                // prioritize the lower EAR to detect asymmetric closure/ptosis
                val diff = kotlin.math.abs(validLeft - validRight)
                if (diff > 0.08) min(validLeft, validRight) else (validLeft + validRight) / 2.0
            }
            validLeft != null -> validLeft
            validRight != null -> validRight
            else -> null
        }

        if (rawEar == null) {
            handleFaceLost(timestampMs)
            return snapshot(alarmRequested = false, alertLevel = AlertLevel.NONE, message = "Landmark tracking lost")
        }

        // Temporal smoothing (Exponential Moving Average)
        val currentSmoothedEar = smoothedEar?.let { 0.65 * rawEar + 0.35 * it } ?: rawEar
        smoothedEar = currentSmoothedEar

        // Handle calibration if active
        val calProgress = calibrationManager.processSample(currentSmoothedEar, timestampMs)
        val closedThreshold = calibrationManager.closedThreshold
        val openThreshold = calibrationManager.openThreshold

        // Determine eye state with hysteresis
        val prevEyeState = eyeState
        eyeState = when {
            currentSmoothedEar <= closedThreshold -> EyeState.CLOSED
            currentSmoothedEar >= openThreshold -> EyeState.OPEN
            else -> if (prevEyeState != EyeState.UNCERTAIN) prevEyeState else EyeState.OPEN
        }

        val isClosed = eyeState == EyeState.CLOSED

        // Update duration tracking and rolling episodes
        val (closureDurationMs, _) = episodeTracker.update(isClosed, timestampMs)
        perclosCalculator.recordObservation(timestampMs, isClosed)

        // Update modular indicators
        val yawnResult = yawnTracker.update(mouthAspectRatio, timestampMs)
        val isHeadDown = headPitchDegrees != null && headPitchDegrees < -18.0

        // Adaptive scoring
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
            timestampMs = timestampMs
        )

        detectionState = scoreResult.state

        // Determine Alert Escalation and Cooldowns
        var alarmRequested = false
        var alertLevel = AlertLevel.NONE

        when (detectionState) {
            DetectionState.CRITICAL -> {
                alertLevel = AlertLevel.CRITICAL
                // Critical alert can escalate immediately from warning or normal
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

        val durationCategory = DurationCategory.fromDuration(closureDurationMs)
        val statusMessage = buildStatusMessage(detectionState, durationCategory, yawnResult.isYawning)

        return snapshot(
            alarmRequested = alarmRequested,
            alertLevel = alertLevel,
            message = statusMessage,
            ear = currentSmoothedEar,
            leftEar = validLeft,
            rightEar = validRight,
            closureDurationMs = closureDurationMs,
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
        // Missing face is NOT counted as eye closure.
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
        closureDurationMs: Long = 0L,
        durationCategory: DurationCategory = DurationCategory.NORMAL_BLINK,
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
