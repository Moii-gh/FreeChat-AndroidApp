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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.chatapp.FreeChatActivity
import com.example.chatapp.ui.auth.screens.AboutYouScreen
import com.example.chatapp.ui.auth.screens.AuthWelcomeScreen
import com.example.chatapp.ui.auth.screens.BirthDatePickerScreen
import com.example.chatapp.ui.auth.screens.LegacyMigrationScreen
import com.example.chatapp.ui.auth.screens.PasswordStepScreen
import com.example.chatapp.ui.auth.screens.TelegramCodeScreen
import com.example.chatapp.ui.auth.theme.AppBlack
import com.example.chatapp.viewmodel.AuthEvent
import com.example.chatapp.viewmodel.AuthViewModel

@Composable
fun AuthNavGraph(
    viewModel: AuthViewModel,
    navController: NavHostController = rememberNavController()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = context as? Activity

    LaunchedEffect(viewModel.events) {
        viewModel.events.collect { event ->
            when (event) {
                is AuthEvent.Navigate -> {
                    if (event.route == AuthRoutes.SIGNED_IN) {
                        activity?.startActivity(Intent(activity, FreeChatActivity::class.java))
                        activity?.finish()
                    } else {
                        navController.navigate(event.route) {
                            launchSingleTop = true
                        }
                    }
                }

                is AuthEvent.ShowMessage -> {
                    snackbarHostState.showSnackbar(event.message)
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
                            snackbarHostState.showSnackbar("Не удалось открыть Telegram. Проверьте, установлен ли он.")
                        }
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
                    onContinueWithTelegram = viewModel::beginTelegramRegistration,
                    onLoginClick = viewModel::beginTelegramLogin,
                    onLegacyAccountClick = {
                        viewModel.clearTransientMessage()
                        navController.navigate(AuthRoutes.LEGACY_MIGRATION)
                    }
                )
            }

            authComposable(AuthRoutes.LEGACY_MIGRATION) {
                LegacyMigrationScreen(
                    state = state,
                    onEmailChanged = {
                        viewModel.clearTransientMessage()
                        viewModel.onLegacyEmailChanged(it)
                    },
                    onPasswordChanged = {
                        viewModel.clearTransientMessage()
                        viewModel.onLegacyPasswordChanged(it)
                    },
                    onTogglePasswordVisibility = viewModel::togglePasswordVisibility,
                    onContinue = viewModel::beginTelegramMigration,
                    onBack = { navController.popBackStack() }
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
