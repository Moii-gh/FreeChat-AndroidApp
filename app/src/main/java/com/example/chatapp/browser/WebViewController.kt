package com.example.chatapp.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SafeBrowsingResponse
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

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
        CookieManager.getInstance().flush()
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

        // ── Cookie management ──
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

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
                view?.evaluateJavascript(COSMETIC_AD_BLOCK_SCRIPT, null)
                CookieManager.getInstance().flush()
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
                if (scheme != "http" && scheme != "https") {
                    return emptyResponse("text/plain")
                }

                return if (request?.isForMainFrame == false && AdBlocker.shouldBlock(uri)) {
                    emptyResponse(detectMimeType(uri))
                } else {
                    null
                }
            }
        }
    }

    private fun emptyResponse(mimeType: String): WebResourceResponse =
        WebResourceResponse(
            mimeType,
            "utf-8",
            ByteArrayInputStream(ByteArray(0))
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                responseHeaders = mapOf("Cache-Control" to "no-store")
            }
        }

    private fun detectMimeType(uri: Uri): String {
        val path = uri.path.orEmpty().lowercase()
        return when {
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".gif") -> "image/gif"
            path.endsWith(".webp") -> "image/webp"
            else -> "text/plain"
        }
    }

    private object AdBlocker {
        private val blockedHosts = listOf(
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "adservice.google.",
            "adsystem.com",
            "adnxs.com",
            "criteo.com",
            "taboola.com",
            "outbrain.com",
            "yandex.ru/ads",
            "mc.yandex.",
            "metrika.yandex.",
            "facebook.net",
            "connect.facebook.net",
            "scorecardresearch.com",
            "hotjar.com",
            "googletagmanager.com",
            "google-analytics.com"
        )

        private val blockedPathTokens = listOf(
            "/ads/",
            "/ad/",
            "/adservice",
            "/advert",
            "/analytics",
            "/collect",
            "/g/collect",
            "/pagead/",
            "/partnerads",
            "/sponsor",
            "/tracking",
            "/track/",
            "/pixel",
            "banner"
        )

        fun shouldBlock(uri: Uri): Boolean {
            val host = uri.host.orEmpty().lowercase()
            val full = uri.toString().lowercase()
            if (blockedHosts.any { host.contains(it) || full.contains(it) }) {
                return true
            }
            return blockedPathTokens.any { token -> full.contains(token) }
        }
    }

    private companion object {
        const val KEY_URL = "in_app_browser_url"
        const val KEY_TITLE = "in_app_browser_title"
        const val KEY_PROGRESS = "in_app_browser_progress"
        const val KEY_LOADING = "in_app_browser_loading"
        val COSMETIC_AD_BLOCK_SCRIPT = """
            (function() {
              var selectors = [
                '[id*="ad-"]','[id^="ad_"]','[id$="_ad"]','[class*=" ad-"]',
                '[class*=" ads"]','[class*="advert"]','[class*="banner"]',
                '[aria-label*="advertisement" i]','iframe[src*="doubleclick"]',
                'iframe[src*="googlesyndication"]','iframe[src*="adservice"]'
              ];
              try {
                document.querySelectorAll(selectors.join(',')).forEach(function(el) {
                  el.style.setProperty('display', 'none', 'important');
                });
              } catch (e) {}
            })();
        """.trimIndent()
    }
}
