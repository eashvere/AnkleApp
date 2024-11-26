package com.example.ankleapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.content.Context
import android.os.PowerManager
import com.example.ankleapp.data.AnkleDataRepository

class WalkingDetectionService : Service(), WalkingListener, BleManager.ConnectionListener {

    private lateinit var walkingDetector: WalkingDetector
    private lateinit var bleManager: BleManager
    private lateinit var dataRepository: AnkleDataRepository
    private var wakeLock: PowerManager.WakeLock? = null

    private val NOTIFICATION_CHANNEL_ID = "ankle_monitor_service"
    private val SERVICE_NOTIFICATION_ID = 1

    @SuppressLint("ForegroundServiceType")
    override fun onCreate() {
        super.onCreate()

        // Initialize components
        walkingDetector = WalkingDetector(this, this)
        bleManager = BleManager(this).apply {
            connectionListener = this@WalkingDetectionService
        }
        dataRepository = AnkleDataRepository(this)

        // Create wake lock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AnkleApp::MonitoringWakeLock"
        )

        startForeground(SERVICE_NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Acquire wake lock
        wakeLock?.acquire()

        // Start walking detection
        walkingDetector.startDetection()

        return START_STICKY // Service will be restarted if it's killed
    }

    @SuppressLint("NewApi")
    private fun createNotification(): Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        val channelName = "Ankle Monitor Service"

        // Create notification channel
        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitors ankle posture while walking"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)

        // Create intent to open app when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Ankle Monitor Active")
            .setContentText("Monitoring your walking posture")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // WalkingListener implementation
    override fun onWalkingDetected(isWalking: Boolean) {
        if (isWalking) {
            bleManager.scanForSpecificDevice("SmartAnkleBrace")
            dataRepository.startWalkingSession()
        } else {
            bleManager.disconnectGatt()
            dataRepository.currentSessionId?.let {
                dataRepository.endWalkingSession(it)
            }
        }
    }

    // BleManager.ConnectionListener implementation
    override fun onConnected() {
        // Handle connection
    }

    override fun onDisconnected() {
        if (walkingDetector.isWalking) {
            bleManager.scanForSpecificDevice("SmartAnkleBrace")
        }
    }

    override fun onConnectionStopped() {
        // Handle connection stop
    }

    override fun onDataReceived(data: String) {
        dataRepository.handlePostureData(data)
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        walkingDetector.stopDetection()
        bleManager.cleanup()
    }
}