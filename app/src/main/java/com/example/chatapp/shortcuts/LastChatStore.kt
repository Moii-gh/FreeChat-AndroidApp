package com.example.chatapp.shortcuts

import android.content.Context
import com.example.chatapp.data.AccountScopedSettings

class LastChatStore(context: Context) {
    private val accountSettings = AccountScopedSettings(context.applicationContext)

    fun save(chatId: String) {
        if (chatId.isBlank()) return
        accountSettings.saveString(KEY_LAST_ACTIVE_CHAT_ID, chatId)
    }

    fun get(): String? {
        return accountSettings.getString(KEY_LAST_ACTIVE_CHAT_ID)
            .takeIf { it.isNotBlank() }
    }

    fun clearIfMatches(chatId: String) {
        if (get() == chatId) {
            accountSettings.removeString(KEY_LAST_ACTIVE_CHAT_ID)
        }
    }

    private companion object {
        const val KEY_LAST_ACTIVE_CHAT_ID = "shortcut_last_active_chat_id"
    }
}
