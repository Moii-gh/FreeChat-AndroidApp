package com.example.chatapp.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownParserTest {

    @Test
    fun `incomplete emphasis remains renderable text`() {
        val state = StreamingMarkdownParser.parse(
            previous = StreamingMarkdownState.Empty,
            rawText = "**hel",
            isFinal = false
        )

        assertEquals(1, state.chunks.size)
        assertEquals(ChunkType.Text, state.chunks.single().type)
        assertEquals("**hel", state.chunks.single().content)
        assertFalse(state.chunks.single().isFrozen)
    }

    @Test
    fun `incomplete markdown link does not create link range until closed`() {
        val partial = StreamingMarkdownParser.parse(
            previous = StreamingMarkdownState.Empty,
            rawText = "[OpenAI](https://open",
            isFinal = false
        )
        assertTrue(partial.chunks.single().links.isEmpty())

        val complete = StreamingMarkdownParser.parse(
            previous = partial,
            rawText = "[OpenAI](https://openai.com)",
            isFinal = false
        )
        assertEquals("https://openai.com", complete.chunks.single().links.single().url)
    }

    @Test
    fun `open code fence is isolated and not frozen while streaming`() {
        val state = StreamingMarkdownParser.parse(
            previous = StreamingMarkdownState.Empty,
            rawText = "before\n\n```kot\nfun te",
            isFinal = false
        )

        assertEquals(2, state.chunks.size)
        assertEquals(ChunkType.Text, state.chunks[0].type)
        assertEquals(ChunkType.CodeFence, state.chunks[1].type)
        assertEquals("kot", state.chunks[1].language)
        assertFalse(state.chunks[1].isFrozen)
    }

    @Test
    fun `closed code fence becomes frozen on next parse`() {
        val partial = StreamingMarkdownParser.parse(
            previous = StreamingMarkdownState.Empty,
            rawText = "```kotlin\nfun test()",
            isFinal = false
        )
        val complete = StreamingMarkdownParser.parse(
            previous = partial,
            rawText = "```kotlin\nfun test()\n```\n\nnext",
            isFinal = false
        )

        assertEquals(ChunkType.CodeFence, complete.chunks.first().type)
        assertTrue(complete.chunks.first().isFrozen)
    }

    @Test
    fun `append parsing preserves frozen chunk id`() {
        val first = StreamingMarkdownParser.parse(
            previous = StreamingMarkdownState.Empty,
            rawText = "First paragraph.\n\nSec",
            isFinal = false
        )
        val frozenId = first.chunks.first().id
        assertTrue(first.chunks.first().isFrozen)

        val second = StreamingMarkdownParser.parse(
            previous = first,
            rawText = "First paragraph.\n\nSecond paragraph.",
            isFinal = false
        )

        assertEquals(frozenId, second.chunks.first().id)
        assertEquals("First paragraph.\n\n", second.chunks.first().content)
    }

    @Test
    fun `diff separates unchanged changed inserted and removed chunks`() {
        val old = listOf(
            MarkdownChunk("a", ChunkType.Text, "one", isFrozen = true),
            MarkdownChunk("b", ChunkType.Text, "two", isFrozen = false)
        )
        val new = listOf(
            MarkdownChunk("a", ChunkType.Text, "one", isFrozen = true),
            MarkdownChunk("b", ChunkType.Text, "two!", isFrozen = false),
            MarkdownChunk("c", ChunkType.CodeFence, "code", isFrozen = false)
        )

        val diff = MarkdownChunkDiff.calculate(old, new)
        assertEquals(listOf("a"), diff.unchanged.map { it.id })
        assertEquals(listOf("b"), diff.changed.map { it.id })
        assertEquals(listOf("c"), diff.inserted.map { it.id })
        assertTrue(diff.removed.isEmpty())
    }

    @Test
    fun `unsafe link schemes are rejected`() {
        assertEquals(null, SafeUrlSanitizer.normalize("javascript:alert(1)"))
        assertEquals(null, SafeUrlSanitizer.normalize("file:///tmp/a"))
        assertEquals(null, SafeUrlSanitizer.normalize("intent://open"))
        assertEquals("https://example.com", SafeUrlSanitizer.normalize("https://example.com"))
    }

    @Test
    fun `many links are capped per chunk`() {
        val text = (0 until 160).joinToString(" ") { "[L$it](https://example.com/$it)" }
        val links = StreamingLinkExtractor.extractLinks(text)

        assertEquals(128, links.size)
        assertEquals("https://example.com/0", links.first().url)
    }

    @Test
    fun `tolerant markdown link with space is detected and canonicalized`() {
        val source = "[Google] (https://google.com)"

        val links = StreamingLinkExtractor.extractLinks(source)

        assertEquals("https://google.com", links.single().url)
        assertEquals("[Google](https://google.com)", StreamingLinkExtractor.canonicalizeMarkdownLinks(source))
    }

    @Test
    fun `raw urls are detected during streaming chunks`() {
        val state = StreamingMarkdownParser.parse(
            previous = StreamingMarkdownState.Empty,
            rawText = "Open https://openai.com now",
            isFinal = false
        )

        assertEquals("https://openai.com", state.chunks.single().links.single().url)
    }

    @Test
    fun `links inside lists blockquotes and emphasis are detected together`() {
        val markdown = """
            - **Open [OpenAI](https://openai.com)**
            > See _[Google] (https://google.com)_ and https://example.com
        """.trimIndent()

        val links = StreamingLinkExtractor.extractLinks(markdown)

        assertEquals(
            listOf("https://openai.com", "https://google.com", "https://example.com"),
            links.map { it.url }
        )
    }

    @Test
    fun `incomplete tolerant markdown link suppresses raw url autolink`() {
        val links = StreamingLinkExtractor.extractLinks("[Google] (https://google")

        assertTrue(links.isEmpty())
    }

    @Test
    fun `large malformed response stays chunked and finalizable`() {
        val malformed = buildString {
            repeat(10_000) { index ->
                append("token").append(index).append(' ')
                if (index % 400 == 0) append("[broken")
            }
        }

        val streaming = StreamingMarkdownParser.parse(
            previous = StreamingMarkdownState.Empty,
            rawText = malformed,
            isFinal = false
        )
        val final = StreamingMarkdownParser.parse(
            previous = streaming,
            rawText = malformed,
            isFinal = true
        )

        assertTrue(streaming.chunks.isNotEmpty())
        assertTrue(final.chunks.all { it.isFrozen })
        assertNotEquals(0, final.rawText.length)
    }
}
