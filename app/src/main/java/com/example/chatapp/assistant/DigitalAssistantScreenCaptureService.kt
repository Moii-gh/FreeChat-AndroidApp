package com.example.chatapp.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class DigitalAssistantScreenCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || data == null) {
            DigitalAssistantCaptureRegistry.dispatch(
                Result.failure(IllegalStateException(getStringResource("digital_assistant_capture_failed")))
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }

        ScreenCaptureManager(this).captureOneShot(resultCode, data) { result ->
            DigitalAssistantCaptureRegistry.dispatch(result)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val notification = DigitalAssistantNotifications.captureNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                DigitalAssistantNotifications.CAPTURE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(DigitalAssistantNotifications.CAPTURE_NOTIFICATION_ID, notification)
        }
    }

    private fun getStringResource(key: String): String =
        com.example.chatapp.LocaleHelper.getString(this, key)

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, DigitalAssistantScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

