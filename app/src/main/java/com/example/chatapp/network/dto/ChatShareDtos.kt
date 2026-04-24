package com.example.chatapp.network.dto

import androidx.annotation.Keep

@Keep
data class ChatShareMessageDto(
    val role: String,
    val content: String,
    val timestamp: Long,
    val imageUrl: String? = null
)

@Keep
data class CreateChatShareRequest(
    val sourceChatId: String,
    val title: String,
    val summary: String,
    val messages: List<ChatShareMessageDto>,
    val expiresInDays: Int = 30
)

@Keep
data class CreateChatShareResponse(
    val token: String,
    val shareUrl: String,
    val expiresAt: String
)

@Keep
data class ChatShareSnapshotResponse(
    val token: String,
    val title: String,
    val summary: String,
    val messages: List<ChatShareMessageDto>,
    val createdAt: String?,
    val expiresAt: String
)

@Keep
data class ChatShareItemDto(
    val token: String,
    val title: String,
    val summary: String,
    val createdAt: String?,
    val expiresAt: String
)

@Keep
data class RevokeChatShareResponse(
    val revoked: Boolean,
    val revokedCount: Int
)
