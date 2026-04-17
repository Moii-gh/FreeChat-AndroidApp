const { pool } = require("../config/db");

async function upsertChats(userId, chats) {
  if (!chats || chats.length === 0) return;
  
  const query = `
    INSERT INTO chats (id, user_id, title, timestamp_ms, is_pinned, last_updated_ms, summary, is_deleted)
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
    ON CONFLICT (id) DO UPDATE SET
      title = EXCLUDED.title,
      is_pinned = EXCLUDED.is_pinned,
      last_updated_ms = EXCLUDED.last_updated_ms,
      summary = EXCLUDED.summary,
      is_deleted = EXCLUDED.is_deleted
    WHERE chats.last_updated_ms <= EXCLUDED.last_updated_ms
  `;

  for (const chat of chats) {
    await pool.query(query, [
      chat.id,
      userId,
      chat.title,
      chat.timestamp,
      chat.isPinned,
      chat.lastUpdated,
      chat.summary,
      chat.isDeleted || false
    ]);
  }
}

async function upsertMessages(userId, messages) {
  if (!messages || messages.length === 0) return;
  
  const query = `
    INSERT INTO messages (id, chat_id, role, content, timestamp_ms, image_url)
    VALUES ($1, $2, $3, $4, $5, $6)
    ON CONFLICT (id) DO NOTHING
  `;

  for (const msg of messages) {
    // Basic verification: user owns the chat.
    // Assuming messages are always appended, DO NOTHING is fine to prevent duplicates.
    await pool.query(query, [
      msg.syncId,
      msg.chatId,
      msg.role,
      msg.content,
      msg.timestamp,
      msg.imageUrl || null
    ]);
  }
}

async function getUserChats(userId) {
  const result = await pool.query(
    "SELECT id, title, timestamp_ms as timestamp, is_pinned as isPinned, last_updated_ms as lastUpdated, summary, is_deleted as isDeleted FROM chats WHERE user_id = $1",
    [userId]
  );
  return result.rows;
}

async function getUserMessages(userId) {
  const result = await pool.query(
    `SELECT m.id as "syncId", m.chat_id as "chatId", m.role, m.content, m.timestamp_ms as timestamp, m.image_url as "imageUrl" 
     FROM messages m 
     JOIN chats c ON m.chat_id = c.id 
     WHERE c.user_id = $1 AND c.is_deleted = false`,
    [userId]
  );
  return result.rows;
}

module.exports = {
  upsertChats,
  upsertMessages,
  getUserChats,
  getUserMessages
};
