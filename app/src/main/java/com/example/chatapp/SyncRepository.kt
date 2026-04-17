package com.example.chatapp

import android.content.Context
import android.util.Log
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.network.dto.SyncChatDto
import com.example.chatapp.network.dto.SyncMessageDto
import com.example.chatapp.network.dto.SyncPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.chatDao()
    private val sessionStore = SharedPrefsAccountSessionStore(context)
    private val baseUrl = BuildConfig.APP_API_BASE_URL

    suspend fun trySync() = withContext(Dispatchers.IO) {
        val token = sessionStore.getAuthToken() ?: return@withContext
        val ownerKey = currentOwnerKey() ?: return@withContext

        try {
            val syncApi = NetworkModule.createSyncApiService(baseUrl, token)

            // Gather local state
            val localChats = dao.getAllChatsSync(ownerKey)
            val chatsPayload = localChats.map {
                SyncChatDto(
                    id = it.id,
                    title = it.title,
                    timestamp = it.timestamp,
                    isPinned = it.isPinned,
                    lastUpdated = it.lastUpdated,
                    summary = it.summary,
                    isDeleted = false
                )
            }

            val messagesPayload = mutableListOf<SyncMessageDto>()
            for (chat in localChats) {
                val msgs = dao.getMessagesByChatIdSync(chat.id)
                messagesPayload.addAll(msgs.map {
                    SyncMessageDto(
                        syncId = it.syncId,
                        chatId = it.chatId,
                        role = it.role,
                        content = it.content,
                        timestamp = it.timestamp,
                        imageUrl = it.imageUrl
                    )
                })
            }

            val request = SyncPayload(chatsPayload, messagesPayload)
            val response = syncApi.syncData(request)

            if (response.isSuccessful && response.body() != null) {
                val remoteData = response.body()!!

                // Upsert remote data to local DB
                for (remoteChat in remoteData.chats) {
                    val localChat = dao.getChatById(remoteChat.id, ownerKey)
                    if (localChat == null) {
                        dao.insertChat(
                            ChatEntity(
                                id = remoteChat.id,
                                ownerKey = ownerKey,
                                title = remoteChat.title,
                                timestamp = remoteChat.timestamp,
                                isPinned = remoteChat.isPinned,
                                lastUpdated = remoteChat.lastUpdated,
                                summary = remoteChat.summary
                            )
                        )
                    } else {
                        // Resolve conflict: if remote is newer or same
                        if (remoteChat.lastUpdated >= localChat.lastUpdated) {
                            dao.updateChat(
                                localChat.copy(
                                    title = remoteChat.title,
                                    isPinned = remoteChat.isPinned,
                                    lastUpdated = remoteChat.lastUpdated,
                                    summary = remoteChat.summary
                                )
                            )
                        }
                    }
                }

                // Upsert messages
                for (remoteMsg in remoteData.messages) {
                    val localMsg = dao.getMessageBySyncId(remoteMsg.syncId)
                    if (localMsg == null) {
                        dao.insertMessage(
                            MessageEntity(
                                chatId = remoteMsg.chatId,
                                role = remoteMsg.role,
                                content = remoteMsg.content,
                                timestamp = remoteMsg.timestamp,
                                imageUrl = remoteMsg.imageUrl,
                                syncId = remoteMsg.syncId
                            )
                        )
                    }
                }
            } else {
                Log.e("SyncRepository", "Sync failed: ${response.code()}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun currentOwnerKey(): String? {
        return sessionStore.getCurrentUserId()?.takeIf { it.isNotBlank() }?.let { "user_$it" }
            ?: sessionStore.getCurrentUserEmail()?.takeIf { it.isNotBlank() }?.let {
                "email_${it.lowercase().replace(Regex("[^a-z0-9]"), "_")}"
            }
    }
}
