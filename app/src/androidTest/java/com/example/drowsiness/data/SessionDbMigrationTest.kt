package com.example.drowsiness.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionDbMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After fun cleanup() {
        context.deleteDatabase(SessionDbHelper.DATABASE_NAME)
    }

    @Test fun versionOneHistorySurvivesVersionTwoMigration() {
        val path = context.getDatabasePath(SessionDbHelper.DATABASE_NAME)
        path.parentFile?.mkdirs()
        val oldDb = SQLiteDatabase.openOrCreateDatabase(path, null)
        oldDb.execSQL("""
            CREATE TABLE driving_sessions (
                _id INTEGER PRIMARY KEY AUTOINCREMENT, start_time INTEGER NOT NULL,
                end_time INTEGER NOT NULL, duration_ms INTEGER NOT NULL,
                warning_count INTEGER NOT NULL, critical_count INTEGER NOT NULL,
                longest_closure_ms INTEGER NOT NULL, avg_perclos REAL NOT NULL,
                time_normal_ms INTEGER NOT NULL, time_warning_ms INTEGER NOT NULL,
                time_critical_ms INTEGER NOT NULL, time_face_lost_ms INTEGER NOT NULL,
                yawn_count INTEGER NOT NULL
            )
        """.trimIndent())
        val oldValues = ContentValues().apply {
            put("start_time", 10_000L); put("end_time", 20_000L); put("duration_ms", 10_000L)
            put("warning_count", 2); put("critical_count", 1); put("longest_closure_ms", 2_500L)
            put("avg_perclos", 0.25); put("time_normal_ms", 5_000L); put("time_warning_ms", 2_000L)
            put("time_critical_ms", 1_000L); put("time_face_lost_ms", 2_000L); put("yawn_count", 3)
        }
        assertTrue(oldDb.insertOrThrow(SessionDbHelper.TABLE_SESSIONS, null, oldValues) > 0L)
        oldDb.version = 1
        oldDb.close()

        val helper = SessionDbHelper(context)
        val cursor = helper.readableDatabase.query(SessionDbHelper.TABLE_SESSIONS, null, null, null, null, null, null)
        cursor.use {
            assertEquals(1, it.count)
            assertTrue(it.moveToFirst())
            assertEquals(1L, it.getLong(it.getColumnIndexOrThrow(SessionDbHelper.COL_ID)))
            assertEquals(10_000L, it.getLong(it.getColumnIndexOrThrow(SessionDbHelper.COL_START_TIME)))
            assertEquals(0.25, it.getDouble(it.getColumnIndexOrThrow(SessionDbHelper.COL_AVG_PERCLOS)), 0.0001)
            assertEquals(3, it.getInt(it.getColumnIndexOrThrow(SessionDbHelper.COL_YAWN_COUNT)))
            assertTrue(it.isNull(it.getColumnIndexOrThrow(SessionDbHelper.COL_CRITICAL_DROWSINESS_INCIDENTS)))
            assertTrue(it.isNull(it.getColumnIndexOrThrow(SessionDbHelper.COL_NOD_COUNT)))
            assertTrue(it.isNull(it.getColumnIndexOrThrow(SessionDbHelper.COL_CRITICAL_FACE_LOSS_INCIDENTS)))
        }
        helper.close()
    }
}
