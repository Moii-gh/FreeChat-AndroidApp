package com.example.chatapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.chatapp.network.dto.ApiUser
import com.example.chatapp.network.dto.BillingStatusResponse

private const val LEGACY_PREFS_NAME = "settings_prefs"
private const val SECURE_PREFS_NAME = "secure_settings_prefs"
private const val KEY_SECURE_PREFS_MIGRATED = "__secure_prefs_migrated"

private fun createSecurePreferences(
    context: Context,
    securePrefsName: String = SECURE_PREFS_NAME,
    legacyPrefsName: String = LEGACY_PREFS_NAME
): SharedPreferences {
    val appContext = context.applicationContext
    val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    val securePrefs = EncryptedSharedPreferences.create(
        appContext,
        securePrefsName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    if (!securePrefs.getBoolean(KEY_SECURE_PREFS_MIGRATED, false)) {
        val legacyPrefs = appContext.getSharedPreferences(legacyPrefsName, Context.MODE_PRIVATE)
        val editor = securePrefs.edit()
        legacyPrefs.all.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        editor.putBoolean(KEY_SECURE_PREFS_MIGRATED, true).apply()
        if (legacyPrefs.all.isNotEmpty()) {
            legacyPrefs.edit().clear().apply()
        }
    }

    return securePrefs
}

interface AccountSessionStore {
    fun saveAuthenticatedUser(user: ApiUser?, token: String?)
    fun saveBillingStatus(status: BillingStatusResponse)
    fun isSignedIn(): Boolean
    fun clearSession()
    fun getAuthToken(): String?
    fun getCurrentUserId(): String?
    fun getCurrentUserEmail(): String?
    fun getCurrentUserName(): String?
    fun getCurrentPlanCode(): String?
    fun getCurrentPlanExpiresAt(): String?
    fun isCurrentUserPro(): Boolean
    fun getDailyRequestLimit(): Int?
    fun getRemainingDailyRequests(): Int?
    fun getDailyQuotaResetsAt(): String?
    fun saveRemainingDailyRequests(value: Int?)
    fun consumeDailyRequest()
    fun addDailyRequests(amount: Int)
}

class SharedPrefsAccountSessionStore(
    context: Context
) : AccountSessionStore {

    private val prefs: SharedPreferences = createSecurePreferences(context)

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

    override fun saveBillingStatus(status: BillingStatusResponse) {
        prefs.edit().apply {
            putString(KEY_PLAN_CODE, status.planCode)
            putString(KEY_PLAN_EXPIRES_AT, status.planExpiresAt)
            putBoolean(KEY_IS_PRO, status.isPro)
            putString(KEY_SUBSCRIPTION_STATUS, status.subscriptionStatus)
            putNullableInt(KEY_DAILY_REQUEST_LIMIT, status.dailyRequestLimit)
            putNullableInt(KEY_REMAINING_DAILY_REQUESTS, status.remainingDailyRequests)
            putNullableString(KEY_DAILY_QUOTA_RESETS_AT, status.dailyQuotaResetsAt)
        }.apply()
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
            remove(KEY_DAILY_REQUEST_LIMIT)
            remove(KEY_REMAINING_DAILY_REQUESTS)
            remove(KEY_REWARDED_REQUESTS)
            remove(KEY_DAILY_QUOTA_RESETS_AT)
        }.apply()
    }

    override fun getAuthToken(): String? = prefs.getString(KEY_AUTH_TOKEN, null)

    override fun getCurrentUserId(): String? = prefs.getString(KEY_USER_ID, null)

    override fun getCurrentUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    override fun getCurrentUserName(): String? = prefs.getString(KEY_USER_NAME, null)

    override fun getCurrentPlanCode(): String? = prefs.getString(KEY_PLAN_CODE, null)

    override fun getCurrentPlanExpiresAt(): String? = prefs.getString(KEY_PLAN_EXPIRES_AT, null)

    override fun isCurrentUserPro(): Boolean = prefs.getBoolean(KEY_IS_PRO, false)

    override fun getDailyRequestLimit(): Int? =
        if (prefs.contains(KEY_DAILY_REQUEST_LIMIT)) prefs.getInt(KEY_DAILY_REQUEST_LIMIT, 0) else null

    override fun getRemainingDailyRequests(): Int? {
        val server = if (prefs.contains(KEY_REMAINING_DAILY_REQUESTS)) prefs.getInt(KEY_REMAINING_DAILY_REQUESTS, 0) else null
        val rewarded = prefs.getInt(KEY_REWARDED_REQUESTS, 0)
        return if (server == null) {
            if (rewarded > 0) rewarded else null
        } else {
            server + rewarded
        }
    }

    override fun getDailyQuotaResetsAt(): String? = prefs.getString(KEY_DAILY_QUOTA_RESETS_AT, null)

    override fun saveRemainingDailyRequests(value: Int?) {
        prefs.edit().putNullableInt(KEY_REMAINING_DAILY_REQUESTS, value).apply()
    }

    override fun consumeDailyRequest() {
        val rewarded = prefs.getInt(KEY_REWARDED_REQUESTS, 0)
        if (rewarded > 0) {
            prefs.edit().putInt(KEY_REWARDED_REQUESTS, rewarded - 1).apply()
        } else {
            val server = prefs.getInt(KEY_REMAINING_DAILY_REQUESTS, 0)
            if (server > 0) {
                prefs.edit().putInt(KEY_REMAINING_DAILY_REQUESTS, server - 1).apply()
            }
        }
    }

    override fun addDailyRequests(amount: Int) {
        val rewarded = prefs.getInt(KEY_REWARDED_REQUESTS, 0)
        prefs.edit().putInt(KEY_REWARDED_REQUESTS, rewarded + amount).apply()
    }

    companion object {
        const val PREFS_NAME = SECURE_PREFS_NAME
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
        const val KEY_DAILY_REQUEST_LIMIT = "daily_request_limit"
        const val KEY_REMAINING_DAILY_REQUESTS = "remaining_daily_requests"
        const val KEY_REWARDED_REQUESTS = "rewarded_requests"
        const val KEY_DAILY_QUOTA_RESETS_AT = "daily_quota_resets_at"
    }
}

class AccountScopedSettings(
    private val prefs: SharedPreferences
) {

    init {
        migrateLegacyDataIfNeeded()
    }

    constructor(context: Context) : this(createSecurePreferences(context))

    fun migrateLegacyDataIfNeeded() {
        migrateString("avatar_uri")
        migrateString("user_instructions")
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

    fun getUserInstructions(): String {
        return prefs.getString(scopedKey("user_instructions"), "") ?: ""
    }

    fun saveUserInstructions(value: String) {
        prefs.edit().putString(scopedKey("user_instructions"), value).apply()
    }

    /** Generic scoped string getter for arbitrary keys */
    fun getString(key: String): String {
        return prefs.getString(scopedKey(key), "") ?: ""
    }

    /** Generic scoped string setter for arbitrary keys */
    fun saveString(key: String, value: String) {
        prefs.edit().putString(scopedKey(key), value).apply()
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
}

private fun SharedPreferences.Editor.putNullableInt(key: String, value: Int?): SharedPreferences.Editor {
    return if (value == null) remove(key) else putInt(key, value)
}

private fun SharedPreferences.Editor.putNullableString(key: String, value: String?): SharedPreferences.Editor {
    return if (value.isNullOrBlank()) remove(key) else putString(key, value)
}
