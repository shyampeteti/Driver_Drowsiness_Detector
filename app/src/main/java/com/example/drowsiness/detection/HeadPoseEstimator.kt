package com.example.drowsiness.detection

import kotlin.math.atan2

/**
 * Lightweight on-device head pose and nodding estimator using 2D face mesh landmarks.
 *
 * Landmarks:
 * Forehead: 10
 * Nose tip: 1
 * Chin: 152
 * Left eye corner: 33
 * Right eye corner: 263
 */
class HeadPoseEstimator(
    private val noddingPitchThresholdDegrees: Double = -18.0,
    private val sustainedNodDurationMs: Long = 1_200L
) {
    companion object {
        const val FOREHEAD = 10
        const val NOSE_TIP = 1
        const val CHIN = 152
        const val LEFT_EYE = 33
        const val RIGHT_EYE = 263

        val POSE_INDICES = intArrayOf(FOREHEAD, NOSE_TIP, CHIN, LEFT_EYE, RIGHT_EYE)
    }

    private var nodStartMs: Long? = null
    private var lastObservationMs: Long? = null
    var totalNodCount: Int = 0
        private set

    data class PoseResult(
        val pitchDegrees: Double?,
        val isHeadDown: Boolean,
        val isNoddingSustained: Boolean,
        val totalNodCount: Int
    )

    /**
     * Estimates vertical pitch using the ratio between forehead-to-nose and nose-to-chin distances.
     * When pitching down (nodding off), the chin moves closer to the nose in 2D projection.
     */
    fun estimate(
        forehead: Point2D?,
        noseTip: Point2D?,
        chin: Point2D?,
        leftEye: Point2D?,
        rightEye: Point2D?,
        timestampMs: Long
    ): PoseResult {
        val previousTimestamp = lastObservationMs
        if (timestampMs < 0L || (previousTimestamp != null && timestampMs <= previousTimestamp)) {
            suspendTracking()
            return PoseResult(null, isHeadDown = false, isNoddingSustained = false, totalNodCount)
        }
        if (previousTimestamp != null && timestampMs - previousTimestamp > 600L) nodStartMs = null
        lastObservationMs = timestampMs
        if (forehead == null || noseTip == null || chin == null ||
            !forehead.isValid() || !noseTip.isValid() || !chin.isValid()
        ) {
            suspendTracking()
            return PoseResult(null, isHeadDown = false, isNoddingSustained = false, totalNodCount)
        }

        val upperDist = EyeAspectRatioCalculator.distance(forehead, noseTip)
        val lowerDist = EyeAspectRatioCalculator.distance(noseTip, chin)

        if (lowerDist <= 1e-6 || upperDist <= 1e-6) {
            suspendTracking()
            return PoseResult(null, isHeadDown = false, isNoddingSustained = false, totalNodCount)
        }

        // Neutral ratio upperDist / lowerDist is typically ~0.9 - 1.1.
        // Downward pitch increases this ratio (> 1.4).
        val ratio = upperDist / lowerDist
        // Approximate pitch degrees: neutral ~ 0, looking down -> negative degrees
        val estimatedPitchDegrees = (1.0 - ratio) * 45.0

        val isHeadDown = estimatedPitchDegrees < noddingPitchThresholdDegrees

        if (isHeadDown) {
            if (nodStartMs == null) nodStartMs = timestampMs
            val duration = timestampMs - nodStartMs!!
            val sustained = duration >= sustainedNodDurationMs
            if (sustained && duration < sustainedNodDurationMs + 200L) {
                totalNodCount++
            }
            return PoseResult(estimatedPitchDegrees, isHeadDown = true, isNoddingSustained = sustained, totalNodCount)
        } else {
            nodStartMs = null
            return PoseResult(estimatedPitchDegrees, isHeadDown = false, isNoddingSustained = false, totalNodCount)
        }
    }

    fun reset() {
        nodStartMs = null
        lastObservationMs = null
        totalNodCount = 0
    }

    /** Break a partial nod sequence after missing face data without erasing session totals. */
    fun suspendTracking() {
        nodStartMs = null
        lastObservationMs = null
    }
}
