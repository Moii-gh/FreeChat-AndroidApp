package com.example.chatapp

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatEntity)

    @Update
    suspend fun updateChat(chat: ChatEntity)

    @Query("UPDATE chats SET title = :title WHERE id = :chatId")
    suspend fun updateChatTitle(chatId: String, title: String)

    @Query("UPDATE chats SET lastUpdated = :timestamp WHERE id = :chatId")
    suspend fun updateChatLastUpdated(chatId: String, timestamp: Long)

    @Query("UPDATE chats SET summary = :summary WHERE id = :chatId")
    suspend fun updateChatSummary(chatId: String, summary: String)

    @Query("UPDATE chats SET isDeleted = 1, lastUpdated = :timestamp WHERE id = :chatId")
    suspend fun markChatDeleted(chatId: String, timestamp: Long)

    @Query("UPDATE chats SET isPinned = :pinned WHERE id = :chatId")
    suspend fun updateChatPinned(chatId: String, pinned: Boolean)

    @Query("SELECT * FROM chats WHERE ownerKey = :ownerKey AND isDeleted = 0 ORDER BY lastUpdated DESC")
    suspend fun getAllChatsSync(ownerKey: String): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE ownerKey = :ownerKey ORDER BY lastUpdated DESC")
    suspend fun getAllChatsForSync(ownerKey: String): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :chatId AND ownerKey = :ownerKey AND isDeleted = 0")
    suspend fun getChatById(chatId: String, ownerKey: String): ChatEntity?

    @Query("SELECT * FROM chats WHERE id = :chatId AND ownerKey = :ownerKey")
    suspend fun getChatByIdForSync(chatId: String, ownerKey: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesByChatId(chatId: String)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    suspend fun getMessagesByChatIdSync(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE syncId = :syncId")
    suspend fun getMessageBySyncId(syncId: String): MessageEntity?

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id IN (SELECT id FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC LIMIT -1 OFFSET :fromIndex)")
    suspend fun deleteMessagesFromIndex(chatId: String, fromIndex: Int)
}
