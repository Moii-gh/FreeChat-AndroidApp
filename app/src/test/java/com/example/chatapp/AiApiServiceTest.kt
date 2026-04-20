package com.example.chatapp

import com.example.chatapp.network.AiApiService
import com.example.chatapp.network.AiTarget
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiApiServiceTest {

    @Test
    fun `compatible request includes image data url`() {
        val target = compatibleTarget()
        val message = JSONObject().apply {
            put("role", "user")
            put("content", "What is in this photo?")
            put("base64", "aW1hZ2U=")
            put("mimeType", "image/png")
            put("fileName", "photo.png")
        }

        val body = JSONObject(
            AiApiService.buildCompatibleRequestBody(target, listOf(message), systemPrompt = null)
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
    fun `compatible request embeds extracted text file content`() {
        val target = compatibleTarget()
        val message = JSONObject().apply {
            put("role", "user")
            put("content", "Summarize this file")
            put("mimeType", "text/plain")
            put("fileName", "notes.txt")
            put("fileText", "alpha\nbeta")
        }

        val body = JSONObject(
            AiApiService.buildCompatibleRequestBody(target, listOf(message), systemPrompt = null)
        )

        val content = body.getJSONArray("messages")
            .getJSONObject(0)
            .getString("content")
        assertTrue(content.contains("Attached file: notes.txt"))
        assertTrue(content.contains("File content:"))
        assertTrue(content.contains("alpha\nbeta"))
        assertFalse(content.contains("base64"))
    }

    @Test
    fun `native request includes binary file inline data`() {
        val message = JSONObject().apply {
            put("role", "user")
            put("content", "Analyze this PDF")
            put("base64", "JVBERi0x")
            put("mimeType", "application/pdf")
            put("fileName", "report.pdf")
        }

        val body = JSONObject(
            AiApiService.buildNativeRequestBody(listOf(message), systemPrompt = null)
        )

        val parts = body.getJSONArray("contents")
            .getJSONObject(0)
            .getJSONArray("parts")
        assertTrue(parts.getJSONObject(0).getString("text").contains("Attached file: report.pdf"))
        assertEquals(
            "application/pdf",
            parts.getJSONObject(1)
                .getJSONObject("inline_data")
                .getString("mime_type")
        )
        assertEquals(
            "JVBERi0x",
            parts.getJSONObject(1)
                .getJSONObject("inline_data")
                .getString("data")
        )
    }

    private fun compatibleTarget(): AiTarget =
        AiTarget(
            route = "primary",
            model = "test-model",
            apiUrl = "https://api.example.com/v1/chat/completions",
            usesNativeProtocol = false,
            isImageGeneration = false
        )
}
