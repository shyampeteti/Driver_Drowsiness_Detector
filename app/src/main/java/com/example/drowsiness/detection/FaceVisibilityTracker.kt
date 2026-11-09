package com.example.drowsiness.detection

/** Timestamp-driven face lock, loss grace period, and stable reacquisition tracker. */
class FaceVisibilityTracker(
    private val lockObservationCount: Int = 3,
    private val lockDurationMs: Long = 500L,
    private val lossGracePeriodMs: Long = 1_500L,
    private val recoveryObservationCount: Int = 3,
    private val recoveryDurationMs: Long = 500L,
    private val maxObservationGapMs: Long = 600L
) {
    data class Result(
        val isFaceLocked: Boolean,
        val isFaceVisible: Boolean,
        val isReacquiring: Boolean,
        val isFaceLossCritical: Boolean,
        val faceLossIncidentActivated: Boolean,
        val faceLossRecovered: Boolean,
        val faceLossIncidentCount: Int
    )

    private var lastTimestampMs: Long? = null
    private var candidateStartMs: Long? = null
    private var candidateCount = 0
    private var faceLocked = false
    private var faceVisible = false
    private var missingSinceMs: Long? = null
    private var recoveryStartMs: Long? = null
    private var recoveryCount = 0
    private var lossCritical = false
    private var incidentCount = 0

    /** Total face-loss incidents activated during the current monitoring session. */
    val faceLossIncidentCount: Int
        get() = incidentCount
    val isFaceVisible: Boolean
        get() = faceVisible
    val isFaceLocked: Boolean
        get() = faceLocked

    fun observe(faceVisible: Boolean, timestampMs: Long): Result {
        val previous = lastTimestampMs
        if (timestampMs < 0L || (previous != null && timestampMs <= previous)) return result(false, false)
        val observationGap = previous?.let { timestampMs - it }
        if (observationGap != null && observationGap > maxObservationGapMs) {
            resetCandidate()
            recoveryStartMs = null
            recoveryCount = 0
        }
        lastTimestampMs = timestampMs

        if (!faceVisible) {
            this.faceVisible = false
            if (!faceLocked) {
                resetCandidate()
                return result(false, false)
            }
            if (missingSinceMs == null) missingSinceMs = timestampMs
            recoveryStartMs = null
            recoveryCount = 0
            val activate = !lossCritical && timestampMs - missingSinceMs!! >= lossGracePeriodMs
            if (activate) {
                lossCritical = true
                incidentCount++
            }
            return result(activate, false)
        }

        this.faceVisible = true
        if (!faceLocked) {
            addCandidate(timestampMs)
            if (candidateCount >= lockObservationCount && timestampMs - candidateStartMs!! >= lockDurationMs) {
                faceLocked = true
                resetCandidate()
            }
            return result(false, false)
        }

        if (missingSinceMs == null) return result(false, false)
        val lossDurationMs = timestampMs - missingSinceMs!!
        val activate = !lossCritical && lossDurationMs >= lossGracePeriodMs
        if (activate) {
            lossCritical = true
            incidentCount++
        }
        addRecoveryCandidate(timestampMs)
        if (recoveryCount >= recoveryObservationCount && timestampMs - recoveryStartMs!! >= recoveryDurationMs) {
            missingSinceMs = null
            recoveryStartMs = null
            recoveryCount = 0
            val recovered = lossCritical
            lossCritical = false
            return result(activate, recovered)
        }
        return result(activate, false)
    }

    fun reset() {
        lastTimestampMs = null
        candidateStartMs = null
        candidateCount = 0
        faceLocked = false
        faceVisible = false
        missingSinceMs = null
        recoveryStartMs = null
        recoveryCount = 0
        lossCritical = false
        incidentCount = 0
    }

    private fun addCandidate(timestampMs: Long) {
        if (candidateStartMs == null) candidateStartMs = timestampMs
        candidateCount++
    }

    private fun addRecoveryCandidate(timestampMs: Long) {
        if (recoveryStartMs == null) recoveryStartMs = timestampMs
        recoveryCount++
    }

    private fun resetCandidate() {
        candidateStartMs = null
        candidateCount = 0
    }

    private fun result(activated: Boolean, recovered: Boolean) = Result(
        isFaceLocked = faceLocked,
        isFaceVisible = faceVisible,
        isReacquiring = faceLocked && missingSinceMs != null,
        isFaceLossCritical = lossCritical,
        faceLossIncidentActivated = activated,
        faceLossRecovered = recovered,
        faceLossIncidentCount = incidentCount
    )
}
