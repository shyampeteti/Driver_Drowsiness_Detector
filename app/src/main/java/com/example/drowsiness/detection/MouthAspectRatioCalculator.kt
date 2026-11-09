package com.example.drowsiness.detection

/**
 * Pure Kotlin Mouth Aspect Ratio (MAR) calculator and Yawning detector.
 *
 * Uses MediaPipe face mesh mouth landmarks:
 * Corners: 61, 291
 * Vertical pairs: (13, 14), (82, 87), (312, 317)
 *
 * MAR = (vertical_center + vertical_left + vertical_right) / (2 * horizontal_width)
 *
 * A higher MAR indicates wider mouth opening. Yawn detection additionally
 * requires that the opening be sustained for a minimum duration to avoid
 * counting tongue protrusions, speech, smiling, or brief gasps.
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

    data class MouthMetrics(
        /** MAR value: ratio of average vertical opening to horizontal width. */
        val mar: Double,
        /** Raw horizontal width in pixels (useful for sanity checks). */
        val horizontalPx: Double,
        /** Raw vertical opening at mouth center in pixels. */
        val verticalCenterPx: Double
    )

    /**
     * Compute mouth aspect ratio and supplemental metrics.
     * Returns null if any landmark is invalid or the geometry is degenerate.
     */
    fun computeMetrics(
        leftCorner: Point2D,
        rightCorner: Point2D,
        upperCenter: Point2D,
        lowerCenter: Point2D,
        upperLeft: Point2D,
        lowerLeft: Point2D,
        upperRight: Point2D,
        lowerRight: Point2D
    ): MouthMetrics? {
        val points = listOf(leftCorner, rightCorner, upperCenter, lowerCenter,
            upperLeft, lowerLeft, upperRight, lowerRight)
        if (points.any { !it.isValid() }) return null

        val horizontal = EyeAspectRatioCalculator.distance(leftCorner, rightCorner)
        if (!horizontal.isFinite() || horizontal <= 1e-9) return null

        val vCenter = EyeAspectRatioCalculator.distance(upperCenter, lowerCenter)
        val vLeft = EyeAspectRatioCalculator.distance(upperLeft, lowerLeft)
        val vRight = EyeAspectRatioCalculator.distance(upperRight, lowerRight)

        val mar = (vCenter + vLeft + vRight) / (2.0 * horizontal)
        if (!mar.isFinite() || mar < 0.0) return null

        return MouthMetrics(mar = mar, horizontalPx = horizontal, verticalCenterPx = vCenter)
    }

    /** Convenience wrapper returning only the MAR scalar (for backward compat). */
    fun calculate(
        leftCorner: Point2D,
        rightCorner: Point2D,
        upperCenter: Point2D,
        lowerCenter: Point2D,
        upperLeft: Point2D,
        lowerLeft: Point2D,
        upperRight: Point2D,
        lowerRight: Point2D
    ): Double? = computeMetrics(
        leftCorner, rightCorner, upperCenter, lowerCenter,
        upperLeft, lowerLeft, upperRight, lowerRight
    )?.mar
}

/**
 * Tracks mouth openness over time to detect genuine yawning events.
 *
 * Key design decisions to reduce false positives:
 *
 * 1. **Sustained duration**: The mouth must remain above [yawnThreshold] for at
 *    least [minSustainedDurationMs] (default 1500 ms) before a yawn is registered.
 *    Brief openings from speech, tongue protrusion, or laughing typically last
 *    < 500 ms and will not cross this gate.
 *
 * 2. **Minimum vertical opening**: An absolute pixel-height guard prevents cases
 *    where tongue protrusion creates a large MAR with a small absolute opening
 *    (e.g., small face in frame). The pixel threshold is relative to estimated
 *    mouth width so it scales with face size.
 *
 * 3. **Cooldown**: After a confirmed yawn, a cooldown period ([cooldownMs])
 *    suppresses repeated counting from the same continuous open event.
 *
 * 4. **Hysteresis**: The candidate is only reset when MAR drops below
 *    [yawnReopeningThreshold] (which is lower than [yawnThreshold]).
 *
 * IMPORTANT: MAR alone cannot perfectly distinguish yawns from all speech
 * or other mouth gestures. This detector uses temporal evidence and conservative
 * thresholds to minimise false positives, but it is not infallible.
 */
class YawnTracker(
    /** MAR above this triggers the candidate yawn timer. */
    val yawnThreshold: Double = 0.65,
    /**
     * MAR must drop below this before a new yawn candidate can start.
     * Lower than [yawnThreshold] to implement hysteresis.
     */
    val yawnReopeningThreshold: Double = 0.50,
    /** Mouth must stay above [yawnThreshold] for at least this long to confirm a yawn. */
    val minSustainedDurationMs: Long = 1_500L,
    /** No new yawn counted within this period after the previous confirmed yawn end. */
    val cooldownMs: Long = 6_000L,
    /**
     * Minimum vertical-opening-to-horizontal-width ratio at onset.
     * Guards against cases where MAR is elevated by mouth width narrowing
     * (e.g. pursed lips) rather than genuine vertical opening.
     * Value of 0.15 means the vertical gap must be at least 15% of mouth width.
     * Set to 0.0 to disable this guard.
     */
    val minVerticalToHorizontalRatio: Double = 0.15
) {
    private var candidateStartMs: Long? = null
    /** True while MAR is above the hysteresis lower bound (candidate window is open). */
    private var inCandidateWindow: Boolean = false
    private var lastYawnEndMs: Long = Long.MIN_VALUE
    var totalYawnCount: Int = 0
        private set

    /**
     * Process one frame of mouth metrics.
     *
     * @param mar Mouth aspect ratio from [MouthAspectRatioCalculator], or null if unavailable.
     * @param mouthMetrics Full metrics including pixel dimensions, or null.
     * @param timestampMs Monotonic timestamp in milliseconds.
     */
    fun update(
        mar: Double?,
        timestampMs: Long,
        mouthMetrics: MouthAspectRatioCalculator.MouthMetrics? = null
    ): YawnResult {
        // --- Reset candidate if mouth is closed enough (hysteresis lower bound) ---
        if (mar == null || mar < yawnReopeningThreshold) {
            if (inCandidateWindow) {
                // Mouth has genuinely closed — record when this yawn event ended
                lastYawnEndMs = timestampMs
            }
            candidateStartMs = null
            inCandidateWindow = false
            return YawnResult(isYawning = false, currentDurationMs = 0L, totalCount = totalYawnCount)
        }

        // --- Above lower hysteresis bound: candidate window is open ---
        inCandidateWindow = true

        // Apply geometric guard: reject if vertical opening is below minimum ratio
        // This helps distinguish tongue-out (small vertical gap, stretched corners)
        // from genuine yawn (large vertical gap)
        if (mouthMetrics != null && mouthMetrics.horizontalPx > 0) {
            val verticalToHorizontal = mouthMetrics.verticalCenterPx / mouthMetrics.horizontalPx
            if (verticalToHorizontal < minVerticalToHorizontalRatio) {
                // Geometry suggests mouth width is stretched but not deeply open vertically
                candidateStartMs = null
                return YawnResult(isYawning = false, currentDurationMs = 0L, totalCount = totalYawnCount)
            }
        }

        // --- Above threshold: start or continue the sustained timer ---
        if (mar >= yawnThreshold) {
            if (candidateStartMs == null) {
                candidateStartMs = timestampMs
            }
        } else {
            // Between hysteresis lower bound and threshold: don't advance timer
            // but don't reset either (keeps candidate window open)
        }

        val start = candidateStartMs
        if (start == null) {
            return YawnResult(isYawning = false, currentDurationMs = 0L, totalCount = totalYawnCount)
        }

        val duration = timestampMs - start
        val sustained = duration >= minSustainedDurationMs

        // Count one yawn per sustained event, with cooldown
        if (sustained) {
            val sinceLastYawn = if (lastYawnEndMs == Long.MIN_VALUE) Long.MAX_VALUE
            else timestampMs - lastYawnEndMs
            if (sinceLastYawn >= cooldownMs) {
                totalYawnCount++
                lastYawnEndMs = timestampMs
            }
        }

        return YawnResult(
            isYawning = sustained,
            currentDurationMs = duration,
            totalCount = totalYawnCount
        )
    }

    /** Convenience overload for callers that only have the MAR scalar (no pixel metrics). */
    fun update(mar: Double?, timestampMs: Long): YawnResult =
        update(mar = mar, timestampMs = timestampMs, mouthMetrics = null)

    fun reset() {
        candidateStartMs = null
        inCandidateWindow = false
        lastYawnEndMs = Long.MIN_VALUE
        totalYawnCount = 0
    }
}

data class YawnResult(
    val isYawning: Boolean,
    val currentDurationMs: Long,
    val totalCount: Int
)
