package com.example.chatapp.notifications

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SmartNotificationsSettingsStore(context: Context) {
    private val prefs = securePrefs(context.applicationContext)

    init {
        prefs.edit().remove(KEY_VSEGPT_API_KEY).apply()
    }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var whitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_WHITELIST, value).apply()

    var hasShownSpamNotificationTip: Boolean
        get() = prefs.getBoolean(KEY_SHOWN_SPAM_TIP, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOWN_SPAM_TIP, value).apply()

    var spamNotifications: List<SpamNotification>
        get() {
            val json = prefs.getString(KEY_SPAM_NOTIFICATIONS, null) ?: return emptyList()
            val type = object : com.google.gson.reflect.TypeToken<List<SpamNotification>>() {}.type
            return runCatching { com.google.gson.Gson().fromJson<List<SpamNotification>>(json, type) }.getOrElse { emptyList() } ?: emptyList()
        }
        set(value) {
            val json = com.google.gson.Gson().toJson(value)
            prefs.edit().putString(KEY_SPAM_NOTIFICATIONS, json).apply()
        }

    fun addSpamNotification(spam: SpamNotification) {
        val list = spamNotifications.toMutableList()
        list.add(0, spam)
        if (list.size > 100) {
            list.removeAt(list.lastIndex)
        }
        spamNotifications = list
    }

    fun clearAllSpam() {
        prefs.edit().remove(KEY_SPAM_NOTIFICATIONS).apply()
    }

    fun removeSpamNotification(id: String) {
        val list = spamNotifications.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index != -1) {
            list.removeAt(index)
            spamNotifications = list
        }
    }

    companion object {
        private const val PREFS_NAME = "smart_notifications_secure_prefs"
        private const val KEY_ENABLED = "smart_notifications_enabled"
        private const val KEY_WHITELIST = "smart_notifications_whitelist"
        private const val KEY_VSEGPT_API_KEY = "vsegpt_api_key"
        private const val KEY_SHOWN_SPAM_TIP = "smart_notifications_shown_spam_tip"
        private const val KEY_SPAM_NOTIFICATIONS = "smart_notifications_spam_list"

        private fun securePrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}

data class SpamNotification(
    val id: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timestamp: Long
)
