package com.example.chatapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SecurityLocalizationTest {

    @Test
    fun `security localization keys exist for every supported language`() {
        val languageMaps = LocaleHelper.SUPPORTED_LANGUAGE_CODES.associateWith { code ->
            readCsv(languageFile(code))
        }

        languageMaps.forEach { (code, translations) ->
            val missingKeys = REQUIRED_KEYS.filterNot(translations::containsKey)
            assertTrue("$code is missing keys: $missingKeys", missingKeys.isEmpty())
        }
    }

    @Test
    fun `security localization placeholders match english`() {
        val english = readCsv(languageFile("en"))
        val expectedPlaceholders = REQUIRED_KEYS.associateWith { key ->
            PLACEHOLDER_REGEX.findAll(english.getValue(key)).map { it.value }.toList()
        }

        LocaleHelper.SUPPORTED_LANGUAGE_CODES
            .filterNot { it == "en" }
            .forEach { code ->
                val translations = readCsv(languageFile(code))
                REQUIRED_KEYS.forEach { key ->
                    val actual = PLACEHOLDER_REGEX.findAll(translations.getValue(key)).map { it.value }.toList()
                    assertEquals("$code:$key placeholders differ", expectedPlaceholders.getValue(key), actual)
                }
            }
    }

    private fun languageFile(code: String): File {
        return listOf(
            File("src/main/assets/languages/$code.csv"),
            File("app/src/main/assets/languages/$code.csv")
        ).first { it.exists() }
    }

    private fun readCsv(file: File): Map<String, String> {
        val keys = linkedMapOf<String, String>()
        file.readLines(Charsets.UTF_8).drop(1).forEach { line ->
            val separatorIndex = line.indexOf(';')
            if (separatorIndex > 0) {
                val key = line.substring(0, separatorIndex)
                val value = line.substring(separatorIndex + 1)
                require(!keys.containsKey(key)) { "Duplicate localization key '$key' in ${file.name}" }
                keys[key] = value
            }
        }
        return keys
    }

    private companion object {
        val PLACEHOLDER_REGEX = Regex("%(?:\\d+\\$)?[sd]")

        val REQUIRED_KEYS = listOf(
            "password_error_confirm_required",
            "password_error_current_incorrect",
            "password_error_new_complexity",
            "password_error_new_required",
            "password_requirement_letters_digits",
            "security_biometric_auth_failed",
            "security_biometric_disabled",
            "security_biometric_enabled",
            "security_biometric_no_hardware",
            "security_biometric_not_enrolled",
            "security_biometric_prompt_subtitle",
            "security_biometric_prompt_title",
            "security_biometric_required_message",
            "security_biometric_required_title",
            "security_biometric_retry",
            "security_biometric_status_absent",
            "security_biometric_status_enabled",
            "security_biometric_title",
            "security_biometric_unlock_subtitle",
            "security_biometric_unlock_title",
            "security_biometric_unavailable",
            "security_biometric_use_login",
            "security_encryption_description",
            "security_encryption_title",
            "security_faq_action_ask_ai",
            "security_faq_action_answer",
            "security_faq_action_view",
            "security_faq_answer_label",
            "security_faq_data_protection_answer",
            "security_faq_data_storage_answer",
            "security_faq_data_storage_title",
            "security_faq_safe_password_ai_question",
            "security_faq_safe_password_answer",
            "security_faq_safe_password_title",
            "security_faq_telegram_answer",
            "security_faq_telegram_title",
            "security_faq_title",
            "security_password_change_success",
            "security_password_mask",
            "security_password_not_set",
            "security_password_unavailable",
            "security_your_password_label"
        )
    }
}
