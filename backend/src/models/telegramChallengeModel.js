const { pool } = require("../config/db");

const CHALLENGE_COLUMNS = `
  id,
  start_token,
  purpose,
  code,
  user_id,
  telegram_user_id,
  telegram_chat_id,
  telegram_username,
  verified_at,
  expires_at,
  consumed_at,
  created_at
`;

async function createChallenge({ purpose, startToken, userId = null, expiresAt }) {
  const result = await pool.query(
    `insert into telegram_auth_challenges (start_token, purpose, user_id, expires_at)
     values ($1, $2, $3, $4)
     returning ${CHALLENGE_COLUMNS}`,
    [startToken, purpose, userId, expiresAt]
  );

  return result.rows[0];
}

async function findById(challengeId) {
  const result = await pool.query(
    `select ${CHALLENGE_COLUMNS}
     from telegram_auth_challenges
     where id = $1`,
    [challengeId]
  );

  return result.rows[0] || null;
}

async function findByStartToken(startToken) {
  const result = await pool.query(
    `select ${CHALLENGE_COLUMNS}
     from telegram_auth_challenges
     where start_token = $1`,
    [startToken]
  );

  return result.rows[0] || null;
}

async function assignTelegramIdentityAndCode(
  challengeId,
  { telegramUserId, telegramChatId, telegramUsername, code }
) {
  const result = await pool.query(
    `update telegram_auth_challenges
     set telegram_user_id = $2,
         telegram_chat_id = $3,
         telegram_username = $4,
         code = $5,
         verified_at = null
     where id = $1
     returning ${CHALLENGE_COLUMNS}`,
    [
      challengeId,
      String(telegramUserId),
      String(telegramChatId),
      telegramUsername || null,
      code
    ]
  );

  return result.rows[0] || null;
}

async function markVerified(challengeId) {
  const result = await pool.query(
    `update telegram_auth_challenges
     set verified_at = now()
     where id = $1
     returning ${CHALLENGE_COLUMNS}`,
    [challengeId]
  );

  return result.rows[0] || null;
}

async function attachUser(challengeId, userId) {
  const result = await pool.query(
    `update telegram_auth_challenges
     set user_id = $2
     where id = $1
     returning ${CHALLENGE_COLUMNS}`,
    [challengeId, userId]
  );

  return result.rows[0] || null;
}

async function consumeChallenge(challengeId) {
  const result = await pool.query(
    `update telegram_auth_challenges
     set consumed_at = now()
     where id = $1
     returning ${CHALLENGE_COLUMNS}`,
    [challengeId]
  );

  return result.rows[0] || null;
}

module.exports = {
  createChallenge,
  findById,
  findByStartToken,
  assignTelegramIdentityAndCode,
  markVerified,
  attachUser,
  consumeChallenge
};
