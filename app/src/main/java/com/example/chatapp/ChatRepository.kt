package com.example.chatapp

import android.content.Context
import androidx.room.withTransaction
import com.example.chatapp.data.AccountScopedSettings
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.AiApiService
import com.example.chatapp.network.AiProvider
import com.example.chatapp.network.AiProviderSettings
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.network.OpenAiDirectService
import com.example.chatapp.network.dto.ChatShareMessageDto
import com.example.chatapp.network.dto.ChatShareItemDto
import com.example.chatapp.network.dto.CreateChatShareRequest
import com.example.chatapp.network.dto.CreateChatShareResponse
import com.example.chatapp.network.dto.RevokeChatShareResponse
import com.example.chatapp.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatRepository(context: Context) {

    data class MessageEditResult(
        val syncId: String,
        val timestamp: Long,
        val updatedAt: Long,
        val editRevision: Int
    )

    private val appContext = context.applicationContext
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.chatDao()
    private val sessionStore = SharedPrefsAccountSessionStore(context)
    private val scopedSettings = AccountScopedSettings(context)
    private val aiProviderSettings = AiProviderSettings(scopedSettings)
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

    suspend fun getMessagesForSync(chatId: String): List<MessageEntity> =
        dao.getMessagesByChatIdForSync(chatId)

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
                    val sharedImageUrl = shareableImageUrl(message)
                    ChatShareMessageDto(
                        role = message.role,
                        content = contentForShare(message.content, sharedImageUrl),
                        timestamp = message.timestamp,
                        imageUrl = sharedImageUrl,
                        attachmentData = message.attachmentData,
                        attachmentMimeType = message.attachmentMimeType,
                        attachmentFileName = message.attachmentFileName,
                        attachmentContext = message.attachmentContext
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

    suspend fun revokeShareLinkByToken(token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val authToken = requireAuthToken()
            val response = NetworkModule.createChatShareApiService(baseUrl, authToken)
                .revokeChatShare(token)
            if (!response.isSuccessful) {
                error(response.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: "Share link revoke failed")
            }

            response.body()?.revoked ?: false
        }
    }

    suspend fun getMySharedLinks(): Result<List<ChatShareItemDto>> = withContext(Dispatchers.IO) {
        runCatching {
            val authToken = requireAuthToken()
            val response = NetworkModule.createChatShareApiService(baseUrl, authToken)
                .getMySharedLinks()
            if (!response.isSuccessful) {
                error(response.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: "Failed to get shared links")
            }

            response.body() ?: emptyList()
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
                    val cachedImageUrl = cacheSharedDataImage(
                        imageUrl = message.imageUrl,
                        mimeType = message.attachmentMimeType,
                        fileName = message.attachmentFileName
                    )
                    MessageEntity(
                        chatId = newChatId,
                        role = message.role,
                        content = message.content,
                        timestamp = message.timestamp,
                        imageUrl = cachedImageUrl ?: message.imageUrl,
                        attachmentData = message.attachmentData,
                        attachmentMimeType = message.attachmentMimeType,
                        attachmentFileName = message.attachmentFileName,
                        attachmentContext = message.attachmentContext,
                        syncId = UUID.randomUUID().toString()
                    )
                }
            )
            newChatId
        }
    }

    suspend fun addUserMessage(
        chatId: String,
        content: String,
        imageUrl: String? = null,
        attachmentData: String? = null,
        attachmentMimeType: String? = null,
        attachmentFileName: String? = null,
        attachmentContext: String? = null,
        syncId: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis(),
        updatedAt: Long = timestamp,
        editRevision: Int = 0
    ): Long {
        val msg = MessageEntity(
            chatId = chatId,
            role = "user",
            content = content,
            timestamp = timestamp,
            imageUrl = imageUrl,
            attachmentData = attachmentData,
            attachmentMimeType = attachmentMimeType,
            attachmentFileName = attachmentFileName,
            attachmentContext = attachmentContext,
            syncId = syncId,
            updatedAt = updatedAt,
            editRevision = editRevision
        )
        val id = dao.insertMessage(msg)
        dao.updateChatLastUpdated(chatId, System.currentTimeMillis())
        return id
    }

    suspend fun addAssistantMessage(
        chatId: String,
        content: String,
        imageUrl: String? = null,
        attachmentData: String? = null,
        attachmentMimeType: String? = null,
        attachmentFileName: String? = null,
        syncId: String = UUID.randomUUID().toString(),
        timestamp: Long = System.currentTimeMillis(),
        updatedAt: Long = timestamp,
        editRevision: Int = 0,
        reaction: String? = null
    ): Long {
        val msg = MessageEntity(
            chatId = chatId,
            role = "assistant",
            content = content,
            timestamp = timestamp,
            imageUrl = imageUrl,
            attachmentData = attachmentData,
            attachmentMimeType = attachmentMimeType,
            attachmentFileName = attachmentFileName,
            syncId = syncId,
            updatedAt = updatedAt,
            editRevision = editRevision,
            reaction = reaction
        )
        val id = dao.insertMessage(msg)
        dao.updateChatLastUpdated(chatId, System.currentTimeMillis())
        return id
    }

    suspend fun updateMessageReaction(syncId: String, reaction: String?) {
        dao.updateMessageReaction(syncId, reaction)
    }

    suspend fun deleteMessagesFromIndex(chatId: String, fromIndex: Int) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            dao.tombstoneMessagesFromIndex(chatId, fromIndex, now)
            dao.updateChatSummary(chatId, "")
            dao.updateChatLastUpdated(chatId, now)
        }
    }

    suspend fun updateUserMessageAndTombstoneTail(
        chatId: String,
        syncId: String,
        historyIndex: Int,
        content: String,
        imageUrl: String?,
        attachmentData: String?,
        attachmentMimeType: String?,
        attachmentFileName: String?,
        attachmentContext: String?
    ): MessageEditResult {
        val now = System.currentTimeMillis()
        return db.withTransaction {
            val existing = dao.getMessageBySyncId(syncId)
                ?: error("Message not found")
            if (existing.chatId != chatId || existing.role != "user") {
                error("Message cannot be edited")
            }

            val nextRevision = existing.editRevision + 1
            dao.updateMessage(
                existing.copy(
                    content = content,
                    imageUrl = imageUrl,
                    attachmentData = attachmentData,
                    attachmentMimeType = attachmentMimeType,
                    attachmentFileName = attachmentFileName,
                    attachmentContext = attachmentContext,
                    updatedAt = now,
                    isDeleted = false,
                    editRevision = nextRevision
                )
            )
            dao.tombstoneMessagesFromIndex(chatId, historyIndex + 1, now)
            dao.updateChatSummary(chatId, "")
            dao.updateChatLastUpdated(chatId, now)

            MessageEditResult(
                syncId = syncId,
                timestamp = existing.timestamp,
                updatedAt = now,
                editRevision = nextRevision
            )
        }
    }

    suspend fun generateChatTitle(
        firstUserMessage: String
    ): String? = withContext(Dispatchers.IO) {
        when (aiProviderSettings.getProvider()) {
            AiProvider.OPENAI -> {
                val apiKey = aiProviderSettings.getOpenAiApiKey()
                if (apiKey.isBlank()) return@withContext null
                OpenAiDirectService.generateTitle(apiKey, firstUserMessage)
            }
            AiProvider.VSEGPT -> {
                val authToken = sessionStore.getAuthToken()?.trim().orEmpty()
                if (authToken.isBlank()) return@withContext null
                AiApiService.generateTitle(authToken, firstUserMessage)
            }
        }
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

    private fun shareableImageUrl(message: MessageEntity): String? {
        val rawImageUrl = message.imageUrl ?: extractImageUrl(message.content)
        if (rawImageUrl.isNullOrBlank()) {
            return null
        }

        if (
            rawImageUrl.startsWith("content://", ignoreCase = true) ||
            rawImageUrl.startsWith("file://", ignoreCase = true)
        ) {
            return FileUtils.localImageUriToDataUrl(
                context = appContext,
                uriString = rawImageUrl,
                fallbackMimeType = message.attachmentMimeType
            )
        }

        return rawImageUrl.takeIf {
            it.startsWith("http", ignoreCase = true) ||
                it.startsWith("data:image", ignoreCase = true)
        }
    }

    private fun cacheSharedDataImage(
        imageUrl: String?,
        mimeType: String?,
        fileName: String?
    ): String? {
        if (imageUrl.isNullOrBlank() || !imageUrl.startsWith("data:image", ignoreCase = true)) {
            return null
        }

        val base64Data = imageUrl.substringAfter(",", "").takeIf { it.isNotBlank() }
            ?: return null
        val resolvedMimeType = mimeType
            ?: imageUrl.substringAfter("data:", "")
                .substringBefore(";")
                .takeIf { it.startsWith("image/", ignoreCase = true) }
            ?: "image/png"
        val extension = when (resolvedMimeType.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> "png"
        }
        val resolvedFileName = fileName?.takeIf { it.isNotBlank() }
            ?: "shared_image_${System.currentTimeMillis()}.$extension"

        return FileUtils.saveBase64FileToCache(appContext, base64Data, resolvedFileName)?.toString()
    }

    private fun contentForShare(content: String, imageUrl: String?): String {
        return if (!imageUrl.isNullOrBlank() && extractImageUrl(content) != null) {
            ""
        } else {
            content
        }
    }

    private fun extractImageUrl(content: String): String? {
        val imageStart = content.indexOf("![")
        if (imageStart == -1) {
            return null
        }

        val start = content.indexOf("](", imageStart)
        if (start == -1) {
            return null
        }

        val end = content.indexOf(')', start + 2)
        if (end == -1) {
            return null
        }

        return content.substring(start + 2, end).takeIf { imageUrl ->
            imageUrl.startsWith("http", ignoreCase = true) ||
                imageUrl.startsWith("data:image", ignoreCase = true)
        }
    }
}
