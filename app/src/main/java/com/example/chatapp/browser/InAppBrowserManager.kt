package com.example.chatapp.browser

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
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
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
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
    private var browserCloseButton: ImageButton? = null
    private var browserRefreshButton: ImageButton? = null
    private var bottomFullscreenGestureView: View? = null
    private var pageSplashOverlay: FrameLayout? = null
    private var pageSplashAnimator: Animator? = null
    private var pageSplashToken = 0
    private var isBrowserVisible = false
    private var isBrowserOpening = false
    private var isBrowserClosing = false
    private var isBrowserFullscreen = false
    private var openAnimationToken = 0
    private var panelMarginAnimator: ValueAnimator? = null
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
        showPageLoadingSplash()
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
        closeBrowser(animate = false)
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
            setSwipeDownCloseGesture()
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

        val titleBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp(), 0, 10.dp(), 0)
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

        toolbar.addView(titleBox, LinearLayout.LayoutParams(0, 40.dp(), 1f))
        browserRefreshButton = browserControlButton(
            iconRes = R.drawable.ic_browser_refresh,
            contentDescription = LocaleHelper.getString(activity, "auth_refresh_widget"),
            onClick = { refreshCurrentPageFromActionButton() }
        )
        browserCloseButton = browserControlButton(
            iconRes = R.drawable.ic_assistant_close,
            contentDescription = LocaleHelper.getString(activity, "digital_assistant_close"),
            onClick = { closeBrowser() }
        )
        toolbar.addView(
            browserRefreshButton,
            LinearLayout.LayoutParams(BROWSER_CONTROL_SIZE_DP.dp(), BROWSER_CONTROL_SIZE_DP.dp()).apply {
                rightMargin = BROWSER_CONTROL_GAP_DP.dp()
            }
        )
        toolbar.addView(
            browserCloseButton,
            LinearLayout.LayoutParams(BROWSER_CONTROL_SIZE_DP.dp(), BROWSER_CONTROL_SIZE_DP.dp())
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

        isBrowserFullscreen = false
        val panelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.BOTTOM
        ).apply {
            topMargin = compactBrowserTopMargin()
        }
        root?.addView(
            panel,
            panelParams
        )

        bottomFullscreenGestureView = View(activity).apply {
            setBackgroundColor(Color.TRANSPARENT)
            elevation = 96f
            isClickable = true
            setSwipeUpFullscreenGesture()
        }
        root?.addView(
            bottomFullscreenGestureView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                BROWSER_EDGE_GESTURE_HEIGHT_DP.dp(),
                Gravity.BOTTOM
            )
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
        isBrowserClosing = false

        browserRoot.isVisible = true
        browserRoot.bringToFront()
        backdrop?.animate()?.cancel()
        browserPanel.animate().cancel()
        browserRefreshButton?.animate()?.cancel()
        browserCloseButton?.animate()?.cancel()
        browserRefreshButton?.isEnabled = true
        browserCloseButton?.isEnabled = true
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
            showBrowserControls(immediate = true)
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
        hideBrowserControls(immediate = true)

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
            showBrowserControls(immediate = false, startDelay = 80L)
        }
    }

    private fun closeBrowser(animate: Boolean = true) {
        val browserRoot = root
        val browserPanel = panel
        val refreshControl = browserRefreshButton
        val closeControl = browserCloseButton

        if (!animate || browserRoot == null || browserPanel == null) {
            openAnimationToken++
            teardownBrowser()
            return
        }
        if (isBrowserClosing) return

        openAnimationToken++
        val animationToken = openAnimationToken
        isBrowserOpening = false
        isBrowserVisible = false
        isBrowserClosing = true
        hideSearchBar(immediate = true)
        browserPanel.animate().cancel()
        dimView?.animate()?.cancel()
        panelMarginAnimator?.cancel()
        refreshControl?.animate()?.cancel()
        closeControl?.animate()?.cancel()
        refreshControl?.isEnabled = false
        closeControl?.isEnabled = false
        browserPanel.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val endOffset = browserRoot.height
            .takeIf { it > 0 }
            ?: activity.resources.displayMetrics.heightPixels

        dimView?.animate()
            ?.alpha(0f)
            ?.setDuration(BROWSER_CLOSE_DIM_FADE_DURATION_MS)
            ?.setInterpolator(openInterpolator)
            ?.start()
        hideBrowserControls(immediate = false)
        browserPanel.animate()
            .translationY(endOffset.toFloat())
            .setDuration(BROWSER_CLOSE_DURATION_MS)
            .setInterpolator(openInterpolator)
            .withEndAction {
                if (animationToken != openAnimationToken || panel !== browserPanel) return@withEndAction
                teardownBrowser()
            }
            .start()
    }

    private fun teardownBrowser() {
        isBrowserOpening = false
        isBrowserVisible = false
        isBrowserClosing = false
        isBrowserFullscreen = false
        panelMarginAnimator?.cancel()
        panelMarginAnimator = null
        panel?.animate()?.cancel()
        dimView?.animate()?.cancel()
        browserRefreshButton?.animate()?.cancel()
        browserCloseButton?.animate()?.cancel()
        panel?.setLayerType(View.LAYER_TYPE_NONE, null)
        pageSplashToken += 1
        pageSplashAnimator?.cancel()
        pageSplashAnimator = null
        pageSplashOverlay?.let { overlay ->
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        pageSplashOverlay = null
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
        browserRefreshButton = null
        browserCloseButton = null
        bottomFullscreenGestureView = null
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
        browserRefreshButton?.isEnabled = active != null
        browserRefreshButton?.alpha = if (active != null) BROWSER_CONTROL_ALPHA else 0.38f
    }

    private fun browserControlButton(
        iconRes: Int,
        contentDescription: String,
        onClick: () -> Unit
    ): ImageButton =
        ImageButton(activity).apply {
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            setBackgroundResource(R.drawable.circle_button_bg)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(11.dp(), 11.dp(), 11.dp(), 11.dp())
            alpha = 0f
            scaleX = 0.9f
            scaleY = 0.9f
            translationY = (-4).dp().toFloat()
            elevation = 98f
            isClickable = true
            isFocusable = true
            this.contentDescription = contentDescription
            setOnClickListener {
                performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
        }

    private fun refreshCurrentPageFromActionButton() {
        controller?.reload()
        browserRefreshButton?.animate()
            ?.rotationBy(180f)
            ?.setDuration(BROWSER_REFRESH_SPIN_DURATION_MS)
            ?.setInterpolator(openInterpolator)
            ?.start()
    }

    private fun showPageLoadingSplash() {
        val container = webContainer ?: return
        pageSplashToken += 1
        val token = pageSplashToken

        pageSplashAnimator?.cancel()
        pageSplashOverlay?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
        }

        val overlay = FrameLayout(activity).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.BLACK,
                    Color.parseColor("#050506"),
                    Color.BLACK
                )
            )
            alpha = 1f
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val logo = ImageView(activity).apply {
            setImageResource(R.drawable.logo)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            rotation = 0f
        }

        val logoSize = BROWSER_SPLASH_LOGO_SIZE_DP.dp()
        overlay.addView(logo, FrameLayout.LayoutParams(logoSize, logoSize, Gravity.CENTER))
        container.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        pageSplashOverlay = overlay

        val fadeIn = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logo, View.ALPHA, 0f, BROWSER_SPLASH_LOGO_ALPHA),
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 0.92f, 1f),
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 0.92f, 1f)
            )
            duration = BROWSER_SPLASH_FADE_IN_MS
            interpolator = openInterpolator
        }
        val fadeOut = AnimatorSet().apply {
            startDelay = BROWSER_SPLASH_HOLD_MS
            playTogether(
                ObjectAnimator.ofFloat(overlay, View.ALPHA, 1f, 0f),
                ObjectAnimator.ofFloat(logo, View.ALPHA, BROWSER_SPLASH_LOGO_ALPHA, 0f),
                ObjectAnimator.ofFloat(logo, View.SCALE_X, 1f, 1.04f),
                ObjectAnimator.ofFloat(logo, View.SCALE_Y, 1f, 1.04f)
            )
            duration = BROWSER_SPLASH_FADE_OUT_MS
            interpolator = openInterpolator
        }
        val rotation = ObjectAnimator.ofFloat(logo, View.ROTATION, 0f, BROWSER_SPLASH_ROTATION_DEGREES).apply {
            duration = BROWSER_SPLASH_TOTAL_MS
            interpolator = LinearInterpolator()
        }
        val fadeSequence = AnimatorSet().apply {
            playSequentially(fadeIn, fadeOut)
        }

        pageSplashAnimator = AnimatorSet().apply {
            playTogether(fadeSequence, rotation)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (token != pageSplashToken || pageSplashOverlay !== overlay) return
                    (overlay.parent as? ViewGroup)?.removeView(overlay)
                    pageSplashOverlay = null
                    pageSplashAnimator = null
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (pageSplashOverlay === overlay) {
                        (overlay.parent as? ViewGroup)?.removeView(overlay)
                        pageSplashOverlay = null
                    }
                }
            })
            start()
        }
    }

    private fun showBrowserControls(immediate: Boolean, startDelay: Long = 0L) {
        listOfNotNull(browserRefreshButton, browserCloseButton).forEach { button ->
            button.animate().cancel()
            button.isVisible = true
            if (immediate) {
                button.alpha = BROWSER_CONTROL_ALPHA
                button.scaleX = 1f
                button.scaleY = 1f
                button.translationY = 0f
            } else {
                button.alpha = 0f
                button.scaleX = 0.9f
                button.scaleY = 0.9f
                button.translationY = (-4).dp().toFloat()
                button.animate()
                    .alpha(BROWSER_CONTROL_ALPHA)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(BROWSER_CONTROL_FADE_DURATION_MS)
                    .setStartDelay(startDelay)
                    .setInterpolator(openInterpolator)
                    .start()
            }
        }
    }

    private fun hideBrowserControls(immediate: Boolean) {
        listOfNotNull(browserRefreshButton, browserCloseButton).forEach { button ->
            button.animate().cancel()
            if (immediate) {
                button.alpha = 0f
                button.scaleX = 0.9f
                button.scaleY = 0.9f
                button.translationY = (-4).dp().toFloat()
            } else {
                button.animate()
                    .alpha(0f)
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .translationY((-4).dp().toFloat())
                    .setDuration(BROWSER_CONTROL_FADE_DURATION_MS)
                    .setInterpolator(openInterpolator)
                    .start()
            }
        }
    }

    private fun updateSearchOverlayTopMargin(topMargin: Int = currentPanelTopMargin()) {
        searchOverlay?.let { overlay ->
            val params = overlay.layoutParams as? FrameLayout.LayoutParams ?: return@let
            params.topMargin = topMargin
            overlay.layoutParams = params
        }
    }

    private fun currentPanelTopMargin(): Int =
        (panel?.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: compactBrowserTopMargin()

    private fun compactBrowserTopMargin(): Int = BROWSER_COMPACT_TOP_MARGIN_DP.dp()

    private fun setPanelTopMargin(topMargin: Int) {
        val browserPanel = panel ?: return
        val params = browserPanel.layoutParams as? FrameLayout.LayoutParams ?: return
        params.topMargin = topMargin.coerceIn(0, compactBrowserTopMargin())
        browserPanel.layoutParams = params
        updateSearchOverlayTopMargin(params.topMargin)
    }

    private fun setBrowserFullscreen(fullscreen: Boolean, animate: Boolean) {
        val targetTop = if (fullscreen) 0 else compactBrowserTopMargin()
        val startTop = currentPanelTopMargin()
        isBrowserFullscreen = fullscreen
        panelMarginAnimator?.cancel()
        if (!animate || startTop == targetTop) {
            setPanelTopMargin(targetTop)
            return
        }

        panelMarginAnimator = ValueAnimator.ofInt(startTop, targetTop).apply {
            duration = BROWSER_EXPAND_DURATION_MS
            interpolator = openInterpolator
            addUpdateListener { animator ->
                setPanelTopMargin(animator.animatedValue as Int)
            }
            start()
        }
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
                    panelMarginAnimator?.cancel()
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
                    if (dragging && !isBrowserFullscreen && dy > BROWSER_CLOSE_DRAG_THRESHOLD_DP.dp()) {
                        closeBrowser()
                    } else if (dragging && isBrowserFullscreen && dy > BROWSER_COLLAPSE_DRAG_THRESHOLD_DP.dp()) {
                        panel?.translationY = 0f
                        setBrowserFullscreen(fullscreen = false, animate = true)
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

    private fun View.setSwipeUpFullscreenGesture() {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downY = 0f
        var startTop = 0
        var dragging = false

        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startTop = currentPanelTopMargin()
                    dragging = false
                    panelMarginAnimator?.cancel()
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downY
                    if (-dy > touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        setPanelTopMargin((startTop + dy.toInt()).coerceAtLeast(0))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    val dy = event.rawY - downY
                    if (dragging && -dy > BROWSER_EXPAND_DRAG_THRESHOLD_DP.dp()) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        setBrowserFullscreen(fullscreen = true, animate = true)
                    } else {
                        setBrowserFullscreen(fullscreen = isBrowserFullscreen, animate = true)
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
                topMargin = currentPanelTopMargin()
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
        const val BROWSER_CLOSE_DURATION_MS = 250L
        const val BROWSER_EXPAND_DURATION_MS = 260L
        const val BROWSER_DIM_FADE_DURATION_MS = 240L
        const val BROWSER_CLOSE_DIM_FADE_DURATION_MS = 180L
        const val BROWSER_SETTLE_DURATION_MS = 180L
        const val BROWSER_CONTROL_FADE_DURATION_MS = 180L
        const val BROWSER_REFRESH_SPIN_DURATION_MS = 260L
        const val BROWSER_SPLASH_FADE_IN_MS = 280L
        const val BROWSER_SPLASH_HOLD_MS = 980L
        const val BROWSER_SPLASH_FADE_OUT_MS = 420L
        const val BROWSER_SPLASH_TOTAL_MS = BROWSER_SPLASH_FADE_IN_MS + BROWSER_SPLASH_HOLD_MS + BROWSER_SPLASH_FADE_OUT_MS
        const val BROWSER_SPLASH_LOGO_ALPHA = 0.36f
        const val BROWSER_SPLASH_LOGO_SIZE_DP = 92
        const val BROWSER_SPLASH_ROTATION_DEGREES = 14f
        const val BROWSER_CONTROL_ALPHA = 0.92f
        const val BROWSER_CONTROL_SIZE_DP = 40
        const val BROWSER_CONTROL_GAP_DP = 8
        const val BROWSER_EDGE_GESTURE_HEIGHT_DP = 30
        const val BROWSER_COMPACT_TOP_MARGIN_DP = 42
        const val BROWSER_CLOSE_DRAG_THRESHOLD_DP = 120
        const val BROWSER_COLLAPSE_DRAG_THRESHOLD_DP = 34
        const val BROWSER_EXPAND_DRAG_THRESHOLD_DP = 34
    }
}
