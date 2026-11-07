package com.example.drowsiness.detection

/**
 * Explicit operational states for the driver monitoring system.
 */
enum class DetectionState {
    INITIALIZING,
    MONITORING,
    DROWSY,
    CRITICAL,
    FACE_NOT_DETECTED,
    CAMERA_ERROR,
    STOPPED
}

/**
 * Escalating alert levels.
 */
enum class AlertLevel {
    NONE,
    WARNING,
    CRITICAL
}

/**
 * Eye state classification.
 */
enum class EyeState {
    OPEN,
    CLOSED,
    UNCERTAIN
}

/**
 * Duration-based classification of eye-closure episodes.
 */
enum class DurationCategory(val label: String, val minMs: Long, val maxMs: Long) {
    NORMAL_BLINK("Normal blink", 0L, 200L),
    MONITOR("Monitor", 200L, 500L),
    POTENTIAL_DROWSINESS("Potential drowsiness", 500L, 1000L),
    SERIOUS_WARNING("Serious warning", 1000L, 2000L),
    CRITICAL_SUSTAINED("Critical sustained closure", 2000L, Long.MAX_VALUE);

    companion object {
        fun fromDuration(durationMs: Long): DurationCategory = when {
            durationMs < 200L -> NORMAL_BLINK
            durationMs < 500L -> MONITOR
            durationMs < 1000L -> POTENTIAL_DROWSINESS
            durationMs < 2000L -> SERIOUS_WARNING
            else -> CRITICAL_SUSTAINED
        }
    }
}

/**
 * Record of an individual eye closure event.
 */
data class ClosureEpisode(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val category: DurationCategory
)

/**
 * Immutable snapshot of the current driver monitoring state and metrics.
 */
data class DetectionSnapshot(
    val state: DetectionState,
    val eyeState: EyeState,
    val ear: Double?,
    val leftEar: Double?,
    val rightEar: Double?,
    val closureDurationMs: Long,
    val durationCategory: DurationCategory,
    val perclos: Double?,
    val drowsinessScore: Double,
    val alertLevel: AlertLevel,
    val alarmRequested: Boolean,
    val statusMessage: String,
    val warningEventsCount: Int = 0,
    val criticalEventsCount: Int = 0,
    val isYawning: Boolean = false,
    val mouthAspectRatio: Double? = null,
    val yawnCount: Int = 0,
    val headPitchDegrees: Double? = null,
    val isHeadNodding: Boolean = false,
    val isCalibrating: Boolean = false,
    val calibrationProgressPercent: Int = 100
)
