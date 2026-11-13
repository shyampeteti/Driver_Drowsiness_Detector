package com.example.drowsiness.data

object RestRecommendation {
    const val WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
    fun shouldRecommend(sessions: List<DrivingSession>, nowMs: Long): Boolean {
        val startOfWindow = nowMs - WINDOW_MS
        val recentWithKnownCounts = sessions.filter {
            it.startTimeMs in startOfWindow..nowMs && it.criticalDrowsinessIncidentCount != null
        }
        val weeklyCount = recentWithKnownCounts.sumOf { it.criticalDrowsinessIncidentCount ?: 0 }
        val repeatedInOneSession = recentWithKnownCounts.any { (it.criticalDrowsinessIncidentCount ?: 0) >= 2 }
        return weeklyCount >= 3 || repeatedInOneSession
    }
}
