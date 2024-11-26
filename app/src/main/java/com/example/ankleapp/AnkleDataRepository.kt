package com.example.ankleapp.data

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.example.ankleapp.PostureNotificationManager
import java.time.LocalDateTime
import java.time.ZoneOffset

class AnkleDataRepository(context: Context) {
    private val dbHelper = AnkleDbHelper(context)
    var currentSessionId: Long? = null
    private var walkingStartTime: LocalDateTime? = null
    private val activePostureEvents = mutableMapOf<String, PostureEvent>()

    //track overlapping time periods
    private val validDirections = setOf("L", "R", "F")
    private var overlappingStartTime: LocalDateTime? = null
    private var activeDirections = mutableSetOf<String>()

    //Notifications
    private val notificationManager = PostureNotificationManager(context)

    @SuppressLint("NewApi")
    fun startWalkingSession(): Long {
        val now = LocalDateTime.now()
        walkingStartTime = now

        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(AnkleDbHelper.COLUMN_SESSION_START, now.toEpochSecond(ZoneOffset.UTC))
        }

        val id = db.insert(AnkleDbHelper.TABLE_WALKING_SESSIONS, null, values)
        currentSessionId = id
        Log.d("AnkleDataRepository", "Started walking session at $now")
        return id
    }

    fun handlePostureData(data: String) {
        val cleanData = data.trim('[', ']')
        Log.d("AnkleDataRepository", "Handling posture data: $cleanData")

        if (currentSessionId == null) {
            Log.d("AnkleDataRepository", "No active session")
            return
        }

        val parts = cleanData.split(",")
        if (parts.isEmpty()) return

        val eventType = parts[0]
        Log.d("AnkleDataRepository", "Event type: $eventType")

        when {
            // Start of bad posture events
            eventType in validDirections -> {
                Log.d("AnkleDataRepository", "Starting new posture event: $eventType")
                handleStartEvent(eventType)
            }
            // End of bad posture events
            eventType.startsWith("S") -> {
                val direction = eventType.substring(1)
                if (direction in validDirections) {
                    handleStopEvent(direction)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun handleStartEvent(direction: String) {
        if (!validDirections.contains(direction)) {
            Log.d("AnkleDataRepository", "Invalid direction: $direction")
            return
        }

        val sessionId = currentSessionId ?: return
        val now = LocalDateTime.now()

        // If this direction is already active, ignore the new start
        if (activeDirections.contains(direction)) {
            Log.d("AnkleDataRepository", "Direction $direction is already active")
            return
        }

        notificationManager.showBadPostureNotification(direction)

        // Add to active directions and update overlapping time tracking
        activeDirections.add(direction)
        if (overlappingStartTime == null) {
            overlappingStartTime = now
        }

        // Create or update the posture event
        activePostureEvents[direction] = PostureEvent(
            sessionId = sessionId,
            eventType = direction,
            eventStart = now
        )

        Log.d("AnkleDataRepository", """
            Started posture event:
            Direction: $direction
            Time: $now
            Active Directions: ${activeDirections.joinToString()}
        """.trimIndent())
    }

    @SuppressLint("NewApi")
    private fun handleStopEvent(direction: String) {
        if (!validDirections.contains(direction)) {
            Log.d("AnkleDataRepository", "Invalid direction: $direction")
            return
        }

        val now = LocalDateTime.now()

        // If we received a stop signal without a start
        if (!activeDirections.contains(direction)) {
            Log.d("AnkleDataRepository", "Received stop signal without start for direction: $direction")
            walkingStartTime?.let { startTime ->
                handleStartEvent(direction)
            }
        }

        // Record the event
        val event = activePostureEvents[direction]
        if (event != null) {
            savePostureEvent(event, now)
            activePostureEvents.remove(direction)
        }

        // Update active directions and overlap tracking
        activeDirections.remove(direction)
        if (activeDirections.isEmpty()) {
            overlappingStartTime = null
        }

        Log.d("AnkleDataRepository", """
            Ended posture event:
            Direction: $direction
            Time: $now
            Remaining Active Directions: ${activeDirections.joinToString()}
        """.trimIndent())
    }

    @SuppressLint("NewApi")
    private fun savePostureEvent(event: PostureEvent, endTime: LocalDateTime) {
        val duration = endTime.toEpochSecond(ZoneOffset.UTC) -
                event.eventStart.toEpochSecond(ZoneOffset.UTC)

        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(AnkleDbHelper.COLUMN_SESSION_ID, event.sessionId)
            put(AnkleDbHelper.COLUMN_EVENT_TYPE, event.eventType)
            put(AnkleDbHelper.COLUMN_EVENT_START, event.eventStart.toEpochSecond(ZoneOffset.UTC))
            put(AnkleDbHelper.COLUMN_EVENT_END, endTime.toEpochSecond(ZoneOffset.UTC))
            put(AnkleDbHelper.COLUMN_EVENT_DURATION, duration)
        }

        try {
            db.insert(AnkleDbHelper.TABLE_POSTURE_EVENTS, null, values)
            Log.d("AnkleDataRepository", "Saved event: ${event.eventType}, Duration: $duration seconds")
        } catch (e: Exception) {
            Log.e("AnkleDataRepository", "Error saving event: ${e.message}")
        }
    }

    @SuppressLint("NewApi")
    fun endWalkingSession(sessionId: Long) {
        val now = LocalDateTime.now()
        Log.d("AnkleDataRepository", "Ending walking session. Active directions: ${activeDirections.joinToString()}")

        // Handle any pending events that didn't receive stop signals
        activeDirections.forEach { direction ->
            Log.d("AnkleDataRepository", "Found pending event for direction: $direction")
            val event = activePostureEvents[direction]
            if (event != null) {
                Log.d("AnkleDataRepository", """
                Ending pending event due to walking stop:
                Direction: $direction
                Start time: ${event.eventStart}
                End time: $now
            """.trimIndent())

                savePostureEvent(event, now)  // Use current time as stop time
            }
        }

        // Clear all tracking variables
        activePostureEvents.clear()
        activeDirections.clear()
        overlappingStartTime = null

        // Update walking session in database
        val db = dbHelper.writableDatabase
        val cursor = db.query(
            AnkleDbHelper.TABLE_WALKING_SESSIONS,
            arrayOf(AnkleDbHelper.COLUMN_SESSION_START),
            "${AnkleDbHelper.COLUMN_ID} = ?",
            arrayOf(sessionId.toString()),
            null, null, null
        )

        if (cursor.moveToFirst()) {
            val startTime = cursor.getLong(0)
            val duration = now.toEpochSecond(ZoneOffset.UTC) - startTime

            val values = ContentValues().apply {
                put(AnkleDbHelper.COLUMN_SESSION_END, now.toEpochSecond(ZoneOffset.UTC))
                put(AnkleDbHelper.COLUMN_TOTAL_DURATION, duration)
            }

            db.update(
                AnkleDbHelper.TABLE_WALKING_SESSIONS,
                values,
                "${AnkleDbHelper.COLUMN_ID} = ?",
                arrayOf(sessionId.toString())
            )
        }
        cursor.close()

        // Reset session tracking
        currentSessionId = null
        walkingStartTime = null

        Log.d("AnkleDataRepository", "Walking session fully ended at $now")
    }

    @SuppressLint("NewApi")
    fun getDailyStats(date: LocalDateTime): DailyStats {
        val db = dbHelper.readableDatabase
        val startOfDay = date.toLocalDate().atStartOfDay()
        val endOfDay = startOfDay.plusDays(1)

        val startTimestamp = startOfDay.toEpochSecond(ZoneOffset.UTC)
        val endTimestamp = endOfDay.toEpochSecond(ZoneOffset.UTC)

        // Get total walking time
        var totalWalkingTime = 0L
        db.rawQuery("""
            SELECT COALESCE(SUM(${AnkleDbHelper.COLUMN_TOTAL_DURATION}), 0)
            FROM ${AnkleDbHelper.TABLE_WALKING_SESSIONS}
            WHERE ${AnkleDbHelper.COLUMN_SESSION_START} >= ? 
            AND ${AnkleDbHelper.COLUMN_SESSION_START} < ?
        """, arrayOf(startTimestamp.toString(), endTimestamp.toString())).use { cursor ->
            if (cursor.moveToFirst()) {
                totalWalkingTime = cursor.getLong(0)
            }
        }

        // Get bad posture events with proper overlap handling
        val events = mutableListOf<Pair<Long, Long>>() // List of start and end times
        db.rawQuery("""
            SELECT 
                ${AnkleDbHelper.COLUMN_EVENT_START},
                ${AnkleDbHelper.COLUMN_EVENT_END}
            FROM ${AnkleDbHelper.TABLE_POSTURE_EVENTS} e
            JOIN ${AnkleDbHelper.TABLE_WALKING_SESSIONS} s 
                ON e.${AnkleDbHelper.COLUMN_SESSION_ID} = s.${AnkleDbHelper.COLUMN_ID}
            WHERE s.${AnkleDbHelper.COLUMN_SESSION_START} >= ? 
            AND s.${AnkleDbHelper.COLUMN_SESSION_START} < ?
            ORDER BY ${AnkleDbHelper.COLUMN_EVENT_START}
        """, arrayOf(startTimestamp.toString(), endTimestamp.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                events.add(Pair(cursor.getLong(0), cursor.getLong(1)))
            }
        }

        // Calculate total bad posture time accounting for overlaps
        val mergedIntervals = mergeTimeIntervals(events)
        val totalBadPostureTime = mergedIntervals.sumOf { it.second - it.first }

        // Get event counts by direction
        val eventCounts = mutableMapOf<String, Int>()
        db.rawQuery("""
            SELECT 
                ${AnkleDbHelper.COLUMN_EVENT_TYPE},
                COUNT(*) as count
            FROM ${AnkleDbHelper.TABLE_POSTURE_EVENTS} e
            JOIN ${AnkleDbHelper.TABLE_WALKING_SESSIONS} s 
                ON e.${AnkleDbHelper.COLUMN_SESSION_ID} = s.${AnkleDbHelper.COLUMN_ID}
            WHERE s.${AnkleDbHelper.COLUMN_SESSION_START} >= ? 
            AND s.${AnkleDbHelper.COLUMN_SESSION_START} < ?
            GROUP BY ${AnkleDbHelper.COLUMN_EVENT_TYPE}
        """, arrayOf(startTimestamp.toString(), endTimestamp.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                eventCounts[cursor.getString(0)] = cursor.getInt(1)
            }
        }

        return DailyStats(
            date = date,
            totalWalkingTime = totalWalkingTime,
            totalBadPostureTime = totalBadPostureTime,
            leftSideEvents = eventCounts.getOrDefault("L", 0),
            rightSideEvents = eventCounts.getOrDefault("R", 0),
            frontEvents = eventCounts.getOrDefault("F", 0),
            averageEventDuration = if (events.isNotEmpty())
                totalBadPostureTime.toDouble() / events.size
            else 0.0
        )
    }

    private fun mergeTimeIntervals(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
        if (intervals.isEmpty()) return emptyList()

        val sorted = intervals.sortedBy { it.first }
        val merged = mutableListOf<Pair<Long, Long>>()
        var current = sorted[0]

        for (i in 1 until sorted.size) {
            if (current.second >= sorted[i].first) {
                // Intervals overlap, merge them
                current = Pair(current.first, maxOf(current.second, sorted[i].second))
            } else {
                // No overlap, add current interval and start new one
                merged.add(current)
                current = sorted[i]
            }
        }
        merged.add(current)
        return merged
    }
}