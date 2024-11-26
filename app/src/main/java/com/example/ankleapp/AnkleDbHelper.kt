package com.example.ankleapp.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.ankleapp.data.AnkleDbHelper.Companion.COLUMN_EVENT_DURATION
import com.example.ankleapp.data.AnkleDbHelper.Companion.COLUMN_EVENT_TYPE
import com.example.ankleapp.data.AnkleDbHelper.Companion.COLUMN_ID
import com.example.ankleapp.data.AnkleDbHelper.Companion.COLUMN_SESSION_ID
import com.example.ankleapp.data.AnkleDbHelper.Companion.COLUMN_SESSION_START
import com.example.ankleapp.data.AnkleDbHelper.Companion.COLUMN_TOTAL_DURATION
import java.time.LocalDateTime
import java.time.ZoneOffset

// Database Helper
class AnkleDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        const val DATABASE_NAME = "AnkleData.db"
        const val DATABASE_VERSION = 1

        // Table names
        const val TABLE_WALKING_SESSIONS = "walking_sessions"
        const val TABLE_POSTURE_EVENTS = "posture_events"

        // Common columns
        const val COLUMN_ID = "id"
        const val COLUMN_TIMESTAMP = "timestamp"

        // Walking sessions columns
        const val COLUMN_SESSION_START = "session_start"
        const val COLUMN_SESSION_END = "session_end"
        const val COLUMN_TOTAL_DURATION = "total_duration" // in seconds

        // Posture events columns
        const val COLUMN_EVENT_TYPE = "event_type" // 'L' or 'R' for left/right
        const val COLUMN_EVENT_START = "event_start"
        const val COLUMN_EVENT_END = "event_end"
        const val COLUMN_EVENT_DURATION = "event_duration" // in seconds
        const val COLUMN_SESSION_ID = "session_id"
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Create walking sessions table
        val createWalkingSessionsTable = """
            CREATE TABLE $TABLE_WALKING_SESSIONS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SESSION_START INTEGER NOT NULL,
                $COLUMN_SESSION_END INTEGER,
                $COLUMN_TOTAL_DURATION INTEGER
            )
        """.trimIndent()

        // Create posture events table
        val createPostureEventsTable = """
            CREATE TABLE $TABLE_POSTURE_EVENTS (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SESSION_ID INTEGER NOT NULL,
                $COLUMN_EVENT_TYPE TEXT NOT NULL,
                $COLUMN_EVENT_START INTEGER NOT NULL,
                $COLUMN_EVENT_END INTEGER,
                $COLUMN_EVENT_DURATION INTEGER,
                FOREIGN KEY ($COLUMN_SESSION_ID) REFERENCES $TABLE_WALKING_SESSIONS($COLUMN_ID)
            )
        """.trimIndent()

        db.execSQL(createWalkingSessionsTable)
        db.execSQL(createPostureEventsTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_POSTURE_EVENTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_WALKING_SESSIONS")
        onCreate(db)
    }
}
