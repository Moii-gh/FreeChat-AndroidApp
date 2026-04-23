package com.example.chatapp.network

import com.example.chatapp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object AiApiService {

    interface StreamCallback {
        fun onChunk(accumulatedText: String)
        fun onComplete(fullText: String)
        fun onError(errorMessage: String)
    }

    fun buildSystemPrompt(
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        filesContext: String = ""
    ): String? {
        val baseSystemPrompt = when (currentMode) {
            "shopping" -> "РўС‹ РїРѕРјРѕС‰РЅРёРє РїРѕ РїРѕРєСѓРїРєР°Рј. РС‰Рё РІ РёРЅС‚РµСЂРЅРµС‚Рµ С‚РѕР»СЊРєРѕ С‚РѕРІР°СЂС‹, РїСЂРµРґРѕСЃС‚Р°РІР»СЏСЏ РІР°СЂРёР°РЅС‚С‹ СЃ С†РµРЅР°РјРё Рё РІРѕР·РјРѕР¶РЅС‹РјРё РјРµСЃС‚Р°РјРё РїСЂРёРѕР±СЂРµС‚РµРЅРёСЏ."
            "study" -> "РўС‹ СѓС‡РёС‚РµР»СЊ. РћС‚РІРµС‡Р°Р№ РєР°Рє РѕРїС‹С‚РЅС‹Р№ РїСЂРµРїРѕРґР°РІР°С‚РµР»СЊ, РѕР±СЉСЏСЃРЅСЏР№ РїРѕРґСЂРѕР±РЅРѕ, РїСЂРёРІРѕРґРё РЅР°РіР»СЏРґРЅС‹Рµ РїСЂРёРјРµСЂС‹ Рё Р·Р°РґР°РІР°Р№ РІРѕРїСЂРѕСЃС‹ РґР»СЏ РїСЂРѕРІРµСЂРєРё РїРѕРЅРёРјР°РЅРёСЏ."
            else -> null
        }

        val parts = mutableListOf<String>()
        if (baseSystemPrompt != null) parts.add(baseSystemPrompt)
        if (customInstructions.isNotEmpty()) {
            parts.add("РџРѕР»СЊР·РѕРІР°С‚РµР»СЊСЃРєРёРµ РёРЅСЃС‚СЂСѓРєС†РёРё (СЃС‚СЂРѕРіРѕ СЃР»РµРґСѓР№ РёРј):\n$customInstructions")
        }
        if (filesContext.isNotEmpty()) {
            parts.add("РџРѕР»РЅРѕРµ СЃРѕРґРµСЂР¶РёРјРѕРµ РїСЂРёРєСЂРµРїР»С‘РЅРЅС‹С… С„Р°Р№Р»РѕРІ (С‚С‹ Р·РЅР°РµС€СЊ РёС… РїРѕР»РЅРѕСЃС‚СЊСЋ, РёСЃРїРѕР»СЊР·СѓР№ СЌС‚Рё РґР°РЅРЅС‹Рµ РґР»СЏ РѕС‚РІРµС‚РѕРІ):\n$filesContext")
        }
        if (chatContextSummary.isNotEmpty()) {
            parts.add("РљСЂР°С‚РєР°СЏ РІС‹Р¶РёРјРєР° РїСЂРµРґС‹РґСѓС‰РµРіРѕ СЂР°Р·РіРѕРІРѕСЂР° (РІР°Р¶РЅРѕ РґР»СЏ РєРѕРЅС‚РµРєСЃС‚Р°):\n$chatContextSummary")
        }

        return if (parts.isNotEmpty()) parts.joinToString("\n\n") else null
    }

    fun buildRequestBody(
        isImageGeneration: Boolean,
        messagesToKeep: List<JSONObject>,
        systemPrompt: String?
    ): String {
        return JSONObject().apply {
            if (isImageGeneration) {
                put("response_format", "b64_json")
                val lastPrompt = messagesToKeep.lastOrNull {
                    it.getString("role") == "user"
                }?.getString("content") ?: "Creative image"
                put("prompt", lastPrompt)
            } else {
                put("stream", true)

                val messages = JSONArray()
                if (systemPrompt != null) {
                    messages.put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }

                messagesToKeep.forEach { msg ->
                    val messageText = buildMessageText(msg)
                    val mimeType = normalizedMimeType(msg)
                    if (msg.has("base64") && isImageMimeType(mimeType)) {
                        messages.put(
                            JSONObject().apply {
                                put("role", msg.optString("role", "user"))
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", messageText)
                                    })
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", "data:$mimeType;base64," + msg.getString("base64"))
                                        })
                                    })
                                })
                            }
                        )
                    } else {
                        messages.put(JSONObject().apply {
                            put("role", msg.optString("role", "user"))
                            put("content", messageText)
                        })
                    }
                }

                put("messages", messages)
            }
        }.toString()
    }

    private fun buildMessageText(msg: JSONObject): String {
        val content = msg.optString("content", "")
        val fileName = msg.optString("fileName", "")
        val mimeType = msg.optString("mimeType", "")
        val fileText = msg.optString("fileText", "")

        if (fileName.isBlank() && mimeType.isBlank() && fileText.isBlank()) {
            return content
        }

        return buildString {
            append(content)
            if (isNotBlank()) append("\n\n")
            append("РџСЂРёРєСЂРµРїР»С‘РЅРЅС‹Р№ С„Р°Р№Р»")
            if (fileName.isNotBlank()) append(": ").append(fileName)
            if (mimeType.isNotBlank()) append("\nРўРёРї: ").append(mimeType)
            if (fileText.isNotBlank()) {
                append("\n\n===== РџРћР›РќРћР• РЎРћР”Р•Р Р–РРњРћР• Р¤РђР™Р›Рђ =====\n")
                append(fileText)
                append("\n===== РљРћРќР•Р¦ Р¤РђР™Р›Рђ =====")
            } else if (msg.has("base64") && !isImageMimeType(mimeType)) {
                append("\n\n(Р‘РёРЅР°СЂРЅС‹Р№ С„Р°Р№Р» вЂ” С‚РµРєСЃС‚РѕРІРѕРµ СЃРѕРґРµСЂР¶РёРјРѕРµ РЅРµРґРѕСЃС‚СѓРїРЅРѕ)")
            }
        }
    }

    private fun normalizedMimeType(msg: JSONObject): String {
        val rawMimeType = msg.optString("mimeType", "image/jpeg")
        return rawMimeType.ifBlank { "image/jpeg" }
    }

    private fun isImageMimeType(mimeType: String): Boolean =
        mimeType.startsWith("image/", ignoreCase = true)

    suspend fun fetchStreamingResponse(
        authToken: String,
        messagesToKeep: List<JSONObject>,
        currentMode: String?,
        customInstructions: String,
        chatContextSummary: String,
        filesContext: String = "",
        callback: StreamCallback
    ) {
        withContext(Dispatchers.IO) {
            if (authToken.isBlank()) {
                withContext(Dispatchers.Main) {
                    callback.onError("РўСЂРµР±СѓРµС‚СЃСЏ Р°РІС‚РѕСЂРёР·Р°С†РёСЏ")
                }
                return@withContext
            }

            try {
                val isImageGeneration = currentMode == "create_image"
                val systemPrompt = buildSystemPrompt(currentMode, customInstructions, chatContextSummary, filesContext)
                val jsonInput = buildRequestBody(isImageGeneration, messagesToKeep, systemPrompt)
                val payload = JSONObject().apply {
                    put("currentMode", currentMode)
                    put("request", JSONObject(jsonInput))
                }.toString()

                val connection = (URL("${BuildConfig.APP_API_BASE_URL}ai/chat").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $authToken")
                    doOutput = true
                }

                OutputStreamWriter(connection.outputStream).use {
                    it.write(payload)
                    it.flush()
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    var finalReply = ""

                    if (isImageGeneration) {
                        val response = BufferedReader(
                            InputStreamReader(connection.inputStream, "utf-8")
                        ).readText()
                        val b64 = JSONObject(response)
                            .getJSONArray("data")
                            .getJSONObject(0)
                            .getString("b64_json")
                        finalReply = "![image](data:image/png;base64,$b64)"
                        withContext(Dispatchers.Main) {
                            callback.onChunk(finalReply)
                        }
                    } else {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream, "utf-8"))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (line!!.startsWith("data:")) {
                                val data = line!!.substring(5).trim()
                                if (data == "[DONE]" || data.isEmpty()) continue

                                try {
                                    val json = JSONObject(data)
                                    val chunk = if (json.has("choices")) {
                                        val choices = json.getJSONArray("choices")
                                        if (choices.length() > 0) {
                                            choices.getJSONObject(0)
                                                .getJSONObject("delta")
                                                .optString("content", "")
                                        } else {
                                            ""
                                        }
                                    } else {
                                        ""
                                    }

                                    if (chunk.isNotEmpty()) {
                                        finalReply += chunk
                                        withContext(Dispatchers.Main) {
                                            callback.onChunk(finalReply)
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    withContext(Dispatchers.Main) {
                        callback.onComplete(finalReply)
                    }
                } else {
                    val errorBody = try {
                        connection.errorStream?.bufferedReader()?.readText() ?: ""
                    } catch (_: Exception) {
                        ""
                    }

                    val errorMessage = try {
                        val json = JSONObject(errorBody)
                        when {
                            json.has("error") -> json.getJSONObject("error").optString("message")
                            json.has("message") -> json.optString("message")
                            else -> ""
                        }
                    } catch (_: Exception) {
                        "РљРѕРґ ${connection.responseCode}"
                    }

                    withContext(Dispatchers.Main) {
                        callback.onError("РћС€РёР±РєР°: $errorMessage")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback.onError("РћС€РёР±РєР° СЃРµС‚Рё: ${e.message}")
                }
            }
        }
    }

    suspend fun summarizeMessages(
        authToken: String,
        messagesToSummarize: List<JSONObject>
    ): String? {
        if (authToken.isBlank()) {
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val connection = (URL("${BuildConfig.APP_API_BASE_URL}ai/summary").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer $authToken")
                    doOutput = true
                }

                val promptText = buildString {
                    append("РЎРґРµР»Р°Р№ РєСЂР°С‚РєСѓСЋ РІС‹Р¶РёРјРєСѓ РІР°Р¶РЅС‹С… С„Р°РєС‚РѕРІ РёР· СЌС‚РѕР№ С‡Р°СЃС‚Рё РїРµСЂРµРїРёСЃРєРё (СЃРѕС…СЂР°РЅРё РєР»СЋС‡РµРІС‹Рµ РґРµС‚Р°Р»Рё, РІРєР»СЋС‡Р°СЏ РёРјРµРЅР° С„Р°Р№Р»РѕРІ Рё РёС… СЃРѕРґРµСЂР¶РёРјРѕРµ):\n")
                    for (msg in messagesToSummarize) {
                        val role = msg.getString("role")
                        val content = msg.getString("content")
                        append("$role: $content\n")
                        val fileName = msg.optString("fileName", "")
                        val fileText = msg.optString("fileText", "")
                        if (fileName.isNotBlank()) {
                            append("[РџСЂРёРєСЂРµРїР»С‘РЅ С„Р°Р№Р»: $fileName]\n")
                        }
                        if (fileText.isNotBlank()) {
                            val preview = if (fileText.length > 2000) fileText.take(2000) + "..." else fileText
                            append("[РЎРѕРґРµСЂР¶РёРјРѕРµ С„Р°Р№Р»Р°:\n$preview]\n")
                        }
                    }
                }

                val jsonInput = JSONObject().apply {
                    put("promptText", promptText)
                }.toString()

                OutputStreamWriter(connection.outputStream).use {
                    it.write(jsonInput)
                    it.flush()
                }

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = BufferedReader(
                        InputStreamReader(connection.inputStream, "utf-8")
                    ).readText()
                    JSONObject(response).optString("content", null)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
