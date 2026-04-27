const { pool } = require("../config/db");

const USER_COLUMNS = `
  id,
  email,
  password_hash,
  full_name,
  birth_date,
  is_verified,
  verification_code_hash,
  verification_code_expires_at,
  verification_code_sent_at,
  verification_attempt_count,
  telegram_user_id,
  telegram_chat_id,
  telegram_username,
  telegram_first_name,
  telegram_last_name,
  telegram_photo_url,
  auth_provider,
  bonus_requests,
  token_invalid_before,
  avatar_file_id,
  created_at
`;

function getExecutor(executor) {
  return executor || pool;
}

function toPublicUser(row) {
  return {
    id: row.id,
    email: row.email ?? null,
    fullName: row.full_name,
    birthDate: row.birth_date,
    isVerified: row.is_verified,
    telegramId: row.telegram_user_id ? String(row.telegram_user_id) : null,
    telegramUsername: row.telegram_username ?? null,
    telegramFirstName: row.telegram_first_name ?? null,
    telegramLastName: row.telegram_last_name ?? null,
    telegramPhotoUrl: row.telegram_photo_url ?? null,
    avatarFileId: row.avatar_file_id ?? null,
    avatarUrl: row.avatar_url ?? null,
    avatarThumbUrl: row.avatar_thumb_url ?? null,
    authProvider: row.auth_provider,
    bonusRequests: row.bonus_requests ?? 0
  };
}

async function findByEmail(email, executor) {
  if (!email) {
    return null;
  }

  const result = await getExecutor(executor).query(
    `select u.*, f.url as avatar_url, f.thumb_url as avatar_thumb_url
     from users u
     left join files f on u.avatar_file_id = f.id
     where u.email = $1`,
    [email.toLowerCase()]
  );

  return result.rows[0] || null;
}

async function findById(userId, executor) {
  const result = await getExecutor(executor).query(
    `select u.*, f.url as avatar_url, f.thumb_url as avatar_thumb_url
     from users u
     left join files f on u.avatar_file_id = f.id
     where u.id = $1`,
    [userId]
  );

  return result.rows[0] || null;
}

async function findByTelegramUserId(telegramUserId, executor) {
  if (!telegramUserId) {
    return null;
  }

  const result = await getExecutor(executor).query(
    `select u.*, f.url as avatar_url, f.thumb_url as avatar_thumb_url
     from users u
     left join files f on u.avatar_file_id = f.id
     where u.telegram_user_id = $1`,
    [String(telegramUserId)]
  );

  return result.rows[0] || null;
}

async function createUser(
  { email, passwordHash, fullName, birthDate, verificationCodeHash, verificationCodeExpiresAt, verificationCodeSentAt },
  executor
) {
  const result = await getExecutor(executor).query(
    `insert into users (
       email,
       password_hash,
       full_name,
       birth_date,
       is_verified,
       verification_code_hash,
       verification_code_expires_at,
       verification_code_sent_at,
       verification_attempt_count
     )
     values ($1, $2, $3, $4, false, $5, $6, $7, 0)
     returning ${USER_COLUMNS}`,
    [
      email.toLowerCase(),
      passwordHash,
      fullName,
      birthDate,
      verificationCodeHash,
      verificationCodeExpiresAt,
      verificationCodeSentAt
    ]
  );

  return result.rows[0];
}

async function createTelegramUser(
  {
    passwordHash,
    fullName,
    birthDate,
    telegramUserId,
    telegramChatId,
    telegramUsername
  },
  executor
) {
  const result = await getExecutor(executor).query(
    `insert into users (
      email,
      password_hash,
      full_name,
      birth_date,
      is_verified,
      verification_code_hash,
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

async function createTelegramWidgetUser(
  {
    fullName,
    telegramUserId,
    telegramUsername,
    telegramFirstName,
    telegramLastName,
    telegramPhotoUrl
  },
  executor
) {
  const result = await getExecutor(executor).query(
    `insert into users (
      email,
      password_hash,
      full_name,
      birth_date,
      is_verified,
      verification_code_hash,
      telegram_user_id,
      telegram_chat_id,
      telegram_username,
      telegram_first_name,
      telegram_last_name,
      telegram_photo_url,
      auth_provider
    )
    values (null, null, $1, null, true, null, $2, null, $3, $4, $5, $6, 'telegram')
    returning ${USER_COLUMNS}`,
    [
      fullName,
      String(telegramUserId),
      telegramUsername || null,
      telegramFirstName || null,
      telegramLastName || null,
      telegramPhotoUrl || null
    ]
  );

  return result.rows[0];
}

async function updateTelegramWidgetProfile(
  userId,
  { telegramUsername, telegramFirstName, telegramLastName, telegramPhotoUrl },
  executor
) {
  const result = await getExecutor(executor).query(
    `update users
     set telegram_username = $2,
         telegram_first_name = $3,
         telegram_last_name = $4,
         telegram_photo_url = $5,
         auth_provider = 'telegram',
         is_verified = true
     where id = $1
     returning ${USER_COLUMNS}`,
    [
      userId,
      telegramUsername || null,
      telegramFirstName || null,
      telegramLastName || null,
      telegramPhotoUrl || null
    ]
  );

  return result.rows[0];
}

async function updateUnverifiedUser(
  userId,
  {
    passwordHash,
    fullName,
    birthDate,
    verificationCodeHash,
    verificationCodeExpiresAt,
    verificationCodeSentAt
  },
  executor
) {
  const result = await getExecutor(executor).query(
    `update users
     set password_hash = $2,
         full_name = $3,
         birth_date = $4,
         verification_code_hash = $5,
         verification_code_expires_at = $6,
         verification_code_sent_at = $7,
         verification_attempt_count = 0,
         is_verified = false
     where id = $1
     returning ${USER_COLUMNS}`,
    [
      userId,
      passwordHash,
      fullName,
      birthDate,
      verificationCodeHash,
      verificationCodeExpiresAt,
      verificationCodeSentAt
    ]
  );

  return result.rows[0];
}

async function updateVerificationChallenge(
  userId,
  { verificationCodeHash, verificationCodeExpiresAt, verificationCodeSentAt },
  executor
) {
  const result = await getExecutor(executor).query(
    `update users
     set verification_code_hash = $2,
         verification_code_expires_at = $3,
         verification_code_sent_at = $4,
         verification_attempt_count = 0
     where id = $1
     returning ${USER_COLUMNS}`,
    [userId, verificationCodeHash, verificationCodeExpiresAt, verificationCodeSentAt]
  );

  return result.rows[0];
}

async function incrementVerificationAttempts(userId, executor) {
  const result = await getExecutor(executor).query(
    `update users
     set verification_attempt_count = verification_attempt_count + 1
     where id = $1
     returning ${USER_COLUMNS}`,
    [userId]
  );

  return result.rows[0];
}

async function verifyUser(userId, executor) {
  const result = await getExecutor(executor).query(
    `update users
     set is_verified = true,
         verification_code_hash = null,
         verification_code_expires_at = null,
         verification_code_sent_at = null,
         verification_attempt_count = 0
     where id = $1
     returning ${USER_COLUMNS}`,
    [userId]
  );

  return result.rows[0];
}

async function attachTelegramIdentity(
  userId,
  { telegramUserId, telegramChatId, telegramUsername, authProvider = "telegram" },
  executor
) {
  const result = await getExecutor(executor).query(
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

async function updatePassword(userId, passwordHash, executor) {
  const result = await getExecutor(executor).query(
    `update users
     set password_hash = $2,
         token_invalid_before = now()
     where id = $1
     returning ${USER_COLUMNS}`,
    [userId, passwordHash]
  );

  return result.rows[0];
}

async function addBonusRequests(userId, amount, executor) {
  const result = await getExecutor(executor).query(
    `update users
     set bonus_requests = bonus_requests + $2
     where id = $1
     returning ${USER_COLUMNS}`,
    [userId, amount]
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
  createTelegramWidgetUser,
  updateTelegramWidgetProfile,
  updateUnverifiedUser,
  updateVerificationChallenge,
  incrementVerificationAttempts,
  verifyUser,
  attachTelegramIdentity,
  updatePassword,
  addBonusRequests
};
