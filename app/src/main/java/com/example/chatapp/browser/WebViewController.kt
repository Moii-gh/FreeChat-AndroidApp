package com.example.chatapp.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
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
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.onPause()
            webView.pauseTimers()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            CookieManager.getInstance().flush()
            webView.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.overScrollMode = WebView.OVER_SCROLL_IF_CONTENT_SCROLLS
        webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)

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
            setSupportMultipleWindows(true)
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

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                if (!isUserGesture) return false
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = createPopupRelayWebView()
                resultMsg.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString()
                val safeUrl = BrowserUrlSanitizer.normalize(url)
                val isMainFrame = request?.isForMainFrame != false
                return if (safeUrl == null) {
                    if (isMainFrame) listener.onBlockedUrl(url)
                    true
                } else if (isBlockedUrl(safeUrl)) {
                    if (isMainFrame) listener.onBlockedUrl(url)
                    true
                } else {
                    if (isMainFrame) currentUrl = safeUrl
                    false
                }
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val safeUrl = BrowserUrlSanitizer.normalize(url)
                return if (safeUrl == null) {
                    listener.onBlockedUrl(url)
                    true
                } else if (isBlockedUrl(safeUrl)) {
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
                    return null
                }

                return if (request.isForMainFrame == false && AdBlocker.shouldBlock(uri)) {
                    emptyResponse(detectMimeType(uri))
                } else {
                    null
                }
            }
        }
    }

    private fun createPopupRelayWebView(): WebView =
        WebView(webView.context).apply {
            settings.apply {
                javaScriptEnabled = false
                domStorageEnabled = false
                loadsImagesAutomatically = false
                mediaPlaybackRequiresUserGesture = true
                setSupportMultipleWindows(false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    relayPopupUrl(request?.url?.toString(), view)
                    return true
                }

                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    relayPopupUrl(url, view)
                    return true
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    relayPopupUrl(url, view)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? = emptyResponse("text/plain")
            }
        }

    private fun relayPopupUrl(url: String?, popupView: WebView?) {
        val safeUrl = BrowserUrlSanitizer.normalize(url)
        if (safeUrl == null || isBlockedUrl(safeUrl)) {
            popupView?.post { popupView.destroy() }
            if (safeUrl == null && !url.isNullOrBlank()) {
                listener.onBlockedUrl(url)
            }
            return
        }

        currentUrl = safeUrl
        webView.post {
            webView.loadUrl(safeUrl)
            listener.onStateChanged(this)
        }
        popupView?.post { popupView.destroy() }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return true
        return AdBlocker.shouldBlock(uri)
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
            "adform.net",
            "adfox.ru",
            "adnxs.com",
            "adroll.com",
            "adsafeprotected.com",
            "adsrvr.org",
            "amazon-adsystem.com",
            "an.yandex.",
            "appsflyer.com",
            "betweendigital.com",
            "bidr.io",
            "criteo.com",
            "doubleclick.net",
            "exoclick.com",
            "facebook.net",
            "flashtalking.com",
            "google-analytics.com",
            "googlesyndication.com",
            "googleadservices.com",
            "googletagmanager.com",
            "hotjar.com",
            "imrworldwide.com",
            "mc.yandex.",
            "metrika.yandex.",
            "mgid.com",
            "moatads.com",
            "openx.net",
            "outbrain.com",
            "popads.net",
            "propellerads.com",
            "pubmatic.com",
            "relap.io",
            "revcontent.com",
            "rubiconproject.com",
            "scorecardresearch.com",
            "smartadserver.com",
            "taboola.com",
            "trafficjunky.net",
            "yieldmo.com",
            "adservice.google.",
            "adsystem.com",
            "connect.facebook.net"
        )

        private val blockedPathTokens = listOf(
            "/ads/",
            "/ad/",
            "/adserver",
            "/adservice",
            "/advert",
            "/analytics",
            "/collect",
            "/g/collect",
            "/pagead/",
            "/prebid",
            "/partnerads",
            "/pubads",
            "/sponsor",
            "/tracking",
            "/track/",
            "/pixel",
            "adsbygoogle",
            "banner",
            "prebid.js",
            "yandex.ru/ads"
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
        val COSMETIC_AD_BLOCK_SCRIPT = """
            (function() {
              var selectors = [
                '[id*="ad-"]','[id^="ad_"]','[id$="_ad"]','[class*=" ad-"]',
                '[class*=" ads"]','[class*="advert"]','[class*="banner"]',
                '[aria-label*="advertisement" i]','iframe[src*="doubleclick"]',
                'iframe[src*="googlesyndication"]','iframe[src*="adservice"]',
                'iframe[src*="adfox"]','iframe[src*="taboola"]',
                'iframe[src*="outbrain"]','iframe[src*="mgid"]'
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
