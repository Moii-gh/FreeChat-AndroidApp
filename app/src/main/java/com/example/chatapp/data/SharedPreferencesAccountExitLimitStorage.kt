package com.example.chatapp.data

import android.content.SharedPreferences

internal class SharedPreferencesAccountExitLimitStorage(
    private val prefs: SharedPreferences
) : AccountExitLimitStorage {

    override fun readLogoutCount(): Int? =
        runCatching {
            if (prefs.contains(KEY_LOGOUT_COUNT)) {
                prefs.getInt(KEY_LOGOUT_COUNT, 0)
            } else {
                null
            }
        }.getOrNull()

    override fun readLastResetDate(): String? =
        runCatching {
            prefs.getString(KEY_LAST_RESET_DATE, null)
        }.getOrNull()

    override fun readBlockEndsAtMillis(): Long? =
        runCatching {
            if (prefs.contains(KEY_BLOCK_ENDS_AT_MILLIS)) {
                prefs.getLong(KEY_BLOCK_ENDS_AT_MILLIS, 0L)
            } else {
                null
            }
        }.getOrNull()

    override fun writeState(
        logoutCount: Int,
        lastResetDate: String,
        blockEndsAtMillis: Long
    ) {
        prefs.edit()
            .putInt(KEY_LOGOUT_COUNT, logoutCount)
            .putString(KEY_LAST_RESET_DATE, lastResetDate)
            .putLong(KEY_BLOCK_ENDS_AT_MILLIS, blockEndsAtMillis)
            .apply()
    }

    private companion object {
        const val KEY_LOGOUT_COUNT = "logout_count"
        const val KEY_LAST_RESET_DATE = "last_reset_date"
        const val KEY_BLOCK_ENDS_AT_MILLIS = "block_ends_at_millis"
    }
}

