package com.example.chatapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatShareDeepLinkTest {
    private val token = "a".repeat(32)

    @Test
    fun `extracts token from app scheme`() {
        assertEquals(token, ChatShareDeepLink.extractToken("freechat://share/$token"))
    }

    @Test
    fun `rejects short or malformed tokens`() {
        assertNull(ChatShareDeepLink.extractToken("freechat://share/short"))
        assertNull(ChatShareDeepLink.extractToken("freechat://share/${"a".repeat(31)}!"))
        assertNull(ChatShareDeepLink.extractToken("freechat://share/$token/extra"))
    }

    @Test
    fun `rejects public share links from unconfigured hosts`() {
        assertNull(ChatShareDeepLink.extractToken("https://evil.example/share/$token"))
    }

    @Test
    fun `extracts public share link only from configured host`() {
        val link = "${BuildConfig.CHAT_SHARE_PUBLIC_BASE_URL.trimEnd('/')}/share/$token"

        assertEquals(token, ChatShareDeepLink.extractToken(link))
    }
}
