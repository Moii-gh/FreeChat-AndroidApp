package com.example.chatapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class AccountExitLimitNotificationHelper {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            AccountExitLimitMessages.title(context),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }

    fun canPostNotifications(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun showLogoutLimitNotification(context: Context, remainingHours: Long): Boolean {
        if (!canPostNotifications(context)) {
            return false
        }

        val notificationManager = NotificationManagerCompat.from(context)
        if (!notificationManager.areNotificationsEnabled()) {
            return false
        }

        ensureChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            CONTENT_REQUEST_CODE,
            Intent(context, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logout)
            .setContentTitle(AccountExitLimitMessages.title(context))
            .setContentText(AccountExitLimitMessages.body(context, remainingHours))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(AccountExitLimitMessages.fullMessage(context, remainingHours))
            )
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        return runCatching {
            notificationManager.notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    private companion object {
        const val CHANNEL_ID = "account_exit_limits"
        const val NOTIFICATION_ID = 2601
        const val CONTENT_REQUEST_CODE = 2602
    }
}
