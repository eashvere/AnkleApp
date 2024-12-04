package com.example.ankleapp.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.ankleapp.PostureNotificationManager
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

class AnkleDataRepository(context: Context) {
    // Core components for database and notifications management
    val dbHelper = AnkleDbHelper(context)
    private val sharedPreferences = context.getSharedPreferences("AnkleAppPrefs", Context.MODE_PRIVATE)
    private val notificationManager = PostureNotificationManager(context)

    // Event tracking state
    private var badPostureStartTime: Long? = null
    private var currentPostureType: String? = null
    private var wasWalkingAtStart: Boolean? = null

    // Listener for UI updates
    var statsUpdateListener: StatsUpdateListener? = null

    companion object {
        private const val INSTALL_TIME_KEY = "install_time"
        private const val DATA_SAMPLING_INTERVAL = 3L // Seconds between samples
    }

    // Record installation time
    init {
        if (!sharedPreferences.contains(INSTALL_TIME_KEY)) {
            sharedPreferences.edit().putLong(INSTALL_TIME_KEY, System.currentTimeMillis()).apply()
            Log.d("AnkleDataRepository", "Recorded initial installation time")
        }
    }

    // Data models for statistics
    data class DailyStats(
        val date: String,
        var totalWalkingTime: Long,
        var totalStaticTime: Long,
        var walkingBadPostureTime: Long,
        var staticBadPostureTime: Long,
        var leftEvents: Int,
        var rightEvents: Int,
        var frontEvents: Int
    ) {
        // Calculate total bad posture percentage
        fun getBadPosturePercentage(): Double {
            val totalTime = totalWalkingTime + totalStaticTime
            val totalBadTime = walkingBadPostureTime + staticBadPostureTime
            return if (totalTime > 0) {
                (totalBadTime.toDouble() / totalTime.toDouble()) * 100
            } else 0.0
        }
    }

    data class AggregatedStats(
        val periodType: String,
        val startDate: LocalDateTime,
        val endDate: LocalDateTime,
        val totalWalkingTime: Long,
        val totalStaticTime: Long,
        val walkingBadPosture: Long,
        val staticBadPosture: Long,
        val leftEvents: Int,
        val rightEvents: Int,
        val frontEvents: Int,
        val bestDay: String?,
        val worstDay: String?
    ) {
        fun getTotalTime() = totalWalkingTime + totalStaticTime
        fun getTotalBadPostureTime() = walkingBadPosture + staticBadPosture
        fun getBadPosturePercentage(): Double {
            val totalTime = getTotalTime()
            return if (totalTime > 0) {
                (getTotalBadPostureTime().toDouble() / totalTime.toDouble()) * 100
            } else 0.0
        }
    }

    data class DailyChartData(
        val day: String,
        val totalWalkingTime: Long,
        val walkingBadPosture: Long,
        val totalStaticTime: Long,
        val staticBadPosture: Long,
        val date: String  // Full date for reference
    )

    data class WeeklyChartData(
        val startDate: String,
        val endDate: String,
        val dailyData: List<DailyChartData>
    )

    data class MonthlyChartData(
        val month: String,
        val weeklyData: List<WeeklyChartData>
    )


    interface StatsUpdateListener {
        fun onStatsUpdated(dailyStats: DailyStats)
        fun onAggregatedStatsUpdated(aggregatedStats: AggregatedStats)
    }

    // Daily statistics management
    @SuppressLint("NewApi")
    fun updateWalkingTime() {
        val currentDate = LocalDateTime.now().toLocalDate().toString()
        val db = dbHelper.writableDatabase

        db.beginTransaction()
        try {
            ensureTodayRecord(db, currentDate)

            db.execSQL("""
                UPDATE ${AnkleDbHelper.TABLE_DAILY_STATS}
                SET ${AnkleDbHelper.DAILY_COLUMN_TOTAL_WALKING} = 
                    ${AnkleDbHelper.DAILY_COLUMN_TOTAL_WALKING} + 1
                WHERE ${AnkleDbHelper.DAILY_COLUMN_DATE} = ?
            """, arrayOf(currentDate))

            db.setTransactionSuccessful()
            statsUpdateListener?.onStatsUpdated(getDailyStats(LocalDateTime.now()))
        } finally {
            db.endTransaction()
        }
    }

    @SuppressLint("NewApi")
    fun updateStaticTime() {
        val currentDate = LocalDateTime.now().toLocalDate().toString()
        val db = dbHelper.writableDatabase

        db.beginTransaction()
        try {
            ensureTodayRecord(db, currentDate)

            db.execSQL("""
                UPDATE ${AnkleDbHelper.TABLE_DAILY_STATS}
                SET ${AnkleDbHelper.DAILY_COLUMN_TOTAL_STATIC} = 
                    ${AnkleDbHelper.DAILY_COLUMN_TOTAL_STATIC} + 1
                WHERE ${AnkleDbHelper.DAILY_COLUMN_DATE} = ?
            """, arrayOf(currentDate))

            db.setTransactionSuccessful()
            statsUpdateListener?.onStatsUpdated(getDailyStats(LocalDateTime.now()))
        } finally {
            db.endTransaction()
        }
    }

    // Handle posture events and notifications
    @SuppressLint("NewApi")
    fun handlePostureData(data: String, isWalking: Boolean) {
        val db = dbHelper.writableDatabase
        val currentDate = LocalDateTime.now().toLocalDate().toString()
        val currentTime = System.currentTimeMillis()

        when {
            data.startsWith("L") || data.startsWith("R") || data.startsWith("F") -> {
                badPostureStartTime = currentTime
                currentPostureType = data[0].toString()
                wasWalkingAtStart = isWalking
                notificationManager.showBadPostureNotification(data[0].toString())
            }
            data.startsWith("S") -> {
                badPostureStartTime?.let { startTime ->
                    val duration = (currentTime - startTime) / 1000
                    updatePostureEventWithDuration(
                        db, currentDate, currentPostureType ?: return,
                        wasWalkingAtStart ?: return, duration
                    )
                }
                badPostureStartTime = null
                currentPostureType = null
                wasWalkingAtStart = null
            }
        }
    }

    // Statistics retrieval methods
    @SuppressLint("NewApi")
    fun getDailyStats(date: LocalDateTime): DailyStats {
        val db = dbHelper.readableDatabase
        val dateStr = date.toLocalDate().toString()

        val cursor = db.query(
            AnkleDbHelper.TABLE_DAILY_STATS,
            null,
            "${AnkleDbHelper.DAILY_COLUMN_DATE} = ?",
            arrayOf(dateStr),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) {
                DailyStats(
                    date = dateStr,
                    totalWalkingTime = it.getLong(it.getColumnIndexOrThrow(
                        AnkleDbHelper.DAILY_COLUMN_TOTAL_WALKING)),
                    totalStaticTime = it.getLong(it.getColumnIndexOrThrow(
                        AnkleDbHelper.DAILY_COLUMN_TOTAL_STATIC)),
                    walkingBadPostureTime = it.getLong(it.getColumnIndexOrThrow(
                        AnkleDbHelper.DAILY_COLUMN_WALKING_BAD_POSTURE)),
                    staticBadPostureTime = it.getLong(it.getColumnIndexOrThrow(
                        AnkleDbHelper.DAILY_COLUMN_STATIC_BAD_POSTURE)),
                    leftEvents = it.getInt(it.getColumnIndexOrThrow(
                        AnkleDbHelper.DAILY_COLUMN_LEFT_EVENTS)),
                    rightEvents = it.getInt(it.getColumnIndexOrThrow(
                        AnkleDbHelper.DAILY_COLUMN_RIGHT_EVENTS)),
                    frontEvents = it.getInt(it.getColumnIndexOrThrow(
                        AnkleDbHelper.DAILY_COLUMN_FRONT_EVENTS))
                )
            } else {
                DailyStats(dateStr, 0, 0, 0, 0, 0, 0, 0)
            }
        }
    }

    // Aggregated statistics methods
    @SuppressLint("NewApi")
    fun getWeeklyStats(weekStart: LocalDateTime): AggregatedStats {
        val weekEnd = weekStart.plusDays(6)
        return getAggregatedStats(weekStart, weekEnd, "W")
    }

    @SuppressLint("NewApi")
    fun getMonthlyStats(monthStart: LocalDateTime): AggregatedStats {
        val monthEnd = monthStart.plusMonths(1).minusDays(1)
        return getAggregatedStats(monthStart, monthEnd, "M")
    }

    @SuppressLint("NewApi")
    private fun getAggregatedStats(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        periodType: String
    ): AggregatedStats {
        val db = dbHelper.readableDatabase

        // First check if we have cached aggregated stats
        val cachedStats = getCachedAggregatedStats(db, startDate, periodType)
        if (cachedStats != null) return cachedStats

        // If not cached, calculate from daily stats
        val stats = calculateAggregatedStats(db, startDate, endDate, periodType)
        cacheAggregatedStats(stats)
        return stats
    }

    // Helper methods
    @SuppressLint("NewApi")
    private fun updatePostureEventWithDuration(
        db: SQLiteDatabase,
        date: String,
        eventType: String,
        wasWalking: Boolean,
        duration: Long
    ) {
        val (eventColumn, timeColumn) = when (eventType) {
            "L" -> AnkleDbHelper.DAILY_COLUMN_LEFT_EVENTS to
                    (if (wasWalking) AnkleDbHelper.DAILY_COLUMN_WALKING_BAD_POSTURE
                    else AnkleDbHelper.DAILY_COLUMN_STATIC_BAD_POSTURE)
            "R" -> AnkleDbHelper.DAILY_COLUMN_RIGHT_EVENTS to
                    (if (wasWalking) AnkleDbHelper.DAILY_COLUMN_WALKING_BAD_POSTURE
                    else AnkleDbHelper.DAILY_COLUMN_STATIC_BAD_POSTURE)
            "F" -> AnkleDbHelper.DAILY_COLUMN_FRONT_EVENTS to
                    (if (wasWalking) AnkleDbHelper.DAILY_COLUMN_WALKING_BAD_POSTURE
                    else AnkleDbHelper.DAILY_COLUMN_STATIC_BAD_POSTURE)
            else -> return
        }

        db.execSQL("""
            UPDATE ${AnkleDbHelper.TABLE_DAILY_STATS}
            SET $eventColumn = $eventColumn + 1,
                $timeColumn = $timeColumn + ?
            WHERE ${AnkleDbHelper.DAILY_COLUMN_DATE} = ?
        """, arrayOf(duration, date))

        // Trigger aggregation update for the current period
        updateAggregatedStats(LocalDateTime.now())
    }

    private fun ensureTodayRecord(db: SQLiteDatabase, currentDate: String) {
        val cursor = db.query(
            AnkleDbHelper.TABLE_DAILY_STATS,
            null,
            "${AnkleDbHelper.DAILY_COLUMN_DATE} = ?",
            arrayOf(currentDate),
            null, null, null
        )

        if (!cursor.moveToFirst()) {
            val values = ContentValues().apply {
                put(AnkleDbHelper.DAILY_COLUMN_DATE, currentDate)
                put(AnkleDbHelper.DAILY_COLUMN_TOTAL_WALKING, 0L)
                put(AnkleDbHelper.DAILY_COLUMN_TOTAL_STATIC, 0L)
                put(AnkleDbHelper.DAILY_COLUMN_WALKING_BAD_POSTURE, 0L)
                put(AnkleDbHelper.DAILY_COLUMN_STATIC_BAD_POSTURE, 0L)
                put(AnkleDbHelper.DAILY_COLUMN_LEFT_EVENTS, 0)
                put(AnkleDbHelper.DAILY_COLUMN_RIGHT_EVENTS, 0)
                put(AnkleDbHelper.DAILY_COLUMN_FRONT_EVENTS, 0)
            }
            db.insert(AnkleDbHelper.TABLE_DAILY_STATS, null, values)
        }
        cursor.close()
    }

    @SuppressLint("NewApi")
    private fun updateAggregatedStats(currentDate: LocalDateTime) {
        // Get start of current week and month
        val weekFields = WeekFields.of(Locale.getDefault())
        val weekStart = currentDate.with(weekFields.dayOfWeek(), 1L)
        val monthStart = currentDate.withDayOfMonth(1)

        // Update both weekly and monthly aggregations
        getWeeklyStats(weekStart)
        getMonthlyStats(monthStart)
    }

    @SuppressLint("NewApi")
    private fun getCachedAggregatedStats(
        db: SQLiteDatabase,
        startDate: LocalDateTime,
        periodType: String
    ): AggregatedStats? {
        // Query the aggregated_stats table for cached data
        val cursor = db.query(
            AnkleDbHelper.TABLE_AGGREGATED_STATS,
            null,
            "${AnkleDbHelper.AGG_COLUMN_PERIOD_TYPE} = ? AND ${AnkleDbHelper.AGG_COLUMN_START_DATE} = ?",
            arrayOf(periodType, startDate.toLocalDate().toString()),
            null, null, null
        )

        return cursor.use {
            if (it.moveToFirst()) {
                AggregatedStats(
                    periodType = it.getString(it.getColumnIndexOrThrow(
                        AnkleDbHelper.AGG_COLUMN_PERIOD_TYPE)),
                    startDate = LocalDateTime.parse(
                        it.getString(it.getColumnIndexOrThrow(AnkleDbHelper.AGG_COLUMN_START_DATE))
                                + "T00:00:00"),
                    endDate = LocalDateTime.parse(
                        it.getString(it.getColumnIndexOrThrow(AnkleDbHelper.AGG_COLUMN_END_DATE))
                                + "T23:59:59"),
                    totalWalkingTime = it.getLong(it.getColumnIndexOrThrow(
                        AnkleDbHelper.AGG_COLUMN_TOTAL_WALKING)),
                    totalStaticTime = it.getLong(it.getColumnIndexOrThrow(
                        AnkleDbHelper.AGG_COLUMN_TOTAL_STATIC)),
                    walkingBadPosture = it.getLong(it.getColumnIndexOrThrow(
                        AnkleDbHelper.AGG_COLUMN_WALKING_BAD_POSTURE)),
                    staticBadPosture = it.getLong(it.getColumnIndexOrThrow(
                        AnkleDbHelper.AGG_COLUMN_STATIC_BAD_POSTURE)),
                    leftEvents = it.getInt(it.getColumnIndexOrThrow(
                        AnkleDbHelper.AGG_COLUMN_LEFT_EVENTS)),
                    rightEvents = it.getInt(it.getColumnIndexOrThrow(
                        AnkleDbHelper.AGG_COLUMN_RIGHT_EVENTS)),
                    frontEvents = it.getInt(it.getColumnIndexOrThrow(
                        AnkleDbHelper.AGG_COLUMN_FRONT_EVENTS)),
                    bestDay = it.getString(it.getColumnIndexOrThrow(
                        AnkleDbHelper.AGG_COLUMN_BEST_DAY)),
                    worstDay = it.getString(it.getColumnIndexOrThrow(
                        AnkleDbHelper.AGG_COLUMN_WORST_DAY))
                )
            } else null
        }
    }

    @SuppressLint("NewApi")
    private fun calculateAggregatedStats(
        db: SQLiteDatabase,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        periodType: String
    ): AggregatedStats {
        var totalWalkingTime = 0L
        var totalStaticTime = 0L
        var walkingBadPosture = 0L
        var staticBadPosture = 0L
        var leftEvents = 0
        var rightEvents = 0
        var frontEvents = 0
        var bestDay: Pair<String, Double>? = null
        var worstDay: Pair<String, Double>? = null

        // Query daily stats within the date range
        val cursor = db.query(
            AnkleDbHelper.TABLE_DAILY_STATS,
            null,
            "${AnkleDbHelper.DAILY_COLUMN_DATE} BETWEEN ? AND ?",
            arrayOf(startDate.toLocalDate().toString(), endDate.toLocalDate().toString()),
            null, null, AnkleDbHelper.DAILY_COLUMN_DATE
        )

        cursor.use {
            while (it.moveToNext()) {
                val date = it.getString(it.getColumnIndexOrThrow(AnkleDbHelper.DAILY_COLUMN_DATE))
                val walkingTime = it.getLong(it.getColumnIndexOrThrow(
                    AnkleDbHelper.DAILY_COLUMN_TOTAL_WALKING))
                val staticTime = it.getLong(it.getColumnIndexOrThrow(
                    AnkleDbHelper.DAILY_COLUMN_TOTAL_STATIC))
                val walkingBad = it.getLong(it.getColumnIndexOrThrow(
                    AnkleDbHelper.DAILY_COLUMN_WALKING_BAD_POSTURE))
                val staticBad = it.getLong(it.getColumnIndexOrThrow(
                    AnkleDbHelper.DAILY_COLUMN_STATIC_BAD_POSTURE))

                // Calculate bad posture percentage for this day
                val totalTime = walkingTime + staticTime
                val totalBadTime = walkingBad + staticBad
                val badPosturePercentage = if (totalTime > 0) {
                    (totalBadTime.toDouble() / totalTime.toDouble()) * 100
                } else 0.0

                // Update best/worst days
                if (bestDay == null || badPosturePercentage < bestDay!!.second) {
                    bestDay = date to badPosturePercentage
                }
                if (worstDay == null || badPosturePercentage > worstDay!!.second) {
                    worstDay = date to badPosturePercentage
                }

                // Accumulate totals
                totalWalkingTime += walkingTime
                totalStaticTime += staticTime
                walkingBadPosture += walkingBad
                staticBadPosture += staticBad
                leftEvents += it.getInt(it.getColumnIndexOrThrow(
                    AnkleDbHelper.DAILY_COLUMN_LEFT_EVENTS))
                rightEvents += it.getInt(it.getColumnIndexOrThrow(
                    AnkleDbHelper.DAILY_COLUMN_RIGHT_EVENTS))
                frontEvents += it.getInt(it.getColumnIndexOrThrow(
                    AnkleDbHelper.DAILY_COLUMN_FRONT_EVENTS))
            }
        }

        return AggregatedStats(
            periodType = periodType,
            startDate = startDate,
            endDate = endDate,
            totalWalkingTime = totalWalkingTime,
            totalStaticTime = totalStaticTime,
            walkingBadPosture = walkingBadPosture,
            staticBadPosture = staticBadPosture,
            leftEvents = leftEvents,
            rightEvents = rightEvents,
            frontEvents = frontEvents,
            bestDay = bestDay?.first,
            worstDay = worstDay?.first
        )
    }

    @SuppressLint("NewApi")
    private fun cacheAggregatedStats(stats: AggregatedStats) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(AnkleDbHelper.AGG_COLUMN_PERIOD_TYPE, stats.periodType)
            put(AnkleDbHelper.AGG_COLUMN_START_DATE, stats.startDate.toLocalDate().toString())
            put(AnkleDbHelper.AGG_COLUMN_END_DATE, stats.endDate.toLocalDate().toString())
            put(AnkleDbHelper.AGG_COLUMN_TOTAL_WALKING, stats.totalWalkingTime)
            put(AnkleDbHelper.AGG_COLUMN_TOTAL_STATIC, stats.totalStaticTime)
            put(AnkleDbHelper.AGG_COLUMN_WALKING_BAD_POSTURE, stats.walkingBadPosture)
            put(AnkleDbHelper.AGG_COLUMN_STATIC_BAD_POSTURE, stats.staticBadPosture)
            put(AnkleDbHelper.AGG_COLUMN_LEFT_EVENTS, stats.leftEvents)
            put(AnkleDbHelper.AGG_COLUMN_RIGHT_EVENTS, stats.rightEvents)
            put(AnkleDbHelper.AGG_COLUMN_FRONT_EVENTS, stats.frontEvents)
            put(AnkleDbHelper.AGG_COLUMN_BEST_DAY, stats.bestDay)
            put(AnkleDbHelper.AGG_COLUMN_WORST_DAY, stats.worstDay)
        }

        db.insertWithOnConflict(
            AnkleDbHelper.TABLE_AGGREGATED_STATS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @SuppressLint("NewApi")
    fun getWeeklyChartData(weekStart: LocalDateTime): WeeklyChartData {
        val dailyData = mutableListOf<DailyChartData>()
        val formatter = DateTimeFormatter.ofPattern("EEE") // Mon, Tue, etc.
        val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        // Get 7 days of data starting from weekStart
        for (i in 0..6) {
            val currentDate = weekStart.plusDays(i.toLong())
            val stats = getDailyStats(currentDate)

            dailyData.add(DailyChartData(
                day = currentDate.format(formatter),
                totalWalkingTime = stats.totalWalkingTime,
                walkingBadPosture = stats.walkingBadPostureTime,
                totalStaticTime = stats.totalStaticTime,
                staticBadPosture = stats.staticBadPostureTime,
                date = currentDate.format(dateFormatter)
            ))
        }

        return WeeklyChartData(
            startDate = weekStart.format(dateFormatter),
            endDate = weekStart.plusDays(6).format(dateFormatter),
            dailyData = dailyData
        )
    }

    @SuppressLint("NewApi")
    fun getMonthlyChartData(monthStart: LocalDateTime): MonthlyChartData {
        val weeklyData = mutableListOf<WeeklyChartData>()
        val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

        // Calculate the number of weeks in the month
        var currentWeekStart = monthStart
        val monthEnd = monthStart.plusMonths(1).minusDays(1)

        while (currentWeekStart.isBefore(monthEnd) || currentWeekStart.isEqual(monthEnd)) {
            weeklyData.add(getWeeklyChartData(currentWeekStart))
            currentWeekStart = currentWeekStart.plusDays(7)
        }

        return MonthlyChartData(
            month = monthStart.format(monthFormatter),
            weeklyData = weeklyData
        )
    }

    fun cleanup() {
        notificationManager.cancelAllNotifications()
        dbHelper.close()
    }
}