package com.example.chatapp.ui.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R
import com.example.chatapp.util.dpToPx

internal class ChatSearchController(
    private val context: Context,
    private val searchBar: View,
    private val input: EditText,
    private val statusView: TextView,
    private val previousButton: ImageButton,
    private val nextButton: ImageButton,
    private val closeButton: ImageButton,
    private val messagesScrollView: ScrollView,
    private val messagesContainer: LinearLayout,
    private val messagesProvider: () -> List<ChatSearchMessage>
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingSearchRunnable: Runnable? = null
    private var suppressInputCallback = false
    private var currentQuery = ""
    private var matches: List<ChatSearchMatch> = emptyList()
    private var activeIndex = -1

    val isOpen: Boolean
        get() = searchBar.isVisible

    init {
        input.doAfterTextChanged { editable ->
            if (!suppressInputCallback) {
                scheduleSearch(editable?.toString().orEmpty())
            }
        }
        input.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            false
        }
        previousButton.setOnClickListener { moveActive(-1) }
        nextButton.setOnClickListener { moveActive(1) }
        closeButton.setOnClickListener { close() }
        updateControls()
    }

    fun open() {
        if (!searchBar.isVisible) {
            searchBar.animate().cancel()
            searchBar.alpha = 0f
            searchBar.translationY = (-8).dpToPx().toFloat()
            searchBar.isVisible = true
            searchBar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                .start()
        }
        input.requestFocus()
        input.post {
            showKeyboard()
            runSearchNow(input.text?.toString().orEmpty(), keepActive = true, scrollToActive = false)
        }
    }

    fun close(): Boolean {
        if (!searchBar.isVisible) return false

        pendingSearchRunnable?.let(handler::removeCallbacks)
        pendingSearchRunnable = null
        suppressInputCallback = true
        input.text?.clear()
        suppressInputCallback = false
        currentQuery = ""
        matches = emptyList()
        activeIndex = -1
        clearHighlights()
        updateControls()
        hideKeyboard()

        searchBar.animate().cancel()
        searchBar.animate()
            .alpha(0f)
            .translationY((-8).dpToPx().toFloat())
            .setDuration(140L)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                searchBar.isGone = true
                searchBar.alpha = 1f
                searchBar.translationY = 0f
            }
            .start()
        return true
    }

    fun refreshAfterMessagesChanged() {
        if (!searchBar.isVisible) return
        pendingSearchRunnable?.let(handler::removeCallbacks)
        pendingSearchRunnable = null
        runSearchNow(input.text?.toString().orEmpty(), keepActive = true, scrollToActive = false)
    }

    private fun scheduleSearch(query: String) {
        pendingSearchRunnable?.let(handler::removeCallbacks)
        val runnable = Runnable {
            pendingSearchRunnable = null
            runSearchNow(query, keepActive = false, scrollToActive = true)
        }
        pendingSearchRunnable = runnable
        handler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
    }

    private fun runSearchNow(query: String, keepActive: Boolean, scrollToActive: Boolean) {
        currentQuery = query.trim()
        val previousActive = matches.getOrNull(activeIndex)
        matches = ChatSearchMatcher.findMatches(messagesProvider(), currentQuery)
        activeIndex = when {
            matches.isEmpty() -> -1
            keepActive && previousActive != null -> matches.indexOfFirst {
                it.messageKey == previousActive.messageKey &&
                    it.matchIndexInMessage == previousActive.matchIndexInMessage
            }.takeIf { it >= 0 } ?: activeIndex.coerceIn(0, matches.lastIndex)
            else -> 0
        }

        updateControls()
        if (matches.isEmpty() || currentQuery.isEmpty()) {
            clearHighlights()
            return
        }

        if (scrollToActive) {
            scrollToActiveMatchThenHighlight()
        } else {
            applyHighlights()
        }
    }

    private fun moveActive(delta: Int) {
        if (matches.isEmpty()) return
        activeIndex = (activeIndex + delta + matches.size) % matches.size
        updateControls()
        scrollToActiveMatchThenHighlight()
    }

    private fun updateControls() {
        statusView.text = when {
            currentQuery.isEmpty() -> ""
            matches.isEmpty() -> LocaleHelper.getString(context, "no_search_results")
            else -> LocaleHelper.formatString(
                context,
                "chat_search_count_format",
                activeIndex + 1,
                matches.size
            )
        }
        val canNavigate = matches.size > 1
        setNavigationEnabled(previousButton, canNavigate)
        setNavigationEnabled(nextButton, canNavigate)
    }

    private fun setNavigationEnabled(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.isClickable = enabled
        button.alpha = if (enabled) 1f else 0.38f
    }

    private fun scrollToActiveMatchThenHighlight() {
        val active = matches.getOrNull(activeIndex) ?: return applyHighlights()
        val target = findMessageView(active.messageKey)
        if (target == null) {
            applyHighlights()
            return
        }

        messagesScrollView.post {
            val rect = Rect()
            target.getDrawingRect(rect)
            messagesContainer.offsetDescendantRectToMyCoords(target, rect)
            val floatingSearchOffset = if (searchBar.isVisible) searchBar.height + 12.dpToPx() else 0
            val targetY = (rect.top - messagesScrollView.paddingTop - floatingSearchOffset).coerceAtLeast(0)
            messagesScrollView.smoothScrollTo(0, targetY)
            messagesScrollView.postDelayed({ applyHighlights() }, HIGHLIGHT_AFTER_SCROLL_DELAY_MS)
        }
    }

    private fun findMessageView(messageKey: String): View? {
        for (index in 0 until messagesContainer.childCount) {
            val child = messagesContainer.getChildAt(index)
            if (child.getTag(R.id.chat_search_message_key) == messageKey) {
                return child
            }
        }
        return findTaggedView(messagesContainer, messageKey)
    }

    private fun findTaggedView(view: View, messageKey: String): View? {
        if (view.getTag(R.id.chat_search_message_key) == messageKey) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findTaggedView(view.getChildAt(index), messageKey)?.let { return it }
        }
        return null
    }

    private fun applyHighlights() {
        clearHighlights()
        if (currentQuery.isEmpty() || matches.isEmpty()) return

        val active = matches.getOrNull(activeIndex)
        val messageKeysWithMatches = matches.mapTo(mutableSetOf()) { it.messageKey }
        val seenRangesByMessage = mutableMapOf<String, Int>()
        forEachSearchTextView(messagesContainer) { textView, messageKey ->
            if (messageKey !in messageKeysWithMatches) return@forEachSearchTextView
            val text = textView.text?.toString().orEmpty()
            val ranges = ChatSearchMatcher.findRanges(text, currentQuery)
            if (ranges.isEmpty()) return@forEachSearchTextView

            val baseRangeIndex = seenRangesByMessage[messageKey] ?: 0
            val builder = withoutSearchSpans(textView.text)
            ranges.forEachIndexed { rangeIndex, range ->
                val start = range.first
                val end = range.last + 1
                val absoluteRangeIndex = baseRangeIndex + rangeIndex
                val isActive = active?.messageKey == messageKey &&
                    active.matchIndexInMessage == absoluteRangeIndex
                builder.setSpan(
                    SearchHighlightSpan(
                        backgroundColor = if (isActive) ACTIVE_HIGHLIGHT_COLOR else HIGHLIGHT_COLOR,
                        foregroundColor = if (isActive) Color.WHITE else null,
                        fakeBold = isActive
                    ),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            seenRangesByMessage[messageKey] = baseRangeIndex + ranges.size
            textView.text = builder
        }
    }

    private fun clearHighlights() {
        forEachSearchTextView(messagesContainer) { textView, _ ->
            val text = textView.text ?: return@forEachSearchTextView
            if (text !is Spanned) return@forEachSearchTextView
            val spans = text.getSpans(0, text.length, SearchHighlightSpan::class.java)
            if (spans.isEmpty()) return@forEachSearchTextView
            textView.text = withoutSearchSpans(text)
        }
    }

    private fun withoutSearchSpans(source: CharSequence): SpannableStringBuilder {
        val builder = SpannableStringBuilder(source)
        val spans = builder.getSpans(0, builder.length, SearchHighlightSpan::class.java)
        spans.forEach(builder::removeSpan)
        return builder
    }

    private fun forEachSearchTextView(root: View, block: (TextView, String) -> Unit) {
        if (root is TextView && root.getTag(R.id.chat_search_text_view) == true) {
            val messageKey = root.getTag(R.id.chat_search_message_key) as? String
            if (messageKey != null) block(root, messageKey)
        }
        if (root !is ViewGroup) return
        for (index in 0 until root.childCount) {
            forEachSearchTextView(root.getChildAt(index), block)
        }
    }

    private fun showKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        manager?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private class SearchHighlightSpan(
        private val backgroundColor: Int,
        private val foregroundColor: Int?,
        private val fakeBold: Boolean
    ) : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(tp: TextPaint) {
            tp.bgColor = backgroundColor
            foregroundColor?.let { tp.color = it }
            tp.isFakeBoldText = fakeBold
        }
    }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 120L
        private const val HIGHLIGHT_AFTER_SCROLL_DELAY_MS = 140L
        private val HIGHLIGHT_COLOR = Color.parseColor("#66FFD166")
        private val ACTIVE_HIGHLIGHT_COLOR = Color.parseColor("#D90A84FF")

        fun messageKeyForHistoryIndex(historyIndex: Int): String = historyIndex.toString()

        fun tagMessageContainer(view: View, messageKey: String?) {
            view.setTag(R.id.chat_search_message_key, messageKey)
        }

        fun tagSearchTextView(textView: TextView, messageKey: String?) {
            textView.setTag(R.id.chat_search_message_key, messageKey)
            textView.setTag(R.id.chat_search_text_view, messageKey != null)
        }

        fun tagTableTextViews(root: View, messageKey: String?) {
            tagTableTextViews(root, messageKey, insideTable = false)
        }

        private fun tagTableTextViews(view: View, messageKey: String?, insideTable: Boolean) {
            val nextInsideTable = insideTable || view is TableLayout || view is TableRow
            if (nextInsideTable && view is TextView) {
                tagSearchTextView(view, messageKey)
            }
            if (view !is ViewGroup) return
            for (index in 0 until view.childCount) {
                tagTableTextViews(view.getChildAt(index), messageKey, nextInsideTable)
            }
        }
    }
}