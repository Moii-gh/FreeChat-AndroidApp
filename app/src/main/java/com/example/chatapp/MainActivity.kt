package com.example.chatapp

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.chatapp.data.AuthRepository
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.navigation.AuthNavGraph
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.telegram.TelegramNativeLoginClient
import com.example.chatapp.ui.LaunchLogoAnimator
import com.example.chatapp.ui.auth.theme.ChatAppTheme
import com.example.chatapp.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val accountSessionStore by lazy {
        SharedPrefsAccountSessionStore(applicationContext)
    }

    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory(
            repository = AuthRepository(
                service = NetworkModule.createAuthApiService(BuildConfig.APP_API_BASE_URL),
                localize = LocaleHelper.localizer(applicationContext)
            ),
            accountSessionStore = accountSessionStore,
            localize = { key -> LocaleHelper.getString(applicationContext, key) }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Chatapp)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (accountSessionStore.isSignedIn()) {
            openChatActivity(intent?.data)
            finish()
            return
        }

        setContent {
            ChatAppTheme {
                AuthNavGraph(viewModel = authViewModel)
            }
        }
        LaunchLogoAnimator.show(this)

        handleTelegramLoginRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (accountSessionStore.isSignedIn() && ChatShareDeepLink.extractToken(intent.data) != null) {
            openChatActivity(intent.data)
            finish()
            return
        }
        handleTelegramLoginRedirect(intent)
    }

    fun setInputContext(
        title: String,
        iconRes: Int,
        hint: String,
        iconTint: String = "#FFFFFF",
        mode: String? = null
    ) = Unit

    fun showFilePreview(fileUri: Uri) = Unit

    fun buildPostAuthIntent(): Intent {
        return Intent(this, FreeChatActivity::class.java).apply {
            val shareUri = intent?.data?.takeIf { ChatShareDeepLink.extractToken(it) != null }
            if (shareUri != null) {
                data = shareUri
            }
        }
    }

    private fun openChatActivity(data: Uri?) {
        startActivity(Intent(this, FreeChatActivity::class.java).apply {
            if (ChatShareDeepLink.extractToken(data) != null) {
                this.data = data
            }
        })
    }

    private fun handleTelegramLoginRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!TelegramNativeLoginClient.isTelegramLoginRedirect(
                uri = uri,
                redirectUri = BuildConfig.TELEGRAM_LOGIN_REDIRECT_URI
            )
        ) {
            return
        }

        intent?.data = null

        lifecycleScope.launch {
            TelegramNativeLoginClient.handleLoginResponse(
                context = this@MainActivity,
                uri = uri,
                clientId = BuildConfig.TELEGRAM_LOGIN_CLIENT_ID,
                redirectUri = BuildConfig.TELEGRAM_LOGIN_REDIRECT_URI
            ).onSuccess { idToken ->
                authViewModel.completeTelegramNativeLogin(idToken)
            }.onFailure { error ->
                authViewModel.onTelegramWidgetError(
                    error.message ?: LocaleHelper.getString(this@MainActivity, "auth_telegram_login_not_completed")
                )
            }
        }
    }
}
