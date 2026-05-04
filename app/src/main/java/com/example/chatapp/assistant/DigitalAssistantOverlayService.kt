package com.example.chatapp.assistant

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast

class DigitalAssistantOverlayService : Service() {
    private var host: WindowManagerOverlayHost? = null
    private val viewModel by lazy { DigitalAssistantRuntime.get(this) }
    private val stateListener: (DigitalAssistantState) -> Unit = {
        if (host == null && !it.isGenerating) {
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(
            DigitalAssistantNotifications.OVERLAY_NOTIFICATION_ID,
            DigitalAssistantNotifications.overlayNotification(this)
        )
        viewModel.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_START_NOTIFICATION, null -> Unit
        }
        return START_STICKY
    }

    override fun onDestroy() {
        host?.detach()
        host = null
        viewModel.removeListener(stateListener)
        super.onDestroy()
    }

    private fun showOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                com.example.chatapp.LocaleHelper.getString(this, "digital_assistant_overlay_permission_needed"),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (host == null) {
            host = WindowManagerOverlayHost(this, viewModel) {
                host = null
                if (!viewModel.state.isGenerating) {
                    stopSelf()
                }
            }
        }
        host?.attach()
    }

    companion object {
        const val ACTION_START_NOTIFICATION = "com.example.chatapp.assistant.START_NOTIFICATION"
        const val ACTION_SHOW_OVERLAY = "com.example.chatapp.assistant.SHOW_OVERLAY"

        fun startFallbackNotification(context: Context) {
            val intent = Intent(context, DigitalAssistantOverlayService::class.java).apply {
                action = ACTION_START_NOTIFICATION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

