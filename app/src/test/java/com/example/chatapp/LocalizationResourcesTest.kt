package com.example.chatapp

import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationResourcesTest {

    @Test
    fun `runtime localization files use fallback key order`() {
        val fallbackKeys = readStrings(stringsFile("en")).keys.toList()

        LocaleHelper.SUPPORTED_LANGUAGE_CODES
            .filterNot { it == "en" }
            .forEach { code ->
                val localizedKeys = readStrings(stringsFile(code)).keys.toList()
                assertEquals("$code localization key order differs from fallback", fallbackKeys, localizedKeys)
            }
    }

    @Test
    fun `runtime localization placeholders match fallback`() {
        val fallback = readStrings(stringsFile("en"))
        val expectedPlaceholders = fallback.mapValues { (_, value) ->
            PLACEHOLDER_REGEX.findAll(value).map { it.value }.toList()
        }

        LocaleHelper.SUPPORTED_LANGUAGE_CODES
            .filterNot { it == "en" }
            .forEach { code ->
                val translations = readStrings(stringsFile(code))
                fallback.keys.forEach { key ->
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
    }
}
