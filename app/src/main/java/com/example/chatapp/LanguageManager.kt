package com.example.chatapp

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LanguageManager {

    private const val FALLBACK_LANGUAGE = "en"

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
        val locale = resolveAppLocale()
        Locale.setDefault(locale)

        val configuration = Configuration(baseContext.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
        }
        return baseContext.createConfigurationContext(configuration)
    }

    fun getAppLanguage(): String = resolveAppLocale().language

    fun getAppLocale(): Locale = resolveAppLocale()

    fun isSupported(languageCode: String): Boolean {
        return normalizeLanguageCode(languageCode) in supportedLocales
    }

    fun toSupportedLanguage(languageCode: String): String {
        return normalizeLanguageCode(languageCode).takeIf(::isSupported) ?: FALLBACK_LANGUAGE
    }

    private fun resolveAppLocale(): Locale {
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
