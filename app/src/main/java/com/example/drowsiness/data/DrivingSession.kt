package com.example.drowsiness.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Record of a completed driver monitoring session.
 * Contains aggregate telemetry and safety statistics.
 * Explicitly does not store images or raw video frames.
 */
data class DrivingSession(
    val id: Long = 0L,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val durationMs: Long,
    val warningEventsCount: Int,
    val criticalEventsCount: Int,
    val longestClosureMs: Long,
    /** Legacy stored PERCLOS field; older values are preserved but not presented as time-weighted averages. */
    val averagePerclos: Double?,
    val timeNormalMs: Long,
    val timeWarningMs: Long,
    val timeCriticalMs: Long,
    val timeFaceLostMs: Long,
    val yawnCount: Int = 0,
    val nodCount: Int? = null,
    /** Null for rows saved before activation-based drowsiness incident tracking existed. */
    val criticalDrowsinessIncidentCount: Int? = null,
    val criticalFaceLossIncidentCount: Int? = null,
    val terminationReason: String? = null,
    val timeWeightedAveragePerclos: Double? = null
) {
    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("MMM dd, yyyy · hh:mm a", Locale.getDefault())
            return sdf.format(Date(startTimeMs))
        }

    val formattedDuration: String
        get() {
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / (1000 * 60)) % 60
            val hours = durationMs / (1000 * 60 * 60)
            return when {
                hours > 0 -> String.format(Locale.getDefault(), "%dh %02dm %02ds", hours, minutes, seconds)
                minutes > 0 -> String.format(Locale.getDefault(), "%dm %02ds", minutes, seconds)
                else -> String.format(Locale.getDefault(), "%ds", seconds)
            }
        }

    val safetyStatus: String
        get() = when {
            criticalEventsCount > 0 -> "Critical Risk"
            warningEventsCount > 2 -> "High Drowsiness"
            warningEventsCount > 0 -> "Mild Drowsiness"
            else -> "Attentive & Safe"
        }

    val safetyColor: Int
        get() = when {
            criticalEventsCount > 0 -> 0xFFD32F2F.toInt() // Red
            warningEventsCount > 0 -> 0xFFF57C00.toInt() // Amber
            else -> 0xFF2E7D32.toInt() // Green
        }
}
