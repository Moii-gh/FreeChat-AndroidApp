package com.example.chatapp.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingLinkSpanPlannerTest {

    @Test
    fun `markdown link is mapped onto rendered label immediately`() {
        val spans = StreamingLinkSpanPlanner.plan(
            sourceMarkdown = "[OpenAI](https://openai.com)",
            renderedText = "OpenAI"
        )

        assertEquals(listOf(RenderedLinkSpan(0, 6, "https://openai.com")), spans)
    }

    @Test
    fun `tolerant markdown link with space maps onto rendered label`() {
        val spans = StreamingLinkSpanPlanner.plan(
            sourceMarkdown = "[Google] (https://google.com)",
            renderedText = "Google"
        )

        assertEquals(listOf(RenderedLinkSpan(0, 6, "https://google.com")), spans)
    }

    @Test
    fun `multiple equal markdown labels keep stable source order`() {
        val spans = StreamingLinkSpanPlanner.plan(
            sourceMarkdown = "[Docs](https://a.example) and [Docs](https://b.example)",
            renderedText = "Docs and Docs"
        )

        assertEquals(
            listOf(
                RenderedLinkSpan(0, 4, "https://a.example"),
                RenderedLinkSpan(9, 13, "https://b.example")
            ),
            spans
        )
    }

    @Test
    fun `links inside emphasis lists and quotes map onto rendered text`() {
        val spans = StreamingLinkSpanPlanner.plan(
            sourceMarkdown = "- **Open [OpenAI](https://openai.com)**\n> See _[Google] (https://google.com)_",
            renderedText = "- Open OpenAI\n> See Google"
        )

        assertEquals(
            listOf(
                RenderedLinkSpan(7, 13, "https://openai.com"),
                RenderedLinkSpan(20, 26, "https://google.com")
            ),
            spans
        )
    }

    @Test
    fun `raw urls are detected in rendered streaming text with trailing punctuation trimmed`() {
        val spans = StreamingLinkSpanPlanner.plan(
            sourceMarkdown = "Open https://openai.com.",
            renderedText = "Open https://openai.com."
        )

        assertEquals(listOf(RenderedLinkSpan(5, 23, "https://openai.com")), spans)
    }

    @Test
    fun `html anchor maps onto rendered label`() {
        val spans = StreamingLinkSpanPlanner.plan(
            sourceMarkdown = """<a href="https://example.com"><strong>Example</strong></a>""",
            renderedText = "Example"
        )

        assertEquals(listOf(RenderedLinkSpan(0, 7, "https://example.com")), spans)
    }

    @Test
    fun `unsafe schemes do not produce rendered spans`() {
        val spans = StreamingLinkSpanPlanner.plan(
            sourceMarkdown = "[Bad](javascript:alert(1)) and file:///tmp/a",
            renderedText = "Bad and file:///tmp/a"
        )

        assertEquals(emptyList<RenderedLinkSpan>(), spans)
    }
}
