package com.example.chatapp

import android.content.Context
import android.util.Log
import java.util.Locale

object LocaleHelper {

    private const val TAG = "LocaleHelper"

    val SUPPORTED_LANGUAGE_CODES: List<String> = LanguageManager.supportedLanguageCodes

    val LANGUAGES: LinkedHashMap<String, String> = linkedMapOf(
        *SUPPORTED_LANGUAGE_CODES.map { it to it }.toTypedArray()
    )

    fun getSelectedLanguage(context: Context): String {
        return LanguageManager.getAppLanguage()
    }

    fun setSelectedLanguage(context: Context, languageCode: String) {
        Log.i(TAG, "Manual app language selection is disabled; using device locale.")
    }

    fun getString(context: Context, tid: String): String {
        val resourceId = context.resources.getIdentifier(tid, "string", context.packageName)
        if (resourceId == 0) {
            Log.w(TAG, "Missing string resource: key='$tid'")
            return tid
        }
        return runCatching { context.getString(resourceId) }
            .getOrElse {
                Log.w(TAG, "Failed to load string resource '$tid': ${it.message}")
                tid
            }
    }

    fun getStringList(context: Context, keyPrefix: String): List<String> {
        return (1..100)
            .mapNotNull { index ->
                val key = "${keyPrefix}_$index"
                val resourceId = context.resources.getIdentifier(key, "string", context.packageName)
                if (resourceId != 0) context.getString(resourceId).takeIf { it.isNotBlank() } else null
            }
            .distinct()
    }

    fun formatString(context: Context, tid: String, vararg args: Any): String {
        val resourceId = context.resources.getIdentifier(tid, "string", context.packageName)
        if (resourceId == 0) return getString(context, tid)

        return runCatching { context.getString(resourceId, *args) }
            .getOrElse {
                Log.w(TAG, "Failed to format string resource '$tid': ${it.message}")
                getString(context, tid)
            }
    }

    fun localizer(context: Context): (String, Array<out Any>) -> String {
        val appContext = context.applicationContext
        return { key, args -> formatString(appContext, key, *args) }
    }

    fun getLanguageDisplayName(context: Context, languageCode: String): String {
        val locale = when (LanguageManager.toSupportedLanguage(languageCode)) {
            "en" -> Locale.ENGLISH
            "ru" -> Locale("ru")
            "fr" -> Locale.FRENCH
            "it" -> Locale.ITALIAN
            "uk" -> Locale("uk")
            "ka" -> Locale("ka")
            else -> Locale.ENGLISH
        }
        return locale.getDisplayLanguage(LanguageManager.getAppLocale()).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(LanguageManager.getAppLocale()) else it.toString()
        }
    }

    fun getSelectedLocale(context: Context): Locale {
        return LanguageManager.getAppLocale()
    }

    fun clearCache() = Unit
}
