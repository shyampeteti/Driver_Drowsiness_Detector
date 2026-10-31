package com.example.drowsiness.detection

import java.util.ArrayDeque
import kotlin.math.max

/** Pure Kotlin detection engine; timestamps are monotonic milliseconds. */
class DrowsinessDetector(
    private val closedThreshold: Double = 0.20,
    private val openThreshold: Double = 0.24,
    private val prolongedClosureMs: Long = 1_500L,
    private val observationGapResetMs: Long = 500L,
    private val perclosWindowMs: Long = 30_000L,
    private val minimumPerclosObservations: Int = 10,
    private val perclosAlertThreshold: Double = 0.40,
    private val alarmCooldownMs: Long = 5_000L
) {
    private data class Sample(val timeMs: Long, val closed: Boolean)
    private val samples = ArrayDeque<Sample>()
    private var eyeState = EyeState.UNCERTAIN
    private var detectionState = DetectionState.STOPPED
    private var closureStartMs: Long? = null
    private var lastValidObservationMs: Long? = null
    private var lastAlarmMs: Long = Long.MIN_VALUE
    private var lastEar: Double? = null

    init {
        require(closedThreshold >= 0 && openThreshold > closedThreshold)
        require(prolongedClosureMs > 0 && observationGapResetMs > 0 && perclosWindowMs > 0)
        require(minimumPerclosObservations > 0 && perclosAlertThreshold in 0.0..1.0)
    }

    fun start(): DetectionSnapshot {
        samples.clear()
        eyeState = EyeState.UNCERTAIN
        detectionState = DetectionState.MONITORING
        closureStartMs = null
        lastValidObservationMs = null
        lastEar = null
        lastAlarmMs = Long.MIN_VALUE
        return snapshot(false, "Monitoring — looking for face")
    }

    fun observe(ear: Double?, timestampMs: Long, faceDetected: Boolean = true): DetectionSnapshot {
        if (detectionState == DetectionState.STOPPED || detectionState == DetectionState.CAMERA_ERROR || detectionState == DetectionState.INITIALIZING) {
            return snapshot(false, if (detectionState == DetectionState.CAMERA_ERROR) "Camera or model error" else "Monitoring stopped")
        }
        if (!faceDetected || ear == null || !ear.isFinite() || ear < 0.0) {
            // An invalid frame breaks the continuous-closure interval; it is not evidence of closure.
            prune(timestampMs)
            closureStartMs = null
            eyeState = EyeState.UNCERTAIN
            detectionState = DetectionState.FACE_NOT_DETECTED
            return snapshot(false, "Face not detected — adjust camera position")
        }

        val previousTime = lastValidObservationMs
        if (previousTime != null && (timestampMs <= previousTime || timestampMs - previousTime > observationGapResetMs)) {
            closureStartMs = null
            if (detectionState == DetectionState.DROWSINESS_DETECTED) detectionState = DetectionState.MONITORING
        }
        lastValidObservationMs = timestampMs
        lastEar = ear
        val wasDrowsy = detectionState == DetectionState.DROWSINESS_DETECTED
        eyeState = when {
            ear <= closedThreshold -> EyeState.CLOSED
            ear >= openThreshold -> EyeState.OPEN
            else -> eyeState.takeIf { it != EyeState.UNCERTAIN } ?: EyeState.UNCERTAIN
        }

        val closed = eyeState == EyeState.CLOSED
        samples.addLast(Sample(timestampMs, closed))
        prune(timestampMs)
        if (closed) {
            if (closureStartMs == null) closureStartMs = timestampMs
        } else if (eyeState == EyeState.OPEN) {
            closureStartMs = null
            detectionState = DetectionState.MONITORING
        }

        val closureDuration = closureStartMs?.let { max(0L, timestampMs - it) } ?: 0L
        val perclos = calculatePerclos()
        val prolonged = closed && closureDuration >= prolongedClosureMs
        val perclosAlert = perclos != null && perclos >= perclosAlertThreshold && samples.size >= minimumPerclosObservations
        val drowsy = prolonged || perclosAlert
        detectionState = if (drowsy) DetectionState.DROWSINESS_DETECTED else DetectionState.MONITORING
        val cooldownPassed = lastAlarmMs == Long.MIN_VALUE || timestampMs - lastAlarmMs >= alarmCooldownMs
        val requestAlarm = drowsy && (!wasDrowsy || cooldownPassed)
        if (requestAlarm) lastAlarmMs = timestampMs
        return snapshot(requestAlarm, if (drowsy) "Drowsiness detected — take a break" else "Monitoring")
    }

    fun faceLost(timestampMs: Long): DetectionSnapshot = observe(null, timestampMs, faceDetected = false)

    fun fail(): DetectionSnapshot {
        detectionState = DetectionState.CAMERA_ERROR
        eyeState = EyeState.UNCERTAIN
        closureStartMs = null
        return snapshot(false, "Camera or model error")
    }

    fun stop(): DetectionSnapshot {
        detectionState = DetectionState.STOPPED
        eyeState = EyeState.UNCERTAIN
        closureStartMs = null
        lastValidObservationMs = null
        samples.clear()
        return snapshot(false, "Monitoring stopped")
    }

    private fun prune(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first.timeMs > perclosWindowMs) samples.removeFirst()
    }

    private fun calculatePerclos(): Double? {
        if (samples.size < minimumPerclosObservations) return null
        val valid = samples.count { it.closed || !it.closed }
        if (valid < minimumPerclosObservations) return null
        return samples.count { it.closed }.toDouble() / valid
    }

    private fun snapshot(alarm: Boolean, message: String): DetectionSnapshot {
        val now = lastValidObservationMs
        val duration = if (now != null && closureStartMs != null) max(0L, now - closureStartMs!!) else 0L
        return DetectionSnapshot(detectionState, eyeState, lastEar, duration, calculatePerclos(), alarm, message)
    }
}
