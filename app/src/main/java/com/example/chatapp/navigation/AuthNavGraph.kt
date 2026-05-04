package com.example.chatapp.navigation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.chatapp.BuildConfig
import com.example.chatapp.FreeChatActivity
import com.example.chatapp.MainActivity
import com.example.chatapp.telegram.TelegramNativeLoginClient
import com.example.chatapp.ui.auth.screens.AboutYouScreen
import com.example.chatapp.ui.auth.screens.AuthWelcomeScreen
import com.example.chatapp.ui.auth.screens.BirthDatePickerScreen
import com.example.chatapp.ui.auth.screens.PasswordStepScreen
import com.example.chatapp.ui.auth.screens.TelegramCodeScreen
import com.example.chatapp.ui.auth.screens.TelegramLoginWidgetScreen
import com.example.chatapp.ui.auth.theme.AppBlack
import com.example.chatapp.LocaleHelper
import com.example.chatapp.network.dto.VkNativeLoginRequest
import com.example.chatapp.viewmodel.AuthEvent
import com.example.chatapp.viewmodel.AuthViewModel
import com.vk.id.AccessToken
import com.vk.id.VKID
import com.vk.id.VKIDAuthFail
import com.vk.id.auth.VKIDAuthCallback
import com.vk.id.auth.VKIDAuthParams
import kotlinx.coroutines.launch

@Composable
fun AuthNavGraph(
    viewModel: AuthViewModel,
    navController: NavHostController = rememberNavController()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            when (event) {
                is AuthEvent.Navigate -> {
                    if (event.route == AuthRoutes.SIGNED_IN) {
                        val chatIntent = (activity as? MainActivity)?.buildPostAuthIntent()
                            ?: Intent(activity, FreeChatActivity::class.java)
                        activity?.startActivity(chatIntent)
                        activity?.finish()
                    } else {
                        navController.navigate(event.route) {
                            launchSingleTop = true
                        }
                    }
                }

                is AuthEvent.ShowMessage -> {
                    snackbarScope.launch {
                        snackbarHostState.showSnackbar(event.message)
                    }
                }

                is AuthEvent.OpenTelegram -> {
                    // Преобразуем https://t.me/Bot?start=Code в tg://resolve?domain=Bot&start=Code
                    // чтобы Telegram открывался напрямую, минуя браузер.
                    var directUrl = event.url
                    if (directUrl.startsWith("https://t.me/")) {
                        directUrl = directUrl.replace("https://t.me/", "tg://resolve?domain=")
                            .replace("?start=", "&start=")
                    }
                    
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(directUrl))
                    val launched = runCatching { context.startActivity(intent) }.isSuccess
                    if (!launched) {
                        // Фолбэк на https ссылку, если tg:// не сработал (например, официальный клиент не установлен)
                        val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                        val fallbackLaunched = runCatching { context.startActivity(fallbackIntent) }.isSuccess
                        if (!fallbackLaunched) {
                            snackbarHostState.showSnackbar(LocaleHelper.getString(context, "auth_telegram_open_error"))
                        }
                    }
                }

                is AuthEvent.OpenTelegramNativeLogin -> {
                    TelegramNativeLoginClient.startLogin(
                        context = context,
                        clientId = event.clientId,
                        redirectUri = event.redirectUri,
                        scopes = event.scopes
                    ).onFailure { error ->
                        viewModel.onTelegramWidgetError(
                            error.message ?: LocaleHelper.getString(context, "auth_telegram_login_open_error")
                        )
                    }
                }

                is AuthEvent.OpenVkLogin -> {
                    runCatching {
                        VKID.instance.authorize(
                            lifecycleOwner = lifecycleOwner,
                            callback = object : VKIDAuthCallback {
                                override fun onAuth(accessToken: AccessToken) {
                                    viewModel.completeVkNativeLogin(
                                        VkNativeLoginRequest(
                                            accessToken = accessToken.token,
                                            idToken = accessToken.idToken,
                                            userId = accessToken.userID.toString()
                                        )
                                    )
                                }

                                override fun onFail(fail: VKIDAuthFail) {
                                    if (fail is VKIDAuthFail.Canceled) {
                                        viewModel.onVkLoginCanceled()
                                    } else {
                                        viewModel.onVkLoginError(
                                            fail.description.ifBlank {
                                                LocaleHelper.getString(context, "auth_vk_login_not_completed")
                                            }
                                        )
                                    }
                                }
                            },
                            params = VKIDAuthParams {
                                scopes = event.scopes.toSet()
                            }
                        )
                    }.onFailure { error ->
                        viewModel.onVkLoginError(
                            error.message ?: LocaleHelper.getString(context, "auth_vk_login_open_error")
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = AppBlack,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AuthRoutes.WELCOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            authComposable(AuthRoutes.WELCOME) {
                AuthWelcomeScreen(
                    state = state,
                    onContinueWithTelegram = {
                        viewModel.beginTelegramWidgetLogin(
                            clientId = BuildConfig.TELEGRAM_LOGIN_CLIENT_ID,
                            redirectUri = BuildConfig.TELEGRAM_LOGIN_REDIRECT_URI,
                            scopes = parseTelegramLoginScopes(BuildConfig.TELEGRAM_LOGIN_SCOPES)
                        )
                    },
                    onContinueWithVk = {
                        viewModel.beginVkLogin(
                            isConfigured = BuildConfig.VKID_NATIVE_LOGIN_ENABLED,
                            scopes = parseVkIdScopes(BuildConfig.VKID_SCOPES)
                        )
                    }
                )
            }

            authComposable(AuthRoutes.TELEGRAM_WIDGET) {
                TelegramLoginWidgetScreen(
                    state = state,
                    widgetUrl = buildTelegramWidgetUrl(BuildConfig.APP_API_BASE_URL),
                    onAuthData = viewModel::completeTelegramWidgetLogin,
                    onError = viewModel::onTelegramWidgetError,
                    onBack = {
                        viewModel.onTelegramWidgetCanceled()
                        navController.popBackStack()
                    }
                )
            }



            authComposable(AuthRoutes.TELEGRAM_CODE) {
                TelegramCodeScreen(
                    state = state,
                    onCodeChanged = {
                        viewModel.clearTransientMessage()
                        viewModel.onTelegramCodeChanged(it)
                    },
                    onOpenTelegram = viewModel::openTelegramBot,
                    onContinue = viewModel::verifyTelegramCode,
                    onBack = { navController.popBackStack() }
                )
            }

            authComposable(AuthRoutes.ABOUT_YOU) {
                AboutYouScreen(
                    state = state,
                    onFullNameChanged = {
                        viewModel.clearTransientMessage()
                        viewModel.onFullNameChanged(it)
                    },
                    onBirthDateClick = {
                        viewModel.prepareBirthDateDraft()
                        navController.navigate(AuthRoutes.BIRTH_DATE_PICKER)
                    },
                    onContinue = { navController.navigate(AuthRoutes.PASSWORD_STEP) },
                    onBack = { navController.popBackStack() }
                )
            }

            authComposable(AuthRoutes.BIRTH_DATE_PICKER) {
                BirthDatePickerScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onDaySelected = { viewModel.updateBirthDateDraft(day = it) },
                    onMonthSelected = { viewModel.updateBirthDateDraft(month = it) },
                    onYearSelected = { viewModel.updateBirthDateDraft(year = it) },
                    onConfirm = {
                        viewModel.confirmBirthDateSelection()
                        navController.popBackStack()
                    }
                )
            }

            authComposable(AuthRoutes.PASSWORD_STEP) {
                PasswordStepScreen(
                    state = state,
                    onPasswordChanged = {
                        viewModel.clearTransientMessage()
                        viewModel.onPasswordChanged(it)
                    },
                    onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
                    onContinue = viewModel::submitPasswordStep,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

private fun NavGraphBuilder.authComposable(
    route: String,
    content: @Composable () -> Unit
) {
    composable(
        route = route,
        enterTransition = {
            fadeIn(animationSpec = tween(320)) + slideInHorizontally(
                animationSpec = tween(320),
                initialOffsetX = { it / 4 }
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(280)) + slideOutHorizontally(
                animationSpec = tween(280),
                targetOffsetX = { -it / 10 }
            )
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(320)) + slideInHorizontally(
                animationSpec = tween(320),
                initialOffsetX = { -it / 4 }
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(280)) + slideOutHorizontally(
                animationSpec = tween(280),
                targetOffsetX = { it / 8 }
            )
        }
    ) {
        content()
    }
}

private fun buildTelegramWidgetUrl(apiBaseUrl: String): String {
    val normalizedBaseUrl = if (apiBaseUrl.endsWith("/")) apiBaseUrl else "$apiBaseUrl/"
    return "${normalizedBaseUrl}telegram-auth/widget"
}

private fun parseTelegramLoginScopes(raw: String): List<String> =
    raw.split(",", " ")
        .map(String::trim)
        .filter(String::isNotEmpty)

private fun parseVkIdScopes(raw: String): List<String> =
    raw.split(",", " ")
        .map(String::trim)
        .filter(String::isNotEmpty)
