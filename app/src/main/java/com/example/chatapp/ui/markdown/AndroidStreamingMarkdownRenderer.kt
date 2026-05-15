package com.example.chatapp.ui.markdown

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.Layout
import android.text.Spanned
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.text.style.URLSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.browser.BrowserUrlSanitizer
import com.example.chatapp.ui.ChatMessageRenderer
import com.example.chatapp.ui.MarkdownTableRenderer
import com.example.chatapp.ui.SelectableTextSupport
import com.example.chatapp.util.FileUtils
import com.example.chatapp.util.SyntaxHighlighter
import com.example.chatapp.util.dpToPx
import io.noties.markwon.Markwon

internal class AndroidStreamingMarkdownRenderer(
    private val context: Context,
    private val contentArea: LinearLayout,
    private val markwon: Markwon,
    private val onOpenLink: (String) -> Unit,
    private val linkColor: Int,
    private val copyText: (String) -> Unit = { FileUtils.copyToClipboard(context, it) }
) {
    private var state = StreamingMarkdownState.Empty
    private val viewByChunkId = linkedMapOf<String, View>()
    private val lastRenderedContentByChunkId = linkedMapOf<String, String>()
    private val lastRenderedTypeByChunkId = linkedMapOf<String, ChunkType>()
    private val frozenSpannableCache = linkedMapOf<String, CharSequence>()

    fun render(markdown: String, isFinal: Boolean) {
        val nextState = StreamingMarkdownParser.parse(state, markdown, isFinal)
        val diff = MarkdownChunkDiff.calculate(state.chunks, nextState.chunks)

        diff.removed.forEach(::removeChunk)
        nextState.chunks.forEachIndexed { index, chunk ->
            val view = ensureView(chunk, isFinal)
            ensureChunkIndex(view, index)
            bindChunkIfNeeded(chunk, view, isFinal)
        }

        trimCaches(nextState.chunks)
        state = nextState
    }

    fun clear() {
        viewByChunkId.values.toList().forEach { view ->
            if (view.parent == contentArea) {
                contentArea.removeView(view)
            }
        }
        viewByChunkId.clear()
        lastRenderedContentByChunkId.clear()
        lastRenderedTypeByChunkId.clear()
        frozenSpannableCache.clear()
        state = StreamingMarkdownState.Empty
    }

    private fun removeChunk(chunk: MarkdownChunk) {
        viewByChunkId.remove(chunk.id)?.let { view ->
            if (view.parent == contentArea) {
                contentArea.removeView(view)
            }
        }
        lastRenderedContentByChunkId.remove(chunk.id)
        lastRenderedTypeByChunkId.remove(chunk.id)
        frozenSpannableCache.remove(chunk.id)
    }

    private fun ensureView(chunk: MarkdownChunk, isFinal: Boolean): View {
        val existing = viewByChunkId[chunk.id]
        val needsFinalCodeView = chunk.type == ChunkType.CodeFence && (chunk.isFrozen || isFinal)
        val existingType = existing?.getTag(R.id.markdown_chunk_type) as? ChunkType
        val existingFinalCode = existing?.getTag(R.id.code_text_view) != null
        val existingTableRaw = existing?.getTag(R.id.table_raw_content)
        val tableContentChanged = chunk.type == ChunkType.Table &&
            existingTableRaw != null &&
            existingTableRaw != chunk.content
        if (existing != null &&
            existingType == chunk.type &&
            !tableContentChanged &&
            (!needsFinalCodeView || existingFinalCode)
        ) {
            return existing
        }

        if (existing != null && existing.parent == contentArea) {
            contentArea.removeView(existing)
        }

        val created = when (chunk.type) {
            ChunkType.Text -> createTextView()
            ChunkType.CodeFence -> if (needsFinalCodeView) {
                createFinalCodeView(chunk)
            } else {
                createStreamingCodeView(chunk.language)
            }
            ChunkType.Table -> createTableView(chunk)
            ChunkType.Image,
            ChunkType.Sources -> createTextView()
        }
        created.setTag(R.id.markdown_chunk_id, chunk.id)
        created.setTag(R.id.markdown_chunk_type, chunk.type)
        viewByChunkId[chunk.id] = created
        lastRenderedContentByChunkId.remove(chunk.id)
        lastRenderedTypeByChunkId[chunk.id] = chunk.type
        return created
    }

    private fun bindChunkIfNeeded(chunk: MarkdownChunk, view: View, isFinal: Boolean) {
        val cacheKey = buildString {
            append(chunk.content)
            append('\u0000')
            append(chunk.language)
            append('\u0000')
            append(chunk.isFrozen || isFinal)
        }
        if (lastRenderedContentByChunkId[chunk.id] == cacheKey) return

        when (chunk.type) {
            ChunkType.Text -> bindText(view as TextView, chunk)
            ChunkType.CodeFence -> bindCode(view, chunk, isFinal)
            ChunkType.Table -> bindTable(view, chunk)
            ChunkType.Image,
            ChunkType.Sources -> bindText(view as TextView, chunk)
        }

        lastRenderedContentByChunkId[chunk.id] = cacheKey
    }

    private fun bindText(textView: TextView, chunk: MarkdownChunk) {
        val cached = if (chunk.isFrozen) frozenSpannableCache[chunk.id] else null
        if (cached != null) {
            textView.text = cached
            textView.configureLinkableTextView()
            return
        }

        val markdown = StreamingLinkExtractor.canonicalizeMarkdownLinks(
            rewriteHtmlAnchors(chunk.content)
        )
        markwon.setMarkdown(textView, markdown)
        val spannable = SpannableStringBuilder(textView.text)
        replaceUrlSpans(spannable)
        applyPlannedLinkSpans(spannable, chunk)

        if (chunk.isFrozen && frozenSpannableCache.size < MAX_SPANNABLE_CACHE_SIZE) {
            frozenSpannableCache[chunk.id] = spannable
        }
        textView.text = spannable
        textView.configureLinkableTextView()
    }

    private fun bindCode(view: View, chunk: MarkdownChunk, isFinal: Boolean) {
        val codeView = view.getTag(R.id.code_text_view) as? TextView
        if (codeView != null) {
            val highlighted = if (chunk.isFrozen || isFinal) {
                SyntaxHighlighter.highlight(chunk.content, chunk.language)
            } else {
                chunk.content
            }
            if (TextUtils.equals(codeView.text, highlighted).not()) {
                codeView.text = highlighted
            }
            return
        }

        val streamingCode = view.findViewWithTag<TextView>(STREAMING_CODE_TEXT_TAG) ?: return
        if (streamingCode.text.toString() != chunk.content) {
            streamingCode.text = chunk.content
        }
    }

    private fun bindTable(view: View, chunk: MarkdownChunk) {
        view.setTag(R.id.table_raw_content, chunk.content)
    }

    private fun createTextView(): TextView =
        TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            textSize = 16f
            setLineSpacing(0f, 1.2f)
            setPadding(0, 6.dpToPx(), 0, 6.dpToPx())
            configureLinkableTextView()
        }

    private fun TextView.configureLinkableTextView() {
        SelectableTextSupport.configure(
            textView = this,
            linkColor = linkColor,
            openLinksOnTap = true
        )
        setLinkTextColor(linkColor)
        setHorizontallyScrolling(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
        }
    }

    private fun createStreamingCodeView(language: String): View {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_code_block)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = (8 * density).toInt()
                setMargins(0, margin, 0, margin)
            }
        }

        if (language.isNotBlank()) {
            container.addView(TextView(context).apply {
                text = language.trim()
                setTextColor(Color.parseColor("#C9D1D9"))
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(14.dpToPx(), 9.dpToPx(), 14.dpToPx(), 0)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }

        val codeText = TextView(context).apply {
            tag = STREAMING_CODE_TEXT_TAG
            setTextColor(Color.parseColor("#D6DEEB"))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            includeFontPadding = false
            setLineSpacing((2 * density), 1.0f)
            setHorizontallyScrolling(true)
            setPadding(14.dpToPx(), 13.dpToPx(), 14.dpToPx(), 13.dpToPx())
            minWidth = (context.resources.displayMetrics.widthPixels - 48.dpToPx()).coerceAtLeast(0)
        }

        container.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            addView(codeText, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        })
        return container
    }

    private fun createFinalCodeView(chunk: MarkdownChunk): View {
        val (container, codeText) = ChatMessageRenderer.createCodeBlockView(
            context,
            chunk.content,
            chunk.language
        )
        container.setTag(R.id.code_text_view, codeText)
        container.setTag(R.id.code_language, chunk.language)
        codeText.text = SyntaxHighlighter.highlight(chunk.content, chunk.language)
        return container
    }

    private fun createTableView(chunk: MarkdownChunk): View {
        val parsed = MarkdownTableRenderer.parseTableLines(chunk.content.lines())
        return if (parsed != null) {
            MarkdownTableRenderer.createTableView(context, parsed, chunk.content)
        } else {
            createTextView()
        }
    }

    private fun ensureChunkIndex(view: View, chunkIndex: Int) {
        val desiredIndex = absoluteIndexForChunkIndex(chunkIndex)
        val currentIndex = contentArea.indexOfChild(view)
        if (currentIndex == desiredIndex) return

        if (currentIndex >= 0) {
            contentArea.removeViewAt(currentIndex)
            val adjustedIndex = if (currentIndex < desiredIndex) desiredIndex - 1 else desiredIndex
            contentArea.addView(view, adjustedIndex.coerceIn(0, contentArea.childCount))
        } else {
            contentArea.addView(view, desiredIndex.coerceIn(0, contentArea.childCount))
        }
    }

    private fun absoluteIndexForChunkIndex(chunkIndex: Int): Int {
        var seenChunks = 0
        for (index in 0 until contentArea.childCount) {
            val child = contentArea.getChildAt(index)
            if (child.getTag(R.id.markdown_chunk_id) != null) {
                if (seenChunks == chunkIndex) return index
                seenChunks++
            } else if (seenChunks == chunkIndex) {
                return index
            }
        }
        return contentArea.childCount
    }

    private fun applyPlannedLinkSpans(spannable: SpannableStringBuilder, chunk: MarkdownChunk) {
        val planned = StreamingLinkSpanPlanner.plan(
            sourceMarkdown = chunk.content,
            renderedText = spannable.toString(),
            extractedLinks = chunk.links.ifEmpty { StreamingLinkExtractor.extractLinks(chunk.content) }
        )
        planned.forEach { link ->
            if (!hasUrlSpanOverlap(spannable, link.start, link.end)) {
                setInternalUrlSpan(spannable, link.start, link.end, link.url)
            }
        }
    }

    private fun replaceUrlSpans(spannable: SpannableStringBuilder) {
        val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
        spans.forEach { span ->
            if (span is InternalBrowserUrlSpan) return@forEach
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            val safeUrl = BrowserUrlSanitizer.normalize(span.url)
            spannable.removeSpan(span)
            if (start >= 0 && end > start && safeUrl != null && !hasUrlSpanOverlap(spannable, start, end)) {
                setInternalUrlSpan(spannable, start, end, safeUrl)
            }
        }
    }

    private fun setInternalUrlSpan(
        spannable: SpannableStringBuilder,
        start: Int,
        end: Int,
        url: String
    ) {
        val safeUrl = BrowserUrlSanitizer.normalize(url) ?: return
        if (start < 0 || end <= start || end > spannable.length) return
        spannable.setSpan(
            InternalBrowserUrlSpan(
                url = safeUrl,
                opener = onOpenLink,
                copier = copyText,
                color = linkColor
            ),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(ForegroundColorSpan(linkColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun hasUrlSpanOverlap(spannable: SpannableStringBuilder, start: Int, end: Int): Boolean =
        spannable.getSpans(start, end, URLSpan::class.java).any { span ->
            val spanStart = spannable.getSpanStart(span)
            val spanEnd = spannable.getSpanEnd(span)
            spanStart < end && start < spanEnd
        }

    private fun rewriteHtmlAnchors(markdown: String): String {
        if (!markdown.contains("<a", ignoreCase = true)) return markdown
        return HTML_ANCHOR_REGEX.replace(markdown) { match ->
            val url = BrowserUrlSanitizer.normalize(match.groupValues[2])
            val label = match.groupValues[3]
                .replace(Regex("<[^>]+>"), "")
                .replace("]", "\\]")
                .trim()
            if (url == null || label.isBlank()) {
                label
            } else {
                "[$label]($url)"
            }
        }
    }

    private fun trimCaches(chunks: List<MarkdownChunk>) {
        val activeIds = chunks.mapTo(mutableSetOf()) { it.id }
        viewByChunkId.keys.toList().filter { it !in activeIds }.forEach { id ->
            viewByChunkId.remove(id)
            lastRenderedContentByChunkId.remove(id)
            lastRenderedTypeByChunkId.remove(id)
            frozenSpannableCache.remove(id)
        }

        while (frozenSpannableCache.size > MAX_SPANNABLE_CACHE_SIZE) {
            val eldest = frozenSpannableCache.keys.firstOrNull() ?: break
            frozenSpannableCache.remove(eldest)
        }
        while (lastRenderedContentByChunkId.size > MAX_RENDER_CACHE_SIZE) {
            val eldest = lastRenderedContentByChunkId.keys.firstOrNull() ?: break
            lastRenderedContentByChunkId.remove(eldest)
        }
    }

    private class InternalBrowserUrlSpan(
        private val url: String,
        private val opener: (String) -> Unit,
        private val copier: (String) -> Unit,
        private val color: Int
    ) : URLSpan(url), SelectableTextSupport.LongClickableSpan {
        private var lastClickAt = 0L

        override fun onClick(widget: View) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastClickAt < LINK_TAP_DEBOUNCE_MS) return
            lastClickAt = now
            opener(url)
        }

        override fun onLongClick(widget: View): Boolean {
            copier(url)
            Toast.makeText(widget.context, LocaleHelper.getString(widget.context, "toast_copied"), Toast.LENGTH_SHORT).show()
            return true
        }

        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = true
        }
    }

    private companion object {
        const val STREAMING_CODE_TEXT_TAG = "streaming_code_text"
        const val MAX_SPANNABLE_CACHE_SIZE = 96
        const val MAX_RENDER_CACHE_SIZE = 192
        const val LINK_TAP_DEBOUNCE_MS = 400L
        val HTML_ANCHOR_REGEX = Regex(
            pattern = """<a\s+[^>]*href\s*=\s*(['"])(.*?)\1[^>]*>(.*?)</a>""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
    }
}
