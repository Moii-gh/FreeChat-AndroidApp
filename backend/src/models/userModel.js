const { pool } = require("../config/db");

const USER_COLUMNS = `
  id,
  email,
  password_hash,
  full_name,
  birth_date,
  is_verified,
  verification_code,
  telegram_user_id,
  telegram_chat_id,
  telegram_username,
  auth_provider,
  created_at
`;

function toPublicUser(row) {
  return {
    id: row.id,
    email: row.email ?? null,
    fullName: row.full_name,
    birthDate: row.birth_date,
    isVerified: row.is_verified,
    telegramUsername: row.telegram_username ?? null,
    authProvider: row.auth_provider
  };
}

async function findByEmail(email) {
  if (!email) {
    return null;
  }

  const result = await pool.query(
    `select ${USER_COLUMNS}
     from users
     where email = $1`,
    [email.toLowerCase()]
  );

  return result.rows[0] || null;
}

async function findById(userId) {
  const result = await pool.query(
    `select ${USER_COLUMNS}
     from users
     where id = $1`,
    [userId]
  );

  return result.rows[0] || null;
}

async function findByTelegramUserId(telegramUserId) {
  if (!telegramUserId) {
    return null;
  }

  const result = await pool.query(
    `select ${USER_COLUMNS}
     from users
     where telegram_user_id = $1`,
    [String(telegramUserId)]
  );

  return result.rows[0] || null;
}

async function createUser({ email, passwordHash, fullName, birthDate, verificationCode }) {
  const result = await pool.query(
    `insert into users (email, password_hash, full_name, birth_date, is_verified, verification_code)
     values ($1, $2, $3, $4, false, $5)
     returning ${USER_COLUMNS}`,
    [email.toLowerCase(), passwordHash, fullName, birthDate, verificationCode]
  );

  return result.rows[0];
}

async function createTelegramUser({
  passwordHash,
  fullName,
  birthDate,
  telegramUserId,
  telegramChatId,
  telegramUsername
}) {
  const result = await pool.query(
    `insert into users (
      email,
      password_hash,
      full_name,
      birth_date,
      is_verified,
      verification_code,
      telegram_user_id,
      telegram_chat_id,
      telegram_username,
      auth_provider
    )
    values (null, $1, $2, $3, true, null, $4, $5, $6, 'telegram')
    returning ${USER_COLUMNS}`,
    [
      passwordHash,
      fullName,
      birthDate,
      String(telegramUserId),
      String(telegramChatId),
      telegramUsername || null
    ]
  );

  return result.rows[0];
}

async function updateUnverifiedUser(userId, { passwordHash, fullName, birthDate, verificationCode }) {
  const result = await pool.query(
    `update users
     set password_hash = $2,
         full_name = $3,
         birth_date = $4,
         verification_code = $5,
         is_verified = false
     where id = $1
     returning ${USER_COLUMNS}`,
    [userId, passwordHash, fullName, birthDate, verificationCode]
  );

  return result.rows[0];
}

async function updateVerificationCode(userId, verificationCode) {
  const result = await pool.query(
    `update users
     set verification_code = $2
     where id = $1
     returning ${USER_COLUMNS}`,
    [userId, verificationCode]
  );

  return result.rows[0];
}

async function verifyUser(userId) {
  const result = await pool.query(
    `update users
     set is_verified = true,
         verification_code = null
     where id = $1
     returning ${USER_COLUMNS}`,
    [userId]
  );

  return result.rows[0];
}

async function attachTelegramIdentity(
  userId,
  { telegramUserId, telegramChatId, telegramUsername, authProvider = "telegram" }
) {
  const result = await pool.query(
    `update users
     set telegram_user_id = $2,
         telegram_chat_id = $3,
         telegram_username = $4,
         auth_provider = $5,
         is_verified = true
     where id = $1
     returning ${USER_COLUMNS}`,
    [
      userId,
      String(telegramUserId),
      String(telegramChatId),
      telegramUsername || null,
      authProvider
    ]
  );

  return result.rows[0];
}

async function updatePassword(userId, passwordHash) {
  const result = await pool.query(
    `update users
     set password_hash = $2
     where id = $1
     returning ${USER_COLUMNS}`,
    [userId, passwordHash]
  );

  return result.rows[0];
}

module.exports = {
  toPublicUser,
  findById,
  findByEmail,
  findByTelegramUserId,
  createUser,
  createTelegramUser,
  updateUnverifiedUser,
  updateVerificationCode,
  verifyUser,
  attachTelegramIdentity,
  updatePassword
};
