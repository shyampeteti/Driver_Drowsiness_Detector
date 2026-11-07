package com.example.drowsiness.detection

import kotlin.math.min
import kotlin.math.max

/**
 * Weighted adaptive drowsiness scoring engine.
 *
 * Integrates:
 * 1. Continuous eye-closure duration (Weight: 40%)
 * 2. Rolling PERCLOS (Weight: 25%)
 * 3. Frequency of prolonged closures (Weight: 20%)
 * 4. Average closure episode duration (Weight: 15%)
 * Plus modular yawning and head pose contributions.
 *
 * Produces a composite score (0.0 to 100.0) and manages stable state transitions with hysteresis.
 */
class DrowsinessScoringEngine(
    val sustainedWeight: Double = 0.40,
    val perclosWeight: Double = 0.25,
    val prolongedFrequencyWeight: Double = 0.20,
    val episodeAvgWeight: Double = 0.15,
    val drowsyScoreThreshold: Double = 40.0,
    val criticalScoreThreshold: Double = 70.0,
    val recoveryConfirmationDurationMs: Long = 1_500L
) {
    init {
        val totalWeight = sustainedWeight + perclosWeight + prolongedFrequencyWeight + episodeAvgWeight
        require(totalWeight > 0.99 && totalWeight < 1.01) { "Weights must sum to 1.0" }
    }

    private var currentState = DetectionState.MONITORING
    private var lastScore: Double = 0.0
    private var recoveryStartMs: Long? = null

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
     */
    fun evaluate(
        closureDurationMs: Long,
        perclos: Double?,
        prolongedCount: Int,
        avgEpisodeDurationMs: Double,
        isYawning: Boolean,
        isNodding: Boolean,
        isEyeClosed: Boolean,
        timestampMs: Long
    ): ScoreBreakdown {
        // 1. Continuous sustained closure score (0 to 100)
        // 0-200ms -> 0; 200-500ms -> 20; 500-1000ms -> 50; 1000-2000ms -> 85; >=2000ms -> 100
        val sustainedScore = when {
            closureDurationMs >= 2000L -> 100.0
            closureDurationMs >= 1000L -> 70.0 + (closureDurationMs - 1000L) * 0.03
            closureDurationMs >= 500L -> 40.0 + (closureDurationMs - 500L) * 0.06
            closureDurationMs >= 200L -> 10.0 + (closureDurationMs - 200L) * 0.10
            else -> 0.0
        }

        // 2. PERCLOS score (0 to 100)
        // Normal PERCLOS < 0.15 -> 0-20; 0.15-0.30 -> 20-50; > 0.40 -> 80-100
        val p = perclos ?: 0.0
        val perclosScore = min(100.0, max(0.0, (p / 0.40) * 100.0))

        // 3. Frequency of prolonged closures score (0 to 100)
        // 0 -> 0; 1 -> 35; 2 -> 70; >= 3 -> 100
        val freqScore = min(100.0, prolongedCount * 35.0)

        // 4. Episode average duration score (0 to 100)
        // Typical blink is ~150ms. If average duration > 400ms, sign of micro-sleeps.
        val avgScore = min(100.0, max(0.0, ((avgEpisodeDurationMs - 150.0) / 450.0) * 100.0))

        // Optional modular bonuses
        val yawnBonus = if (isYawning) 15.0 else 0.0
        val nodBonus = if (isNodding) 20.0 else 0.0

        // Base weighted sum
        var composite = (sustainedScore * sustainedWeight) +
                (perclosScore * perclosWeight) +
                (freqScore * prolongedFrequencyWeight) +
                (avgScore * episodeAvgWeight) +
                yawnBonus + nodBonus

        // High priority rule: continuous closure over 2000ms ALWAYS forces a critical score
        if (closureDurationMs >= 2000L) {
            composite = max(composite, 85.0)
        } else if (closureDurationMs >= 1000L) {
            composite = max(composite, 55.0)
        }

        val finalScore = min(100.0, max(0.0, composite))
        lastScore = finalScore

        // State Machine with Hysteresis
        val nextCandidateState = when {
            finalScore >= criticalScoreThreshold || closureDurationMs >= 2000L -> DetectionState.CRITICAL
            finalScore >= drowsyScoreThreshold || closureDurationMs >= 1000L || prolongedCount >= 2 -> DetectionState.DROWSY
            else -> DetectionState.MONITORING
        }

        currentState = applyHysteresis(currentState, nextCandidateState, isEyeClosed, timestampMs)

        return ScoreBreakdown(
            totalScore = finalScore,
            sustainedClosureScore = sustainedScore,
            perclosScore = perclosScore,
            prolongedFrequencyScore = freqScore,
            episodeAvgScore = avgScore,
            yawnBonus = yawnBonus,
            nodBonus = nodBonus,
            state = currentState
        )
    }

    private fun applyHysteresis(
        current: DetectionState,
        candidate: DetectionState,
        isEyeClosed: Boolean,
        timestampMs: Long
    ): DetectionState {
        // Immediate escalation: WARNING or CRITICAL triggers without delay
        if (candidate == DetectionState.CRITICAL && current != DetectionState.CRITICAL) {
            recoveryStartMs = null
            return DetectionState.CRITICAL
        }
        if (candidate == DetectionState.DROWSY && current == DetectionState.MONITORING) {
            recoveryStartMs = null
            return DetectionState.DROWSY
        }

        // Downgrade / Recovery protection: do not drop immediately on a single open frame
        if (candidate.ordinal < current.ordinal) {
            if (isEyeClosed) {
                // If eyes are closed, do not begin recovery
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
    }
}
