package com.example.chatapp

import android.content.Context
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.AiApiService
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.network.dto.ChatShareMessageDto
import com.example.chatapp.network.dto.CreateChatShareRequest
import com.example.chatapp.network.dto.CreateChatShareResponse
import com.example.chatapp.network.dto.RevokeChatShareResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatRepository(context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val dao = db.chatDao()
    private val sessionStore = SharedPrefsAccountSessionStore(context)
    private val scopedSettings = AccountScopedSettings(context)
    private val baseUrl = BuildConfig.APP_API_BASE_URL

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

    suspend fun getMessages(chatId: String): List<MessageEntity> =
        dao.getMessagesByChatIdSync(chatId)

    suspend fun createShareLink(chatId: String): Result<CreateChatShareResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val token = requireAuthToken()
            val chat = getChatById(chatId) ?: error("Chat not found")
            val messages = getMessages(chatId)
            if (messages.isEmpty()) {
                error("Chat is empty")
            }

            val request = CreateChatShareRequest(
                sourceChatId = chat.id,
                title = chat.title.ifBlank { "Shared chat" },
                summary = chat.summary,
                messages = messages.map { message ->
                    ChatShareMessageDto(
                        role = message.role,
                        content = message.content,
                        timestamp = message.timestamp,
                        imageUrl = message.imageUrl
                    )
                }
            )

            val response = NetworkModule.createChatShareApiService(baseUrl, token)
                .createChatShare(request)
            if (!response.isSuccessful) {
                error(response.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: "Share link creation failed")
            }

            response.body() ?: error("Empty share response")
        }
    }

    suspend fun revokeShareLinks(chatId: String): Result<RevokeChatShareResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val token = requireAuthToken()
            val response = NetworkModule.createChatShareApiService(baseUrl, token)
                .revokeChatSharesForChat(chatId)
            if (!response.isSuccessful) {
                error(response.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: "Share link revoke failed")
            }

            response.body() ?: error("Empty revoke response")
        }
    }

    suspend fun importSharedChat(shareToken: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val response = NetworkModule.createChatShareApiService(baseUrl)
                .getChatShare(shareToken)
            if (!response.isSuccessful) {
                error(response.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: "Share link is unavailable")
            }

            val snapshot = response.body() ?: error("Empty share response")
            if (snapshot.messages.isEmpty()) {
                error("Shared chat is empty")
            }

            val now = System.currentTimeMillis()
            val newChatId = UUID.randomUUID().toString()
            dao.insertChat(
                ChatEntity(
                    id = newChatId,
                    ownerKey = currentOwnerKey(),
                    title = snapshot.title.ifBlank { "Shared chat" },
                    timestamp = now,
                    lastUpdated = now,
                    summary = snapshot.summary
                )
            )
            dao.insertMessages(
                snapshot.messages.map { message ->
                    MessageEntity(
                        chatId = newChatId,
                        role = message.role,
                        content = message.content,
                        timestamp = message.timestamp,
                        imageUrl = message.imageUrl,
                        syncId = UUID.randomUUID().toString()
                    )
                }
            )
            newChatId
        }
    }

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

    suspend fun deleteMessagesFromIndex(chatId: String, fromIndex: Int) {
        dao.deleteMessagesFromIndex(chatId, fromIndex)
    }

    suspend fun generateChatTitle(
        firstUserMessage: String
    ): String? = withContext(Dispatchers.IO) {
        val authToken = sessionStore.getAuthToken()?.trim().orEmpty()
        if (authToken.isBlank()) {
            return@withContext null
        }

        AiApiService.generateTitle(authToken, firstUserMessage)
    }

    private fun currentOwnerKey(): String {
        return sessionStore.getCurrentUserId()?.takeIf { it.isNotBlank() }?.let { "user_$it" }
            ?: sessionStore.getCurrentUserEmail()?.takeIf { it.isNotBlank() }?.let {
                "email_${it.lowercase().replace(Regex("[^a-z0-9]"), "_")}"
            }
            ?: scopedSettings.currentAccountKey()
    }

    private fun requireAuthToken(): String {
        return sessionStore.getAuthToken()?.trim()?.takeIf { it.isNotBlank() }
            ?: error("Session expired. Sign in again.")
    }
}
