package com.example.chatapp.viewmodel

import android.content.Context

internal class SummaryCacheStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun cachedHash(accountKey: String, chatId: String?): String? =
        prefs.getString(cacheKey(accountKey, chatId), "")

    fun saveHash(accountKey: String, chatId: String?, hash: String) {
        prefs.edit().putString(cacheKey(accountKey, chatId), hash).apply()
    }

    fun clear(accountKey: String, chatId: String?) {
        if (chatId.isNullOrBlank()) return
        prefs.edit().remove(cacheKey(accountKey, chatId)).apply()
    }

    private fun cacheKey(accountKey: String, chatId: String?): String =
        "chat_${accountKey}_${chatId}_hash"

    private companion object {
        // Хэш хранится отдельно от истории, чтобы не пересуммаризировать один и тот же контекст после поворота экрана.
        const val PREFS_NAME = "summary_cache"
    }
}
