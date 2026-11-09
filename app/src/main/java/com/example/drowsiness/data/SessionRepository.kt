package com.example.drowsiness.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Clean SQLite local database for driving session analytics.
 */
class SessionDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "driver_safety_sessions.db"
        const val DATABASE_VERSION = 2

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
        const val COL_NOD_COUNT = "nod_count"
        const val COL_CRITICAL_DROWSINESS_INCIDENTS = "critical_drowsiness_incidents"
        const val COL_CRITICAL_FACE_LOSS_INCIDENTS = "critical_face_loss_incidents"
        const val COL_TERMINATION_REASON = "termination_reason"
        const val COL_TIME_WEIGHTED_AVG_PERCLOS = "time_weighted_avg_perclos"
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSessionsTable(db, TABLE_SESSIONS)
    }

    private fun createSessionsTable(db: SQLiteDatabase, tableName: String) {
        val createSql = """
            CREATE TABLE $tableName (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_START_TIME INTEGER NOT NULL,
                $COL_END_TIME INTEGER NOT NULL,
                $COL_DURATION INTEGER NOT NULL,
                $COL_WARNING_COUNT INTEGER NOT NULL,
                $COL_CRITICAL_COUNT INTEGER NOT NULL,
                $COL_LONGEST_CLOSURE INTEGER NOT NULL,
                $COL_AVG_PERCLOS REAL,
                $COL_TIME_NORMAL INTEGER NOT NULL,
                $COL_TIME_WARNING INTEGER NOT NULL,
                $COL_TIME_CRITICAL INTEGER NOT NULL,
                $COL_TIME_FACE_LOST INTEGER NOT NULL,
                $COL_YAWN_COUNT INTEGER NOT NULL,
                $COL_NOD_COUNT INTEGER,
                $COL_CRITICAL_DROWSINESS_INCIDENTS INTEGER,
                $COL_CRITICAL_FACE_LOSS_INCIDENTS INTEGER,
                $COL_TERMINATION_REASON TEXT,
                $COL_TIME_WEIGHTED_AVG_PERCLOS REAL
            )
        """.trimIndent()
        db.execSQL(createSql)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val migratedTable = "${TABLE_SESSIONS}_v2"
            createSessionsTable(db, migratedTable)
            val preservedColumns = listOf(
                COL_ID, COL_START_TIME, COL_END_TIME, COL_DURATION, COL_WARNING_COUNT,
                COL_CRITICAL_COUNT, COL_LONGEST_CLOSURE, COL_AVG_PERCLOS,
                COL_TIME_NORMAL, COL_TIME_WARNING, COL_TIME_CRITICAL,
                COL_TIME_FACE_LOST, COL_YAWN_COUNT
            ).joinToString(",")
            db.execSQL("INSERT INTO $migratedTable ($preservedColumns) SELECT $preservedColumns FROM $TABLE_SESSIONS")
            db.execSQL("DROP TABLE $TABLE_SESSIONS")
            db.execSQL("ALTER TABLE $migratedTable RENAME TO $TABLE_SESSIONS")
        }
    }
}

/**
 * Repository providing asynchronous CRUD operations for driving sessions.
 */
class SessionRepository(context: Context) {
    private val dbHelper = SessionDbHelper(context.applicationContext)
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val taskLock = Any()

    fun insertSession(session: DrivingSession, onComplete: ((Long) -> Unit)? = null, onError: ((Exception) -> Unit)? = null) {
        enqueue {
            try {
                val values = ContentValues().apply {
                    put(SessionDbHelper.COL_START_TIME, session.startTimeMs)
                    put(SessionDbHelper.COL_END_TIME, session.endTimeMs)
                    put(SessionDbHelper.COL_DURATION, session.durationMs)
                    put(SessionDbHelper.COL_WARNING_COUNT, session.warningEventsCount)
                    put(SessionDbHelper.COL_CRITICAL_COUNT, session.criticalEventsCount)
                    put(SessionDbHelper.COL_LONGEST_CLOSURE, session.longestClosureMs)
                    session.averagePerclos?.let { put(SessionDbHelper.COL_AVG_PERCLOS, it) }
                    put(SessionDbHelper.COL_TIME_NORMAL, session.timeNormalMs)
                    put(SessionDbHelper.COL_TIME_WARNING, session.timeWarningMs)
                    put(SessionDbHelper.COL_TIME_CRITICAL, session.timeCriticalMs)
                    put(SessionDbHelper.COL_TIME_FACE_LOST, session.timeFaceLostMs)
                    put(SessionDbHelper.COL_YAWN_COUNT, session.yawnCount)
                    session.nodCount?.let { put(SessionDbHelper.COL_NOD_COUNT, it) }
                    session.criticalDrowsinessIncidentCount?.let { put(SessionDbHelper.COL_CRITICAL_DROWSINESS_INCIDENTS, it) }
                    session.criticalFaceLossIncidentCount?.let { put(SessionDbHelper.COL_CRITICAL_FACE_LOSS_INCIDENTS, it) }
                    session.terminationReason?.let { put(SessionDbHelper.COL_TERMINATION_REASON, it) }
                    session.timeWeightedAveragePerclos?.let { put(SessionDbHelper.COL_TIME_WEIGHTED_AVG_PERCLOS, it) }
                }
                val id = dbHelper.writableDatabase.insertOrThrow(SessionDbHelper.TABLE_SESSIONS, null, values)
                onComplete?.let { callback -> mainHandler.post { if (!closed.get()) callback(id) } }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert driving session", e)
                onError?.let { callback -> mainHandler.post { if (!closed.get()) callback(e) } }
            }
        }
    }

    fun getAllSessions(onResult: (List<DrivingSession>) -> Unit, onError: ((Exception) -> Unit)? = null) {
        enqueue {
            try {
                val sessions = mutableListOf<DrivingSession>()
                val cursor = dbHelper.readableDatabase.query(
                    SessionDbHelper.TABLE_SESSIONS, null, null, null, null, null,
                    "${SessionDbHelper.COL_START_TIME} DESC"
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        sessions.add(DrivingSession(
                            id = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_ID)),
                            startTimeMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_START_TIME)),
                            endTimeMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_END_TIME)),
                            durationMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_DURATION)),
                            warningEventsCount = c.getInt(c.getColumnIndexOrThrow(SessionDbHelper.COL_WARNING_COUNT)),
                            criticalEventsCount = c.getInt(c.getColumnIndexOrThrow(SessionDbHelper.COL_CRITICAL_COUNT)),
                            longestClosureMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_LONGEST_CLOSURE)),
                            averagePerclos = c.getColumnIndexOrThrow(SessionDbHelper.COL_AVG_PERCLOS).let { if (c.isNull(it)) null else c.getDouble(it) },
                            timeNormalMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_TIME_NORMAL)),
                            timeWarningMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_TIME_WARNING)),
                            timeCriticalMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_TIME_CRITICAL)),
                            timeFaceLostMs = c.getLong(c.getColumnIndexOrThrow(SessionDbHelper.COL_TIME_FACE_LOST)),
                            yawnCount = c.getInt(c.getColumnIndexOrThrow(SessionDbHelper.COL_YAWN_COUNT)),
                            nodCount = c.getColumnIndexOrThrow(SessionDbHelper.COL_NOD_COUNT).let { if (c.isNull(it)) null else c.getInt(it) },
                            criticalDrowsinessIncidentCount = c.getColumnIndexOrThrow(SessionDbHelper.COL_CRITICAL_DROWSINESS_INCIDENTS).let { if (c.isNull(it)) null else c.getInt(it) },
                            criticalFaceLossIncidentCount = c.getColumnIndexOrThrow(SessionDbHelper.COL_CRITICAL_FACE_LOSS_INCIDENTS).let { if (c.isNull(it)) null else c.getInt(it) },
                            terminationReason = c.getColumnIndexOrThrow(SessionDbHelper.COL_TERMINATION_REASON).let { if (c.isNull(it)) null else c.getString(it) },
                            timeWeightedAveragePerclos = c.getColumnIndexOrThrow(SessionDbHelper.COL_TIME_WEIGHTED_AVG_PERCLOS).let { if (c.isNull(it)) null else c.getDouble(it) }
                        ))
                    }
                }
                mainHandler.post { if (!closed.get()) onResult(sessions) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load driving sessions", e)
                onError?.let { callback -> mainHandler.post { if (!closed.get()) callback(e) } }
            }
        }
    }

    fun clearAllSessions(onComplete: (() -> Unit)? = null, onError: ((Exception) -> Unit)? = null) {
        enqueue {
            try {
                dbHelper.writableDatabase.delete(SessionDbHelper.TABLE_SESSIONS, null, null)
                onComplete?.let { callback -> mainHandler.post { if (!closed.get()) callback() } }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear driving sessions", e)
                onError?.let { callback -> mainHandler.post { if (!closed.get()) callback(e) } }
            }
        }
    }

    fun close() {
        synchronized(taskLock) {
            if (closed.compareAndSet(false, true)) {
                // Queue database closure behind any outstanding database work, then reject new work.
                executor.execute { dbHelper.close() }
                executor.shutdown()
                mainHandler.removeCallbacksAndMessages(null)
            }
        }
    }

    private fun enqueue(task: () -> Unit) {
        synchronized(taskLock) {
            if (!closed.get()) executor.execute(task)
        }
    }

    private companion object {
        const val TAG = "SessionRepository"
    }
}
