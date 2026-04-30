package com.example.chatapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

/**
 * Loads app localization from CSV files in assets/languages/ with format TID;TEXT.
 */
object LocaleHelper {

    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANGUAGE = "selected_language"
    private const val DEFAULT_LANGUAGE = "en"
    private const val TAG = "LocaleHelper"

    val SUPPORTED_LANGUAGE_CODES = listOf("en", "ru", "fr", "it", "br", "uk", "go")

    val LANGUAGES: LinkedHashMap<String, String> = linkedMapOf(
        *SUPPORTED_LANGUAGE_CODES.map { it to it }.toTypedArray()
    )

    private val languageLocales = mapOf(
        "en" to Locale.ENGLISH,
        "ru" to Locale("ru"),
        "fr" to Locale.FRENCH,
        "it" to Locale.ITALIAN,
        "br" to Locale("be"),
        "uk" to Locale("uk"),
        "go" to Locale("ka")
    )

    private val cachedTranslations = mutableMapOf<String, Map<String, String>>()
    private val loggedMissingKeys = mutableSetOf<String>()
    private val loggedDuplicateKeys = mutableSetOf<String>()

    fun getSelectedLanguage(context: Context): String {
        val prefs = getPrefs(context)
        val storedLanguage = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        return storedLanguage.takeIf { isSupportedLanguage(it) } ?: DEFAULT_LANGUAGE
    }

    fun setSelectedLanguage(context: Context, languageCode: String) {
        val normalizedLanguageCode = languageCode.takeIf { isSupportedLanguage(it) } ?: DEFAULT_LANGUAGE
        getPrefs(context).edit().putString(KEY_LANGUAGE, normalizedLanguageCode).apply()
        clearCache()
    }

    fun getString(context: Context, tid: String): String {
        val langCode = getSelectedLanguage(context)
        val translations = getTranslations(context, langCode)
        translations[tid]?.takeIf { it.isNotBlank() }?.let { return it }

        logMissingTranslation(langCode, tid)

        val defaults = getTranslations(context, DEFAULT_LANGUAGE)
        defaults[tid]?.takeIf { it.isNotBlank() }?.let { return it }

        logMissingTranslation(DEFAULT_LANGUAGE, tid)
        return tid
    }

    fun getStringList(context: Context, keyPrefix: String): List<String> {
        val langCode = getSelectedLanguage(context)
        val localized = getPrefixedStrings(getTranslations(context, langCode), keyPrefix)
        if (localized.isNotEmpty()) return localized

        logMissingTranslation(langCode, "${keyPrefix}_*")

        val defaults = getPrefixedStrings(getTranslations(context, DEFAULT_LANGUAGE), keyPrefix)
        if (defaults.isNotEmpty()) return defaults

        logMissingTranslation(DEFAULT_LANGUAGE, "${keyPrefix}_*")
        return emptyList()
    }

    fun formatString(context: Context, tid: String, vararg args: Any): String {
        val template = getString(context, tid)
        return runCatching {
            String.format(getSelectedLocale(context), template, *args)
        }.getOrElse {
            Log.w(TAG, "Failed to format localization key '$tid': ${it.message}")
            template
        }
    }

    fun localizer(context: Context): (String, Array<out Any>) -> String {
        val appContext = context.applicationContext
        return { key, args -> formatString(appContext, key, *args) }
    }

    fun getTranslations(context: Context, languageCode: String): Map<String, String> {
        val normalizedLanguageCode = languageCode.takeIf { isSupportedLanguage(it) } ?: DEFAULT_LANGUAGE
        cachedTranslations[normalizedLanguageCode]?.let { return it }

        val translations = loadTranslationsFromCsv(context, normalizedLanguageCode)
        cachedTranslations[normalizedLanguageCode] = translations
        return translations
    }

    private fun loadTranslationsFromCsv(context: Context, languageCode: String): Map<String, String> {
        val translations = mutableMapOf<String, String>()
        try {
            val inputStream = context.assets.open("languages/$languageCode.csv")
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

            reader.readLine()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                val separatorIndex = currentLine.indexOf(';')
                if (separatorIndex > 0 && separatorIndex < currentLine.length - 1) {
                    val tid = currentLine.substring(0, separatorIndex).trim()
                    val text = decodeCsvText(currentLine.substring(separatorIndex + 1).trim())
                    if (tid.isNotEmpty() && text.isNotEmpty()) {
                        if (translations.containsKey(tid)) {
                            logDuplicateTranslation(languageCode, tid)
                        }
                        translations[tid] = text
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load language '$languageCode': ${e.message}")
            if (languageCode != DEFAULT_LANGUAGE) {
                return getTranslations(context, DEFAULT_LANGUAGE)
            }
        }
        return translations
    }

    fun getLanguageDisplayName(context: Context, languageCode: String): String {
        return getString(context, "language_name_$languageCode")
    }

    fun getSelectedLocale(context: Context): Locale {
        return languageLocales[getSelectedLanguage(context)] ?: Locale.ENGLISH
    }

    fun clearCache() {
        cachedTranslations.clear()
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun isSupportedLanguage(languageCode: String): Boolean {
        return languageCode in SUPPORTED_LANGUAGE_CODES
    }

    private fun getPrefixedStrings(translations: Map<String, String>, keyPrefix: String): List<String> {
        val keyPattern = Regex("^${Regex.escape(keyPrefix)}_(\\d+)$")
        return translations
            .mapNotNull { (key, value) ->
                val index = keyPattern.matchEntire(key)?.groupValues?.getOrNull(1)?.toIntOrNull()
                if (index != null && value.isNotBlank()) index to value else null
            }
            .sortedBy { it.first }
            .map { it.second }
            .distinct()
    }

    private fun decodeCsvText(text: String): String {
        return text
            .replace("\\n", "\n")
            .replace("\\;", ";")
    }

    private fun logMissingTranslation(languageCode: String, tid: String) {
        val logKey = "$languageCode:$tid"
        if (loggedMissingKeys.add(logKey)) {
            Log.w(TAG, "Missing translation: language='$languageCode', key='$tid'")
        }
    }

    private fun logDuplicateTranslation(languageCode: String, tid: String) {
        val logKey = "$languageCode:$tid"
        if (loggedDuplicateKeys.add(logKey)) {
            Log.w(TAG, "Duplicate translation key: language='$languageCode', key='$tid'")
        }
    }
}
