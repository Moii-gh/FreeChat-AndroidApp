package com.example.chatapp

import android.Manifest
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
import com.example.chatapp.util.SafeLog

object ChatResponseNotifications {
    const val CHANNEL_ID = "chat_responses"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FreeChat",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun showAnswerReady(
        context: Context,
        chatId: String,
        assistantSyncId: String,
        isError: Boolean
    ) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val appContext = context.applicationContext
        val openChatIntent = Intent(appContext, FreeChatActivity::class.java).apply {
            action = "com.example.chatapp.action.OPEN_CHAT_RESPONSE"
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(FreeChatActivity.EXTRA_OPEN_CHAT_ID, chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            assistantSyncId.hashCode(),
            openChatIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_brain)
            .setContentTitle("FreeChat")
            .setContentText(if (isError) "Не удалось получить ответ" else "Ответ готов")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext)
                .notify(assistantSyncId.hashCode(), notification)
        }.onFailure { error ->
            SafeLog.w("ChatResponseNotifications", "Could not show response notification", error)
        }
    }
}
