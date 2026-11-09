package com.example.drowsiness.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFinalizationGuardTest {
    @Test fun normalStopAndPipelineFailureCanOnlyPersistOnce() {
        for (reason in listOf("NORMAL_STOP", "PIPELINE_FAILURE")) {
            val guard = SessionFinalizationGuard()
            val persisted = mutableListOf<String>()
            guard.begin()
            assertTrue(guard.finalize(reason, 3_000L, persisted::add))
            assertFalse(guard.finalize(reason, 3_000L, persisted::add))
            assertEquals(listOf(reason), persisted)
        }
    }

    @Test fun sessionsAtOrBelowExistingMinimumDurationAreNotSaved() {
        val guard = SessionFinalizationGuard()
        var saved = false
        guard.begin()
        assertTrue(guard.finalize("NORMAL_STOP", 2_000L) { saved = true })
        assertFalse(saved)
        assertFalse(guard.isActive)
    }
}
