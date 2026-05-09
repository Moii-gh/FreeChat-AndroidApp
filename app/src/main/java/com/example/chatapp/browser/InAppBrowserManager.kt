package com.example.chatapp.browser

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import com.example.chatapp.LocaleHelper
import com.example.chatapp.R

class InAppBrowserManager(
    private val activity: Activity,
    private val host: ViewGroup
) : WebViewController.Listener {

    enum class FutureContentType {
        WebPage,
        Pdf,
        Image
    }

    private var controller: WebViewController? = null
    private var root: FrameLayout? = null
    private var dimView: View? = null
    private var panel: LinearLayout? = null
    private var toolbarTitle: TextView? = null
    private var toolbarSubtitle: TextView? = null
    private var webContainer: FrameLayout? = null
    private var progressBar: ProgressBar? = null
    private var refreshButton: ImageButton? = null
    private var isBrowserVisible = false
    private var isBrowserOpening = false
    private var openAnimationToken = 0
    private val openInterpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)

    private var searchOverlay: FrameLayout? = null
    private var searchBar: LinearLayout? = null
    private var searchInput: EditText? = null
    private var isSearchVisible = false

    fun open(rawUrl: String, contentType: FutureContentType = FutureContentType.WebPage) {
        if (contentType != FutureContentType.WebPage) {
            showBlockedToast()
            return
        }

        val url = BrowserUrlSanitizer.normalize(rawUrl)
        if (url == null) {
            showBlockedToast()
            return
        }

        ensureViews()
        hideSearchBar(immediate = true)
        val shouldAnimateOpen = !isBrowserShowing()
        if (controller == null) {
            replaceController()
            attachController()
        } else if (controller?.webView?.parent !== webContainer) {
            attachController()
        }
        if (shouldAnimateOpen) {
            showBrowser(animate = true)
        } else {
            root?.bringToFront()
            refreshToolbar()
        }
        controller?.load(url)
    }

    fun onBackPressed(): Boolean {
        if (controller == null) return false
        closeBrowser()
        return true
    }

    fun onResume() {
        controller?.onResume()
    }

    fun onPause() {
        controller?.onPause()
    }

    fun onDestroy() {
        closeBrowser()
    }

    override fun onStateChanged(controller: WebViewController) {
        if (controller !== this.controller) return
        refreshToolbar()
    }

    override fun onBlockedUrl(url: String?) {
        showBlockedToast()
    }

    private fun ensureViews() {
        if (root != null) return

        val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val surface = if (isDark) Color.parseColor("#F21C1C1E") else Color.parseColor("#F7FFFFFF")
        val stroke = if (isDark) Color.parseColor("#3A3A3C") else Color.parseColor("#33000000")
        val primary = if (isDark) Color.WHITE else Color.parseColor("#101014")
        val secondary = if (isDark) Color.parseColor("#B3B3B3") else Color.parseColor("#707078")

        root = FrameLayout(activity).apply {
            visibility = View.GONE
            clipChildren = true
            clipToPadding = true
            elevation = 90f
        }

        dimView = View(activity).apply {
            setBackgroundColor(Color.parseColor(if (isDark) "#99000000" else "#66000000"))
            alpha = 0f
            isClickable = true
            isFocusable = false
        }
        root?.addView(dimView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            background = roundedDrawable(surface, 26.dp(), stroke, 1.dp())
            elevation = 94f
        }

        val topDepth = View(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor(if (isDark) "#33FFFFFF" else "#26000000"),
                    Color.TRANSPARENT
                )
            )
        }
        panel?.addView(topDepth, LinearLayout.LayoutParams.MATCH_PARENT, 6.dp())

        val handle = View(activity).apply {
            background = roundedDrawable(
                if (isDark) Color.parseColor("#5A5A5C") else Color.parseColor("#C4C4C8"),
                999.dp()
            )
        }
        val handleWrap = FrameLayout(activity).apply {
            setPadding(0, 10.dp(), 0, 4.dp())
            addView(handle, FrameLayout.LayoutParams(44.dp(), 4.dp(), Gravity.CENTER))
            setSwipeDownCloseGesture()
        }
        panel?.addView(handleWrap, LinearLayout.LayoutParams.MATCH_PARENT, 22.dp())

        val toolbar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 0, 10.dp(), 8.dp())
        }

        refreshButton = browserButton(R.drawable.ic_browser_refresh, primary) {
            controller?.reload()
        }

        val titleBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10.dp(), 0, 10.dp(), 0)
            isClickable = true
            isFocusable = true
            setOnClickListener { showSearchBar() }
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

        toolbar.addView(refreshButton, LinearLayout.LayoutParams(36.dp(), 36.dp()))
        toolbar.addView(titleBox, LinearLayout.LayoutParams(0, 40.dp(), 1f))
        toolbar.addView(
            browserButton(R.drawable.ic_assistant_close, primary) { closeBrowser() },
            LinearLayout.LayoutParams(36.dp(), 36.dp())
        )
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
                topMargin = 42.dp()
            }
        )

        host.addView(
            root ?: return,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun replaceController() {
        controller?.destroy()
        controller = WebViewController(activity, this)
    }

    private fun attachController() {
        val webView = controller?.webView ?: return
        val container = webContainer ?: return
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.removeAllViews()
        container.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        controller?.onResume()
    }

    private fun showBrowser(animate: Boolean) {
        val browserRoot = root ?: return
        val browserPanel = panel ?: return
        val backdrop = dimView

        openAnimationToken++
        val animationToken = openAnimationToken

        browserRoot.isVisible = true
        browserRoot.bringToFront()
        backdrop?.animate()?.cancel()
        browserPanel.animate().cancel()
        backdrop?.isVisible = true
        browserPanel.isVisible = true
        browserPanel.alpha = 1f
        browserPanel.scaleX = 1f
        browserPanel.scaleY = 1f
        refreshToolbar()

        if (!animate) {
            isBrowserOpening = false
            isBrowserVisible = true
            backdrop?.alpha = 1f
            browserPanel.translationY = 0f
            browserPanel.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }

        isBrowserOpening = true
        isBrowserVisible = false
        val fallbackOffset = browserRoot.height
            .takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels
        browserPanel.translationY = fallbackOffset.toFloat()
        backdrop?.alpha = 0f
        browserPanel.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        browserPanel.doOnPreDraw {
            if (animationToken != openAnimationToken || panel !== browserPanel) return@doOnPreDraw

            val startOffset = browserPanel.height
                .takeIf { it > 0 }
                ?: fallbackOffset
            browserPanel.translationY = startOffset.toFloat()
            backdrop?.animate()
                ?.alpha(1f)
                ?.setDuration(BROWSER_DIM_FADE_DURATION_MS)
                ?.setInterpolator(openInterpolator)
                ?.start()
            browserPanel.animate()
                .translationY(0f)
                .setDuration(BROWSER_OPEN_DURATION_MS)
                .setInterpolator(openInterpolator)
                .withEndAction {
                    if (animationToken != openAnimationToken || panel !== browserPanel) return@withEndAction
                    isBrowserOpening = false
                    isBrowserVisible = true
                    browserPanel.translationY = 0f
                    browserPanel.setLayerType(View.LAYER_TYPE_NONE, null)
                    backdrop?.alpha = 1f
                }
                .start()
        }
    }

    private fun closeBrowser() {
        openAnimationToken++
        isBrowserOpening = false
        isBrowserVisible = false
        hideSearchBar(immediate = true)
        panel?.animate()?.cancel()
        dimView?.animate()?.cancel()
        panel?.setLayerType(View.LAYER_TYPE_NONE, null)
        controller?.destroy()
        controller = null
        webContainer?.removeAllViews()
        root?.let { host.removeView(it) }
        root = null
        dimView = null
        panel = null
        toolbarTitle = null
        toolbarSubtitle = null
        webContainer = null
        progressBar = null
        refreshButton = null
        searchOverlay = null
        searchBar = null
        searchInput = null
    }

    private fun refreshToolbar() {
        val active = controller
        val title = active?.title?.takeIf { it.isNotBlank() }
            ?: BrowserUrlSanitizer.displayHost(active?.currentUrl)
            ?: ""
        toolbarTitle?.text = title.ifBlank { "Web" }
        toolbarSubtitle?.text = BrowserUrlSanitizer.displayHost(active?.currentUrl)
        progressBar?.progress = active?.progress ?: 0
        progressBar?.isVisible = active?.isLoading == true
        refreshButton?.isEnabled = active != null
        refreshButton?.alpha = if (active != null) 1f else 0.38f
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

    private fun View.setSwipeDownCloseGesture() {
        var downY = 0f
        var dragging = false
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    dragging = false
                    openAnimationToken++
                    isBrowserOpening = false
                    isBrowserVisible = true
                    panel?.animate()?.cancel()
                    dimView?.animate()?.cancel()
                    panel?.setLayerType(View.LAYER_TYPE_NONE, null)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - downY).coerceAtLeast(0f)
                    if (dy > 6.dp()) dragging = true
                    if (dragging) {
                        panel?.translationY = dy
                        dimView?.alpha = (1f - dy / 360.dp()).coerceIn(0.35f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = (event.rawY - downY).coerceAtLeast(0f)
                    if (dragging && dy > 120.dp()) {
                        closeBrowser()
                    } else {
                        panel?.animate()
                            ?.translationY(0f)
                            ?.setDuration(BROWSER_SETTLE_DURATION_MS)
                            ?.setInterpolator(openInterpolator)
                            ?.start()
                        dimView?.animate()
                            ?.alpha(1f)
                            ?.setDuration(BROWSER_SETTLE_DURATION_MS)
                            ?.setInterpolator(openInterpolator)
                            ?.start()
                        if (!dragging) view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun showSearchBar() {
        if (isSearchVisible || controller == null) return
        isSearchVisible = true

        val isDark = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val surface = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#F2F2F7")
        val primary = if (isDark) Color.WHITE else Color.parseColor("#101014")
        val secondary = if (isDark) Color.parseColor("#B3B3B3") else Color.parseColor("#707078")
        val accent = Color.parseColor("#0A84FF")

        if (searchOverlay == null) {
            searchOverlay = FrameLayout(activity).apply {
                isClickable = true
                isFocusable = true
                setOnClickListener { hideSearchBar() }
            }

            searchBar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedDrawable(
                    surface,
                    999.dp(),
                    if (isDark) Color.parseColor("#48484A") else Color.parseColor("#C6C6C8"),
                    1.dp()
                )
                setPadding(14.dp(), 10.dp(), 10.dp(), 10.dp())
                elevation = 4f
                isClickable = true
            }

            val searchIcon = ImageView(activity).apply {
                setImageResource(R.drawable.ic_search)
                setColorFilter(secondary)
            }
            searchBar?.addView(searchIcon, LinearLayout.LayoutParams(20.dp(), 20.dp()).apply {
                rightMargin = 8.dp()
            })

            searchInput = EditText(activity).apply {
                setTextColor(primary)
                setHintTextColor(secondary)
                hint = LocaleHelper.getString(activity, "button_search_web")
                textSize = 14f
                background = null
                maxLines = 1
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_GO
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_GO ||
                        actionId == EditorInfo.IME_ACTION_DONE ||
                        actionId == EditorInfo.IME_ACTION_SEARCH
                    ) {
                        navigateOrSearch(text?.toString().orEmpty())
                        true
                    } else {
                        false
                    }
                }
            }
            searchBar?.addView(searchInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val cancelButton = TextView(activity).apply {
                text = LocaleHelper.getString(activity, "button_cancel")
                setTextColor(accent)
                textSize = 14f
                setPadding(10.dp(), 6.dp(), 6.dp(), 6.dp())
                isClickable = true
                isFocusable = true
                setOnClickListener { hideSearchBar() }
            }
            searchBar?.addView(cancelButton, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            searchOverlay?.addView(
                searchBar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP
                ).apply {
                    leftMargin = 10.dp()
                    rightMargin = 10.dp()
                    topMargin = 8.dp()
                }
            )
        }

        searchInput?.setText(controller?.currentUrl.orEmpty())

        if (searchOverlay?.parent != null) {
            (searchOverlay?.parent as? ViewGroup)?.removeView(searchOverlay)
        }
        root?.addView(
            searchOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = 42.dp()
            }
        )
        searchOverlay?.elevation = 98f

        searchInput?.post {
            searchInput?.requestFocus()
            searchInput?.selectAll()
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideSearchBar(immediate: Boolean = false) {
        if (!isSearchVisible && !immediate) return
        isSearchVisible = false

        val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput?.windowToken, 0)
        (searchOverlay?.parent as? ViewGroup)?.removeView(searchOverlay)
    }

    private fun navigateOrSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            hideSearchBar()
            return
        }

        val targetUrl = BrowserUrlSanitizer.normalize(trimmed)
            ?: "https://www.google.com/search?q=${Uri.encode(trimmed)}"
        controller?.load(targetUrl)
        hideSearchBar()
    }

    private fun showBlockedToast() {
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

    private fun isBrowserShowing(): Boolean =
        root?.isVisible == true && (isBrowserVisible || isBrowserOpening)

    private fun Int.dp(): Int = (this * activity.resources.displayMetrics.density).toInt()

    private companion object {
        const val BROWSER_OPEN_DURATION_MS = 340L
        const val BROWSER_DIM_FADE_DURATION_MS = 240L
        const val BROWSER_SETTLE_DURATION_MS = 180L
    }
}
