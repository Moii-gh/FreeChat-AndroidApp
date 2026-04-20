package com.example.chatapp.telegram

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

object TelegramNativeLoginClient {
    private const val OAUTH_BASE_URL = "https://oauth.telegram.org"
    private const val PREFS_NAME = "telegram_native_login"
    private const val KEY_CODE_VERIFIER = "code_verifier"

    suspend fun startLogin(
        context: Context,
        clientId: String,
        redirectUri: String,
        scopes: List<String>
    ): Result<Unit> {
        if (clientId.isBlank() || redirectUri.isBlank()) {
            return Result.failure(IllegalStateException("Telegram Login не настроен"))
        }

        return runCatching {
            val verifier = generateCodeVerifier()
            saveCodeVerifier(context, verifier)
            val challenge = generateCodeChallenge(verifier)

            // Same flow as Telegram's Android SDK: try Telegram app through /crossapp first,
            // then fall back to the OIDC authorization endpoint in a Custom Tab.
            val inAppUrl = fetchInAppUrl(
                clientId = clientId,
                redirectUri = redirectUri,
                codeChallenge = challenge,
                scopes = scopes
            ).getOrNull()

            val openedInTelegram = inAppUrl != null &&
                tryOpenIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(inAppUrl)))

            if (!openedInTelegram) {
                openWebAuth(
                    context = context,
                    clientId = clientId,
                    redirectUri = redirectUri,
                    codeChallenge = challenge,
                    scopes = scopes
                )
            }
        }.onFailure {
            clearCodeVerifier(context)
        }
    }

    suspend fun handleLoginResponse(
        context: Context,
        uri: Uri,
        clientId: String,
        redirectUri: String
    ): Result<String> {
        uri.getQueryParameter("error")?.let { error ->
            val description = uri.getQueryParameter("error_description") ?: error
            clearCodeVerifier(context)
            return Result.failure(IllegalStateException(description))
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Telegram не вернул authorization code"))
        }

        val verifier = loadCodeVerifier(context)
        if (verifier.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Сессия Telegram Login истекла. Попробуйте ещё раз."))
        }

        return exchangeCode(
            code = code,
            clientId = clientId,
            redirectUri = redirectUri,
            codeVerifier = verifier
        ).also {
            if (it.isSuccess) {
                clearCodeVerifier(context)
            }
        }
    }

    fun isTelegramLoginRedirect(uri: Uri?, redirectUri: String): Boolean {
        if (uri == null || redirectUri.isBlank()) {
            return false
        }

        val expected = runCatching { Uri.parse(redirectUri) }.getOrNull() ?: return false
        return uri.scheme == expected.scheme &&
            uri.host == expected.host &&
            uri.path?.startsWith(expected.path.orEmpty()) == true
    }

    private suspend fun fetchInAppUrl(
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        scopes: List<String>
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = Uri.parse("$OAUTH_BASE_URL/crossapp").buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", buildScopeString(scopes))
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("android_sdk", "1")
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
                .toString()

            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }

            try {
                val body = readResponseBody(connection)
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Telegram cross-app auth failed: $body")
                }

                JSONObject(body).let { json ->
                    json.optString("url").takeIf(String::isNotBlank)
                        ?: json.optJSONObject("result")?.optString("url")?.takeIf(String::isNotBlank)
                        ?: throw IllegalStateException("Telegram did not return native auth URL")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openWebAuth(
        context: Context,
        clientId: String,
        redirectUri: String,
        codeChallenge: String,
        scopes: List<String>
    ) {
        val authUri = Uri.parse("$OAUTH_BASE_URL/auth").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", buildScopeString(scopes))
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .build()

        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(context, authUri)
    }

    private suspend fun exchangeCode(
        code: String,
        clientId: String,
        redirectUri: String,
        codeVerifier: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL("$OAUTH_BASE_URL/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }

            try {
                val postBody = listOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "code_verifier" to codeVerifier
                ).joinToString("&") { (key, value) ->
                    "${urlEncode(key)}=${urlEncode(value)}"
                }

                OutputStreamWriter(connection.outputStream).use { it.write(postBody) }

                val body = readResponseBody(connection)
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("Telegram token exchange failed: $body")
                }

                JSONObject(body).let { json ->
                    json.optString("id_token").takeIf(String::isNotBlank)
                        ?: json.optString("result").takeIf(String::isNotBlank)
                }
                    ?: throw IllegalStateException("Telegram did not return id_token")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun readResponseBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun buildScopeString(scopes: List<String>): String {
        return buildList {
            add("openid")
            scopes.map(String::trim)
                .filter { it.isNotEmpty() && it != "openid" }
                .forEach(::add)
        }.joinToString(" ")
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun saveCodeVerifier(context: Context, verifier: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CODE_VERIFIER, verifier)
            .apply()
    }

    private fun loadCodeVerifier(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CODE_VERIFIER, null)

    private fun clearCodeVerifier(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CODE_VERIFIER)
            .apply()
    }

    private fun tryOpenIntent(context: Context, intent: Intent): Boolean {
        return try {
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
