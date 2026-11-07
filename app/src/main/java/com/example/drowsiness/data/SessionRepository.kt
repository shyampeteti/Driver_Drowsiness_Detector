package com.example.drowsiness.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.concurrent.Executors

/**
 * Clean SQLite local database for driving session analytics.
 */
class SessionDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "driver_safety_sessions.db"
        const val DATABASE_VERSION = 1

        const val TABLE_SESSIONS = "driving_sessions"
        const val COL_ID = "_id"
        const val COL_START_TIME = "start_time"
        const val COL_END_TIME = "end_time"
        const val COL_DURATION = "duration_ms"
        const val COL_WARNING_COUNT = "warning_count"
        const val COL_CRITICAL_COUNT = "critical_count"
        const val COL_LONGEST_CLOSURE = "longest_closure_ms"
        const val COL_AVG_PERCLOS = "avg_perclos"
        const val COL_TIME_NORMAL = "time_normal_ms"
        const val COL_TIME_WARNING = "time_warning_ms"
        const val COL_TIME_CRITICAL = "time_critical_ms"
        const val COL_TIME_FACE_LOST = "time_face_lost_ms"
        const val COL_YAWN_COUNT = "yawn_count"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createSql = """
            CREATE TABLE $TABLE_SESSIONS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_START_TIME INTEGER NOT NULL,
                $COL_END_TIME INTEGER NOT NULL,
                $COL_DURATION INTEGER NOT NULL,
                $COL_WARNING_COUNT INTEGER NOT NULL,
                $COL_CRITICAL_COUNT INTEGER NOT NULL,
                $COL_LONGEST_CLOSURE INTEGER NOT NULL,
                $COL_AVG_PERCLOS REAL NOT NULL,
                $COL_TIME_NORMAL INTEGER NOT NULL,
                $COL_TIME_WARNING INTEGER NOT NULL,
                $COL_TIME_CRITICAL INTEGER NOT NULL,
                $COL_TIME_FACE_LOST INTEGER NOT NULL,
                $COL_YAWN_COUNT INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createSql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SESSIONS")
        onCreate(db)
    }
}

/**
 * Repository providing asynchronous CRUD operations for driving sessions.
 */
class SessionRepository(context: Context) {
    private val dbHelper = SessionDbHelper(context)
    private val executor = Executors.newSingleThreadExecutor()

    fun insertSession(session: DrivingSession, onComplete: ((Long) -> Unit)? = null) {
        executor.execute {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put(SessionDbHelper.COL_START_TIME, session.startTimeMs)
                put(SessionDbHelper.COL_END_TIME, session.endTimeMs)
                put(SessionDbHelper.COL_DURATION, session.durationMs)
                put(SessionDbHelper.COL_WARNING_COUNT, session.warningEventsCount)
                put(SessionDbHelper.COL_CRITICAL_COUNT, session.criticalEventsCount)
                put(SessionDbHelper.COL_LONGEST_CLOSURE, session.longestClosureMs)
                put(SessionDbHelper.COL_AVG_PERCLOS, session.averagePerclos)
                put(SessionDbHelper.COL_TIME_NORMAL, session.timeNormalMs)
                put(SessionDbHelper.COL_TIME_WARNING, session.timeWarningMs)
                put(SessionDbHelper.COL_TIME_CRITICAL, session.timeCriticalMs)
                put(SessionDbHelper.COL_TIME_FACE_LOST, session.timeFaceLostMs)
                put(SessionDbHelper.COL_YAWN_COUNT, session.yawnCount)
            }
            val id = db.insert(SessionDbHelper.TABLE_SESSIONS, null, values)
            onComplete?.invoke(id)
        }
    }

    fun getAllSessions(onResult: (List<DrivingSession>) -> Unit) {
        executor.execute {
            val sessions = mutableListOf<DrivingSession>()
            val db = dbHelper.readableDatabase
            val cursor = db.query(
                SessionDbHelper.TABLE_SESSIONS,
                null,
                null,
                null,
                null,
                null,
                "${SessionDbHelper.COL_START_TIME} DESC"
            )
            cursor.use { c ->
                while (c.moveToNext()) {
                    sessions.add(
                        DrivingSession(
                            id = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_ID)),
                            startTimeMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_START_TIME)),
                            endTimeMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_END_TIME)),
                            durationMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_DURATION)),
                            warningEventsCount = c.getInt(c.getColumnIndexOrThrow(SessionDbHelper.COL_WARNING_COUNT)),
                            criticalEventsCount = c.getInt(c.getColumnIndexOrThrow(SessionDbHelper.COL_CRITICAL_COUNT)),
                            longestClosureMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_LONGEST_CLOSURE)),
                            averagePerclos = c.getDouble(c.getColumnIndexOrThrow(SessionDbHelper.COL_AVG_PERCLOS)),
                            timeNormalMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_TIME_NORMAL)),
                            timeWarningMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_TIME_WARNING)),
                            timeCriticalMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_TIME_CRITICAL)),
                            timeFaceLostMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_TIME_FACE_LOST)),
                            yawnCount = c.getInt(c.getColumnIndexOrThrow(SessionDbHelper.COL_YAWN_COUNT))
                        )
                    )
                }
            }
            onResult(sessions)
        }
    }

    fun clearAllSessions(onComplete: (() -> Unit)? = null) {
        executor.execute {
            val db = dbHelper.writableDatabase
            db.delete(SessionDbHelper.TABLE_SESSIONS, null, null)
            onComplete?.invoke()
        }
    }
}
