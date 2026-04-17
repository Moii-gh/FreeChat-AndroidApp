package com.example.chatapp

import android.net.Uri
import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.chatapp.data.AuthRepository
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.navigation.AuthNavGraph
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.ui.auth.theme.ChatAppTheme
import com.example.chatapp.viewmodel.AuthViewModel

class MainActivity : ComponentActivity() {

    private val accountSessionStore by lazy {
        SharedPrefsAccountSessionStore(applicationContext)
    }

    private val authViewModel: AuthViewModel by viewModels {
        AuthViewModel.Factory(
            repository = AuthRepository(
                service = NetworkModule.createAuthApiService(BuildConfig.APP_API_BASE_URL)
            ),
            accountSessionStore = accountSessionStore
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (accountSessionStore.isSignedIn()) {
            startActivity(Intent(this, FreeChatActivity::class.java))
            finish()
            return
        }

        setContent {
            ChatAppTheme {
                AuthNavGraph(viewModel = authViewModel)
            }
        }
    }

    fun setInputContext(
        title: String,
        iconRes: Int,
        hint: String,
        iconTint: String = "#34C759",
        mode: String? = null
    ) = Unit

    fun showFilePreview(fileUri: Uri) = Unit
}
