package com.example.chatapp.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R

object DigitalAssistantNotifications {
    const val CHANNEL_ID = "digital_assistant"
    const val OVERLAY_NOTIFICATION_ID = 2401
    const val CAPTURE_NOTIFICATION_ID = 2402

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            LocaleHelper.getString(context, "digital_assistant_title"),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    fun overlayNotification(context: Context): Notification {
        ensureChannel(context)
        val actionIntent = Intent(context, DigitalAssistantOverlayService::class.java).apply {
            action = DigitalAssistantOverlayService.ACTION_SHOW_OVERLAY
        }
        val action = PendingIntent.getService(
            context,
            101,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brain)
            .setContentTitle(LocaleHelper.getString(context, "digital_assistant_notification_title"))
            .setContentText(LocaleHelper.getString(context, "digital_assistant_notification_text"))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                R.drawable.ic_brain,
                LocaleHelper.getString(context, "digital_assistant_notification_action_open"),
                action
            )
            .build()
    }

    fun captureNotification(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(LocaleHelper.getString(context, "digital_assistant_screen_analysis"))
            .setContentText(LocaleHelper.getString(context, "digital_assistant_capture_active"))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

