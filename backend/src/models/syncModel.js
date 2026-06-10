const { pool } = require("../config/db");

function getExecutor(executor) {
  return executor || pool;
}

async function upsertChats(userId, chats, executor) {
  if (!chats || chats.length === 0) return;
  const db = getExecutor(executor);
  const query = `
    INSERT INTO chats (
      id,
      user_id,
      title,
      timestamp_ms,
      is_pinned,
      last_updated_ms,
      summary,
      is_deleted,
      is_title_manually_edited
    )
    VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
    ON CONFLICT (id) DO UPDATE SET
      title = EXCLUDED.title,
      timestamp_ms = EXCLUDED.timestamp_ms,
      is_pinned = EXCLUDED.is_pinned,
      last_updated_ms = EXCLUDED.last_updated_ms,
      summary = EXCLUDED.summary,
      is_deleted = EXCLUDED.is_deleted,
      is_title_manually_edited = EXCLUDED.is_title_manually_edited
    WHERE chats.user_id = EXCLUDED.user_id
      AND chats.last_updated_ms <= EXCLUDED.last_updated_ms
  `;

  const promises = chats.map(chat =>
    db.query(query, [
      chat.id,
      userId,
      chat.title,
      chat.timestamp,
      chat.isPinned,
      chat.lastUpdated,
      chat.summary,
      chat.isDeleted || false,
      Boolean(chat.isTitleManuallyEdited)
    ])
  );
  await Promise.all(promises);
}

async function upsertMessages(userId, messages, executor) {
  if (!messages || messages.length === 0) return;
  const db = getExecutor(executor);
  const query = `
    INSERT INTO messages (
      id,
      chat_id,
      role,
      content,
      timestamp_ms,
      image_url,
      attachment_data,
      attachment_mime_type,
      attachment_file_name,
      attachment_context,
      updated_at_ms,
      is_deleted,
      edit_revision
    )
    SELECT $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13
    WHERE EXISTS (
      SELECT 1
      FROM chats
      WHERE id = $2
        AND user_id = $14
        AND is_deleted = false
    )
    ON CONFLICT (id) DO UPDATE SET
      role = EXCLUDED.role,
      content = EXCLUDED.content,
      timestamp_ms = EXCLUDED.timestamp_ms,
      image_url = EXCLUDED.image_url,
      attachment_data = EXCLUDED.attachment_data,
      attachment_mime_type = EXCLUDED.attachment_mime_type,
      attachment_file_name = EXCLUDED.attachment_file_name,
      attachment_context = EXCLUDED.attachment_context,
      updated_at_ms = EXCLUDED.updated_at_ms,
      is_deleted = EXCLUDED.is_deleted,
      edit_revision = EXCLUDED.edit_revision
    WHERE EXISTS (
      SELECT 1
      FROM chats
      WHERE id = messages.chat_id
        AND user_id = $14
        AND is_deleted = false
    )
      AND (
        EXCLUDED.edit_revision > messages.edit_revision
        OR (
          EXCLUDED.edit_revision = messages.edit_revision
          AND EXCLUDED.updated_at_ms >= messages.updated_at_ms
        )
      )
  `;

  const promises = messages.map(msg => {
    const isDeleted = Boolean(msg.isDeleted);
    const canonicalUpdatedAt = Date.now();
    return db.query(query, [
      msg.syncId,
      msg.chatId,
      msg.role,
      isDeleted ? "" : msg.content,
      msg.timestamp,
      isDeleted ? null : msg.imageUrl || null,
      isDeleted ? null : msg.attachmentData || null,
      isDeleted ? null : msg.attachmentMimeType || null,
      isDeleted ? null : msg.attachmentFileName || null,
      isDeleted ? null : msg.attachmentContext || null,
      canonicalUpdatedAt,
      isDeleted,
      msg.editRevision || 0,
      userId
    ]);
  });
  await Promise.all(promises);
}

async function getUserChats(userId, sinceLastUpdatedMs = 0, executor) {
  if (sinceLastUpdatedMs && (typeof sinceLastUpdatedMs === "object" || typeof sinceLastUpdatedMs.query === "function")) {
    executor = sinceLastUpdatedMs;
    sinceLastUpdatedMs = 0;
  }
  const db = getExecutor(executor);
  const result = await db.query(
    `SELECT
        id,
        title,
        timestamp_ms as timestamp,
        is_pinned as "isPinned",
        last_updated_ms as "lastUpdated",
        summary,
        is_deleted as "isDeleted",
        is_title_manually_edited as "isTitleManuallyEdited"
     FROM chats
     WHERE user_id = $1 AND last_updated_ms > $2`,
    [userId, Number(sinceLastUpdatedMs) || 0]
  );
  return result.rows;
}

async function getUserMessages(userId, sinceLastUpdatedMs = 0, executor) {
  if (sinceLastUpdatedMs && (typeof sinceLastUpdatedMs === "object" || typeof sinceLastUpdatedMs.query === "function")) {
    executor = sinceLastUpdatedMs;
    sinceLastUpdatedMs = 0;
  }
  const db = getExecutor(executor);
  const result = await db.query(
    `SELECT
        m.id as "syncId",
        m.chat_id as "chatId",
        m.role,
        m.content,
        m.timestamp_ms as timestamp,
        m.image_url as "imageUrl",
        m.attachment_data as "attachmentData",
        m.attachment_mime_type as "attachmentMimeType",
        m.attachment_file_name as "attachmentFileName",
        m.attachment_context as "attachmentContext",
        m.updated_at_ms as "updatedAt",
        m.is_deleted as "isDeleted",
        m.edit_revision as "editRevision"
     FROM messages m 
     JOIN chats c ON m.chat_id = c.id 
     WHERE c.user_id = $1 AND c.is_deleted = false AND m.updated_at_ms > $2
     ORDER BY m.timestamp_ms ASC, m.created_at ASC`,
    [userId, Number(sinceLastUpdatedMs) || 0]
  );
  return result.rows;
}

module.exports = {
  upsertChats,
  upsertMessages,
  getUserChats,
  getUserMessages
};
