package com.example.chatapp.notifications

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SmartNotificationsSettingsStore(context: Context) {
    private val prefs = securePrefs(context.applicationContext)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun getVseGptApiKey(): String =
        prefs.getString(KEY_VSEGPT_API_KEY, null)?.trim().orEmpty()

    fun hasVseGptApiKey(): Boolean = getVseGptApiKey().isNotBlank()

    fun saveVseGptApiKey(apiKey: String) {
        val normalized = apiKey.trim()
        if (normalized.isBlank()) return
        prefs.edit().putString(KEY_VSEGPT_API_KEY, normalized).apply()
    }

    companion object {
        private const val PREFS_NAME = "smart_notifications_secure_prefs"
        private const val KEY_ENABLED = "smart_notifications_enabled"
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
