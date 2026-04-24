const { pool } = require("../config/db");

function getExecutor(executor) {
  return executor || pool;
}

async function createShare(
  {
    ownerUserId,
    sourceChatId,
    tokenHash,
    title,
    summary,
    snapshot,
    expiresAt
  },
  executor
) {
  const result = await getExecutor(executor).query(
    `INSERT INTO chat_share_links
      (owner_user_id, source_chat_id, token_hash, title, summary, snapshot_json, expires_at)
     VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7)
     RETURNING id, created_at as "createdAt"`,
    [
      ownerUserId,
      sourceChatId,
      tokenHash,
      title,
      summary,
      JSON.stringify(snapshot),
      expiresAt
    ]
  );

  return result.rows[0] || null;
}

async function findActiveByTokenHash(tokenHash, executor) {
  const result = await getExecutor(executor).query(
    `SELECT
        id,
        owner_user_id as "ownerUserId",
        source_chat_id as "sourceChatId",
        title,
        summary,
        snapshot_json as snapshot,
        created_at as "createdAt",
        expires_at as "expiresAt"
     FROM chat_share_links
     WHERE token_hash = $1
       AND revoked_at IS NULL
       AND expires_at > now()
     LIMIT 1`,
    [tokenHash]
  );

  return result.rows[0] || null;
}

async function revokeByTokenHash(ownerUserId, tokenHash, executor) {
  const result = await getExecutor(executor).query(
    `UPDATE chat_share_links
     SET revoked_at = now()
     WHERE owner_user_id = $1
       AND token_hash = $2
       AND revoked_at IS NULL
     RETURNING id`,
    [ownerUserId, tokenHash]
  );

  return result.rowCount || 0;
}

async function revokeBySourceChat(ownerUserId, sourceChatId, executor) {
  const result = await getExecutor(executor).query(
    `UPDATE chat_share_links
     SET revoked_at = now()
     WHERE owner_user_id = $1
       AND source_chat_id = $2
       AND revoked_at IS NULL
     RETURNING id`,
    [ownerUserId, sourceChatId]
  );

  return result.rowCount || 0;
}

module.exports = {
  createShare,
  findActiveByTokenHash,
  revokeByTokenHash,
  revokeBySourceChat
};
