package com.example.drowsiness.data

/** Ensures a monitoring run is persisted at most once while retaining the existing duration policy. */
class SessionFinalizationGuard(private val minimumDurationMs: Long = 2_000L) {
    var isActive: Boolean = false
        private set

    fun begin() {
        isActive = true
    }

    /** Returns false for duplicate finalization; eligible sessions invoke [persist] exactly once. */
    fun finalize(reason: String, durationMs: Long, persist: (String) -> Unit): Boolean {
        if (!isActive) return false
        isActive = false
        if (durationMs > minimumDurationMs) persist(reason)
        return true
    }
}
