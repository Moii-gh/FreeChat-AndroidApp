package com.example.chatapp.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class WebViewController(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStateChanged(controller: WebViewController)
        fun onBlockedUrl(url: String?)
    }

    val webView: WebView = WebView(context)
    var currentUrl: String? = null
        private set
    var title: String = ""
        private set
    var progress: Int = 0
        private set
    var isLoading: Boolean = false
        private set

    init {
        configureWebView()
    }

    fun load(url: String) {
        val safeUrl = BrowserUrlSanitizer.normalize(url)
        if (safeUrl == null) {
            listener.onBlockedUrl(url)
            return
        }
        currentUrl = safeUrl
        webView.loadUrl(safeUrl)
        listener.onStateChanged(this)
    }

    fun restore(state: Bundle): Boolean {
        val restored = webView.restoreState(state) != null
        currentUrl = state.getString(KEY_URL, currentUrl)
        title = state.getString(KEY_TITLE, title)
        progress = state.getInt(KEY_PROGRESS, progress)
        isLoading = state.getBoolean(KEY_LOADING, false)
        listener.onStateChanged(this)
        return restored
    }

    fun saveState(outState: Bundle) {
        webView.saveState(outState)
        outState.putString(KEY_URL, currentUrl)
        outState.putString(KEY_TITLE, title)
        outState.putInt(KEY_PROGRESS, progress)
        outState.putBoolean(KEY_LOADING, isLoading)
    }

    fun canGoBack(): Boolean = webView.canGoBack()

    fun canGoForward(): Boolean = webView.canGoForward()

    fun goBack() {
        if (webView.canGoBack()) webView.goBack()
    }

    fun goForward() {
        if (webView.canGoForward()) webView.goForward()
    }

    fun reload() {
        webView.reload()
    }

    fun onResume() {
        webView.onResume()
        webView.resumeTimers()
    }

    fun onPause() {
        webView.onPause()
        webView.pauseTimers()
    }

    fun destroy() {
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.settings.safeBrowsingEnabled = true
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress.coerceIn(0, 100)
                isLoading = progress in 1..99
                listener.onStateChanged(this@WebViewController)
            }

            override fun onReceivedTitle(view: WebView?, newTitle: String?) {
                title = newTitle.orEmpty()
                listener.onStateChanged(this@WebViewController)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                val safeUrl = BrowserUrlSanitizer.normalize(url)
                return if (safeUrl == null) {
                    listener.onBlockedUrl(url)
                    true
                } else {
                    currentUrl = safeUrl
                    false
                }
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val safeUrl = BrowserUrlSanitizer.normalize(url)
                return if (safeUrl == null) {
                    listener.onBlockedUrl(url)
                    true
                } else {
                    currentUrl = safeUrl
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                currentUrl = BrowserUrlSanitizer.normalize(url) ?: url
                isLoading = true
                listener.onStateChanged(this@WebViewController)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                currentUrl = BrowserUrlSanitizer.normalize(url) ?: url
                isLoading = false
                progress = 100
                listener.onStateChanged(this@WebViewController)
            }

            override fun onSafeBrowsingHit(
                view: WebView?,
                request: WebResourceRequest?,
                threatType: Int,
                callback: SafeBrowsingResponse?
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    callback?.backToSafety(true)
                }
                listener.onBlockedUrl(request?.url?.toString())
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri: Uri = request?.url ?: return null
                val scheme = uri.scheme?.lowercase()
                return if (scheme == "http" || scheme == "https") null else WebResourceResponse(
                    "text/plain",
                    "utf-8",
                    null
                )
            }
        }
    }

    private companion object {
        const val KEY_URL = "in_app_browser_url"
        const val KEY_TITLE = "in_app_browser_title"
        const val KEY_PROGRESS = "in_app_browser_progress"
        const val KEY_LOADING = "in_app_browser_loading"
    }
}
