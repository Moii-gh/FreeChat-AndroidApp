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

    companion object {
        private const val PREFS_NAME = "smart_notifications_secure_prefs"
        private const val KEY_ENABLED = "smart_notifications_enabled"
        private const val KEY_WHITELIST = "smart_notifications_whitelist"
        private const val KEY_VSEGPT_API_KEY = "vsegpt_api_key"

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
