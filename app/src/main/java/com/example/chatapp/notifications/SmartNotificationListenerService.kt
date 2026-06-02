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
        val notificationKey = sbn.key

        serviceScope.launch {
            classificationLimiter.withPermit {
                val decision = classifier.classify(payload)
                if (decision == SmartNotificationDecision.SPAM) {
                    runCatching { cancelNotification(notificationKey) }
                        .onFailure { SafeLog.w(TAG, "Failed to cancel spam notification", it) }
                }
            }
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
