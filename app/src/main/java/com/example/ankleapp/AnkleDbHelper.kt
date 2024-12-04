package com.example.ankleapp.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class AnkleDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    companion object {
        // Database version and name
        const val DATABASE_NAME = "AnkleData.db"
        const val DATABASE_VERSION = 3  // Incremented for new aggregated stats table

        // Table names - each table has a distinct purpose
        const val TABLE_DAILY_STATS = "daily_stats"           // Stores individual day statistics
        const val TABLE_AGGREGATED_STATS = "aggregated_stats" // Stores weekly/monthly summaries

        // Daily stats table columns - tracking detailed daily metrics
        const val DAILY_COLUMN_DATE = "date"  // Primary key for daily records
        const val DAILY_COLUMN_TOTAL_WALKING = "total_walking_time"
        const val DAILY_COLUMN_TOTAL_STATIC = "total_static_time"
        const val DAILY_COLUMN_WALKING_BAD_POSTURE = "walking_bad_posture_time"
        const val DAILY_COLUMN_STATIC_BAD_POSTURE = "static_bad_posture_time"
        const val DAILY_COLUMN_LEFT_EVENTS = "left_events"
        const val DAILY_COLUMN_RIGHT_EVENTS = "right_events"
        const val DAILY_COLUMN_FRONT_EVENTS = "front_events"

        // Aggregated stats table columns - storing period summaries
        const val AGG_COLUMN_PERIOD_TYPE = "period_type"      // 'W' for weekly, 'M' for monthly
        const val AGG_COLUMN_START_DATE = "period_start_date" // Start of the period
        const val AGG_COLUMN_END_DATE = "period_end_date"     // End of the period
        const val AGG_COLUMN_TOTAL_WALKING = "total_walking_time"
        const val AGG_COLUMN_TOTAL_STATIC = "total_static_time"
        const val AGG_COLUMN_WALKING_BAD_POSTURE = "walking_bad_posture_time"
        const val AGG_COLUMN_STATIC_BAD_POSTURE = "static_bad_posture_time"
        const val AGG_COLUMN_LEFT_EVENTS = "left_events"
        const val AGG_COLUMN_RIGHT_EVENTS = "right_events"
        const val AGG_COLUMN_FRONT_EVENTS = "front_events"
        const val AGG_COLUMN_BEST_DAY = "best_day_date"      // Date with best posture
        const val AGG_COLUMN_WORST_DAY = "worst_day_date"    // Date with worst posture
    }

    override fun onCreate(db: SQLiteDatabase) {
        // Create daily stats table
        val createDailyStatsTable = """
            CREATE TABLE $TABLE_DAILY_STATS (
                $DAILY_COLUMN_DATE TEXT PRIMARY KEY,
                $DAILY_COLUMN_TOTAL_WALKING INTEGER NOT NULL DEFAULT 0,
                $DAILY_COLUMN_TOTAL_STATIC INTEGER NOT NULL DEFAULT 0,
                $DAILY_COLUMN_WALKING_BAD_POSTURE INTEGER NOT NULL DEFAULT 0,
                $DAILY_COLUMN_STATIC_BAD_POSTURE INTEGER NOT NULL DEFAULT 0,
                $DAILY_COLUMN_LEFT_EVENTS INTEGER NOT NULL DEFAULT 0,
                $DAILY_COLUMN_RIGHT_EVENTS INTEGER NOT NULL DEFAULT 0,
                $DAILY_COLUMN_FRONT_EVENTS INTEGER NOT NULL DEFAULT 0
            );
        """.trimIndent()

        // Create aggregated stats table
        val createAggregatedStatsTable = """
            CREATE TABLE $TABLE_AGGREGATED_STATS (
                $AGG_COLUMN_PERIOD_TYPE TEXT NOT NULL,
                $AGG_COLUMN_START_DATE TEXT NOT NULL,
                $AGG_COLUMN_END_DATE TEXT NOT NULL,
                $AGG_COLUMN_TOTAL_WALKING INTEGER NOT NULL DEFAULT 0,
                $AGG_COLUMN_TOTAL_STATIC INTEGER NOT NULL DEFAULT 0,
                $AGG_COLUMN_WALKING_BAD_POSTURE INTEGER NOT NULL DEFAULT 0,
                $AGG_COLUMN_STATIC_BAD_POSTURE INTEGER NOT NULL DEFAULT 0,
                $AGG_COLUMN_LEFT_EVENTS INTEGER NOT NULL DEFAULT 0,
                $AGG_COLUMN_RIGHT_EVENTS INTEGER NOT NULL DEFAULT 0,
                $AGG_COLUMN_FRONT_EVENTS INTEGER NOT NULL DEFAULT 0,
                $AGG_COLUMN_BEST_DAY TEXT,
                $AGG_COLUMN_WORST_DAY TEXT,
                PRIMARY KEY ($AGG_COLUMN_PERIOD_TYPE, $AGG_COLUMN_START_DATE)
            );
        """.trimIndent()

        try {
            db.beginTransaction()
            db.execSQL(createDailyStatsTable)
            db.execSQL(createAggregatedStatsTable)
            db.setTransactionSuccessful()
            Log.d("AnkleDbHelper", "Database tables created successfully")
        } catch (e: Exception) {
            Log.e("AnkleDbHelper", "Error creating tables: ${e.message}")
        } finally {
            db.endTransaction()
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Handle database upgrades - drop and recreate tables
        try {
            db.beginTransaction()
            // Drop existing tables
            db.execSQL("DROP TABLE IF EXISTS $TABLE_DAILY_STATS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_AGGREGATED_STATS")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        // Create new tables
        onCreate(db)
    }

    fun verifyAndUpdateDatabase() {
        val db = writableDatabase

        // Ensure tables exist with correct structure
        db.execSQL("DROP TABLE IF EXISTS $TABLE_DAILY_STATS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_AGGREGATED_STATS")

        onCreate(db)
    }
}