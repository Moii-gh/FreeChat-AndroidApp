package com.example.chatapp.browser

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R

class InAppBrowserManager(
    private val activity: Activity,
    private val host: ViewGroup,
    savedState: Bundle? = null
) : WebViewController.Listener {

    private data class BrowserTab(
        val id: Long,
        val controller: WebViewController
    )

    enum class FutureContentType {
        WebPage,
        Pdf,
        Image
    }

    private val tabs = mutableListOf<BrowserTab>()
    private var activeIndex = -1
    private var nextTabId = 1L
    private var isExpanded = false
    private var isMinimized = false

    private var root: FrameLayout? = null
    private var rootLayoutParams: ViewGroup.LayoutParams? = null
    private var dimView: View? = null
    private var panel: LinearLayout? = null
    private var tabStrip: LinearLayout? = null
    private var tabScroll: HorizontalScrollView? = null
    private var toolbarTitle: TextView? = null
    private var toolbarSubtitle: TextView? = null
    private var webContainer: FrameLayout? = null
    private var minimizedCard: LinearLayout? = null
    private var progressBar: ProgressBar? = null
    private var backButton: ImageButton? = null
    private var forwardButton: ImageButton? = null
    private var refreshButton: ImageButton? = null

    init {
        restore(savedState)
    }

    fun open(rawUrl: String, contentType: FutureContentType = FutureContentType.WebPage) {
        if (contentType != FutureContentType.WebPage) {
            showBlockedToast(rawUrl)
            return
        }

        val url = BrowserUrlSanitizer.normalize(rawUrl)
        if (url == null) {
            showBlockedToast(rawUrl)
            return
        }

        ensureViews()
        val tab = BrowserTab(nextTabId++, WebViewController(activity, this))
        tabs.add(tab)
        activeIndex = tabs.lastIndex
        attachActiveWebView()
        refreshTabs()
        refreshToolbar()
        showExpanded()
        tab.controller.load(url)
    }

    fun onBackPressed(): Boolean {
        val active = activeController() ?: return false
        if (isExpanded) {
            if (active.canGoBack()) {
                active.goBack()
            } else {
                minimize()
            }
            return true
        }
        if (isMinimized) {
            closeAll()
            return true
        }
        return false
    }

    fun onResume() {
        activeController()?.onResume()
    }

    fun onPause() {
        tabs.forEach { it.controller.onPause() }
    }

    fun onDestroy() {
        closeAll(removeRoot = true)
    }

    fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_ACTIVE_INDEX, activeIndex)
        outState.putLong(KEY_NEXT_TAB_ID, nextTabId)
        outState.putBoolean(KEY_EXPANDED, isExpanded)
        outState.putBoolean(KEY_MINIMIZED, isMinimized)
        outState.putInt(KEY_TAB_COUNT, tabs.size)
        tabs.forEachIndexed { index, tab ->
            val tabState = Bundle()
            tab.controller.saveState(tabState)
            tabState.putLong(KEY_TAB_ID, tab.id)
            outState.putBundle("$KEY_TAB_PREFIX$index", tabState)
        }
    }

    override fun onStateChanged(controller: WebViewController) {
        refreshToolbar()
        refreshTabs()
        refreshMinimizedCard()
    }

    override fun onBlockedUrl(url: String?) {
        showBlockedToast(url)
    }

    private fun restore(savedState: Bundle?) {
        savedState ?: return
        val count = savedState.getInt(KEY_TAB_COUNT, 0)
        if (count <= 0) return

        ensureViews()
        activeIndex = savedState.getInt(KEY_ACTIVE_INDEX, 0).coerceIn(0, count - 1)
        nextTabId = savedState.getLong(KEY_NEXT_TAB_ID, 1L)
        isExpanded = savedState.getBoolean(KEY_EXPANDED, false)
        isMinimized = savedState.getBoolean(KEY_MINIMIZED, false)

        repeat(count) { index ->
            val tabState = savedState.getBundle("$KEY_TAB_PREFIX$index") ?: return@repeat
            val controller = WebViewController(activity, this)
            controller.restore(tabState)
            tabs.add(BrowserTab(tabState.getLong(KEY_TAB_ID, nextTabId++), controller))
        }

        attachActiveWebView()
        refreshTabs()
        refreshToolbar()
        refreshMinimizedCard()

        when {
            isExpanded -> showExpanded(animate = false)
            isMinimized -> showMinimized(animate = false)
            else -> root?.isGone = true
        }
    }

    private fun ensureViews() {
        if (root != null) return

        val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val surface = if (isDark) Color.parseColor("#F21C1C1E") else Color.parseColor("#F7FFFFFF")
        val stroke = if (isDark) Color.parseColor("#3A3A3C") else Color.parseColor("#33000000")
        val primary = if (isDark) Color.WHITE else Color.parseColor("#101014")
        val secondary = if (isDark) Color.parseColor("#B3B3B3") else Color.parseColor("#707078")

        root = FrameLayout(activity).apply {
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
            elevation = 90f
        }

        dimView = View(activity).apply {
            setBackgroundColor(Color.parseColor(if (isDark) "#99000000" else "#66000000"))
            alpha = 0f
            isClickable = true
            setOnClickListener { minimize() }
        }
        root?.addView(dimView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            background = roundedDrawable(surface, 26.dp(), stroke, 1.dp())
            elevation = 94f
        }

        val handle = View(activity).apply {
            background = roundedDrawable(if (isDark) Color.parseColor("#5A5A5C") else Color.parseColor("#C4C4C8"), 999.dp())
        }

        val handleWrap = FrameLayout(activity).apply {
            setPadding(0, 10.dp(), 0, 4.dp())
            addView(handle, FrameLayout.LayoutParams(44.dp(), 4.dp(), Gravity.CENTER))
            setDragGesture()
        }
        panel?.addView(handleWrap, LinearLayout.LayoutParams.MATCH_PARENT, 22.dp())

        tabStrip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 0, 12.dp(), 6.dp())
        }
        tabScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabStrip)
        }
        panel?.addView(tabScroll, LinearLayout.LayoutParams.MATCH_PARENT, 38.dp())

        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 0, 10.dp(), 8.dp())
            setDragGesture()
        }

        backButton = browserButton(R.drawable.ic_arrow_back, primary) { activeController()?.goBack() }
        forwardButton = browserButton(R.drawable.ic_browser_forward, primary) { activeController()?.goForward() }
        refreshButton = browserButton(R.drawable.ic_browser_refresh, primary) { activeController()?.reload() }

        val titleBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 0, 10.dp(), 0)
        }
        toolbarTitle = TextView(activity).apply {
            setTextColor(primary)
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        toolbarSubtitle = TextView(activity).apply {
            setTextColor(secondary)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        titleBox.addView(toolbarTitle, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        titleBox.addView(toolbarSubtitle, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        toolbar.addView(backButton, LinearLayout.LayoutParams(36.dp(), 36.dp()))
        toolbar.addView(forwardButton, LinearLayout.LayoutParams(36.dp(), 36.dp()))
        toolbar.addView(refreshButton, LinearLayout.LayoutParams(36.dp(), 36.dp()))
        toolbar.addView(titleBox, LinearLayout.LayoutParams(0, 40.dp(), 1f))
        toolbar.addView(browserButton(R.drawable.ic_browser_minimize, primary) { minimize() }, LinearLayout.LayoutParams(36.dp(), 36.dp()))
        toolbar.addView(browserButton(R.drawable.ic_assistant_close, primary) { closeActiveTab() }, LinearLayout.LayoutParams(36.dp(), 36.dp()))
        panel?.addView(toolbar, LinearLayout.LayoutParams.MATCH_PARENT, 48.dp())

        progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable.setTint(Color.parseColor("#0A84FF"))
            indeterminateDrawable.setTint(Color.parseColor("#0A84FF"))
        }
        panel?.addView(progressBar, LinearLayout.LayoutParams.MATCH_PARENT, 2.dp())

        webContainer = FrameLayout(activity).apply {
            setBackgroundColor(if (isDark) Color.parseColor("#101014") else Color.WHITE)
        }
        webContainer?.let {
            panel?.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        root?.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM
            ).apply {
                leftMargin = 8.dp()
                rightMargin = 8.dp()
                topMargin = 42.dp()
                bottomMargin = 8.dp()
            }
        )

        minimizedCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 9.dp(), 8.dp(), 9.dp())
            background = roundedDrawable(surface, 18.dp(), stroke, 1.dp())
            elevation = 96f
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            isClickable = true
            isFocusable = true
            setOnClickListener { showExpanded() }
        }
        val cardIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_globe_new)
            setColorFilter(Color.parseColor("#0A84FF"))
        }
        val cardText = TextView(activity).apply {
            id = R.id.browser_minimized_title
            setTextColor(primary)
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(9.dp(), 0, 10.dp(), 0)
        }
        minimizedCard?.addView(cardIcon, LinearLayout.LayoutParams(18.dp(), 18.dp()))
        minimizedCard?.addView(cardText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        minimizedCard?.addView(browserButton(R.drawable.ic_assistant_close, primary) { closeAll() }, LinearLayout.LayoutParams(30.dp(), 30.dp()))

        root?.addView(
            minimizedCard,
            FrameLayout.LayoutParams(230.dp(), LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        rootLayoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        host.addView(root, rootLayoutParams)
    }

    private fun showExpanded(animate: Boolean = true) {
        ensureViews()
        if (tabs.isEmpty()) return
        isExpanded = true
        isMinimized = false
        root?.isVisible = true
        root?.isClickable = true
        setRootFullscreen()
        dimView?.isVisible = true
        panel?.isVisible = true
        minimizedCard?.isGone = true
        attachActiveWebView()
        applyBlur(true)

        panel?.animate()?.cancel()
        dimView?.animate()?.cancel()
        if (!animate) {
            panel?.alpha = 1f
            panel?.translationY = 0f
            panel?.scaleX = 1f
            panel?.scaleY = 1f
            dimView?.alpha = 1f
            return
        }

        panel?.alpha = 0f
        panel?.translationY = 56.dp().toFloat()
        panel?.scaleX = 0.985f
        panel?.scaleY = 0.985f
        dimView?.alpha = 0f
        dimView?.animate()?.alpha(1f)?.setDuration(220L)?.start()
        panel?.animate()
            ?.alpha(1f)
            ?.translationY(0f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(260L)
            ?.setInterpolator(DecelerateInterpolator(1.7f))
            ?.start()
    }

    private fun minimize() {
        if (tabs.isEmpty()) return
        showMinimized(animate = true)
    }

    private fun showMinimized(animate: Boolean) {
        isExpanded = false
        isMinimized = true
        root?.isVisible = true
        root?.isClickable = false
        setRootFloatingCardBounds()
        applyBlur(false)
        refreshMinimizedCard()

        dimView?.animate()?.cancel()
        panel?.animate()?.cancel()
        minimizedCard?.animate()?.cancel()

        val finishPanel = {
            panel?.isGone = true
            dimView?.isGone = true
        }

        minimizedCard?.isVisible = true
        if (!animate) {
            finishPanel()
            minimizedCard?.alpha = 1f
            minimizedCard?.scaleX = 1f
            minimizedCard?.scaleY = 1f
            return
        }

        dimView?.animate()?.alpha(0f)?.setDuration(180L)?.start()
        panel?.animate()
            ?.alpha(0f)
            ?.translationY(42.dp().toFloat())
            ?.scaleX(0.975f)
            ?.scaleY(0.975f)
            ?.setDuration(180L)
            ?.withEndAction { finishPanel() }
            ?.start()

        minimizedCard?.alpha = 0f
        minimizedCard?.scaleX = 0.88f
        minimizedCard?.scaleY = 0.88f
        minimizedCard?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setStartDelay(70L)
            ?.setDuration(210L)
            ?.setInterpolator(DecelerateInterpolator(1.8f))
            ?.start()
    }

    private fun closeActiveTab() {
        if (activeIndex !in tabs.indices) {
            closeAll()
            return
        }
        val removed = tabs.removeAt(activeIndex)
        removed.controller.destroy()
        if (tabs.isEmpty()) {
            closeAll()
            return
        }
        activeIndex = activeIndex.coerceAtMost(tabs.lastIndex)
        attachActiveWebView()
        refreshTabs()
        refreshToolbar()
    }

    private fun closeAll(removeRoot: Boolean = false) {
        tabs.forEach { it.controller.destroy() }
        tabs.clear()
        activeIndex = -1
        isExpanded = false
        isMinimized = false
        applyBlur(false)
        webContainer?.removeAllViews()
        if (removeRoot) {
            root?.let { host.removeView(it) }
            root = null
            rootLayoutParams = null
        } else {
            root?.animate()?.cancel()
            panel?.animate()?.cancel()
            minimizedCard?.animate()?.cancel()
            root?.isGone = true
        }
    }

    private fun attachActiveWebView() {
        val controller = activeController() ?: return
        val container = webContainer ?: return
        if (controller.webView.parent == container) return

        (controller.webView.parent as? ViewGroup)?.removeView(controller.webView)
        container.removeAllViews()
        container.addView(controller.webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        controller.onResume()
    }

    private fun refreshToolbar() {
        val controller = activeController()
        val title = controller?.title?.takeIf { it.isNotBlank() }
            ?: BrowserUrlSanitizer.displayHost(controller?.currentUrl)
            ?: ""
        toolbarTitle?.text = title.ifBlank { "Web" }
        toolbarSubtitle?.text = BrowserUrlSanitizer.displayHost(controller?.currentUrl)
        progressBar?.progress = controller?.progress ?: 0
        progressBar?.isVisible = controller?.isLoading == true
        backButton?.isEnabled = controller?.canGoBack() == true
        forwardButton?.isEnabled = controller?.canGoForward() == true
        refreshButton?.isEnabled = controller != null
        listOf(backButton, forwardButton, refreshButton).forEach { button ->
            button?.alpha = if (button?.isEnabled == true) 1f else 0.38f
        }
    }

    private fun refreshTabs() {
        val strip = tabStrip ?: return
        strip.removeAllViews()
        if (tabs.size <= 1) {
            tabScroll?.isGone = true
            return
        }
        tabScroll?.isVisible = true
        tabs.forEachIndexed { index, tab ->
            val selected = index == activeIndex
            val controller = tab.controller
            val label = controller.title.takeIf { it.isNotBlank() }
                ?: BrowserUrlSanitizer.displayHost(controller.currentUrl).ifBlank { "Tab ${index + 1}" }
            val chip = TextView(activity).apply {
                text = label
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (selected) Color.WHITE else Color.parseColor("#B3B3B3"))
                setPadding(12.dp(), 7.dp(), 12.dp(), 7.dp())
                background = roundedDrawable(
                    if (selected) Color.parseColor("#0A84FF") else Color.parseColor("#332C2C2E"),
                    999.dp(),
                    if (selected) Color.TRANSPARENT else Color.parseColor("#334A4A4C"),
                    1.dp()
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    activeIndex = index
                    attachActiveWebView()
                    refreshTabs()
                    refreshToolbar()
                }
            }
            strip.addView(chip, LinearLayout.LayoutParams(128.dp(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = 8.dp()
            })
        }
    }

    private fun refreshMinimizedCard() {
        val title = activeController()?.title?.takeIf { it.isNotBlank() }
            ?: BrowserUrlSanitizer.displayHost(activeController()?.currentUrl)
        minimizedCard?.findViewById<TextView>(R.id.browser_minimized_title)?.text = title.ifNullOrBlank("Browser")
    }

    private fun activeController(): WebViewController? =
        tabs.getOrNull(activeIndex)?.controller

    private fun setRootFullscreen() {
        val view = root ?: return
        val params = rootLayoutParams ?: view.layoutParams ?: return
        if (params.width == ViewGroup.LayoutParams.MATCH_PARENT &&
            params.height == ViewGroup.LayoutParams.MATCH_PARENT
        ) {
            return
        }
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        if (params is FrameLayout.LayoutParams) {
            params.gravity = Gravity.NO_GRAVITY
            params.setMargins(0, 0, 0, 0)
        }
        view.layoutParams = params
    }

    private fun setRootFloatingCardBounds() {
        val view = root ?: return
        val params = rootLayoutParams ?: view.layoutParams ?: return
        val targetWidth = 262.dp()
        val targetHeight = 96.dp()
        if (params.width == targetWidth && params.height == targetHeight) {
            return
        }
        params.width = targetWidth
        params.height = targetHeight
        if (params is FrameLayout.LayoutParams) {
            params.gravity = Gravity.BOTTOM or Gravity.END
            params.setMargins(0, 0, 10.dp(), 96.dp())
        }
        view.layoutParams = params
    }

    private fun browserButton(iconRes: Int, tint: Int, onClick: () -> Unit): ImageButton =
        ImageButton(activity).apply {
            setImageResource(iconRes)
            setColorFilter(tint)
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(9.dp(), 9.dp(), 9.dp(), 9.dp())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun View.setDragGesture() {
        var downY = 0f
        var dragging = false
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    dragging = false
                    panel?.animate()?.cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - downY).coerceAtLeast(0f)
                    if (dy > 6.dp()) dragging = true
                    panel?.translationY = dy
                    dimView?.alpha = (1f - dy / 360.dp()).coerceIn(0.35f, 1f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = (event.rawY - downY).coerceAtLeast(0f)
                    if (dy > 220.dp()) {
                        closeAll()
                    } else if (dy > 84.dp()) {
                        minimize()
                    } else {
                        panel?.animate()
                            ?.translationY(0f)
                            ?.setDuration(180L)
                            ?.setInterpolator(DecelerateInterpolator())
                            ?.start()
                        dimView?.animate()?.alpha(1f)?.setDuration(180L)?.start()
                    }
                    dragging
                }
                else -> false
            }
        }
    }

    private fun applyBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val overlay = root
        if (enabled) {
            val effect = RenderEffect.createBlurEffect(8f, 8f, Shader.TileMode.CLAMP)
            for (index in 0 until host.childCount) {
                val child = host.getChildAt(index)
                if (child !== overlay) child.setRenderEffect(effect)
            }
        } else {
            for (index in 0 until host.childCount) {
                host.getChildAt(index).setRenderEffect(null)
            }
        }
    }

    private fun showBlockedToast(url: String?) {
        Toast.makeText(
            activity,
            LocaleHelper.getString(activity, "toast_open_link_error"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun roundedDrawable(
        fill: Int,
        radius: Int,
        stroke: Int? = null,
        strokeWidth: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != null && strokeWidth > 0) {
            setStroke(strokeWidth, stroke)
        }
    }

    private fun String?.ifNullOrBlank(fallback: String): String =
        if (isNullOrBlank()) fallback else this

    private fun Int.dp(): Int = (this * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_ACTIVE_INDEX = "in_app_browser_active_index"
        const val KEY_NEXT_TAB_ID = "in_app_browser_next_tab_id"
        const val KEY_EXPANDED = "in_app_browser_expanded"
        const val KEY_MINIMIZED = "in_app_browser_minimized"
        const val KEY_TAB_COUNT = "in_app_browser_tab_count"
        const val KEY_TAB_PREFIX = "in_app_browser_tab_"
        const val KEY_TAB_ID = "in_app_browser_tab_id"
    }
}
