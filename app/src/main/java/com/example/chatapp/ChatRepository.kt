package com.example.chatapp

import android.content.Context
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class ChatRepository(context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val dao = db.chatDao()
    private val sessionStore = SharedPrefsAccountSessionStore(context)
    private val scopedSettings = AccountScopedSettings(context)

    fun getAllChatsFlow(): Flow<List<ChatEntity>> = dao.getAllChats(currentOwnerKey())

    suspend fun getAllChats(): List<ChatEntity> = dao.getAllChatsSync(currentOwnerKey())

    suspend fun getChatById(chatId: String): ChatEntity? = dao.getChatById(chatId, currentOwnerKey())

    suspend fun createChat(temporaryTitle: String): String {
        val chatId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val chat = ChatEntity(
            id = chatId,
            ownerKey = currentOwnerKey(),
            title = temporaryTitle,
            timestamp = now,
            lastUpdated = now
        )
        dao.insertChat(chat)
        return chatId
    }

    suspend fun updateChatTitle(chatId: String, title: String) {
        dao.updateChatTitle(chatId, title)
    }

    suspend fun updateChatSummary(chatId: String, summary: String) {
        dao.updateChatSummary(chatId, summary)
    }

    suspend fun deleteChat(chatId: String) {
        dao.deleteChatWithMessages(chatId)
    }

    suspend fun togglePinChat(chatId: String, pinned: Boolean) {
        dao.updateChatPinned(chatId, pinned)
    }

    suspend fun deleteAllChats() {
        dao.deleteEverything(currentOwnerKey())
    }

    fun getMessagesFlow(chatId: String): Flow<List<MessageEntity>> =
        dao.getMessagesByChatId(chatId)

    suspend fun getMessages(chatId: String): List<MessageEntity> =
        dao.getMessagesByChatIdSync(chatId)

    suspend fun addUserMessage(chatId: String, content: String, imageUrl: String? = null): Long {
        val msg = MessageEntity(
            chatId = chatId,
            role = "user",
            content = content,
            timestamp = System.currentTimeMillis(),
            imageUrl = imageUrl
        )
        val id = dao.insertMessage(msg)
        dao.updateChatLastUpdated(chatId, System.currentTimeMillis())
        return id
    }

    suspend fun addAssistantMessage(chatId: String, content: String): Long {
        val msg = MessageEntity(
            chatId = chatId,
            role = "assistant",
            content = content,
            timestamp = System.currentTimeMillis()
        )
        val id = dao.insertMessage(msg)
        dao.updateChatLastUpdated(chatId, System.currentTimeMillis())
        return id
    }

    suspend fun deleteLastAssistantMessage(chatId: String) {
        dao.deleteLastAssistantMessage(chatId)
    }

    suspend fun deleteMessagesFromIndex(chatId: String, fromIndex: Int) {
        dao.deleteMessagesFromIndex(chatId, fromIndex)
    }

    suspend fun getMessageCount(chatId: String): Int = dao.getMessageCount(chatId)

    suspend fun generateChatTitle(
        firstUserMessage: String
    ): String? = withContext(Dispatchers.IO) {
        val authToken = sessionStore.getAuthToken()?.trim().orEmpty()
        if (authToken.isBlank()) {
            return@withContext null
        }

        try {
            val connection = (URL("${BuildConfig.APP_API_BASE_URL}ai/title").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Authorization", "Bearer $authToken")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }

            val jsonInput = JSONObject().apply {
                put("firstUserMessage", firstUserMessage)
            }.toString()

            OutputStreamWriter(connection.outputStream).use {
                it.write(jsonInput)
                it.flush()
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(
                    InputStreamReader(connection.inputStream, "utf-8")
                ).readText()

                val title = JSONObject(response)
                    .optString("content", "")
                    .trim()
                    .removeSurrounding("\"")
                    .removeSuffix(".")

                if (title.isNotBlank() && title.length <= 60) title else null
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getChatHistoryAsJson(chatId: String): List<JSONObject> {
        return dao.getMessagesByChatIdSync(chatId).map { msg ->
            JSONObject().apply {
                put("role", msg.role)
                put("content", msg.content)
                if (msg.imageUrl != null) {
                    put("fileUri", msg.imageUrl)
                }
            }
        }
    }

    fun currentScopedSettings(): AccountScopedSettings = scopedSettings

    private fun currentOwnerKey(): String {
        return sessionStore.getCurrentUserId()?.takeIf { it.isNotBlank() }?.let { "user_$it" }
            ?: sessionStore.getCurrentUserEmail()?.takeIf { it.isNotBlank() }?.let {
                "email_${it.lowercase().replace(Regex("[^a-z0-9]"), "_")}"
            }
            ?: scopedSettings.currentAccountKey()
    }
}
