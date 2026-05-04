package com.example.chatapp.assistant

import android.content.Context
import com.example.chatapp.data.AccountScopedSettings

class DigitalAssistantSettingsStore(context: Context) {
    private val accountSettings = AccountScopedSettings(context.applicationContext)

    var isEnabled: Boolean
        get() = accountSettings.getBoolean(KEY_ENABLED)
        set(value) = accountSettings.saveBoolean(KEY_ENABLED, value)

    var isFallbackNotificationEnabled: Boolean
        get() = accountSettings.getBoolean(KEY_FALLBACK_NOTIFICATION, defaultValue = true)
        set(value) = accountSettings.saveBoolean(KEY_FALLBACK_NOTIFICATION, value)

    companion object {
        private const val KEY_ENABLED = "digital_assistant_enabled"
        private const val KEY_FALLBACK_NOTIFICATION = "digital_assistant_fallback_notification"
    }
}

