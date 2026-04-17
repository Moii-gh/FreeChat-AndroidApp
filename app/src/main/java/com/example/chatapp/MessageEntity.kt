package com.example.chatapp

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ChatEntity::class,
        parentColumns = ["id"],
        childColumns = ["chatId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("chatId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val chatId: String,
    val role: String,        // "user", "assistant", "system"
    val content: String,
    val timestamp: Long,
    val imageUrl: String? = null,
    val syncId: String = java.util.UUID.randomUUID().toString()
)