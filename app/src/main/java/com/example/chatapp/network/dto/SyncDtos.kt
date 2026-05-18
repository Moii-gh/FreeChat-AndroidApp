package com.example.chatapp.network.dto

import androidx.annotation.Keep

@Keep
data class SyncChatDto(
    val id: String,
    val title: String,
    val timestamp: Long,
    val isPinned: Boolean,
    val lastUpdated: Long,
    val summary: String,
    val isDeleted: Boolean = false,
    val isTitleManuallyEdited: Boolean = false
)

@Keep
data class SyncMessageDto(
    val syncId: String,
    val chatId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val imageUrl: String?,
    val attachmentData: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentFileName: String? = null,
    val attachmentContext: String? = null,
    val updatedAt: Long = timestamp,
    val isDeleted: Boolean = false,
    val editRevision: Int = 0
)

@Keep
data class SyncPayload(
    val chats: List<SyncChatDto> = emptyList(),
    val messages: List<SyncMessageDto> = emptyList()
)
