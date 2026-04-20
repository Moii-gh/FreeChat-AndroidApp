package com.example.chatapp.util

import com.example.chatapp.network.dto.TelegramWidgetLoginRequest
import org.json.JSONObject

object TelegramWidgetPayloadParser {
    fun parse(payload: String): TelegramWidgetLoginRequest? {
        return runCatching {
            val json = JSONObject(payload)

            TelegramWidgetLoginRequest(
                id = json.get("id").toString(),
                firstName = json.getString("first_name"),
                lastName = json.optionalString("last_name"),
                username = json.optionalString("username"),
                photoUrl = json.optionalString("photo_url"),
                authDate = json.getLong("auth_date"),
                hash = json.getString("hash")
            )
        }.getOrNull()
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) {
            return null
        }

        return optString(key).trim().takeIf { it.isNotEmpty() }
    }
}
