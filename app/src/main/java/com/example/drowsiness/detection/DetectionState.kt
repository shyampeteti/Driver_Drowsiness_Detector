package com.example.drowsiness.detection

enum class DetectionState {
    INITIALIZING,
    MONITORING,
    DROWSINESS_DETECTED,
    FACE_NOT_DETECTED,
    CAMERA_ERROR,
    STOPPED
}

enum class EyeState { OPEN, CLOSED, UNCERTAIN }

data class DetectionSnapshot(
    val state: DetectionState,
    val eyeState: EyeState,
    val ear: Double?,
    val closureDurationMs: Long,
    val perclos: Double?,
    val alarmRequested: Boolean,
    val statusMessage: String
)
