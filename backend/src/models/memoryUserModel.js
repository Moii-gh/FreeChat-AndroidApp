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
    auth_provider: "email",
    plan_code: "free",
    subscription_status: "inactive",
    plan_expires_at: null,
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
    auth_provider: "telegram",
    plan_code: "free",
    subscription_status: "inactive",
    plan_expires_at: null,
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
    auth_provider: "telegram",
    plan_code: "free",
    subscription_status: "inactive",
    plan_expires_at: null,
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

async function updatePlanState(userId, { planCode, subscriptionStatus, planExpiresAt }) {
  const user = await findById(userId);
  if (!user) {
    return null;
  }

  user.plan_code = planCode;
  user.subscription_status = subscriptionStatus;
  user.plan_expires_at = planExpiresAt;
  return user;
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
  updatePlanState
};
