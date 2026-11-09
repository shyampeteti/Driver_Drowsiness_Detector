package com.example.drowsiness.detection

import kotlin.math.min
import kotlin.math.max

/**
 * Weighted adaptive drowsiness scoring engine.
 *
 * Score range: 0.0 – 100.0
 * Status thresholds (configurable):
 *   NORMAL   : 0 – 24
 *   WARNING  : 25 – 49
 *   DROWSY   : 50 – 74
 *   CRITICAL : 75 – 100
 *
 * Key design principles:
 * - A single normal blink (<= 200 ms) must NOT meaningfully raise the score.
 * - Yawn and nod bonuses are **event-based** (applied once per confirmed event,
 *   not added every frame), so the score does not inflate continuously.
 * - Composite score is clamped to [0, 100] before state decisions.
 * - State transitions use hysteresis: escalation is immediate; recovery
 *   requires [recoveryConfirmationDurationMs] of open-eye observation.
 * - A sustained closure >= 2000 ms can independently trigger CRITICAL
 *   even if the aggregate score is below the critical threshold.
 * - PERCLOS alone does not trigger CRITICAL; it only contributes to the score.
 */
class DrowsinessScoringEngine(
    val sustainedWeight: Double = 0.40,
    val perclosWeight: Double = 0.25,
    val prolongedFrequencyWeight: Double = 0.20,
    val episodeAvgWeight: Double = 0.15,
    // Status thresholds (score out of 100)
    val normalThreshold: Double = 25.0,
    val warningThreshold: Double = 50.0,
    val criticalScoreThreshold: Double = 75.0,
    // Legacy alias kept for compatibility
    val drowsyScoreThreshold: Double = 50.0,
    val recoveryConfirmationDurationMs: Long = 1_500L
) {
    init {
        val totalWeight = sustainedWeight + perclosWeight + prolongedFrequencyWeight + episodeAvgWeight
        require(totalWeight > 0.99 && totalWeight < 1.01) { "Weights must sum to 1.0" }
    }

    private var currentState = DetectionState.MONITORING
    private var lastScore: Double = 0.0
    private var recoveryStartMs: Long? = null

    // Event-based bonus tracking: bonuses are applied only once per confirmed event
    // to prevent per-frame score inflation.
    // Initialized to 0 (not -1) so that the first evaluate() call with yawnEventCount=0
    // does not spuriously apply a bonus.
    private var lastYawnBonusAppliedAtCount: Int = 0
    private var pendingYawnBonus: Double = 0.0
    private var lastNodBonusAppliedAtCount: Int = 0
    private var pendingNodBonus: Double = 0.0

    data class ScoreBreakdown(
        val totalScore: Double,
        val sustainedClosureScore: Double,
        val perclosScore: Double,
        val prolongedFrequencyScore: Double,
        val episodeAvgScore: Double,
        val yawnBonus: Double,
        val nodBonus: Double,
        val state: DetectionState
    )

    /**
     * Computes the composite score and updates detection state with hysteresis.
     *
     * @param yawnEventCount Total confirmed yawn count (from [YawnTracker]). The engine
     *   applies a one-time bonus whenever this count increases — not every frame.
     * @param nodEventCount  Total confirmed nod count (from [HeadPoseEstimator]).
     */
    fun evaluate(
        closureDurationMs: Long,
        perclos: Double?,
        prolongedCount: Int,
        avgEpisodeDurationMs: Double,
        isYawning: Boolean,
        isNodding: Boolean,
        isEyeClosed: Boolean,
        timestampMs: Long,
        yawnEventCount: Int = if (isYawning) 1 else 0,
        nodEventCount: Int = if (isNodding) 1 else 0
    ): ScoreBreakdown {

        // ── 1. Sustained eye-closure component (0 – 100) ─────────────────────
        // Maps closure duration to a score with steep rises only for genuinely
        // prolonged closures. Normal blinks (< 200 ms) → 0 score.
        val sustainedScore = when {
            closureDurationMs >= 2000L -> 100.0
            closureDurationMs >= 1000L -> 70.0 + (closureDurationMs - 1000L) * 0.030
            closureDurationMs >= 500L  -> 35.0 + (closureDurationMs - 500L)  * 0.070
            closureDurationMs >= 200L  ->  5.0 + (closureDurationMs - 200L)  * 0.100
            else                       ->  0.0
        }

        // ── 2. PERCLOS component (0 – 100) ───────────────────────────────────
        // Normal waking PERCLOS is < 8%. 40%+ is clinically significant.
        // We scale linearly so 40%+ saturates to 100.
        val p = perclos ?: 0.0
        val perclosScore = min(100.0, max(0.0, (p / 0.40) * 100.0))

        // ── 3. Prolonged episode frequency (0 – 100) ─────────────────────────
        // 0 prolonged closures → 0; 1 → 30; 2 → 65; 3+ → 100
        val freqScore: Double = when {
            prolongedCount >= 3 -> 100.0
            prolongedCount == 2 -> 65.0
            prolongedCount == 1 -> 30.0
            else                ->  0.0
        }

        // ── 4. Episode average duration (0 – 100) ────────────────────────────
        // Typical alert blink: ~150 ms. Drowsy micro-sleeps: 400+ ms.
        // We only count episodes, not individual open-eye frames, so the history
        // naturally doesn't inflate from normal open periods.
        val avgScore = min(100.0, max(0.0, ((avgEpisodeDurationMs - 150.0) / 450.0) * 100.0))

        // ── 5. Event-based bonuses ────────────────────────────────────────────
        // Apply a bonus ONCE when a new yawn or nod event is confirmed.
        // Guard: yawnEventCount must be > 0 to avoid spurious bonus on first frame.
        // We ADD to the pending bonus (capped) so overlapping events don't lose signal.
        if (yawnEventCount > 0 && yawnEventCount > lastYawnBonusAppliedAtCount) {
            pendingYawnBonus = minOf(pendingYawnBonus + 12.0, 25.0)
            lastYawnBonusAppliedAtCount = yawnEventCount
        }
        if (nodEventCount > 0 && nodEventCount > lastNodBonusAppliedAtCount) {
            pendingNodBonus = minOf(pendingNodBonus + 15.0, 30.0)
            lastNodBonusAppliedAtCount = nodEventCount
        }
        // Decay bonuses over time (they fade out in subsequent frames)
        pendingYawnBonus = max(0.0, pendingYawnBonus * 0.85)
        pendingNodBonus  = max(0.0, pendingNodBonus  * 0.85)

        // ── 6. Composite weighted sum ─────────────────────────────────────────
        var composite = (sustainedScore * sustainedWeight) +
                (perclosScore * perclosWeight) +
                (freqScore * prolongedFrequencyWeight) +
                (avgScore * episodeAvgWeight) +
                pendingYawnBonus + pendingNodBonus

        // ── 7. High-priority override for sustained closures ─────────────────
        // A continuous closure over 2000 ms is an independent safety condition.
        // It can directly force a critical score, even if aggregate evidence is low.
        // This is intentional: a single very long closure IS critical regardless of
        // rolling PERCLOS (which needs a 5-second window to build up).
        if (closureDurationMs >= 2000L) {
            composite = max(composite, 80.0)
        } else if (closureDurationMs >= 1000L) {
            composite = max(composite, 52.0)
        }

        val finalScore = min(100.0, max(0.0, composite))
        lastScore = finalScore

        // ── 8. State transitions with hysteresis ─────────────────────────────
        val nextCandidateState = when {
            finalScore >= criticalScoreThreshold || closureDurationMs >= 2000L -> DetectionState.CRITICAL
            finalScore >= drowsyScoreThreshold   || closureDurationMs >= 1000L -> DetectionState.DROWSY
            else                                                                -> DetectionState.MONITORING
        }

        currentState = applyHysteresis(currentState, nextCandidateState, isEyeClosed, timestampMs)

        return ScoreBreakdown(
            totalScore = finalScore,
            sustainedClosureScore = sustainedScore,
            perclosScore = perclosScore,
            prolongedFrequencyScore = freqScore,
            episodeAvgScore = avgScore,
            yawnBonus = pendingYawnBonus,
            nodBonus = pendingNodBonus,
            state = currentState
        )
    }

    private fun applyHysteresis(
        current: DetectionState,
        candidate: DetectionState,
        isEyeClosed: Boolean,
        timestampMs: Long
    ): DetectionState {
        // Immediate escalation to higher severity
        if (candidate == DetectionState.CRITICAL && current != DetectionState.CRITICAL) {
            recoveryStartMs = null
            return DetectionState.CRITICAL
        }
        if (candidate == DetectionState.DROWSY && current == DetectionState.MONITORING) {
            recoveryStartMs = null
            return DetectionState.DROWSY
        }

        // Downgrade / Recovery protection: require sustained open-eye observation
        if (candidate.ordinal < current.ordinal) {
            if (isEyeClosed) {
                recoveryStartMs = null
                return current
            }

            if (recoveryStartMs == null) {
                recoveryStartMs = timestampMs
            }

            val recoveryElapsed = timestampMs - recoveryStartMs!!
            return if (recoveryElapsed >= recoveryConfirmationDurationMs) {
                recoveryStartMs = null
                candidate
            } else {
                current
            }
        } else {
            recoveryStartMs = null
            return candidate
        }
    }

    fun reset() {
        currentState = DetectionState.MONITORING
        lastScore = 0.0
        recoveryStartMs = null
        lastYawnBonusAppliedAtCount = 0
        pendingYawnBonus = 0.0
        lastNodBonusAppliedAtCount = 0
        pendingNodBonus = 0.0
    }
}
