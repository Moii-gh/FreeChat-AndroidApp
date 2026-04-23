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
  telegram_first_name,
  telegram_last_name,
  telegram_photo_url,
  auth_provider,
  plan_code,
  subscription_status,
  plan_expires_at,
  created_at
`;

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
    authProvider: row.auth_provider,
    planCode: row.plan_code ?? "free",
    subscriptionStatus: row.subscription_status ?? "inactive",
    planExpiresAt: row.plan_expires_at ?? null,
    isPro: Boolean(
      row.plan_code &&
        row.plan_code !== "free" &&
        row.plan_expires_at &&
        new Date(row.plan_expires_at).getTime() > Date.now()
    )
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

async function createTelegramWidgetUser({
  fullName,
  telegramUserId,
  telegramUsername,
  telegramFirstName,
  telegramLastName,
  telegramPhotoUrl
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
  { telegramUsername, telegramFirstName, telegramLastName, telegramPhotoUrl }
) {
  const result = await pool.query(
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

async function updatePlanState(userId, { planCode, subscriptionStatus, planExpiresAt }) {
  const result = await pool.query(
    `update users
     set plan_code = $2,
         subscription_status = $3,
         plan_expires_at = $4
     where id = $1
     returning ${USER_COLUMNS}`,
    [userId, planCode, subscriptionStatus, planExpiresAt]
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
  updateVerificationCode,
  verifyUser,
  attachTelegramIdentity,
  updatePassword,
  updatePlanState
};
