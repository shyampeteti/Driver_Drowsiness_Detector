package com.example.drowsiness.detection

import java.util.ArrayDeque
import kotlin.math.max

/**
 * Tracks eye closure duration, individual closure episodes, and rolling episode history.
 *
 * Implements anti-flicker hysteresis: brief micro-reopenings (< recoveryConfirmationMs)
 * do not immediately discard an ongoing closure event.
 */
class ClosureEpisodeTracker(
    val recoveryConfirmationMs: Long = 250L,
    val rollingHistoryWindowMs: Long = 45_000L,
    val prolongedEpisodeThresholdMs: Long = 500L
) {
    private var closureStartMs: Long? = null
    private var lastClosedObservationMs: Long? = null
    private var firstOpenObservationMs: Long? = null
    private var currentDurationMs: Long = 0L

    var longestClosureMs: Long = 0L
        private set

    private val episodesHistory = ArrayDeque<ClosureEpisode>()

    /**
     * Updates the closure tracking state.
     * @param isClosed true if eyes are currently considered closed
     * @param timestampMs monotonic timestamp in milliseconds
     * @return pair of (current continuous closure duration in ms, completed episode if one just concluded)
     */
    fun update(isClosed: Boolean, timestampMs: Long): Pair<Long, ClosureEpisode?> {
        pruneOldEpisodes(timestampMs)
        var concludedEpisode: ClosureEpisode? = null

        if (isClosed) {
            // Eyes are closed. Cancel any pending recovery/open confirmation.
            firstOpenObservationMs = null
            if (closureStartMs == null) {
                closureStartMs = timestampMs
            }
            lastClosedObservationMs = timestampMs
            currentDurationMs = max(0L, timestampMs - closureStartMs!!)
            if (currentDurationMs > longestClosureMs) {
                longestClosureMs = currentDurationMs
            }
        } else {
            // Eyes observed open.
            if (closureStartMs != null) {
                if (firstOpenObservationMs == null) {
                    firstOpenObservationMs = timestampMs
                }

                // Check if eyes have remained open for long enough to confirm recovery
                val openDuration = timestampMs - firstOpenObservationMs!!
                if (openDuration >= recoveryConfirmationMs) {
                    // Officially conclude the closure episode
                    val episodeStart = closureStartMs!!
                    val episodeEnd = lastClosedObservationMs ?: firstOpenObservationMs!!
                    val duration = max(0L, episodeEnd - episodeStart)

                    if (duration > 0) {
                        val category = DurationCategory.fromDuration(duration)
                        val episode = ClosureEpisode(episodeStart, episodeEnd, duration, category)
                        episodesHistory.addLast(episode)
                        concludedEpisode = episode
                    }

                    closureStartMs = null
                    lastClosedObservationMs = null
                    firstOpenObservationMs = null
                    currentDurationMs = 0L
                } else {
                    // Still in the anti-flicker confirmation window: retain currentDuration
                    // but don't increase it while open
                }
            } else {
                currentDurationMs = 0L
            }
        }

        return Pair(currentDurationMs, concludedEpisode)
    }

    /**
     * Number of prolonged closure episodes (> 500 ms) in the rolling history window.
     */
    fun countProlongedClosures(nowMs: Long): Int {
        pruneOldEpisodes(nowMs)
        return episodesHistory.count { it.durationMs >= prolongedEpisodeThresholdMs }
    }

    /**
     * Average duration of recent closure episodes in the rolling history window.
     */
    fun averageEpisodeDurationMs(nowMs: Long): Double {
        pruneOldEpisodes(nowMs)
        if (episodesHistory.isEmpty()) return 0.0
        return episodesHistory.map { it.durationMs }.average()
    }

    /**
     * Returns true if repeated prolonged closures have been detected (e.g. >= 2 in recent window).
     */
    fun hasRepeatedProlongedClosures(nowMs: Long, minCount: Int = 2): Boolean {
        return countProlongedClosures(nowMs) >= minCount
    }

    /**
     * Returns a copy of recent closure episodes.
     */
    fun getRecentEpisodes(nowMs: Long): List<ClosureEpisode> {
        pruneOldEpisodes(nowMs)
        return episodesHistory.toList()
    }

    private fun pruneOldEpisodes(nowMs: Long) {
        while (episodesHistory.isNotEmpty() && nowMs - episodesHistory.first.endTimeMs > rollingHistoryWindowMs) {
            episodesHistory.removeFirst()
        }
    }

    fun reset() {
        closureStartMs = null
        lastClosedObservationMs = null
        firstOpenObservationMs = null
        currentDurationMs = 0L
        longestClosureMs = 0L
        episodesHistory.clear()
    }

    /** Ends an in-progress episode at its last observed closed frame, retaining completed history. */
    fun suspendAtLastObservation(): ClosureEpisode? {
        val start = closureStartMs ?: return null
        val end = lastClosedObservationMs ?: start
        val duration = max(0L, end - start)
        val episode = if (duration > 0L) ClosureEpisode(start, end, duration, DurationCategory.fromDuration(duration)) else null
        if (episode != null) episodesHistory.addLast(episode)
        closureStartMs = null
        lastClosedObservationMs = null
        firstOpenObservationMs = null
        currentDurationMs = 0L
        return episode
    }
}
