package com.example.chatapp.ui.markdown

object StreamingLinkExtractor {
    private val markdownLinkRegex = Regex("""!?\[([^]\n]+)]\s*\(([^)\s]+)\)""")
    private val htmlAnchorRegex = Regex(
        pattern = """<a\s+[^>]*href\s*=\s*(['"])(.*?)\1[^>]*>(.*?)</a>""",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val rawUrlRegex = Regex("""(?i)\b((?:https?://|www\.)[^\s<>()]+)""")

    fun extractLinks(markdown: String): List<MarkdownLinkRange> {
        if (markdown.isBlank()) return emptyList()

        val links = mutableListOf<MarkdownLinkRange>()
        val occupied = mutableListOf<IntRange>()

        markdownLinkRegex.findAll(markdown).forEach { match ->
            if (match.value.startsWith("![")) return@forEach
            val safeUrl = SafeUrlSanitizer.normalize(match.groupValues[2]) ?: return@forEach
            links += MarkdownLinkRange(
                start = match.range.first,
                end = match.range.last + 1,
                url = safeUrl,
                label = match.groupValues[1]
            )
            occupied += match.range
        }

        htmlAnchorRegex.findAll(markdown).forEach { match ->
            val safeUrl = SafeUrlSanitizer.normalize(match.groupValues[2]) ?: return@forEach
            if (occupied.any { it.overlaps(match.range) }) return@forEach
            links += MarkdownLinkRange(
                start = match.range.first,
                end = match.range.last + 1,
                url = safeUrl,
                label = match.groupValues[3]
            )
            occupied += match.range
        }

        occupied += incompleteMarkdownLinkRanges(markdown)

        rawUrlRegex.findAll(markdown).forEach { match ->
            if (occupied.any { it.overlaps(match.range) }) return@forEach
            val safeUrl = SafeUrlSanitizer.normalize(match.value) ?: return@forEach
            links += MarkdownLinkRange(
                start = match.range.first,
                end = match.range.last + 1,
                url = safeUrl,
                label = match.value
            )
        }

        return links
            .sortedBy { it.start }
            .take(MAX_LINKS_PER_CHUNK)
    }

    fun canonicalizeMarkdownLinks(markdown: String): String {
        if (!markdown.contains("]")) return markdown
        return markdownLinkRegex.replace(markdown) { match ->
            if (match.value.startsWith("![")) {
                match.value
            } else {
                "[${match.groupValues[1]}](${match.groupValues[2]})"
            }
        }
    }

    private fun incompleteMarkdownLinkRanges(markdown: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var index = markdown.indexOf('[')
        while (index >= 0 && index < markdown.length) {
            val closeBracket = markdown.indexOf(']', startIndex = index + 1)
            if (closeBracket < 0) {
                ranges += index until markdown.length
                break
            }

            var cursor = closeBracket + 1
            while (cursor < markdown.length && markdown[cursor].isWhitespace() && markdown[cursor] != '\n') {
                cursor++
            }
            if (cursor < markdown.length && markdown[cursor] == '(') {
                val closeParen = markdown.indexOf(')', startIndex = cursor + 1)
                if (closeParen < 0) {
                    ranges += index until markdown.length
                    break
                }
                index = markdown.indexOf('[', startIndex = closeParen + 1)
            } else {
                index = markdown.indexOf('[', startIndex = closeBracket + 1)
            }
        }
        return ranges
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last

    private const val MAX_LINKS_PER_CHUNK = 128
}
