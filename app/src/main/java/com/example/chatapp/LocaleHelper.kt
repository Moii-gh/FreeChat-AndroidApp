package com.example.chatapp

import android.content.Context
import com.example.chatapp.util.SafeLog

object LocaleHelper {

    private const val TAG = "LocaleHelper"

    val SUPPORTED_LANGUAGE_CODES: List<String> = LanguageManager.supportedLanguageCodes

    val LANGUAGES: LinkedHashMap<String, String> = linkedMapOf(
        *SUPPORTED_LANGUAGE_CODES.map { it to it }.toTypedArray()
    )

    fun getSelectedLanguage(context: Context): String {
        return LanguageManager.getAppLanguage(context)
    }

    fun setSelectedLanguage(context: Context, languageCode: String) {
        LanguageManager.setAppLanguage(context, languageCode)
    }

    fun getString(context: Context, tid: String): String {
        val localizedContext = LanguageManager.createLocalizedContext(context)
        val resourceId = localizedContext.resources.getIdentifier(tid, "string", localizedContext.packageName)
        if (resourceId == 0) {
            SafeLog.w(TAG, "Missing string resource: key='$tid'")
            return tid
        }
        return runCatching { localizedContext.getString(resourceId) }
            .getOrElse {
                SafeLog.w(TAG, "Failed to load string resource '$tid': ${it.message}")
                tid
            }
    }

    fun getStringList(context: Context, keyPrefix: String): List<String> {
        val localizedContext = LanguageManager.createLocalizedContext(context)
        return (1..100)
            .mapNotNull { index ->
                val key = "${keyPrefix}_$index"
                val resourceId = localizedContext.resources.getIdentifier(
                    key,
                    "string",
                    localizedContext.packageName
                )
                if (resourceId != 0) localizedContext.getString(resourceId).takeIf { it.isNotBlank() } else null
            }
            .distinct()
    }

    fun formatString(context: Context, tid: String, vararg args: Any): String {
        val localizedContext = LanguageManager.createLocalizedContext(context)
        val resourceId = localizedContext.resources.getIdentifier(tid, "string", localizedContext.packageName)
        if (resourceId == 0) return getString(context, tid)

        return runCatching { localizedContext.getString(resourceId, *args) }
            .getOrElse {
                SafeLog.w(TAG, "Failed to format string resource '$tid': ${it.message}")
                getString(context, tid)
            }
    }

    fun localizer(context: Context): (String, Array<out Any>) -> String {
        return { key, args -> formatString(context, key, *args) }
    }

    fun getLanguageDisplayName(context: Context, languageCode: String): String {
        val language = LanguageManager.toSupportedLanguage(languageCode)
        return getString(context, "language_name_$language")
    }

    fun getSelectedLocale(context: Context): java.util.Locale {
        return LanguageManager.getAppLocale(context)
    }

    fun clearCache() = Unit
}
