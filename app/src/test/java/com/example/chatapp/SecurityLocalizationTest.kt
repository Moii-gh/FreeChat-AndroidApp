package com.example.chatapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SecurityLocalizationTest {

    @Test
    fun `all localization files use the same keys`() {
        val englishKeys = readStrings(stringsFile("en")).keys

        LocaleHelper.SUPPORTED_LANGUAGE_CODES
            .filterNot { it == "en" }
            .forEach { code ->
                val localizedKeys = readStrings(stringsFile(code)).keys
                assertEquals("$code localization keys differ from English", englishKeys, localizedKeys)
            }
    }

    @Test
    fun `security localization keys exist for every supported language`() {
        val languageMaps = LocaleHelper.SUPPORTED_LANGUAGE_CODES.associateWith { code ->
            readStrings(stringsFile(code))
        }

        languageMaps.forEach { (code, translations) ->
            val missingKeys = REQUIRED_KEYS.filterNot(translations::containsKey)
            assertTrue("$code is missing keys: $missingKeys", missingKeys.isEmpty())
        }
    }

    @Test
    fun `security localization placeholders match english`() {
        val english = readStrings(stringsFile("en"))
        val expectedPlaceholders = REQUIRED_KEYS.associateWith { key ->
            PLACEHOLDER_REGEX.findAll(english.getValue(key)).map { it.value }.toList()
        }

        LocaleHelper.SUPPORTED_LANGUAGE_CODES
            .filterNot { it == "en" }
            .forEach { code ->
                val translations = readStrings(stringsFile(code))
                REQUIRED_KEYS.forEach { key ->
                    val actual = PLACEHOLDER_REGEX.findAll(translations.getValue(key)).map { it.value }.toList()
                    assertEquals("$code:$key placeholders differ", expectedPlaceholders.getValue(key), actual)
                }
            }
    }

    private fun stringsFile(code: String): File {
        val valuesDirectory = if (code == "en") "values" else "values-$code"
        return listOf(
            File("src/main/res/$valuesDirectory/strings.xml"),
            File("app/src/main/res/$valuesDirectory/strings.xml")
        ).first { it.exists() }
    }

    private fun readStrings(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        val keys = linkedMapOf<String, String>()

        for (index in 0 until strings.length) {
            val element = strings.item(index) as Element
            val key = element.getAttribute("name")
            require(!keys.containsKey(key)) { "Duplicate localization key '$key' in ${file.path}" }
            keys[key] = element.textContent
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
