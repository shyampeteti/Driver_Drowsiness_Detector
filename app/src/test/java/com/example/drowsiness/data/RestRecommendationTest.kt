package com.example.drowsiness.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestRecommendationTest {
    private fun session(
        start: Long,
        drowsinessIncidents: Int?,
        faceLossIncidents: Int = 0
    ) = DrivingSession(
        startTimeMs = start,
        endTimeMs = start + 60_000L,
        durationMs = 60_000L,
        warningEventsCount = 0,
        criticalEventsCount = 0,
        longestClosureMs = 0L,
        averagePerclos = null,
        timeNormalMs = 60_000L,
        timeWarningMs = 0L,
        timeCriticalMs = 0L,
        timeFaceLostMs = 0L,
        criticalDrowsinessIncidentCount = drowsinessIncidents,
        criticalFaceLossIncidentCount = faceLossIncidents
    )

    @Test fun weeklyRecommendationBeginsAtThreeRecentDrowsinessIncidents() {
        val now = 10L * RestRecommendation.WINDOW_MS
        assertFalse(RestRecommendation.shouldRecommend(listOf(session(now - 1_000L, 1), session(now - 2_000L, 1)), now))
        assertTrue(RestRecommendation.shouldRecommend(listOf(session(now - 1_000L, 1), session(now - 2_000L, 2)), now))
    }

    @Test fun twoIncidentsInOneSessionMeetSecondRule() {
        val now = 10L * RestRecommendation.WINDOW_MS
        assertTrue(RestRecommendation.shouldRecommend(listOf(session(now, 2)), now))
    }

    @Test fun sevenDayBoundaryIsIncluded() {
        val now = 10L * RestRecommendation.WINDOW_MS
        assertTrue(RestRecommendation.shouldRecommend(
            listOf(session(now - RestRecommendation.WINDOW_MS, 3)), now
        ))
    }

    @Test fun rollingWindowExcludesOldAndFutureAndFaceLossCounts() {
        val now = 10L * RestRecommendation.WINDOW_MS
        val sessions = listOf(
            session(now - RestRecommendation.WINDOW_MS - 1L, 3),
            session(now + 1L, 3),
            session(now - 1_000L, 0, faceLossIncidents = 10),
            session(now - 2_000L, null)
        )
        assertFalse(RestRecommendation.shouldRecommend(sessions, now))
    }
}
