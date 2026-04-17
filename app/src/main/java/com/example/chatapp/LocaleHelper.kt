package com.example.chatapp

import android.content.Context
import android.content.SharedPreferences
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Helper class for managing app language/localization using CSV translation files.
 * CSV files are stored in assets/languages/ folder with format: TID;TEXT
 */
object LocaleHelper {

    private const val PREFS_NAME = "language_prefs"
    private const val KEY_LANGUAGE = "selected_language"
    private const val DEFAULT_LANGUAGE = "ru"

    // Map of language codes to their display names (as shown in the language selector)
    val LANGUAGES = linkedMapOf(
        "ru" to "Русский",
        "en" to "English",
        "fr" to "French",
        "it" to "Italian",
        "br" to "Беларуская",
        "uk" to "Українська",
        "go" to "ქართული (საქართველო)",
    )

    // Cache for loaded translations
    private var cachedTranslations: Map<String, String>? = null
    private var cachedLanguageCode: String? = null

    /**
     * Get the currently selected language code
     */
    fun getSelectedLanguage(context: Context): String {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    /**
     * Set the selected language code and clear cache
     */
    fun setSelectedLanguage(context: Context, languageCode: String) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()
        cachedTranslations = null
        cachedLanguageCode = null
        cachedDefaultTranslations = null
    }

    // Separate cache for fallback (default language) translations
    private var cachedDefaultTranslations: Map<String, String>? = null

    /**
     * Get a translated string by its TID key.
     * Falls back to Russian if the key is not found in the current language.
     */
    fun getString(context: Context, tid: String): String {
        val langCode = getSelectedLanguage(context)
        val translations = getTranslations(context, langCode)
        if (translations.containsKey(tid)) return translations[tid]!!
        // Fallback to default language without corrupting the main cache
        val defaults = getDefaultTranslations(context)
        return defaults[tid] ?: tid
    }

    /**
     * Load default language translations (separate cache to avoid corrupting main lang cache)
     */
    private fun getDefaultTranslations(context: Context): Map<String, String> {
        cachedDefaultTranslations?.let { return it }
        val translations = loadTranslationsFromCsv(context, DEFAULT_LANGUAGE)
        cachedDefaultTranslations = translations
        return translations
    }

    /**
     * Load all translations for a given language code from CSV
     */
    fun getTranslations(context: Context, languageCode: String): Map<String, String> {
        // Return cached if available
        if (cachedLanguageCode == languageCode && cachedTranslations != null) {
            return cachedTranslations!!
        }

        val translations = loadTranslationsFromCsv(context, languageCode)

        cachedLanguageCode = languageCode
        cachedTranslations = translations
        return translations
    }

    /**
     * Low-level CSV loader, no caching
     */
    private fun loadTranslationsFromCsv(context: Context, languageCode: String): Map<String, String> {
        val translations = mutableMapOf<String, String>()
        try {
            val inputStream = context.assets.open("languages/$languageCode.csv")
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            var line: String?

            // Skip header
            reader.readLine()

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: continue
                val separatorIndex = currentLine.indexOf(';')
                if (separatorIndex > 0 && separatorIndex < currentLine.length - 1) {
                    val tid = currentLine.substring(0, separatorIndex).trim()
                    val text = currentLine.substring(separatorIndex + 1).trim()
                    if (tid.isNotEmpty() && text.isNotEmpty()) {
                        translations[tid] = text
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
            // If file not found, return default language translations
            if (languageCode != DEFAULT_LANGUAGE) {
                return getDefaultTranslations(context)
            }
        }
        return translations
    }

    /**
     * Get the display name for a language code
     */
    fun getLanguageDisplayName(languageCode: String): String {
        return LANGUAGES[languageCode] ?: languageCode
    }

    /**
     * Clear the translation cache (call when language changes)
     */
    fun clearCache() {
        cachedTranslations = null
        cachedLanguageCode = null
        cachedDefaultTranslations = null
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
