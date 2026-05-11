package com.example.chatapp

import com.example.chatapp.network.AiApiService
import com.example.chatapp.network.AiProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiServiceTest {

    @Test
    fun `system prompt asks for meaningful markdown or html links`() {
        val prompt = requireNotNull(AiApiService.buildSystemPrompt(
            currentMode = null,
            customInstructions = "",
            chatContextSummary = "",
            filesContext = ""
        ))

        assertTrue(prompt.contains("[meaningful link text](URL)"))
        assertTrue(prompt.contains("<a href=\"URL\">meaningful link text</a>"))
        assertTrue(prompt.contains("Do not leave raw plain-text URLs"))
    }

    @Test
    fun `request includes image data url`() {
        val message = JSONObject().apply {
            put("role", "user")
            put("content", "What is in this photo?")
            put("base64", "aW1hZ2U=")
            put("mimeType", "image/png")
            put("fileName", "photo.png")
        }

        val body = JSONObject(
            AiApiService.buildRequestBody(
                isImageGeneration = false,
                messagesToKeep = listOf(message),
                systemPrompt = null
            )
        )

        val content = body.getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(
            content.getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url")
                .startsWith("data:image/png;base64,")
        )
    }

    @Test
    fun `screen assistant image uses existing image input shape`() {
        val message = JSONObject().apply {
            put("role", "user")
            put("content", "What is shown on the screen? Help the user understand it.")
            put("base64", "c2NyZWVu")
            put("mimeType", "image/jpeg")
            put("fileName", "screen.jpg")
        }

        val body = JSONObject(
            AiApiService.buildRequestBody(
                isImageGeneration = false,
                messagesToKeep = listOf(message),
                systemPrompt = null
            )
        )

        val content = body.getJSONArray("messages")
            .getJSONObject(0)
            .getJSONArray("content")
        assertTrue(content.getJSONObject(0).getString("text").contains("screen.jpg"))
        assertTrue(content.getJSONObject(0).getString("text").contains("image/jpeg"))
        assertEquals(
            "data:image/jpeg;base64,c2NyZWVu",
            content.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
    }

    @Test
    fun `request embeds extracted text file content`() {
        val message = JSONObject().apply {
            put("role", "user")
            put("content", "Summarize this file")
            put("mimeType", "text/plain")
            put("fileName", "notes.txt")
            put("base64", "YWxwaGEKYmV0YQ==")
            put("fileText", "alpha\nbeta")
        }

        val body = JSONObject(
            AiApiService.buildRequestBody(
                isImageGeneration = false,
                messagesToKeep = listOf(message),
                systemPrompt = null
            )
        )

        val content = body.getJSONArray("messages")
            .getJSONObject(0)
            .getString("content")
        assertTrue(content.contains("notes.txt"))
        assertTrue(content.contains("text/plain"))
        assertTrue(content.contains("alpha\nbeta"))
        assertFalse(content.contains("base64"))

        val fileSearchFile = body.getJSONArray("fileSearchFiles").getJSONObject(0)
        assertEquals("YWxwaGEKYmV0YQ==", fileSearchFile.getString("base64"))
        assertEquals("text/plain", fileSearchFile.getString("mimeType"))
        assertEquals("notes.txt", fileSearchFile.getString("fileName"))
    }

    @Test
    fun `image generation request keeps source image for backend image edit routing`() {
        val message = JSONObject().apply {
            put("role", "user")
            put("content", "Make the background brighter")
            put("base64", "aW1hZ2U=")
            put("mimeType", "image/png")
            put("fileName", "photo.png")
        }

        val body = JSONObject(
            AiApiService.buildRequestBody(
                isImageGeneration = true,
                messagesToKeep = listOf(message),
                systemPrompt = null
            )
        )

        assertEquals("Make the background brighter", body.getString("prompt"))
        assertEquals(
            "data:image/png;base64,aW1hZ2U=",
            body.getJSONArray("images")
                .getJSONObject(0)
                .getString("image_url")
        )
    }

    @Test
    fun `json image response can be rendered outside explicit image mode`() {
        val rendered = AiApiService.parseImageResponseBody(
            """{"data":[{"b64_json":"aW1hZ2U="}]}"""
        )

        assertEquals("![image](data:image/png;base64,aW1hZ2U=)", rendered)
    }

    @Test
    fun `chat payload sends provider and model key without secrets or display names`() {
        val requestBody = AiApiService.buildRequestBody(
            isImageGeneration = false,
            messagesToKeep = listOf(JSONObject().apply {
                put("role", "user")
                put("content", "Hello")
            }),
            systemPrompt = null
        )

        val payload = JSONObject(
            AiApiService.buildChatPayload(
                provider = AiProvider.OPENAI,
                modelKey = "gpt54",
                currentMode = null,
                adultMode = false,
                requestBody = requestBody
            )
        )

        assertEquals("openai", payload.getString("provider"))
        assertEquals("gpt54", payload.getString("modelKey"))
        assertFalse(payload.has("displayName"))
        assertFalse(payload.toString().contains("api_key", ignoreCase = true))
        assertFalse(payload.toString().contains("sk-"))
    }

    @Test
    fun `title payload sends provider model and assistant answer without secrets`() {
        val payload = JSONObject(
            AiApiService.buildTitlePayload(
                provider = AiProvider.VSEGPT,
                modelKey = "gemini3",
                firstUserMessage = "Как сварить кофе?",
                firstAssistantMessage = "Вот короткий рецепт."
            )
        )

        assertEquals("vsegpt", payload.getString("provider"))
        assertEquals("gemini3", payload.getString("modelKey"))
        assertEquals("Как сварить кофе?", payload.getString("firstUserMessage"))
        assertEquals("Вот короткий рецепт.", payload.getString("firstAssistantMessage"))
        assertFalse(payload.has("apiKey"))
        assertFalse(payload.toString().contains("sk-"))
    }
}
