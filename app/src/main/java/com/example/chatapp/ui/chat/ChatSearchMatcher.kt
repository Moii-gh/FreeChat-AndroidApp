package com.example.chatapp.ui.chat

internal data class ChatSearchMessage(
    val messageKey: String,
    val text: String
)

internal data class ChatSearchMatch(
    val messageKey: String,
    val matchIndexInMessage: Int,
    val start: Int,
    val end: Int
)

internal object ChatSearchMatcher {
    fun findMatches(messages: List<ChatSearchMessage>, query: String): List<ChatSearchMatch> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) return emptyList()

        val result = mutableListOf<ChatSearchMatch>()
        messages.forEach { message ->
            var matchIndexInMessage = 0
            findRanges(message.text, normalizedQuery).forEach { range ->
                result += ChatSearchMatch(
                    messageKey = message.messageKey,
                    matchIndexInMessage = matchIndexInMessage,
                    start = range.first,
                    end = range.last + 1
                )
                matchIndexInMessage += 1
            }
        }
        return result
    }

    fun findRanges(text: String, query: String): List<IntRange> {
        if (query.isEmpty() || text.isEmpty() || query.length > text.length) return emptyList()

        val ranges = mutableListOf<IntRange>()
        var index = 0
        while (index <= text.length - query.length) {
            if (text.regionMatches(index, query, 0, query.length, ignoreCase = true)) {
                ranges += index until index + query.length
                index += query.length
            } else {
                index += 1
            }
        }
        return ranges
    }
}