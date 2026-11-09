package com.example.drowsiness.detection

import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent, personalized driver drowsiness detection coordinator.
 *
 * Implements:
 * - Independent left and right eye tracking with personalized thresholds.
 * - Explicit Case A through Case H eye classification.
 * - Sensible bilateral eye fusion (asymmetric closure/winks do not trigger false alarms).
 * - Complete separation of calibration and monitoring (no alarms, scores, or PERCLOS updates during calibration).
 * - Safe transition after calibration with complete state reset.
 * - Anti-flicker temporal closure tracking with observation gap protection.
 * - Time-based rolling PERCLOS.
 * - Slow, gated adaptive baseline updating (cannot learn closed eyes as normal).
 * - Modular yawning and head pose monitoring.
 * - Multi-indicator weighted scoring with alert cooldowns.
 */
class DrowsinessDetector(
    val initialClosedThreshold: Double = 0.18,
    val initialOpenThreshold: Double = 0.22,
    val observationGapResetMs: Long = 600L,
    val warningCooldownMs: Long = 4_000L,
    val criticalCooldownMs: Long = 5_000L,
    val earEmaAlpha: Double = 0.50,
    val enableAdaptiveBaseline: Boolean = true
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
    val adaptiveBaselineTracker = AdaptiveBaselineTracker(enabled = enableAdaptiveBaseline)
    val faceVisibilityTracker = FaceVisibilityTracker(maxObservationGapMs = observationGapResetMs)

    // Operational State
    private var detectionState = DetectionState.STOPPED
    private var eyeState = EyeState.UNCERTAIN
    private var leftEyeState = EyeState.UNCERTAIN
    private var rightEyeState = EyeState.UNCERTAIN

    private var smoothedLeftEar: Double? = null
    private var smoothedRightEar: Double? = null
    private var smoothedEar: Double? = null

    private var lastValidObservationMs: Long? = null
    private var lastWarningAlertMs: Long = Long.MIN_VALUE
    private var lastCriticalAlertMs: Long = Long.MIN_VALUE
    private var drowsinessCriticalActive = false
    private var lastObservedNodCount = 0
    private var lastSessionPerclos: Double? = null
    private var lastSessionPerclosTimestampMs: Long? = null
    private var sessionPerclosIntegral = 0.0
    private var sessionPerclosWeightMs = 0L

    val nodEventCount: Int get() = lastObservedNodCount
    val timeWeightedAveragePerclos: Double?
        get() = sessionPerclosWeightMs.takeIf { it > 0L }?.let { sessionPerclosIntegral / it }

    /**
     * Last completed closure duration in ms.
     * Retained until the next closure begins so UI shows meaningful telemetry.
     */
    private var lastCompletedClosureDurationMs: Long = 0L

    // Event Counters
    var warningEventsCount: Int = 0
        private set
    var criticalEventsCount: Int = 0
        private set
    var criticalDrowsinessIncidentCount: Int = 0
        private set

    fun start(enableCalibration: Boolean = false, nowMs: Long = System.currentTimeMillis()): DetectionSnapshot {
        resetInternalState()

        if (enableCalibration) {
            detectionState = DetectionState.CALIBRATING
            calibrationManager.startCalibration(nowMs)
        } else {
            detectionState = DetectionState.MONITORING
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
        nodEventCount: Int = 0,
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

        val visibility = faceVisibilityTracker.observe(faceDetected, timestampMs)
        lastObservedNodCount = nodEventCount
        if (!faceDetected) {
            handleFaceLost(timestampMs)
            return faceSnapshot(visibility, timestampMs)
        }
        if (visibility.isReacquiring && !visibility.faceLossRecovered) {
            return faceSnapshot(visibility, timestampMs)
        }
        if (visibility.faceLossRecovered) {
            suspendClosureAtLastObservation()
            scoringEngine.suspendRecovery()
            resetEyeSmoothing()
        }
        if (leftEar == null && rightEar == null) {
            handleEyeMeasurementsUnavailable()
            return snapshot(
                alarmRequested = requestCriticalPlaybackIfEligible(timestampMs, visibility.isFaceLossCritical || drowsinessCriticalActive),
                alertLevel = currentAlertLevel(visibility.isFaceLossCritical),
                message = if (visibility.isFaceLossCritical) FACE_LOSS_WARNING else "Face visible — eye measurements unavailable",
                ear = null,
                leftEar = null,
                rightEar = null,
                perclos = null,
                faceLossCritical = visibility.isFaceLossCritical,
                faceLossIncidentCount = visibility.faceLossIncidentCount,
                alertActive = visibility.isFaceLossCritical || drowsinessCriticalActive,
                faceVisible = true,
                faceLocked = visibility.isFaceLocked,
                drowsinessCritical = drowsinessCriticalActive
            )
        }
        // Check for observation time gap (app paused, camera frame drop, reconnection)
        val prevTime = lastValidObservationMs
        if (prevTime != null && (timestampMs <= prevTime || timestampMs - prevTime > observationGapResetMs)) {
            smoothedLeftEar = null
            smoothedRightEar = null
            smoothedEar = null
            suspendClosureAtLastObservation()
            scoringEngine.suspendRecovery()
            perclosCalculator.breakContinuity()
        }
        lastValidObservationMs = timestampMs

        // ═══════════════════════════════════════════════════════════════════
        // SECTION 10: COMPLETE SEPARATION OF CALIBRATION AND MONITORING
        // ═══════════════════════════════════════════════════════════════════
        if (calibrationManager.isCalibrating) {
            detectionState = DetectionState.CALIBRATING
            val calProgress = calibrationManager.processSample(
                leftEar = leftEar,
                rightEar = rightEar,
                headPitchDegrees = headPitchDegrees,
                timestampMs = timestampMs
            )

            if (calibrationManager.isCalibrating) {
                // Calibration in progress: DO NOT update PERCLOS, closure episodes, or scoring.
                // DO NOT trigger alarms.
                val currentCombinedEar = when {
                    leftEar != null && rightEar != null -> (leftEar + rightEar) / 2.0
                    leftEar != null -> leftEar
                    rightEar != null -> rightEar
                    else -> null
                }
                return snapshot(
                    alarmRequested = false,
                    alertLevel = AlertLevel.NONE,
                    message = "Calibrating baseline EAR… Please look ahead at the road ($calProgress%)",
                    ear = currentCombinedEar,
                    leftEar = leftEar,
                    rightEar = rightEar,
                    closureDurationMs = 0L,
                    durationCategory = DurationCategory.NORMAL_BLINK,
                    perclos = null,
                    drowsinessScore = 0.0,
                    isYawning = false,
                    mouthAspectRatio = mouthAspectRatio,
                    headPitchDegrees = headPitchDegrees,
                    isCalibrating = true,
                    calibrationProgressPercent = calProgress,
                    leftEyeState = EyeState.UNCERTAIN,
                    rightEyeState = EyeState.UNCERTAIN,
                    calibrationFailureReason = CalibrationFailureReason.NONE
                )
            } else {
                // Calibration completed on this frame!
                resetMonitoringState()
                if (calibrationManager.calibrationSucceeded) {
                    val baseL = calibrationManager.baselineOpenEarLeft ?: initialOpenThreshold
                    val baseR = calibrationManager.baselineOpenEarRight ?: initialOpenThreshold
                    adaptiveBaselineTracker.initialize(baseL, baseR)
                    detectionState = DetectionState.MONITORING
                } else {
                    detectionState = DetectionState.MONITORING
                    val reason = calibrationManager.failureReason.description
                    return snapshot(
                        alarmRequested = false,
                        alertLevel = AlertLevel.NONE,
                        message = "Calibration unsuccessful: $reason. Using safe default thresholds.",
                        ear = if (leftEar != null && rightEar != null) (leftEar + rightEar) / 2.0 else leftEar ?: rightEar,
                        leftEar = leftEar,
                        rightEar = rightEar,
                        isCalibrating = false,
                        calibrationProgressPercent = 100,
                        calibrationFailureReason = calibrationManager.failureReason
                    )
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // MONITORING MODE: INDEPENDENT LEFT & RIGHT EYE TRACKING & CLASSIFICATION
        // ═══════════════════════════════════════════════════════════════════
        // Resolve active thresholds (personalized or adapted)
        val adaptThresholdsLeft = adaptiveBaselineTracker.getThresholdsLeft()
        val adaptThresholdsRight = adaptiveBaselineTracker.getThresholdsRight()

        val activeClosedThresholdLeft = adaptThresholdsLeft?.first ?: calibrationManager.closedThresholdLeft
        val activeOpenThresholdLeft = adaptThresholdsLeft?.second ?: calibrationManager.openThresholdLeft

        val activeClosedThresholdRight = adaptThresholdsRight?.first ?: calibrationManager.closedThresholdRight
        val activeOpenThresholdRight = adaptThresholdsRight?.second ?: calibrationManager.openThresholdRight

        // Classify left eye independently (Cases A - H)
        val leftResult = EyeClassifier.classifySingleEye(
            rawEar = leftEar,
            prevSmoothedEar = smoothedLeftEar,
            prevEyeState = leftEyeState,
            closedThreshold = activeClosedThresholdLeft,
            openThreshold = activeOpenThresholdLeft,
            emaAlpha = earEmaAlpha,
            faceDetected = faceDetected
        )
        smoothedLeftEar = leftResult.smoothedEar
        leftEyeState = leftResult.state

        // Classify right eye independently (Cases A - H)
        val rightResult = EyeClassifier.classifySingleEye(
            rawEar = rightEar,
            prevSmoothedEar = smoothedRightEar,
            prevEyeState = rightEyeState,
            closedThreshold = activeClosedThresholdRight,
            openThreshold = activeOpenThresholdRight,
            emaAlpha = earEmaAlpha,
            faceDetected = faceDetected
        )
        smoothedRightEar = rightResult.smoothedEar
        rightEyeState = rightResult.state

        // Bilateral fusion
        val fusionResult = EyeClassifier.fuse(leftResult, rightResult)
        eyeState = fusionResult.combinedState
        smoothedEar = fusionResult.combinedEar

        val isBilateralClosed = (eyeState == EyeState.CLOSED)

        // Update closure episodes only with confident bilateral closure
        val (closureDurationMs, concludedEpisode) = episodeTracker.update(isBilateralClosed, timestampMs)

        if (concludedEpisode != null && concludedEpisode.durationMs > 0L) {
            lastCompletedClosureDurationMs = concludedEpisode.durationMs
        }
        if (isBilateralClosed && closureDurationMs > 0L) {
            lastCompletedClosureDurationMs = closureDurationMs
        }

        // Only confident observations contribute to PERCLOS (UNCERTAIN excluded)
        if (eyeState == EyeState.OPEN || eyeState == EyeState.CLOSED) {
            perclosCalculator.recordObservation(timestampMs, isBilateralClosed)
        }

        // Update secondary indicators: Yawn and Head Pose
        val yawnResult = yawnTracker.update(
            mar = mouthAspectRatio,
            timestampMs = timestampMs,
            mouthMetrics = mouthMetrics
        )
        val isHeadDown = headPitchDegrees != null && headPitchDegrees < -18.0

        // Drowsiness scoring
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
            isEyeClosed = isBilateralClosed,
            timestampMs = timestampMs,
            yawnEventCount = yawnTracker.totalYawnCount,
            nodEventCount = nodEventCount
        )

        val wasDrowsinessCritical = drowsinessCriticalActive
        detectionState = scoreResult.state
        drowsinessCriticalActive = detectionState == DetectionState.CRITICAL
        if (!wasDrowsinessCritical && drowsinessCriticalActive) criticalDrowsinessIncidentCount++

        if (rollingPerclos != null) recordSessionPerclos(timestampMs, rollingPerclos)

        // Optional slow adaptive baseline update (Section 9)
        if (calibrationManager.calibrationSucceeded) {
            adaptiveBaselineTracker.update(
                leftEar = leftEar,
                rightEar = rightEar,
                leftEyeState = leftEyeState,
                rightEyeState = rightEyeState,
                closureDurationMs = closureDurationMs,
                drowsinessScore = scoreResult.totalScore,
                detectionState = detectionState,
                headPitchDegrees = headPitchDegrees,
                isYawning = yawnResult.isYawning,
                isNodding = isHeadDown
            )
        }

        // Alert Escalation & Cooldown Management
        var alarmRequested = false
        var alertLevel = AlertLevel.NONE

        when (detectionState) {
            DetectionState.CRITICAL -> {
                alertLevel = AlertLevel.CRITICAL
                alarmRequested = requestCriticalPlaybackIfEligible(timestampMs, true)
                if (alarmRequested) criticalEventsCount++
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
            alertLevel = if (visibility.isFaceLossCritical) AlertLevel.CRITICAL else alertLevel,
            message = statusMessage,
            ear = smoothedEar,
            leftEar = leftResult.smoothedEar ?: leftEar,
            rightEar = rightResult.smoothedEar ?: rightEar,
            closureDurationMs = displayedClosureDurationMs,
            durationCategory = durationCategory,
            perclos = rollingPerclos,
            drowsinessScore = scoreResult.totalScore,
            isYawning = yawnResult.isYawning,
            mouthAspectRatio = mouthAspectRatio,
            yawnCount = yawnTracker.totalYawnCount,
            headPitchDegrees = headPitchDegrees,
            isHeadNodding = isHeadDown,
            isCalibrating = false,
            calibrationProgressPercent = 100,
            leftEyeState = leftEyeState,
            rightEyeState = rightEyeState,
            baselineLeftEar = adaptiveBaselineTracker.currentBaselineLeft ?: calibrationManager.baselineOpenEarLeft,
            baselineRightEar = adaptiveBaselineTracker.currentBaselineRight ?: calibrationManager.baselineOpenEarRight,
            calibrationFailureReason = calibrationManager.failureReason,
            faceLossCritical = visibility.isFaceLossCritical,
            faceLossIncidentCount = visibility.faceLossIncidentCount,
            alertActive = visibility.isFaceLossCritical || detectionState == DetectionState.DROWSY || detectionState == DetectionState.CRITICAL,
            faceVisible = true,
            faceLocked = visibility.isFaceLocked,
            drowsinessCritical = drowsinessCriticalActive
        )
    }

    fun faceLost(timestampMs: Long): DetectionSnapshot {
        val visibility = faceVisibilityTracker.observe(false, timestampMs)
        handleFaceLost(timestampMs)
        return faceSnapshot(visibility, timestampMs)
    }

    private fun faceSnapshot(v: FaceVisibilityTracker.Result, timestampMs: Long) = snapshot(
        alarmRequested = requestCriticalPlaybackIfEligible(timestampMs, v.isFaceLossCritical || drowsinessCriticalActive),
        alertLevel = currentAlertLevel(v.isFaceLossCritical),
        message = when {
            v.isFaceLossCritical -> FACE_LOSS_WARNING
            v.isFaceVisible -> "Face found — confirming stable reacquisition"
            else -> "Face not detected — waiting for stable reacquisition"
        },
        faceLossCritical = v.isFaceLossCritical,
        faceLossIncidentCount = v.faceLossIncidentCount,
        alertActive = v.isFaceLossCritical || drowsinessCriticalActive || detectionState == DetectionState.DROWSY,
        faceVisible = v.isFaceVisible,
        faceLocked = v.isFaceLocked,
        drowsinessCritical = drowsinessCriticalActive
    )

    private fun currentAlertLevel(faceLossCritical: Boolean): AlertLevel = when {
        faceLossCritical || drowsinessCriticalActive -> AlertLevel.CRITICAL
        detectionState == DetectionState.DROWSY -> AlertLevel.WARNING
        else -> AlertLevel.NONE
    }

    private fun requestCriticalPlaybackIfEligible(timestampMs: Long, conditionActive: Boolean): Boolean {
        if (!conditionActive) return false
        val eligible = lastCriticalAlertMs == Long.MIN_VALUE || timestampMs - lastCriticalAlertMs >= criticalCooldownMs
        if (eligible) lastCriticalAlertMs = timestampMs
        return eligible
    }

    private fun recordSessionPerclos(timestampMs: Long, value: Double) {
        val previousTime = lastSessionPerclosTimestampMs
        val previousValue = lastSessionPerclos
        if (previousTime != null && previousValue != null) {
            val interval = timestampMs - previousTime
            if (interval in 1L..observationGapResetMs) {
                sessionPerclosIntegral += (previousValue + value) / 2.0 * interval
                sessionPerclosWeightMs += interval
            }
        }
        lastSessionPerclos = value
        lastSessionPerclosTimestampMs = timestampMs
    }

    private fun handleEyeMeasurementsUnavailable() {
        if (lastValidObservationMs != null) {
            suspendClosureAtLastObservation()
            scoringEngine.suspendRecovery()
        }
        perclosCalculator.breakContinuity()
        lastValidObservationMs = null
        lastSessionPerclos = null
        lastSessionPerclosTimestampMs = null
        resetEyeSmoothing()
    }

    private fun resetEyeSmoothing() {
        smoothedLeftEar = null; smoothedRightEar = null; smoothedEar = null
        eyeState = EyeState.UNCERTAIN; leftEyeState = EyeState.UNCERTAIN; rightEyeState = EyeState.UNCERTAIN
    }

    private fun handleFaceLost(timestampMs: Long) {
        // Missing face is NOT counted as eye closure — do not update episodeTracker or perclos.
        detectionState = DetectionState.FACE_NOT_DETECTED
        eyeState = EyeState.UNCERTAIN
        leftEyeState = EyeState.UNCERTAIN
        rightEyeState = EyeState.UNCERTAIN
        smoothedLeftEar = null
        smoothedRightEar = null
        smoothedEar = null
        if (lastValidObservationMs != null) {
            suspendClosureAtLastObservation()
            scoringEngine.suspendRecovery()
        }
        perclosCalculator.breakContinuity()
        lastValidObservationMs = null
        lastSessionPerclos = null
        lastSessionPerclosTimestampMs = null
    }

    fun startCalibration(timestampMs: Long) {
        detectionState = DetectionState.CALIBRATING
        calibrationManager.startCalibration(timestampMs)
    }

    fun fail(): DetectionSnapshot {
        detectionState = DetectionState.CAMERA_ERROR
        drowsinessCriticalActive = false
        eyeState = EyeState.UNCERTAIN
        leftEyeState = EyeState.UNCERTAIN
        rightEyeState = EyeState.UNCERTAIN
        smoothedLeftEar = null
        smoothedRightEar = null
        smoothedEar = null
        return snapshot(false, AlertLevel.NONE, "Camera or vision model error", drowsinessCritical = false, alertActive = false)
    }

    fun stop(): DetectionSnapshot {
        suspendClosureAtLastObservation()
        detectionState = DetectionState.STOPPED
        resetInternalState()
        return snapshot(false, AlertLevel.NONE, "Monitoring stopped")
    }

    private fun resetMonitoringState() {
        eyeState = EyeState.UNCERTAIN
        leftEyeState = EyeState.UNCERTAIN
        rightEyeState = EyeState.UNCERTAIN
        smoothedLeftEar = null
        smoothedRightEar = null
        smoothedEar = null
        lastValidObservationMs = null
        lastWarningAlertMs = Long.MIN_VALUE
        lastCriticalAlertMs = Long.MIN_VALUE
        lastCompletedClosureDurationMs = 0L
        episodeTracker.reset()
        perclosCalculator.reset()
        scoringEngine.reset()
    }

    private fun suspendClosureAtLastObservation() {
        episodeTracker.suspendAtLastObservation()?.let { episode ->
            lastCompletedClosureDurationMs = max(lastCompletedClosureDurationMs, episode.durationMs)
        }
    }

    private fun resetInternalState() {
        resetMonitoringState()
        calibrationManager.reset()
        adaptiveBaselineTracker.reset()
        yawnTracker.reset()
        warningEventsCount = 0
        criticalEventsCount = 0
        criticalDrowsinessIncidentCount = 0
        faceVisibilityTracker.reset()
        drowsinessCriticalActive = false
        lastObservedNodCount = 0
        lastSessionPerclos = null
        lastSessionPerclosTimestampMs = null
        sessionPerclosIntegral = 0.0
        sessionPerclosWeightMs = 0L
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
        DetectionState.CALIBRATING -> "Calibrating baseline EAR… Please look ahead"
        DetectionState.CAMERA_ERROR -> "Camera or vision error"
        DetectionState.STOPPED -> "Monitoring stopped"
        DetectionState.MONITORING -> when (durationCategory) {
            DurationCategory.MONITOR -> "Eye closure monitored"
            else -> "Driver attentive — system active"
        }
    }

    companion object {
        const val FACE_LOSS_WARNING = "CRITICAL — DRIVER FACE NOT VISIBLE. CHECK DRIVER AND CAMERA."
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
        calibrationProgressPercent: Int = 100,
        leftEyeState: EyeState = this.leftEyeState,
        rightEyeState: EyeState = this.rightEyeState,
        baselineLeftEar: Double? = calibrationManager.baselineOpenEarLeft,
        baselineRightEar: Double? = calibrationManager.baselineOpenEarRight,
        calibrationFailureReason: CalibrationFailureReason = calibrationManager.failureReason,
        faceLossCritical: Boolean = false,
        faceLossIncidentCount: Int = faceVisibilityTracker.faceLossIncidentCount,
        alertActive: Boolean = detectionState == DetectionState.DROWSY || detectionState == DetectionState.CRITICAL,
        faceVisible: Boolean = faceVisibilityTracker.isFaceVisible,
        drowsinessCritical: Boolean = drowsinessCriticalActive,
        faceLocked: Boolean = faceVisibilityTracker.isFaceLocked,
        criticalDrowsinessIncidentCount: Int = this.criticalDrowsinessIncidentCount
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
        calibrationProgressPercent = calibrationProgressPercent,
        leftEyeState = leftEyeState,
        rightEyeState = rightEyeState,
        baselineLeftEar = baselineLeftEar,
        baselineRightEar = baselineRightEar,
        calibrationFailureReason = calibrationFailureReason,
        faceLossCritical = faceLossCritical,
        faceLossIncidentCount = faceLossIncidentCount,
        alertActive = alertActive,
        faceVisible = faceVisible,
        drowsinessCritical = drowsinessCritical,
        faceLocked = faceLocked,
        criticalDrowsinessIncidentCount = criticalDrowsinessIncidentCount
    )

}
