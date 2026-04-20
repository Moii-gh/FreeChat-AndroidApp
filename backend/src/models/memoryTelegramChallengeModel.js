const crypto = require("node:crypto");

const challenges = [];

function nowIso() {
  return new Date().toISOString();
}

async function createChallenge({ purpose, startToken, userId = null, expiresAt }) {
  const challenge = {
    id: crypto.randomUUID(),
    start_token: startToken,
    purpose,
    code: null,
    user_id: userId,
    telegram_user_id: null,
    telegram_chat_id: null,
    telegram_username: null,
    verified_at: null,
    expires_at: expiresAt,
    consumed_at: null,
    created_at: nowIso()
  };
  challenges.push(challenge);
  return challenge;
}

async function findById(challengeId) {
  return challenges.find((challenge) => challenge.id === challengeId) || null;
}

async function findByStartToken(startToken) {
  return challenges.find((challenge) => challenge.start_token === startToken) || null;
}

async function assignTelegramIdentityAndCode(
  challengeId,
  { telegramUserId, telegramChatId, telegramUsername, code }
) {
  const challenge = await findById(challengeId);
  if (!challenge) {
    return null;
  }

  challenge.telegram_user_id = String(telegramUserId);
  challenge.telegram_chat_id = String(telegramChatId);
  challenge.telegram_username = telegramUsername || null;
  challenge.code = code;
  challenge.verified_at = null;
  return challenge;
}

async function markVerified(challengeId) {
  const challenge = await findById(challengeId);
  if (!challenge) {
    return null;
  }

  challenge.verified_at = nowIso();
  return challenge;
}

async function attachUser(challengeId, userId) {
  const challenge = await findById(challengeId);
  if (!challenge) {
    return null;
  }

  challenge.user_id = userId;
  return challenge;
}

async function consumeChallenge(challengeId) {
  const challenge = await findById(challengeId);
  if (!challenge) {
    return null;
  }

  challenge.consumed_at = nowIso();
  return challenge;
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
