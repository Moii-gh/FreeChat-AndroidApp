const crypto = require("node:crypto");

const users = [];

function normalizeEmail(email) {
  return email ? email.toLowerCase() : null;
}

function nowIso() {
  return new Date().toISOString();
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
    vkId: row.vk_user_id ? String(row.vk_user_id) : null,
    vkFirstName: row.vk_first_name ?? null,
    vkLastName: row.vk_last_name ?? null,
    vkPhotoUrl: row.vk_photo_url ?? null,
    vkEmail: row.vk_email ?? null,
    authProvider: row.auth_provider
  };
}

async function findByEmail(email) {
  const normalized = normalizeEmail(email);
  return users.find((user) => user.email === normalized) || null;
}

async function findById(userId) {
  return users.find((user) => user.id === userId) || null;
}

async function findByTelegramUserId(telegramUserId) {
  if (!telegramUserId) {
    return null;
  }

  return users.find((user) => user.telegram_user_id === String(telegramUserId)) || null;
}

async function findByVkUserId(vkUserId) {
  if (!vkUserId) {
    return null;
  }

  return users.find((user) => user.vk_user_id === String(vkUserId)) || null;
}

async function createUser({
  email,
  passwordHash,
  fullName,
  birthDate,
  verificationCodeHash,
  verificationCodeExpiresAt,
  verificationCodeSentAt
}) {
  const user = {
    id: crypto.randomUUID(),
    email: normalizeEmail(email),
    password_hash: passwordHash,
    full_name: fullName,
    birth_date: birthDate,
    is_verified: false,
    verification_code_hash: verificationCodeHash || null,
    verification_code_expires_at: verificationCodeExpiresAt || null,
    verification_code_sent_at: verificationCodeSentAt || null,
    verification_attempt_count: 0,
    telegram_user_id: null,
    telegram_chat_id: null,
    telegram_username: null,
    telegram_first_name: null,
    telegram_last_name: null,
    telegram_photo_url: null,
    vk_user_id: null,
    vk_first_name: null,
    vk_last_name: null,
    vk_photo_url: null,
    vk_email: null,
    auth_provider: "email",
    token_invalid_before: null,
    created_at: nowIso()
  };
  users.push(user);
  return user;
}

async function createTelegramUser({
  passwordHash,
  fullName,
  birthDate,
  telegramUserId,
  telegramChatId,
  telegramUsername
}) {
  const user = {
    id: crypto.randomUUID(),
    email: null,
    password_hash: passwordHash,
    full_name: fullName,
    birth_date: birthDate,
    is_verified: true,
    verification_code_hash: null,
    verification_code_expires_at: null,
    verification_code_sent_at: null,
    verification_attempt_count: 0,
    telegram_user_id: String(telegramUserId),
    telegram_chat_id: String(telegramChatId),
    telegram_username: telegramUsername || null,
    telegram_first_name: null,
    telegram_last_name: null,
    telegram_photo_url: null,
    vk_user_id: null,
    vk_first_name: null,
    vk_last_name: null,
    vk_photo_url: null,
    vk_email: null,
    auth_provider: "telegram",
    token_invalid_before: null,
    created_at: nowIso()
  };
  users.push(user);
  return user;
}

async function createTelegramWidgetUser({
  fullName,
  telegramUserId,
  telegramUsername,
  telegramFirstName,
  telegramLastName,
  telegramPhotoUrl
}) {
  const user = {
    id: crypto.randomUUID(),
    email: null,
    password_hash: null,
    full_name: fullName,
    birth_date: null,
    is_verified: true,
    verification_code_hash: null,
    verification_code_expires_at: null,
    verification_code_sent_at: null,
    verification_attempt_count: 0,
    telegram_user_id: String(telegramUserId),
    telegram_chat_id: null,
    telegram_username: telegramUsername || null,
    telegram_first_name: telegramFirstName || null,
    telegram_last_name: telegramLastName || null,
    telegram_photo_url: telegramPhotoUrl || null,
    vk_user_id: null,
    vk_first_name: null,
    vk_last_name: null,
    vk_photo_url: null,
    vk_email: null,
    auth_provider: "telegram",
    token_invalid_before: null,
    created_at: nowIso()
  };
  users.push(user);
  return user;
}

async function createVkUser({
  fullName,
  vkUserId,
  vkFirstName,
  vkLastName,
  vkPhotoUrl,
  vkEmail
}) {
  const user = {
    id: crypto.randomUUID(),
    email: null,
    password_hash: null,
    full_name: fullName,
    birth_date: null,
    is_verified: true,
    verification_code_hash: null,
    verification_code_expires_at: null,
    verification_code_sent_at: null,
    verification_attempt_count: 0,
    telegram_user_id: null,
    telegram_chat_id: null,
    telegram_username: null,
    telegram_first_name: null,
    telegram_last_name: null,
    telegram_photo_url: null,
    vk_user_id: String(vkUserId),
    vk_first_name: vkFirstName || null,
    vk_last_name: vkLastName || null,
    vk_photo_url: vkPhotoUrl || null,
    vk_email: vkEmail || null,
    auth_provider: "vk",
    token_invalid_before: null,
    created_at: nowIso()
  };
  users.push(user);
  return user;
}

async function updateTelegramWidgetProfile(
  userId,
  { telegramUsername, telegramFirstName, telegramLastName, telegramPhotoUrl }
) {
  const user = await findById(userId);
  if (!user) {
    return null;
  }

  user.telegram_username = telegramUsername || null;
  user.telegram_first_name = telegramFirstName || null;
  user.telegram_last_name = telegramLastName || null;
  user.telegram_photo_url = telegramPhotoUrl || null;
  user.auth_provider = "telegram";
  user.is_verified = true;
  return user;
}

async function updateVkProfile(userId, { vkFirstName, vkLastName, vkPhotoUrl, vkEmail }) {
  const user = await findById(userId);
  if (!user) {
    return null;
  }

  user.vk_first_name = vkFirstName || null;
  user.vk_last_name = vkLastName || null;
  user.vk_photo_url = vkPhotoUrl || null;
  user.vk_email = vkEmail || null;
  user.auth_provider = "vk";
  user.is_verified = true;
  return user;
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
  }
) {
  const user = await findById(userId);
  if (!user) {
    return null;
  }

  user.password_hash = passwordHash;
  user.full_name = fullName;
  user.birth_date = birthDate;
  user.verification_code_hash = verificationCodeHash || null;
  user.verification_code_expires_at = verificationCodeExpiresAt || null;
  user.verification_code_sent_at = verificationCodeSentAt || null;
  user.verification_attempt_count = 0;
  user.is_verified = false;
  return user;
}

async function updateVerificationChallenge(
  userId,
  { verificationCodeHash, verificationCodeExpiresAt, verificationCodeSentAt }
) {
  const user = await findById(userId);
  if (!user) {
    return null;
  }

  user.verification_code_hash = verificationCodeHash || null;
  user.verification_code_expires_at = verificationCodeExpiresAt || null;
  user.verification_code_sent_at = verificationCodeSentAt || null;
  user.verification_attempt_count = 0;
  return user;
}

async function incrementVerificationAttempts(userId) {
  const user = await findById(userId);
  if (!user) {
    return null;
  }

  user.verification_attempt_count = Number(user.verification_attempt_count || 0) + 1;
  return user;
}

async function verifyUser(userId) {
  const user = await findById(userId);
  if (!user) {
    return null;
  }

  user.is_verified = true;
  user.verification_code_hash = null;
  user.verification_code_expires_at = null;
  user.verification_code_sent_at = null;
  user.verification_attempt_count = 0;
  return user;
}

async function attachTelegramIdentity(
  userId,
  { telegramUserId, telegramChatId, telegramUsername, authProvider = "telegram" }
) {
  const user = await findById(userId);
  if (!user) {
    return null;
  }

  user.telegram_user_id = String(telegramUserId);
  user.telegram_chat_id = String(telegramChatId);
  user.telegram_username = telegramUsername || null;
  user.vk_user_id = user.vk_user_id || null;
  user.auth_provider = authProvider;
  user.is_verified = true;
  return user;
}

async function updatePassword(userId, passwordHash) {
  const user = await findById(userId);
  if (!user) {
    return null;
  }

  user.password_hash = passwordHash;
  user.token_invalid_before = nowIso();
  return user;
}

module.exports = {
  toPublicUser,
  findById,
  findByEmail,
  findByTelegramUserId,
  findByVkUserId,
  createUser,
  createTelegramUser,
  createTelegramWidgetUser,
  createVkUser,
  updateTelegramWidgetProfile,
  updateVkProfile,
  updateUnverifiedUser,
  updateVerificationChallenge,
  incrementVerificationAttempts,
  verifyUser,
  attachTelegramIdentity,
  updatePassword
};
