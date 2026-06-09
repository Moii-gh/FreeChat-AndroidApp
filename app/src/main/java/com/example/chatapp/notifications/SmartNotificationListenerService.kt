package com.example.chatapp.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import android.os.Build
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.chatapp.LocaleHelper
import com.example.chatapp.SmartNotificationsSettingsActivity
import com.example.chatapp.R

class SmartNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val classificationLimiter = Semaphore(2)
    private lateinit var settingsStore: SmartNotificationsSettingsStore
    private lateinit var classifier: SmartNotificationServerClassifier

    override fun onCreate() {
        super.onCreate()
        settingsStore = SmartNotificationsSettingsStore(applicationContext)
        val sessionStore = SharedPrefsAccountSessionStore(applicationContext)
        classifier = SmartNotificationServerClassifier(sessionStore::getAuthToken)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (!settingsStore.isEnabled) return
        if (sbn.packageName == packageName) return
        if (settingsStore.whitelist.contains(sbn.packageName)) return
        if (notification.isOngoingEvent()) return

        val payload = notification.toSmartPayload(sbn.packageName) ?: return

        val titleLower = payload.title.lowercase()
        val textLower = payload.text.lowercase()

        // 1. Check Stop words
        val hasStopWord = settingsStore.spamWords.any { word ->
            val bl = word.trim().lowercase()
            bl.isNotEmpty() && (titleLower.contains(bl) || textLower.contains(bl))
        }

        if (hasStopWord) {
            handleSpamNotification(sbn, payload)
            return
        }

        // 2. Otherwise, classify with AI
        serviceScope.launch {
            classificationLimiter.withPermit {
                val decision = classifier.classify(payload)
                if (decision == SmartNotificationDecision.SPAM) {
                    handleSpamNotification(sbn, payload)
                }
            }
        }
    }

    private fun handleSpamNotification(sbn: StatusBarNotification, payload: SmartNotificationPayload) {
        val spam = SpamNotification(
            id = java.util.UUID.randomUUID().toString(),
            packageName = sbn.packageName,
            title = payload.title,
            text = payload.text,
            timestamp = System.currentTimeMillis()
        )
        settingsStore.addSpamNotification(spam)

        runCatching { cancelNotification(sbn.key) }
            .onFailure { SafeLog.w(TAG, "Failed to cancel spam notification", it) }

        if (!settingsStore.hasShownSpamNotificationTip) {
            settingsStore.hasShownSpamNotificationTip = true
            showFirstTimeSpamFilteredNotification()
        }
    }

    private fun showFirstTimeSpamFilteredNotification() {
        val context = applicationContext
        val notificationManager = NotificationManagerCompat.from(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                "smart_notifications_channel",
                LocaleHelper.getString(context, "smart_notifications_title"),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(channel)
        }

        val openSettingsIntent = Intent(context, SmartNotificationsSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            5501,
            openSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = LocaleHelper.getString(context, "smart_notification_spam_detected_title")
        val text = LocaleHelper.getString(context, "smart_notification_spam_detected_text")

        val notification = NotificationCompat.Builder(context, "smart_notifications_channel")
            .setSmallIcon(R.drawable.ic_freechat_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            notificationManager.notify(5502, notification)
        }.onFailure { error ->
            SafeLog.w(TAG, "Could not show spam filtered notification", error)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun Notification.toSmartPayload(sourcePackageName: String): SmartNotificationPayload? {
        val title = extras
            ?.getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            ?.trim()
            .orEmpty()
            .limitForPrompt(MAX_TITLE_LENGTH)

        val text = listOf(
            Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT,
            Notification.EXTRA_SUB_TEXT
        ).mapNotNull { key ->
            extras?.getCharSequence(key)?.toString()?.trim()?.takeIf { it.isNotBlank() }
        }.distinct()
            .joinToString(separator = "\n")
            .limitForPrompt(MAX_TEXT_LENGTH)

        if (title.isBlank() && text.isBlank()) return null

        return SmartNotificationPayload(
            sourcePackageName = sourcePackageName,
            title = title,
            text = text
        )
    }

    private fun Notification.isOngoingEvent(): Boolean {
        val ongoingFlags = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
        return flags and ongoingFlags != 0
    }

    private fun String.limitForPrompt(maxLength: Int): String {
        if (length <= maxLength) return this
        return take(maxLength).trimEnd() + "..."
    }

    private companion object {
        private const val TAG = "SmartNotifications"
        private const val MAX_TITLE_LENGTH = 180
        private const val MAX_TEXT_LENGTH = 800
    }
}
