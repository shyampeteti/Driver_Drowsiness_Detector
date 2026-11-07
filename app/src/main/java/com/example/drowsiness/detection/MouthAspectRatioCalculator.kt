package com.example.drowsiness.detection

/**
 * Pure Kotlin Mouth Aspect Ratio (MAR) calculator and Yawning detector.
 *
 * Uses MediaPipe face mesh mouth landmarks:
 * Corners: 61, 291
 * Vertical pairs: (13, 14), (82, 87), (312, 317)
 */
object MouthAspectRatioCalculator {
    val CORNER_LEFT = 61
    val CORNER_RIGHT = 291
    val UPPER_LIP = 13
    val LOWER_LIP = 14
    val UPPER_LEFT = 82
    val LOWER_LEFT = 87
    val UPPER_RIGHT = 312
    val LOWER_RIGHT = 317

    val ALL_MOUTH_INDICES = intArrayOf(
        CORNER_LEFT, CORNER_RIGHT,
        UPPER_LIP, LOWER_LIP,
        UPPER_LEFT, LOWER_LEFT,
        UPPER_RIGHT, LOWER_RIGHT
    )

    fun calculate(
        leftCorner: Point2D,
        rightCorner: Point2D,
        upperCenter: Point2D,
        lowerCenter: Point2D,
        upperLeft: Point2D,
        lowerLeft: Point2D,
        upperRight: Point2D,
        lowerRight: Point2D
    ): Double? {
        val points = listOf(leftCorner, rightCorner, upperCenter, lowerCenter, upperLeft, lowerLeft, upperRight, lowerRight)
        if (points.any { !it.isValid() }) return null

        val horizontal = EyeAspectRatioCalculator.distance(leftCorner, rightCorner)
        if (!horizontal.isFinite() || horizontal <= 1e-9) return null

        val vCenter = EyeAspectRatioCalculator.distance(upperCenter, lowerCenter)
        val vLeft = EyeAspectRatioCalculator.distance(upperLeft, lowerLeft)
        val vRight = EyeAspectRatioCalculator.distance(upperRight, lowerRight)

        val mar = (vCenter + vLeft + vRight) / (2.0 * horizontal)
        return mar.takeIf { it.isFinite() && it >= 0.0 }
    }
}

/**
 * Tracks mouth openness over time to distinguish yawning (sustained opening)
 * from normal talking or facial expressions.
 */
class YawnTracker(
    private val yawnThreshold: Double = 0.65,
    private val minSustainedDurationMs: Long = 1_500L,
    private val cooldownMs: Long = 6_000L
) {
    private var yawnStartMs: Long? = null
    private var lastYawnRecordedMs: Long = Long.MIN_VALUE
    var totalYawnCount: Int = 0
        private set

    fun update(mar: Double?, timestampMs: Long): YawnResult {
        if (mar == null || mar < yawnThreshold) {
            yawnStartMs = null
            return YawnResult(isYawning = false, currentDurationMs = 0L, totalCount = totalYawnCount)
        }

        if (yawnStartMs == null) {
            yawnStartMs = timestampMs
        }

        val duration = timestampMs - yawnStartMs!!
        val isYawning = duration >= minSustainedDurationMs

        if (isYawning && (lastYawnRecordedMs == Long.MIN_VALUE || timestampMs - lastYawnRecordedMs >= cooldownMs)) {
            totalYawnCount++
            lastYawnRecordedMs = timestampMs
        }

        return YawnResult(
            isYawning = isYawning,
            currentDurationMs = duration,
            totalCount = totalYawnCount
        )
    }

    fun reset() {
        yawnStartMs = null
        lastYawnRecordedMs = Long.MIN_VALUE
        totalYawnCount = 0
    }
}

data class YawnResult(
    val isYawning: Boolean,
    val currentDurationMs: Long,
    val totalCount: Int
)
