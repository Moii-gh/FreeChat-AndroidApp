package com.example.chatapp.notifications

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SmartNotificationServerClassifierTest {

    private val classifier = SmartNotificationServerClassifier(
        authTokenProvider = { "test-token" },
        apiBaseUrl = "https://api.example.test/api/"
    )

    @Test
    fun `buildRequestBody sends notification fields to backend`() {
        val body = JSONObject(
            classifier.buildRequestBody(
                SmartNotificationPayload(
                    sourcePackageName = "com.example.store",
                    title = "Sale",
                    text = "Only today"
                )
            )
        )

        assertEquals("com.example.store", body.getString("packageName"))
        assertEquals("Sale", body.getString("title"))
        assertEquals("Only today", body.getString("text"))
    }

    @Test
    fun `parseDecision recognizes spam response`() {
        assertEquals(
            SmartNotificationDecision.SPAM,
            classifier.parseDecision(decisionResponse("SPAM"))
        )
    }

    @Test
    fun `parseDecision recognizes keep response`() {
        assertEquals(
            SmartNotificationDecision.KEEP,
            classifier.parseDecision(decisionResponse("KEEP"))
        )
    }

    private fun decisionResponse(decision: String): String =
        JSONObject().apply {
            put("decision", decision)
        }.toString()
}
