package com.example.chatapp.telegram

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
    private const val PREFS_NAME = "telegram_native_login_secure"
    private const val KEY_CODE_VERIFIER = "code_verifier"
    private const val KEY_OAUTH_STATE = "oauth_state"
    private const val KEY_ID_TOKEN_NONCE = "id_token_nonce"

    suspend fun startLogin(
        context: Context,
        clientId: String,
        redirectUri: String,
        scopes: List<String>
    ): Result<Unit> {
        if (clientId.isBlank() || redirectUri.isBlank()) {
            return Result.failure(IllegalStateException("auth_error_telegram_login_not_configured"))
        }

        return runCatching {
            val verifier = generateCodeVerifier()
            val state = generateCodeVerifier()
            val nonce = generateCodeVerifier()
            savePendingLogin(context, verifier, state, nonce)
            val challenge = generateCodeChallenge(verifier)

            val inAppUrl = fetchInAppUrl(
                clientId = clientId,
                redirectUri = redirectUri,
                codeChallenge = challenge,
                scopes = scopes,
                state = state,
                nonce = nonce
            ).getOrNull()

            val openedInTelegram = inAppUrl != null &&
                tryOpenIntent(context, Intent(Intent.ACTION_VIEW, Uri.parse(inAppUrl)))

            if (!openedInTelegram) {
                openWebAuth(
                    context = context,
                    clientId = clientId,
                    redirectUri = redirectUri,
                    codeChallenge = challenge,
                    scopes = scopes,
                    state = state,
                    nonce = nonce
                )
            }
        }.onFailure {
            clearPendingLogin(context)
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
            clearPendingLogin(context)
            return Result.failure(IllegalStateException(description))
        }

        val code = uri.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            return Result.failure(IllegalStateException("auth_error_telegram_code_missing"))
        }

        val state = uri.getQueryParameter("state")
        val verifier = loadSecureValue(context, KEY_CODE_VERIFIER)
        val expectedState = loadSecureValue(context, KEY_OAUTH_STATE)
        val expectedNonce = loadSecureValue(context, KEY_ID_TOKEN_NONCE)

        if (verifier.isNullOrBlank() || expectedNonce.isNullOrBlank()) {
            return Result.failure(IllegalStateException("auth_error_telegram_session_expired"))
        }

        if (state != null && state != expectedState) {
            clearPendingLogin(context)
            return Result.failure(IllegalStateException("Telegram login state mismatch. Expected: $expectedState, Got: $state"))
        }

        return exchangeCode(
            code = code,
            clientId = clientId,
            redirectUri = redirectUri,
            codeVerifier = verifier
        ).mapCatching { idToken ->
            verifyIdTokenNonce(idToken, expectedNonce)
            idToken
        }.also {
            clearPendingLogin(context)
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
        scopes: List<String>,
        state: String,
        nonce: String
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
                .appendQueryParameter("state", state)
                .appendQueryParameter("nonce", nonce)
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
        scopes: List<String>,
        state: String,
        nonce: String
    ) {
        val authUri = Uri.parse("$OAUTH_BASE_URL/auth").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", buildScopeString(scopes))
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
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

    private fun verifyIdTokenNonce(idToken: String, expectedNonce: String) {
        val parts = idToken.split(".")
        if (parts.size != 3) {
            throw IllegalStateException("Telegram returned malformed ID token")
        }

        val payloadJson = JSONObject(
            String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8
            )
        )
        val actualNonce = payloadJson.optString("nonce")
        if (actualNonce.isBlank() || actualNonce != expectedNonce) {
            throw IllegalStateException("Telegram ID token nonce mismatch")
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

    private fun savePendingLogin(context: Context, verifier: String, state: String, nonce: String) {
        securePrefs(context).edit()
            .putString(KEY_CODE_VERIFIER, verifier)
            .putString(KEY_OAUTH_STATE, state)
            .putString(KEY_ID_TOKEN_NONCE, nonce)
            .apply()
    }

    private fun loadSecureValue(context: Context, key: String): String? =
        securePrefs(context).getString(key, null)

    private fun clearPendingLogin(context: Context) {
        securePrefs(context).edit()
            .remove(KEY_CODE_VERIFIER)
            .remove(KEY_OAUTH_STATE)
            .remove(KEY_ID_TOKEN_NONCE)
            .apply()
    }

    private fun securePrefs(context: Context) = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS_NAME,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

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
