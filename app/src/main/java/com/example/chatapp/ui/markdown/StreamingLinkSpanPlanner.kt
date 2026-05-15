package com.example.chatapp.ui.markdown

internal data class RenderedLinkSpan(
    val start: Int,
    val end: Int,
    val url: String
)

internal object StreamingLinkSpanPlanner {
    private val rawUrlRegex = Regex("""(?i)\b((?:https?://|www\.)[^\s<>()]+)""")
    private val trailingLinkPunctuation = Regex("""[\s)>}\],.!?;:'"]+$""")

    fun plan(
        sourceMarkdown: String,
        renderedText: String,
        extractedLinks: List<MarkdownLinkRange> = StreamingLinkExtractor.extractLinks(sourceMarkdown)
    ): List<RenderedLinkSpan> {
        if (renderedText.isBlank()) return emptyList()

        val planned = mutableListOf<RenderedLinkSpan>()
        var searchStart = 0

        extractedLinks.forEach { link ->
            if (isRawUrlLabel(link.label)) return@forEach
            val safeUrl = SafeUrlSanitizer.normalize(link.url) ?: return@forEach
            val candidates = displayLabelCandidates(link.label, sourceMarkdown, link)
            val range = findFirstFreeCandidate(renderedText, candidates, searchStart, planned)
                ?: return@forEach

            planned += RenderedLinkSpan(range.first, range.last + 1, safeUrl)
            searchStart = range.last + 1
        }

        rawUrlRegex.findAll(renderedText).forEach { match ->
            val normalized = normalizeRawMatch(match) ?: return@forEach
            val (start, end, url) = normalized
            if (planned.none { it.overlaps(start, end) }) {
                planned += RenderedLinkSpan(start, end, url)
            }
        }

        return planned
            .sortedWith(compareBy<RenderedLinkSpan> { it.start }.thenBy { it.end })
            .take(MAX_LINK_SPANS_PER_CHUNK)
    }

    private fun normalizeRawMatch(match: MatchResult): NormalizedRawLink? {
        val raw = match.value.replace(trailingLinkPunctuation, "")
        if (raw.isBlank()) return null
        val url = SafeUrlSanitizer.normalize(raw) ?: return null
        val start = match.range.first
        val end = start + raw.length
        return if (end > start) NormalizedRawLink(start, end, url) else null
    }

    private fun findFirstFreeCandidate(
        renderedText: String,
        candidates: List<String>,
        preferredStart: Int,
        occupied: List<RenderedLinkSpan>
    ): IntRange? {
        val preferred = findCandidateFrom(renderedText, candidates, preferredStart, occupied)
        if (preferred != null) return preferred
        return findCandidateFrom(renderedText, candidates, 0, occupied)
    }

    private fun findCandidateFrom(
        renderedText: String,
        candidates: List<String>,
        startIndex: Int,
        occupied: List<RenderedLinkSpan>
    ): IntRange? {
        var best: IntRange? = null
        candidates.forEach { candidate ->
            var index = renderedText.indexOf(candidate, startIndex = startIndex.coerceAtMost(renderedText.length))
            while (index >= 0) {
                val range = index until index + candidate.length
                if (occupied.none { it.overlaps(range.first, range.last + 1) }) {
                    if (best == null || range.first < best!!.first ||
                        (range.first == best!!.first && range.last > best!!.last)
                    ) {
                        best = range
                    }
                    break
                }
                index = renderedText.indexOf(candidate, startIndex = index + 1)
            }
        }
        return best
    }

    private fun displayLabelCandidates(
        label: String,
        sourceMarkdown: String,
        link: MarkdownLinkRange
    ): List<String> {
        val sourceSnippet = sourceMarkdown
            .substring(link.start.coerceAtLeast(0), link.end.coerceAtMost(sourceMarkdown.length))
            .trim()
        val stripped = stripInlineMarkdown(label)
        return listOf(stripped, label.trim(), sourceSnippet)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.length }
            .toList()
    }

    private fun stripInlineMarkdown(label: String): String =
        label
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("""!\[([^]]*)]\s*\([^)]+\)"""), "$1")
            .replace(Regex("""\[([^]]+)]\s*\([^)]+\)"""), "$1")
            .let(::unescapeMarkdownPunctuation)
            .replace("**", "")
            .replace("__", "")
            .replace("~~", "")
            .replace("*", "")
            .replace("_", "")
            .replace("`", "")
            .trim()

    private fun unescapeMarkdownPunctuation(value: String): String {
        if (!value.contains('\\')) return value
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val current = value[index]
            val next = value.getOrNull(index + 1)
            if (current == '\\' && next != null && next in markdownEscapableChars) {
                result.append(next)
                index += 2
            } else {
                result.append(current)
                index++
            }
        }
        return result.toString()
    }

    private fun isRawUrlLabel(label: String): Boolean {
        val trimmed = label.trim()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("www.", ignoreCase = true)
    }

    private fun RenderedLinkSpan.overlaps(start: Int, end: Int): Boolean =
        this.start < end && start < this.end

    private data class NormalizedRawLink(
        val start: Int,
        val end: Int,
        val url: String
    )

    private val markdownEscapableChars = setOf('\\', '[', ']', '(', ')', '`', '*', '_', '{', '}', '#', '+', '-', '.', '!')
    private const val MAX_LINK_SPANS_PER_CHUNK = 128
}
