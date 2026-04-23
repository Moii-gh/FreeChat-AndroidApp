package com.example.chatapp

import android.content.Context
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.AiApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
        val now = System.currentTimeMillis()
        dao.markChatDeleted(chatId, now)
        dao.deleteMessagesByChatId(chatId)
    }

    suspend fun togglePinChat(chatId: String, pinned: Boolean) {
        dao.updateChatPinned(chatId, pinned)
    }

    suspend fun deleteAllChats() {
        val ownerKey = currentOwnerKey()
        val now = System.currentTimeMillis()
        dao.markAllChatsDeleted(ownerKey, now)
        dao.deleteAllMessagesByOwnerKey(ownerKey)
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

        AiApiService.generateTitle(authToken, firstUserMessage)
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
