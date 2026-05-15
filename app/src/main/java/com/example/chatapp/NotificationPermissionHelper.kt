package com.example.chatapp

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object NotificationPermissionHelper {
    private const val PREFS_NAME = "freechat_notification_permission"
    private const val KEY_REQUESTED = "post_notifications_requested"
    private const val REQUEST_CODE = 4105

    fun requestOnFirstOpen(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REQUESTED, false)) return

        prefs.edit().putBoolean(KEY_REQUESTED, true).apply()
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_CODE
        )
    }
}
