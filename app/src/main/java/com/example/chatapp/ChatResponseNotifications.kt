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
        isError: Boolean,
        responsePreview: String? = null
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
        val contentText = buildContentText(isError, responsePreview)
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_freechat_notification)
            .setContentTitle("FreeChat")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
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

    @SuppressLint("MissingPermission")
    fun showDebugTest(context: Context): Boolean {
        if (!canNotify(context)) return false
        val appContext = context.applicationContext
        val notificationManager = NotificationManagerCompat.from(appContext)
        if (!notificationManager.areNotificationsEnabled()) return false

        ensureChannel(appContext)
        val contentIntent = PendingIntent.getActivity(
            appContext,
            DEBUG_TEST_REQUEST_CODE,
            Intent(appContext, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentText = LocaleHelper.getString(appContext, "debug_notification_test_text")
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_freechat_notification)
            .setContentTitle(LocaleHelper.getString(appContext, "app_name"))
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        return runCatching {
            notificationManager.notify(DEBUG_TEST_NOTIFICATION_ID, notification)
            true
        }.onFailure { error ->
            SafeLog.w("ChatResponseNotifications", "Could not show debug test notification", error)
        }.getOrDefault(false)
    }

    private fun buildContentText(isError: Boolean, responsePreview: String?): String {
        if (isError) return "Не удалось получить ответ"
        return responsePreview.toNotificationPreview()
    }

    private fun String?.toNotificationPreview(): String {
        val cleaned = this
            ?.replace(Regex("!\\[[^]]*]\\([^)]*\\)"), " ")
            ?.replace(Regex("\\[[^]]+]\\([^)]*\\)"), { match ->
                match.value.substringAfter('[').substringBefore(']')
            })
            ?.replace(Regex("[*_`>#~-]+"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        if (cleaned.isBlank()) return "Новый ответ в FreeChat"
        if (cleaned.length <= MAX_NOTIFICATION_PREVIEW_LENGTH) return cleaned
        return cleaned.take(MAX_NOTIFICATION_PREVIEW_LENGTH).trimEnd() + "..."
    }

    private const val MAX_NOTIFICATION_PREVIEW_LENGTH = 140
    private const val DEBUG_TEST_NOTIFICATION_ID = 4401
    private const val DEBUG_TEST_REQUEST_CODE = 4402
}
