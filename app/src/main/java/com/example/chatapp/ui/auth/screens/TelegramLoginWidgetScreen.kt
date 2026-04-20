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

    BackHandler(onBack = onBack)

    AuthScreenLayout(
        title = "Вход через Telegram",
        subtitle = "Подтвердите вход в официальном Telegram Login Widget.",
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
                        val message = "Не удалось прочитать ответ Telegram"
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
            text = "Обновить виджет",
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
    onPageLoadingChanged: (Boolean) -> Unit,
    onPageError: (String) -> Unit,
    onAuthPayload: (String) -> Unit
) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.javaScriptCanOpenWindowsAutomatically = true
    settings.setSupportMultipleWindows(true)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    overScrollMode = WebView.OVER_SCROLL_NEVER

    // Android forwards the signed Telegram payload to ViewModel; backend is the only trust boundary.
    addJavascriptInterface(
        TelegramAuthBridge(onAuthPayload, onPageError),
        "AndroidTelegramAuth"
    )

    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            return openTelegramScheme(context, request?.url)
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
                onPageError("Нет подключения или Telegram Login Widget недоступен")
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
                    "Telegram Login Widget не настроен на backend. Проверьте TELEGRAM_BOT_TOKEN и TELEGRAM_BOT_USERNAME."
                } else {
                    "Сервер не смог открыть Telegram Login Widget"
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
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
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
            onError(message.ifBlank { "Не удалось загрузить Telegram Login Widget" })
        }
    }
}
