package com.example.ankleapp.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Represents a walking session
data class WalkingSession(
    val id: Long,
    val sessionStart: LocalDateTime,
    val sessionEnd: LocalDateTime? = null,
    val totalDuration: Long? = null // in seconds
)

// Represents a posture event
data class PostureEvent(
    val id: Long,
    val sessionId: Long,
    val eventType: String, // 'L', 'R', or 'F'
    val eventStart: LocalDateTime,
    val eventEnd: LocalDateTime? = null,
    val duration: Long? = null // in seconds
)

// Represents daily statistics
data class DailyStats(
    val date: String, // Storing as "YYYY-MM-DD" for compatibility with the database
    var totalWalkingTime: Long = 0, // Total walking time in seconds
    var totalBadPostureTime: Long = 0, // Total time spent in bad posture in seconds
    var leftSideEvents: Int = 0, // Count of left-leaning posture events
    var rightSideEvents: Int = 0, // Count of right-leaning posture events
    var frontEvents: Int = 0, // Count of forward-leaning posture events
    var averageEventDuration: Double = 0.0 // Average duration of bad posture events in seconds
) {
    // Returns the total number of posture events
    fun totalEvents(): Int {
        return leftSideEvents + rightSideEvents + frontEvents
    }

    // Calculates the percentage of walking time spent in bad posture
    fun badPosturePercentage(): Double {
        return if (totalWalkingTime > 0) {
            (totalBadPostureTime.toDouble() / totalWalkingTime) * 100
        } else {
            0.0
        }
    }

    // Determines the dominant direction of bad posture
    fun dominantDirection(): String? {
        val counts = mapOf(
            "Left" to leftSideEvents,
            "Right" to rightSideEvents,
            "Front" to frontEvents
        )
        return counts.maxByOrNull { it.value }?.key
    }

    // Formats the date as a `LocalDateTime`
    fun getDateAsLocalDateTime(): LocalDateTime {
        return LocalDateTime.parse(date, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
    }
}

// Represents monthly aggregated statistics
data class MonthlyStats(
    val year: Int,
    val month: Int,
    val totalWalkingTime: Long = 0, // Aggregated total walking time in seconds
    val totalBadPostureTime: Long = 0, // Aggregated total bad posture time in seconds
    val dailyStats: List<DailyStats> = emptyList(), // List of daily stats
    val averageWalkingTimePerDay: Long = 0, // Average walking time per day
    val averageBadPostureTimePerDay: Long = 0, // Average bad posture time per day
    val totalLeftEvents: Int = 0, // Total left posture events for the month
    val totalRightEvents: Int = 0, // Total right posture events for the month
    val totalFrontEvents: Int = 0 // Total front posture events for the month
) {
    // Returns the day with the worst bad posture percentage
    fun worstDay(): DailyStats? {
        return dailyStats.maxByOrNull { it.badPosturePercentage() }
    }

    // Returns the day with the best bad posture percentage
    fun bestDay(): DailyStats? {
        return dailyStats.minByOrNull { it.badPosturePercentage() }
    }

    // Calculates the trend of bad posture percentages
    fun getTrend(): String {
        val percentages = dailyStats.map { it.badPosturePercentage() }
        if (percentages.size < 2) return "Stable"

        val firstHalf = percentages.take(percentages.size / 2).average()
        val secondHalf = percentages.drop(percentages.size / 2).average()

        return when {
            secondHalf > firstHalf -> "Worsening"
            secondHalf < firstHalf -> "Improving"
            else -> "Stable"
        }
    }
}