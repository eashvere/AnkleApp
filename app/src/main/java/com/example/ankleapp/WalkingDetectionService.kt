package com.example.ankleapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.ankleapp.data.AnkleDataRepository
import com.example.ankleapp.data.AnkleDbHelper
import kotlinx.coroutines.*
import java.time.LocalDateTime
import java.time.temporal.WeekFields
import java.util.Locale

class WalkingDetectionService : Service(), WalkingListener, BleManager.ConnectionListener {
    // Core components for monitoring and data management
    private lateinit var walkingDetector: WalkingDetector
    private lateinit var bleManager: BleManager
    private lateinit var dataRepository: AnkleDataRepository

    // Coroutine management
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timeTrackingJob: Job? = null
    private var aggregationJob: Job? = null

    // Wake lock for keeping service active
    private var wakeLock: PowerManager.WakeLock? = null

    // Notification setup
    private val NOTIFICATION_CHANNEL_ID = "ankle_monitor_service"
    private val SERVICE_NOTIFICATION_ID = 1

    // Activity state tracking
    private var isCurrentlyWalking = false
    private var currentStepCount = 0
    @SuppressLint("NewApi")
    private var lastAggregationTime = LocalDateTime.now()

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")
        initializeComponents()
        startForeground(SERVICE_NOTIFICATION_ID, createNotification())
        startTimeTracking()
        startPeriodicAggregation()
    }

    private fun initializeComponents() {
        // Initialize monitoring components
        walkingDetector = WalkingDetector(this, this)
        bleManager = BleManager(this).apply {
            connectionListener = this@WalkingDetectionService
        }
        dataRepository = AnkleDataRepository(this)

        // Set up wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AnkleApp::MonitoringWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    @SuppressLint("NewApi")
    private fun startTimeTracking() {
        timeTrackingJob?.cancel()
        timeTrackingJob = serviceScope.launch {
            Log.d(TAG, "Starting continuous time tracking")
            while (isActive) {
                val currentDate = LocalDateTime.now().toLocalDate().toString()
                ensureTodayRecord(currentDate)

                if (isCurrentlyWalking) {
                    updateWalkingTime(currentDate)
                } else {
                    updateStaticTime(currentDate)
                }

                delay(1000) // Update every second
            }
        }
    }

    private fun startPeriodicAggregation() {
        aggregationJob?.cancel()
        aggregationJob = serviceScope.launch {
            Log.d(TAG, "Starting periodic statistics aggregation")
            while (isActive) {
                try {
                    updateAggregatedStats()
                    delay(AGGREGATION_INTERVAL) // Update aggregated stats every hour
                } catch (e: Exception) {
                    Log.e(TAG, "Error during statistics aggregation", e)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun updateAggregatedStats() {
        val now = LocalDateTime.now()
        val weekFields = WeekFields.of(Locale.getDefault())

        // Update weekly statistics
        val weekStart = now.with(weekFields.dayOfWeek(), 1L)
        val weeklyStats = dataRepository.getWeeklyChartData(weekStart)

        // Get current week's daily stats for event counting
        val weeklyDailyStats = (0..6).map { day ->
            dataRepository.getDailyStats(weekStart.plusDays(day.toLong()))
        }

        dataRepository.statsUpdateListener?.onAggregatedStatsUpdated(
            AnkleDataRepository.AggregatedStats(
                periodType = "W",
                startDate = weekStart,
                endDate = weekStart.plusDays(6),
                totalWalkingTime = weeklyStats.dailyData.sumOf { it.totalWalkingTime },
                totalStaticTime = weeklyStats.dailyData.sumOf { it.totalStaticTime },
                walkingBadPosture = weeklyStats.dailyData.sumOf { it.walkingBadPosture },
                staticBadPosture = weeklyStats.dailyData.sumOf { it.staticBadPosture },
                leftEvents = weeklyDailyStats.sumOf { it.leftEvents },
                rightEvents = weeklyDailyStats.sumOf { it.rightEvents },
                frontEvents = weeklyDailyStats.sumOf { it.frontEvents },
                bestDay = null,
                worstDay = null
            )
        )

        // Update monthly statistics if we've crossed into a new day
        if (now.dayOfMonth != lastAggregationTime.dayOfMonth) {
            val monthStart = now.withDayOfMonth(1)
            val monthlyStats = dataRepository.getMonthlyChartData(monthStart)

            // Get current month's daily stats for event counting
            val daysInMonth = monthStart.plusMonths(1).minusDays(1).dayOfMonth
            val monthlyDailyStats = (0 until daysInMonth).map { day ->
                dataRepository.getDailyStats(monthStart.plusDays(day.toLong()))
            }

            dataRepository.statsUpdateListener?.onAggregatedStatsUpdated(
                AnkleDataRepository.AggregatedStats(
                    periodType = "M",
                    startDate = monthStart,
                    endDate = monthStart.plusMonths(1).minusDays(1),
                    totalWalkingTime = monthlyStats.weeklyData.sumOf { week ->
                        week.dailyData.sumOf { it.totalWalkingTime }
                    },
                    totalStaticTime = monthlyStats.weeklyData.sumOf { week ->
                        week.dailyData.sumOf { it.totalStaticTime }
                    },
                    walkingBadPosture = monthlyStats.weeklyData.sumOf { week ->
                        week.dailyData.sumOf { it.walkingBadPosture }
                    },
                    staticBadPosture = monthlyStats.weeklyData.sumOf { week ->
                        week.dailyData.sumOf { it.staticBadPosture }
                    },
                    leftEvents = monthlyDailyStats.sumOf { it.leftEvents },
                    rightEvents = monthlyDailyStats.sumOf { it.rightEvents },
                    frontEvents = monthlyDailyStats.sumOf { it.frontEvents },
                    bestDay = null,
                    worstDay = null
                )
            )
        }

        lastAggregationTime = now
    }

    private fun ensureTodayRecord(currentDate: String) {
        val db = dataRepository.dbHelper.writableDatabase
        db.beginTransaction()
        try {
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
                Log.d(TAG, "Created new daily record for: $currentDate")
            }
            cursor.close()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @SuppressLint("NewApi")
    private fun updateWalkingTime(currentDate: String) {
        val db = dataRepository.dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("""
                UPDATE ${AnkleDbHelper.TABLE_DAILY_STATS}
                SET ${AnkleDbHelper.DAILY_COLUMN_TOTAL_WALKING} = 
                    ${AnkleDbHelper.DAILY_COLUMN_TOTAL_WALKING} + 1
                WHERE ${AnkleDbHelper.DAILY_COLUMN_DATE} = ?
            """, arrayOf(currentDate))
            db.setTransactionSuccessful()

            val stats = dataRepository.getDailyStats(LocalDateTime.now())
            dataRepository.statsUpdateListener?.onStatsUpdated(stats)
        } finally {
            db.endTransaction()
        }
    }

    private fun updateStaticTime(currentDate: String) {
        val db = dataRepository.dbHelper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("""
                UPDATE ${AnkleDbHelper.TABLE_DAILY_STATS}
                SET ${AnkleDbHelper.DAILY_COLUMN_TOTAL_STATIC} = 
                    ${AnkleDbHelper.DAILY_COLUMN_TOTAL_STATIC} + 1
                WHERE ${AnkleDbHelper.DAILY_COLUMN_DATE} = ?
            """, arrayOf(currentDate))
            db.setTransactionSuccessful()

            val stats = dataRepository.getDailyStats(LocalDateTime.now())
            dataRepository.statsUpdateListener?.onStatsUpdated(stats)
        } finally {
            db.endTransaction()
        }
    }

    // Notification handling
    @SuppressLint("NewApi")
    private fun createNotification(): Notification {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Ankle Monitor Active")
            .setContentText(createNotificationText())
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    @SuppressLint("NewApi")
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Ankle Monitor Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors ankle posture continuously"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotificationText(): String {
        return "Status: ${if (isCurrentlyWalking) "Walking" else "Standing"} | Steps: $currentStepCount"
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(SERVICE_NOTIFICATION_ID, createNotification())
    }

    // Service lifecycle methods
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand called")
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT)
        walkingDetector.startDetection()
        bleManager.startContinuousConnection()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Interface implementations
    override fun onWalkingDetected(isWalking: Boolean) {
        isCurrentlyWalking = isWalking
        updateNotification()
    }

    override fun onStepCounted(stepCount: Int) {
        currentStepCount = stepCount
        updateNotification()
    }

    override fun onConnected() = updateNotification()
    override fun onDisconnected() = updateNotification()
    override fun onConnectionStopped() = updateNotification()

    override fun onDataReceived(data: String) {
        dataRepository.handlePostureData(data, isCurrentlyWalking)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service being destroyed")
        wakeLock?.release()
        timeTrackingJob?.cancel()
        aggregationJob?.cancel()
        walkingDetector.stopDetection()
        bleManager.cleanup()
        dataRepository.cleanup()
    }

    companion object {
        private const val TAG = "WalkingDetectionService"
        private const val WAKE_LOCK_TIMEOUT = 24 * 60 * 60 * 1000L // 24 hours
        private const val AGGREGATION_INTERVAL = 60 * 60 * 1000L // 1 hour
    }
}