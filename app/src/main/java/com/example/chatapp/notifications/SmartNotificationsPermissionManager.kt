package com.example.chatapp.notifications

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

object SmartNotificationsPermissionManager {

    fun hasNotificationListenerAccess(context: Context): Boolean {
        return NotificationManagerCompat
            .getEnabledListenerPackages(context.applicationContext)
            .contains(context.packageName)
    }

    fun notificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}
