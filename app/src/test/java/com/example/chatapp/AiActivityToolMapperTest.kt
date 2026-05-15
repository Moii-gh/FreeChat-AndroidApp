package com.example.chatapp

import com.example.chatapp.ai.AiActivityState
import com.example.chatapp.ai.AiActivityToolMapper
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AiActivityToolMapperTest {

    @Test
    fun `maps known tools to concrete activity states`() {
        assertEquals(AiActivityState.WebSearching, AiActivityToolMapper.fromToolName("web_search"))
        assertEquals(AiActivityState.GeneratingImage, AiActivityToolMapper.fromToolName("image_generation"))
        assertEquals(AiActivityState.EditingImage, AiActivityToolMapper.fromToolName("image_edit"))
        assertEquals(AiActivityState.ExecutingCode, AiActivityToolMapper.fromToolName("code_execution"))
        assertEquals(AiActivityState.ReadingFiles, AiActivityToolMapper.fromToolName("file_search"))
    }

    @Test
    fun `falls back for unknown tools`() {
        assertEquals(AiActivityState.ProcessingRequest, AiActivityToolMapper.fromToolName("future_provider_tool"))
    }

    @Test
    fun `initial request state reflects mode and attachments`() {
        val imageMessage = JSONObject().apply {
            put("role", "user")
            put("content", "Make it brighter")
            put("base64", "aW1hZ2U=")
            put("mimeType", "image/png")
        }
        val fileMessage = JSONObject().apply {
            put("role", "user")
            put("content", "Summarize")
            put("base64", "ZmlsZQ==")
            put("mimeType", "text/plain")
            put("fileName", "notes.txt")
        }

        assertEquals(
            AiActivityState.WebSearching,
            AiActivityToolMapper.initialStateForRequest(ChatMode.SEARCH, listOf(JSONObject()))
        )
        assertEquals(
            AiActivityState.EditingImage,
            AiActivityToolMapper.initialStateForRequest(ChatMode.CREATE_IMAGE, listOf(imageMessage))
        )
        assertEquals(
            AiActivityState.ReadingFiles,
            AiActivityToolMapper.initialStateForRequest(null, listOf(fileMessage))
        )
    }

    @Test
    fun `reads provider tool events from streamed chunks`() {
        val event = JSONObject(
            """
            {
              "choices": [
                {
                  "delta": {
                    "tool_calls": [
                      {
                        "type": "function",
                        "function": { "name": "web_search" }
                      }
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(AiActivityState.WebSearching, AiActivityToolMapper.fromProviderEvent(event))
    }
}
