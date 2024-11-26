package com.example.ankleapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class PostureNotificationManager(private val context: Context) {

    private val notificationManager = NotificationManagerCompat.from(context)
    private val channelId = "posture_alerts"

    // Get default notification sound
    private val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Posture Alerts"
            val descriptionText = "Notifications for bad posture detection"
            val importance = NotificationManager.IMPORTANCE_HIGH

            // Create AudioAttributes for the notification channel
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .build()

            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setSound(defaultSoundUri, audioAttributes) // Set sound for the channel
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showBadPostureNotification(direction: String) {
        val (title, message) = when(direction) {
            "L" -> Pair(
                "Leaning Left Detected",
                "You're leaning towards your left side. Please straighten your ankle."
            )
            "R" -> Pair(
                "Leaning Right Detected",
                "You're leaning towards your right side. Please straighten your ankle."
            )
            "F" -> Pair(
                "Forward Lean Detected",
                "You're leaning forward. Please maintain an upright position."
            )
            else -> Pair(
                "Bad Posture Detected",
                "Incorrect posture detected. Please check your stance."
            )
        }

        // Create an intent that opens the app when notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .setSound(defaultSoundUri) // Set sound for the notification
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .build()

        try {
            // Use a unique notification ID for each direction
            val notificationId = when(direction) {
                "L" -> 1
                "R" -> 2
                "F" -> 3
                else -> 0
            }
            notificationManager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    // Optional: Method to cancel specific notification
    fun cancelNotification(direction: String) {
        val notificationId = when(direction) {
            "L" -> 1
            "R" -> 2
            "F" -> 3
            else -> 0
        }
        notificationManager.cancel(notificationId)
    }

    // Optional: Method to cancel all notifications
    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }
}