package com.example.chatapp

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    // ──── Chats ────

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

    @Delete
    suspend fun deleteChat(chat: ChatEntity)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun deleteChatById(chatId: String)

    @Query("UPDATE chats SET isPinned = :pinned WHERE id = :chatId")
    suspend fun updateChatPinned(chatId: String, pinned: Boolean)

    @Query("SELECT * FROM chats WHERE ownerKey = :ownerKey ORDER BY lastUpdated DESC")
    fun getAllChats(ownerKey: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE ownerKey = :ownerKey ORDER BY lastUpdated DESC")
    suspend fun getAllChatsSync(ownerKey: String): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :chatId AND ownerKey = :ownerKey")
    suspend fun getChatById(chatId: String, ownerKey: String): ChatEntity?

    @Query("DELETE FROM chats WHERE ownerKey = :ownerKey")
    suspend fun deleteAllChats(ownerKey: String)

    // ──── Messages ────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteMessagesByChatId(chatId: String)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesByChatId(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    suspend fun getMessagesByChatIdSync(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE syncId = :syncId")
    suspend fun getMessageBySyncId(syncId: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun getMessageCount(chatId: String): Int

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM messages WHERE chatId IN (SELECT id FROM chats WHERE ownerKey = :ownerKey)")
    suspend fun deleteAllMessagesByOwnerKey(ownerKey: String)

    @Query("DELETE FROM messages WHERE id = (SELECT id FROM messages WHERE chatId = :chatId AND role = 'assistant' ORDER BY timestamp DESC LIMIT 1)")
    suspend fun deleteLastAssistantMessage(chatId: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id IN (SELECT id FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC LIMIT -1 OFFSET :fromIndex)")
    suspend fun deleteMessagesFromIndex(chatId: String, fromIndex: Int)

    // ──── Transactions ────

    @Transaction
    suspend fun deleteChatWithMessages(chatId: String) {
        deleteMessagesByChatId(chatId)
        deleteChatById(chatId)
    }

    @Transaction
    suspend fun deleteEverything(ownerKey: String) {
        deleteAllMessagesByOwnerKey(ownerKey)
        deleteAllChats(ownerKey)
    }
}
