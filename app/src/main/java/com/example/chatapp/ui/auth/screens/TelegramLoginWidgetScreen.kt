package com.example.chatapp.ui.auth.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.chatapp.BuildConfig
import com.example.chatapp.network.dto.TelegramWidgetLoginRequest
import com.example.chatapp.ui.auth.components.AuthScreenLayout
import com.example.chatapp.ui.auth.components.SecondaryOutlineButton
import com.example.chatapp.ui.auth.components.StatusMessageCard
import com.example.chatapp.ui.auth.theme.AppStroke
import com.example.chatapp.ui.auth.theme.AppTextPrimary
import com.example.chatapp.LocaleHelper
import com.example.chatapp.util.TelegramWidgetPayloadParser
import com.example.chatapp.viewmodel.AuthUiState

@Composable
fun TelegramLoginWidgetScreen(
    state: AuthUiState,
    widgetUrl: String,
    onAuthData: (TelegramWidgetLoginRequest) -> Unit,
    onError: (String) -> Unit,
    onBack: () -> Unit
) {
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    val context = androidx.compose.ui.platform.LocalContext.current

    BackHandler(onBack = onBack)

    AuthScreenLayout(
        title = LocaleHelper.getString(context, "auth_telegram_widget_title"),
        subtitle = LocaleHelper.getString(context, "auth_telegram_widget_subtitle"),
        onBack = onBack
    ) {
        StatusMessageCard(
            errorMessage = pageError ?: state.errorMessage,
            infoMessage = state.infoMessage
        )
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AppStroke)
        ) {
            TelegramWidgetWebView(
                widgetUrl = widgetUrl,
                reloadKey = reloadKey,
                onPageLoadingChanged = { pageLoading = it },
                onPageError = { message ->
                    pageError = message
                    onError(message)
                },
                onAuthPayload = { payload ->
                    val request = TelegramWidgetPayloadParser.parse(payload)
                    if (request == null) {
                        val message = LocaleHelper.getString(context, "auth_telegram_read_response_error")
                        pageError = message
                        onError(message)
                    } else {
                        pageError = null
                        onAuthData(request)
                    }
                }
            )
            if (pageLoading || state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.38f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = AppTextPrimary,
                        strokeWidth = 3.dp
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SecondaryOutlineButton(
            text = LocaleHelper.getString(context, "auth_refresh_widget"),
            onClick = {
                pageError = null
                reloadKey += 1
            },
            enabled = !state.isLoading && !pageLoading
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TelegramWidgetWebView(
    widgetUrl: String,
    reloadKey: Int,
    onPageLoadingChanged: (Boolean) -> Unit,
    onPageError: (String) -> Unit,
    onAuthPayload: (String) -> Unit
) {
    var loadedReloadKey by remember { mutableStateOf(0) }

    AndroidView(
        factory = { context ->
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                configureTelegramWebView(
                    context = context,
                    widgetUrl = widgetUrl,
                    onPageLoadingChanged = onPageLoadingChanged,
                    onPageError = onPageError,
                    onAuthPayload = onAuthPayload
                )

                loadUrl(widgetUrl)
            }
        },
        update = { webView ->
            if (reloadKey != loadedReloadKey) {
                loadedReloadKey = reloadKey
                onPageLoadingChanged(true)
                webView.loadUrl(widgetUrl)
            }
        }
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureTelegramWebView(
    context: Context,
    widgetUrl: String,
    onPageLoadingChanged: (Boolean) -> Unit,
    onPageError: (String) -> Unit,
    onAuthPayload: (String) -> Unit
) {
    val widgetUri = runCatching { Uri.parse(widgetUrl) }.getOrNull()
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(true)
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    overScrollMode = WebView.OVER_SCROLL_NEVER

    // Мост принимает только подписанный payload; проверка доверия остается на backend.
    addJavascriptInterface(
        TelegramAuthBridge(onAuthPayload, onPageError),
        "AndroidTelegramAuth"
    )

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val targetUri = request?.url
            if (openTelegramScheme(context, targetUri)) {
                return true
            }
            if (request?.isForMainFrame == true && !isAllowedTelegramWidgetNavigation(widgetUri, targetUri)) {
                onPageLoadingChanged(false)
                onPageError(LocaleHelper.getString(context, "auth_telegram_widget_server_open_error"))
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            onPageLoadingChanged(true)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            onPageLoadingChanged(false)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                onPageLoadingChanged(false)
                onPageError(LocaleHelper.getString(context, "auth_telegram_widget_unavailable"))
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            if (request?.isForMainFrame == true) {
                onPageLoadingChanged(false)
                val message = if (errorResponse?.statusCode == 503) {
                    LocaleHelper.getString(context, "auth_telegram_widget_backend_not_configured")
                } else {
                    LocaleHelper.getString(context, "auth_telegram_widget_server_open_error")
                }
                onPageError(message)
            }
        }
    }

    val mainWebView = this
    webChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val popupWebView = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(false)
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(
                    TelegramAuthBridge(onAuthPayload, onPageError),
                    "AndroidTelegramAuth"
                )
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return false
                        if (openTelegramScheme(context, uri)) {
                            return true
                        }
                        if (!isAllowedTelegramWidgetNavigation(widgetUri, uri)) {
                            onPageError(LocaleHelper.getString(context, "auth_telegram_widget_server_open_error"))
                            return true
                        }
                        mainWebView.loadUrl(uri.toString())
                        return true
                    }
                }
            }

            transport.webView = popupWebView
            resultMsg.sendToTarget()
            return true
        }
    }
}

private fun isAllowedTelegramWidgetNavigation(widgetUri: Uri?, uri: Uri?): Boolean {
    val scheme = uri?.scheme?.lowercase().orEmpty()
    if (scheme != "https" && !(BuildConfig.DEBUG && scheme == "http")) {
        return false
    }

    val host = uri?.host?.lowercase().orEmpty()
    val widgetHost = widgetUri?.host?.lowercase().orEmpty()
    return host.isNotBlank() && (
        host == widgetHost ||
            host == "oauth.telegram.org" ||
            host == "telegram.org" ||
            host == "t.me" ||
            host == "web.telegram.org"
        )
}

private fun openTelegramScheme(context: Context, uri: Uri?): Boolean {
    if (uri?.scheme != "tg") {
        return false
    }

    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }.isSuccess
}

private class TelegramAuthBridge(
    private val onAuthPayload: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onTelegramAuth(payload: String) {
        mainHandler.post { onAuthPayload(payload) }
    }

    @JavascriptInterface
    fun onTelegramAuthError(message: String) {
        mainHandler.post {
            onError(message.ifBlank { "Telegram Login Widget error" })
        }
    }
}
