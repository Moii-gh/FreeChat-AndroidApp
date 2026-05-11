package com.example.chatapp

import com.example.chatapp.network.AiModelCatalog
import com.example.chatapp.network.AiCapabilities
import com.example.chatapp.network.AiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderConfigTest {

    @Test
    fun `unknown or missing provider defaults to OpenAI`() {
        assertEquals(AiProvider.OPENAI, AiProvider.fromCode(null))
        assertEquals(AiProvider.OPENAI, AiProvider.fromCode(""))
        assertEquals(AiProvider.OPENAI, AiProvider.fromCode("unknown"))
    }

    @Test
    fun `saved VseGPT provider code remains valid`() {
        assertEquals(AiProvider.VSEGPT, AiProvider.fromCode("vsegpt"))
    }

    @Test
    fun `provider defaults point to whitelisted model keys`() {
        assertEquals("gpt54", AiModelCatalog.defaultModelKey(AiProvider.OPENAI))
        assertEquals("gemini3", AiModelCatalog.defaultModelKey(AiProvider.VSEGPT))
        assertTrue(AiModelCatalog.modelsFor(AiProvider.VSEGPT).any { it.modelKey == "deepseek" })
    }

    @Test
    fun `capabilities are internal routing metadata`() {
        assertTrue(
            AiModelCatalog.supports(
                AiProvider.OPENAI,
                "gpt54",
                AiCapabilities.WEB_SEARCH
            )
        )
        assertTrue(
            AiModelCatalog.supports(
                AiProvider.OPENAI,
                "gpt54",
                AiCapabilities.IMAGE_EDIT
            )
        )
        assertTrue(
            AiModelCatalog.supports(
                AiProvider.VSEGPT,
                "gemini3",
                AiCapabilities.VISION
            )
        )
    }
}
