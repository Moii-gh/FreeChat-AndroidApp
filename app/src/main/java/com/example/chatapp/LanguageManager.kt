package com.example.chatapp

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LanguageManager {

    private const val FALLBACK_LANGUAGE = "en"
    private const val PREFS_NAME = "app_language_preferences"
    private const val KEY_SELECTED_LANGUAGE = "selected_language"

    private val supportedLocales = linkedMapOf(
        "en" to Locale.ENGLISH,
        "ru" to Locale("ru"),
        "fr" to Locale.FRENCH,
        "it" to Locale.ITALIAN,
        "uk" to Locale("uk"),
        "ka" to Locale("ka")
    )

    val supportedLanguageCodes: List<String> = supportedLocales.keys.toList()

    fun applyLocale(baseContext: Context): Context {
        val locale = resolveAppLocale(baseContext)
        Locale.setDefault(locale)
        return createLocalizedContext(baseContext, locale)
    }

    fun createLocalizedContext(baseContext: Context): Context {
        return createLocalizedContext(baseContext, resolveAppLocale(baseContext))
    }

    private fun createLocalizedContext(baseContext: Context, locale: Locale): Context {
        val configuration = Configuration(baseContext.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
        }
        return baseContext.createConfigurationContext(configuration)
    }

    fun getAppLanguage(context: Context): String = resolveAppLocale(context).language

    fun getAppLocale(context: Context): Locale = resolveAppLocale(context)

    fun getAppLocale(): Locale = Locale.getDefault().takeIf {
        isSupported(it.language)
    } ?: supportedLocales.getValue(FALLBACK_LANGUAGE)

    fun setAppLanguage(context: Context, languageCode: String): Boolean {
        val language = toSupportedLanguage(languageCode)
        val previous = getStoredLanguage(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_LANGUAGE, language)
            .apply()
        Locale.setDefault(supportedLocales.getValue(language))
        return previous != language
    }

    fun resetAppLanguage(context: Context): Boolean {
        val previous = getStoredLanguage(context)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SELECTED_LANGUAGE)
            .apply()
        Locale.setDefault(resolveAppLocale(context))
        return previous != null
    }

    fun getStoredLanguage(context: Context): String? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_LANGUAGE, null)
        return stored?.let(::toSupportedLanguage)
    }

    fun isSupported(languageCode: String): Boolean {
        return normalizeLanguageCode(languageCode) in supportedLocales
    }

    fun toSupportedLanguage(languageCode: String): String {
        return normalizeLanguageCode(languageCode).takeIf(::isSupported) ?: FALLBACK_LANGUAGE
    }

    private fun resolveAppLocale(context: Context): Locale {
        getStoredLanguage(context)?.let { language ->
            return supportedLocales.getValue(language)
        }

        val systemLocales = Resources.getSystem().configuration.let { configuration ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                configuration.locales
            } else {
                @Suppress("DEPRECATION")
                LocaleList(configuration.locale)
            }
        }

        for (index in 0 until systemLocales.size()) {
            val locale = systemLocales[index] ?: continue
            supportedLocales[normalizeLanguageCode(locale.language)]?.let { return it }
        }

        return supportedLocales.getValue(FALLBACK_LANGUAGE)
    }

    private fun normalizeLanguageCode(languageCode: String): String {
        return when (languageCode.lowercase(Locale.ROOT)) {
            "go" -> "ka"
            else -> languageCode.lowercase(Locale.ROOT)
        }
    }
}
