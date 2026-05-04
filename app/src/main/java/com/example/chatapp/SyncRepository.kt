package com.example.chatapp

import android.content.Context
import androidx.room.withTransaction
import com.example.chatapp.data.SharedPrefsAccountSessionStore
import com.example.chatapp.network.NetworkModule
import com.example.chatapp.network.dto.SyncChatDto
import com.example.chatapp.network.dto.SyncMessageDto
import com.example.chatapp.network.dto.SyncPayload
import com.example.chatapp.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SyncRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.chatDao()
    private val sessionStore = SharedPrefsAccountSessionStore(context)
    private val baseUrl = BuildConfig.APP_API_BASE_URL

    suspend fun trySync() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            trySyncLocked()
        }
    }

    private suspend fun trySyncLocked() {
        val token = sessionStore.getAuthToken() ?: return
        val ownerKey = currentOwnerKey() ?: return

        try {
            val syncApi = NetworkModule.createSyncApiService(baseUrl, token)

            // Собираем локальное состояние.
            val localChats = dao.getAllChatsForSync(ownerKey)
            val chatsPayload = localChats.map {
                SyncChatDto(
                    id = it.id,
                    title = it.title,
                    timestamp = it.timestamp,
                    isPinned = it.isPinned,
                    lastUpdated = it.lastUpdated,
                    summary = it.summary,
                    isDeleted = it.isDeleted
                )
            }

            val messagesPayload = mutableListOf<SyncMessageDto>()
            val localRevisionSnapshot = mutableMapOf<String, Int>()
            for (chat in localChats) {
                if (chat.isDeleted) {
                    continue
                }
                val msgs = dao.getMessagesByChatIdForSync(chat.id)
                messagesPayload.addAll(msgs.map {
                    localRevisionSnapshot[it.syncId] = it.editRevision
                    SyncMessageDto(
                        syncId = it.syncId,
                        chatId = it.chatId,
                        role = it.role,
                        content = it.content,
                        timestamp = it.timestamp,
                        imageUrl = it.imageUrl,
                        attachmentData = it.attachmentData,
                        attachmentMimeType = it.attachmentMimeType,
                        attachmentFileName = it.attachmentFileName,
                        attachmentContext = it.attachmentContext,
                        updatedAt = it.updatedAt,
                        isDeleted = it.isDeleted,
                        editRevision = it.editRevision
                    )
                })
            }

            val request = SyncPayload(chatsPayload, messagesPayload)
            val response = syncApi.syncData(request)

            if (response.isSuccessful && response.body() != null) {
                val remoteData = response.body()!!

                db.withTransaction {
                    // Сохраняем серверные чаты в локальную БД.
                    for (remoteChat in remoteData.chats) {
                        val localChat = dao.getChatByIdForSync(remoteChat.id, ownerKey)
                        if (localChat == null) {
                            dao.insertChat(
                                ChatEntity(
                                    id = remoteChat.id,
                                    ownerKey = ownerKey,
                                    title = remoteChat.title,
                                    timestamp = remoteChat.timestamp,
                                    isPinned = remoteChat.isPinned,
                                    lastUpdated = remoteChat.lastUpdated,
                                    summary = remoteChat.summary,
                                    isDeleted = remoteChat.isDeleted
                                )
                            )
                        } else {
                            // При равной версии доверяем серверу как каноничному состоянию.
                            if (remoteChat.lastUpdated >= localChat.lastUpdated) {
                                dao.updateChat(
                                    localChat.copy(
                                        title = remoteChat.title,
                                        timestamp = remoteChat.timestamp,
                                        isPinned = remoteChat.isPinned,
                                        lastUpdated = remoteChat.lastUpdated,
                                        summary = remoteChat.summary,
                                        isDeleted = remoteChat.isDeleted
                                    )
                                )
                            }
                        }

                        if (remoteChat.isDeleted) {
                            dao.deleteMessagesByChatId(remoteChat.id)
                        }
                    }

                    // Сохраняем серверные сообщения.
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
                                    attachmentData = remoteMsg.attachmentData,
                                    attachmentMimeType = remoteMsg.attachmentMimeType,
                                    attachmentFileName = remoteMsg.attachmentFileName,
                                    attachmentContext = remoteMsg.attachmentContext,
                                    syncId = remoteMsg.syncId,
                                    updatedAt = remoteMsg.updatedAt,
                                    isDeleted = remoteMsg.isDeleted,
                                    editRevision = remoteMsg.editRevision
                                )
                            )
                        } else if (shouldAdoptCanonicalMessage(localMsg, remoteMsg, localRevisionSnapshot)) {
                            dao.updateMessage(
                                localMsg.copy(
                                    chatId = remoteMsg.chatId,
                                    role = remoteMsg.role,
                                    content = remoteMsg.content,
                                    timestamp = remoteMsg.timestamp,
                                    imageUrl = remoteMsg.imageUrl,
                                    attachmentData = remoteMsg.attachmentData,
                                    attachmentMimeType = remoteMsg.attachmentMimeType,
                                    attachmentFileName = remoteMsg.attachmentFileName,
                                    attachmentContext = remoteMsg.attachmentContext,
                                    updatedAt = remoteMsg.updatedAt,
                                    isDeleted = remoteMsg.isDeleted,
                                    editRevision = remoteMsg.editRevision
                                )
                            )
                        }
                    }
                }
            } else {
                SafeLog.w("SyncRepository", "Sync failed: HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            SafeLog.w("SyncRepository", "Sync failed with exception", e)
        }
    }

    private fun currentOwnerKey(): String? {
        return sessionStore.getCurrentUserId()?.takeIf { it.isNotBlank() }?.let { "user_$it" }
            ?: sessionStore.getCurrentUserEmail()?.takeIf { it.isNotBlank() }?.let {
                "email_${it.lowercase().replace(Regex("[^a-z0-9]"), "_")}"
            }
    }

    private fun shouldAdoptCanonicalMessage(
        local: MessageEntity,
        remote: SyncMessageDto,
        localRevisionSnapshot: Map<String, Int>
    ): Boolean {
        return MessageSyncConflictResolver.shouldAdoptCanonical(
            localEditRevision = local.editRevision,
            localUpdatedAt = local.updatedAt,
            remoteEditRevision = remote.editRevision,
            remoteUpdatedAt = remote.updatedAt,
            snapshotEditRevision = localRevisionSnapshot[remote.syncId]
        )
    }

    companion object {
        private val syncMutex = Mutex()
    }
}
