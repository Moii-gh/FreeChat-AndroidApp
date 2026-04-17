package com.example.chatapp

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey
    val id: String,
    val ownerKey: String,
    val title: String,
    val timestamp: Long,
    val isPinned: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis(),
    val summary: String = ""
)
