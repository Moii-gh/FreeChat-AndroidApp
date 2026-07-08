package com.example.chatapp.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchMatcherTest {
    @Test
    fun findsSubstringInsideLongSentence() {
        val matches = ChatSearchMatcher.findMatches(
            listOf(ChatSearchMessage("0", "This is a long assistant sentence with a hidden keyword inside.")),
            "keyword"
        )

        assertEquals(listOf(ChatSearchMatch("0", 0, 48, 55)), matches)
    }

    @Test
    fun findsPhraseWithSpaces() {
        val matches = ChatSearchMatcher.findMatches(
            listOf(ChatSearchMessage("0", "Please find this exact phrase in the message.")),
            "exact phrase"
        )

        assertEquals(listOf(ChatSearchMatch("0", 0, 17, 29)), matches)
    }

    @Test
    fun findsMultipleMatchesInOneAndSeveralMessages() {
        val matches = ChatSearchMatcher.findMatches(
            listOf(
                ChatSearchMessage("0", "cat dog cat"),
                ChatSearchMessage("1", "another cat")
            ),
            "cat"
        )

        assertEquals(
            listOf(
                ChatSearchMatch("0", 0, 0, 3),
                ChatSearchMatch("0", 1, 8, 11),
                ChatSearchMatch("1", 0, 8, 11)
            ),
            matches
        )
    }

    @Test
    fun matchesCaseInsensitively() {
        val matches = ChatSearchMatcher.findMatches(
            listOf(ChatSearchMessage("0", "FreeChat can search freechat messages.")),
            "FREECHAT"
        )

        assertEquals(
            listOf(
                ChatSearchMatch("0", 0, 0, 8),
                ChatSearchMatch("0", 1, 20, 28)
            ),
            matches
        )
    }

    @Test
    fun returnsEmptyForBlankQuery() {
        val matches = ChatSearchMatcher.findMatches(
            listOf(ChatSearchMessage("0", "text")),
            "   "
        )

        assertTrue(matches.isEmpty())
    }

    @Test
    fun returnsEmptyWhenNothingFound() {
        val matches = ChatSearchMatcher.findMatches(
            listOf(ChatSearchMessage("0", "hello world")),
            "missing"
        )

        assertTrue(matches.isEmpty())
    }
}