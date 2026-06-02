package com.example.chatapp.notifications

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VseGptNotificationClassifierTest {

    private val classifier = VseGptNotificationClassifier(apiKeyProvider = { "test-key" })

    @Test
    fun `buildRequestBody uses VseGPT notification filter model`() {
        val body = JSONObject(
            classifier.buildRequestBody(
                SmartNotificationPayload(
                    sourcePackageName = "com.example.store",
                    title = "Sale",
                    text = "Only today"
                )
            )
        )

        assertEquals("deepseek/deepseek-v4-flash", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))
        assertTrue(body.getJSONArray("messages").toString().contains("SPAM"))
        assertTrue(body.getJSONArray("messages").toString().contains("com.example.store"))
    }

    @Test
    fun `parseDecision recognizes spam response`() {
        val response = completionResponse("SPAM")

        assertEquals(SmartNotificationDecision.SPAM, classifier.parseDecision(response))
    }

    @Test
    fun `parseDecision recognizes keep response`() {
        val response = completionResponse("KEEP")

        assertEquals(SmartNotificationDecision.KEEP, classifier.parseDecision(response))
    }

    private fun completionResponse(content: String): String =
        JSONObject().apply {
            put(
                "choices",
                org.json.JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put(
                                "message",
                                JSONObject().apply {
                                    put("content", content)
                                }
                            )
                        }
                    )
                }
            )
        }.toString()
}
