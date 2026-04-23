package com.example.chatapp.data

import android.content.Context
import android.content.SharedPreferences
import com.example.chatapp.network.dto.ApiUser

interface AccountSessionStore {
    fun saveAuthenticatedUser(user: ApiUser?, token: String?)
    fun isSignedIn(): Boolean
    fun clearSession()
    fun getAuthToken(): String?
    fun getCurrentUserId(): String?
    fun getCurrentUserEmail(): String?
    fun getCurrentUserName(): String?
    fun getCurrentPlanCode(): String?
    fun getCurrentPlanExpiresAt(): String?
    fun isCurrentUserPro(): Boolean
}

class SharedPrefsAccountSessionStore(
    context: Context
) : AccountSessionStore {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun saveAuthenticatedUser(user: ApiUser?, token: String?) {
        val currentEmail = prefs.getString(KEY_USER_EMAIL, null)
        val nextEmail = user?.email?.trim()?.lowercase()
            ?: user?.telegramUsername
                ?.takeIf { it.isNotBlank() }
                ?.let { "@$it" }

        prefs.edit().apply {
            token?.let { putString(KEY_AUTH_TOKEN, it) }
            user?.let {
                putString(KEY_USER_ID, it.id)
                putString(KEY_USER_NAME, it.fullName)
                putString(KEY_USER_EMAIL, nextEmail)
                putString(KEY_USER_BIRTH_DATE, it.birthDate)
                putBoolean(KEY_IS_VERIFIED, it.isVerified)
                putString(KEY_PLAN_CODE, it.planCode)
                putString(KEY_PLAN_EXPIRES_AT, it.planExpiresAt)
                putBoolean(KEY_IS_PRO, it.isPro)
                putString(KEY_SUBSCRIPTION_STATUS, it.subscriptionStatus)
            }
        }.apply()

        val accountSettings = AccountScopedSettings(prefs)
        if (!currentEmail.isNullOrBlank() && currentEmail != nextEmail) {
            accountSettings.migrateLegacyDataIfNeeded()
        } else if (!nextEmail.isNullOrBlank()) {
            accountSettings.migrateLegacyDataIfNeeded()
        }
    }

    override fun isSignedIn(): Boolean {
        return !getAuthToken().isNullOrBlank() &&
            (!getCurrentUserId().isNullOrBlank() || !getCurrentUserEmail().isNullOrBlank())
    }

    override fun clearSession() {
        prefs.edit().apply {
            remove(KEY_AUTH_TOKEN)
            remove(KEY_USER_ID)
            remove(KEY_USER_NAME)
            remove(KEY_USER_EMAIL)
            remove(KEY_USER_BIRTH_DATE)
            remove(KEY_IS_VERIFIED)
            remove(KEY_USER_PASSWORD)
            remove(KEY_PLAN_CODE)
            remove(KEY_PLAN_EXPIRES_AT)
            remove(KEY_IS_PRO)
            remove(KEY_SUBSCRIPTION_STATUS)
        }.apply()
    }

    override fun getAuthToken(): String? = prefs.getString(KEY_AUTH_TOKEN, null)

    override fun getCurrentUserId(): String? = prefs.getString(KEY_USER_ID, null)

    override fun getCurrentUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    override fun getCurrentUserName(): String? = prefs.getString(KEY_USER_NAME, null)

    override fun getCurrentPlanCode(): String? = prefs.getString(KEY_PLAN_CODE, null)

    override fun getCurrentPlanExpiresAt(): String? = prefs.getString(KEY_PLAN_EXPIRES_AT, null)

    override fun isCurrentUserPro(): Boolean = prefs.getBoolean(KEY_IS_PRO, false)

    companion object {
        const val PREFS_NAME = "settings_prefs"
        const val KEY_AUTH_TOKEN = "auth_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_USER_BIRTH_DATE = "user_birth_date"
        const val KEY_IS_VERIFIED = "is_verified"
        const val KEY_USER_PASSWORD = "user_password"
        const val KEY_PLAN_CODE = "plan_code"
        const val KEY_PLAN_EXPIRES_AT = "plan_expires_at"
        const val KEY_IS_PRO = "is_pro"
        const val KEY_SUBSCRIPTION_STATUS = "subscription_status"
    }
}

class AccountScopedSettings(
    private val prefs: SharedPreferences
) {

    init {
        migrateLegacyDataIfNeeded()
    }

    constructor(context: Context) : this(
        context.getSharedPreferences(SharedPrefsAccountSessionStore.PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun migrateLegacyDataIfNeeded() {
        migrateString("avatar_uri")
        migrateString("user_instructions")
        migrateString("ai_mode")
        migrateString("last_reset_date")
        migrateInt("requests_left")
        migrateString("profile_name")
    }

    fun getAvatarUri(): String? = prefs.getString(scopedKey("avatar_uri"), null)

    fun saveAvatarUri(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) {
                remove(scopedKey("avatar_uri"))
            } else {
                putString(scopedKey("avatar_uri"), value)
            }
        }.apply()
    }

    fun getDisplayName(defaultValue: String = "User"): String {
        return prefs.getString(scopedKey("profile_name"), null)
            ?: prefs.getString(SharedPrefsAccountSessionStore.KEY_USER_NAME, defaultValue)
            ?: defaultValue
    }

    fun saveDisplayName(value: String) {
        prefs.edit().putString(scopedKey("profile_name"), value).apply()
    }

    fun getAiMode(defaultValue: String = "auto"): String {
        val storedValue = prefs.getString(scopedKey("ai_mode"), defaultValue) ?: defaultValue
        return normalizeAiMode(storedValue)
    }

    fun saveAiMode(value: String) {
        prefs.edit().putString(scopedKey("ai_mode"), normalizeAiMode(value)).apply()
    }

    fun getUserInstructions(): String {
        return prefs.getString(scopedKey("user_instructions"), "") ?: ""
    }

    fun saveUserInstructions(value: String) {
        prefs.edit().putString(scopedKey("user_instructions"), value).apply()
    }

    fun getRequestsLeft(defaultValue: Int = 20): Int {
        return prefs.getInt(scopedKey("requests_left"), defaultValue)
    }

    fun saveRequestsLeft(value: Int) {
        prefs.edit().putInt(scopedKey("requests_left"), value).apply()
    }

    fun getLastResetDate(): String {
        return prefs.getString(scopedKey("last_reset_date"), "") ?: ""
    }

    fun saveLastResetDate(value: String) {
        prefs.edit().putString(scopedKey("last_reset_date"), value).apply()
    }

    fun currentAccountKey(): String {
        val userId = prefs.getString(SharedPrefsAccountSessionStore.KEY_USER_ID, null)
        if (!userId.isNullOrBlank()) {
            return "user_$userId"
        }

        val email = prefs.getString(SharedPrefsAccountSessionStore.KEY_USER_EMAIL, null)
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]"), "_")
        return if (!email.isNullOrBlank()) "email_$email" else "guest"
    }

    private fun scopedKey(key: String): String = "${currentAccountKey()}__$key"

    private fun migrateString(legacyKey: String) {
        val targetKey = scopedKey(legacyKey)
        if (prefs.contains(targetKey) || !prefs.contains(legacyKey)) {
            return
        }

        prefs.edit()
            .putString(targetKey, prefs.getString(legacyKey, null))
            .remove(legacyKey)
            .apply()
    }

    private fun migrateInt(legacyKey: String) {
        val targetKey = scopedKey(legacyKey)
        if (prefs.contains(targetKey) || !prefs.contains(legacyKey)) {
            return
        }

        prefs.edit()
            .putInt(targetKey, prefs.getInt(legacyKey, 20))
            .remove(legacyKey)
            .apply()
    }

    private fun normalizeAiMode(value: String): String = when (value) {
        "plus", "auto" -> value
        else -> "plus"
    }
}
