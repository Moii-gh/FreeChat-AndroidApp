const test = require("node:test");
const assert = require("node:assert/strict");
const bcrypt = require("bcrypt");
const request = require("supertest");
const { createApp } = require("../app");
const { createTelegramBotService } = require("../utils/telegramBotService");

function createFakeUserModel() {
  const users = new Map();
  let idCounter = 1;

  const normalizeEmail = (email) => email?.toLowerCase() || null;

  const toPublicUser = (user) => ({
    id: user.id,
    email: user.email ?? null,
    fullName: user.full_name,
    birthDate: user.birth_date,
    isVerified: user.is_verified,
    telegramUsername: user.telegram_username ?? null,
    authProvider: user.auth_provider
  });

  return {
    toPublicUser,
    async findById(userId) {
      return [...users.values()].find((item) => item.id === userId) || null;
    },
    async findByEmail(email) {
      return [...users.values()].find((item) => item.email === normalizeEmail(email)) || null;
    },
    async findByTelegramUserId(telegramUserId) {
      return [...users.values()].find((item) => item.telegram_user_id === String(telegramUserId)) || null;
    },
    async createTelegramUser({
      passwordHash,
      fullName,
      birthDate,
      telegramUserId,
      telegramChatId,
      telegramUsername
    }) {
      const user = {
        id: `user-${idCounter++}`,
        email: null,
        password_hash: passwordHash,
        full_name: fullName,
        birth_date: birthDate,
        is_verified: true,
        verification_code: null,
        telegram_user_id: String(telegramUserId),
        telegram_chat_id: String(telegramChatId),
        telegram_username: telegramUsername || null,
        auth_provider: "telegram",
        created_at: new Date().toISOString()
      };
      users.set(user.id, user);
      return user;
    },
    async attachTelegramIdentity(userId, { telegramUserId, telegramChatId, telegramUsername, authProvider }) {
      const user = users.get(userId);
      user.telegram_user_id = String(telegramUserId);
      user.telegram_chat_id = String(telegramChatId);
      user.telegram_username = telegramUsername || null;
      user.auth_provider = authProvider;
      user.is_verified = true;
      return user;
    },
    async updatePassword(userId, passwordHash) {
      const user = users.get(userId);
      user.password_hash = passwordHash;
      return user;
    },
    seed(user) {
      users.set(user.id, user);
    }
  };
}

function createFakeChallengeModel() {
  const challenges = new Map();
  let idCounter = 1;

  return {
    async createChallenge({ purpose, startToken, userId = null, expiresAt }) {
      const challengeId = `00000000-0000-4000-8000-${String(idCounter++).padStart(12, "0")}`;
      const challenge = {
        id: challengeId,
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
        created_at: new Date().toISOString()
      };
      challenges.set(challenge.id, challenge);
      return challenge;
    },
    async findById(challengeId) {
      return challenges.get(challengeId) || null;
    },
    async findByStartToken(startToken) {
      return [...challenges.values()].find((item) => item.start_token === startToken) || null;
    },
    async assignTelegramIdentityAndCode(challengeId, { telegramUserId, telegramChatId, telegramUsername, code }) {
      const challenge = challenges.get(challengeId);
      challenge.telegram_user_id = String(telegramUserId);
      challenge.telegram_chat_id = String(telegramChatId);
      challenge.telegram_username = telegramUsername || null;
      challenge.code = code;
      challenge.verified_at = null;
      return challenge;
    },
    async markVerified(challengeId) {
      const challenge = challenges.get(challengeId);
      challenge.verified_at = new Date().toISOString();
      return challenge;
    },
    async attachUser(challengeId, userId) {
      const challenge = challenges.get(challengeId);
      challenge.user_id = userId;
      return challenge;
    },
    async consumeChallenge(challengeId) {
      const challenge = challenges.get(challengeId);
      challenge.consumed_at = new Date().toISOString();
      return challenge;
    }
  };
}

function createFakeFetch() {
  const calls = [];

  const fetchImpl = async (_url, options) => {
    calls.push(JSON.parse(options.body));
    return {
      ok: true,
      async json() {
        return { ok: true, result: [] };
      },
      async text() {
        return "";
      }
    };
  };

  return { fetchImpl, calls };
}

test("POST /api/telegram-auth/begin-registration creates challenge and bot url", async () => {
  const userModel = createFakeUserModel();
  const challengeModel = createFakeChallengeModel();
  const app = createApp({
    userModel,
    telegramChallengeModel: challengeModel,
    telegramConfig: {
      isConfigured: true,
      botUsername: "freechat_test_bot"
    }
  });

  const response = await request(app)
    .post("/api/telegram-auth/begin-registration")
    .send({});

  assert.equal(response.status, 200);
  assert.match(response.body.challengeId, /^00000000-0000-4000-8000-/);
  assert.match(response.body.botUrl, /^https:\/\/t\.me\/freechat_test_bot\?start=/);
});

test("telegram bot /start generates code and links telegram identity", async () => {
  const userModel = createFakeUserModel();
  const challengeModel = createFakeChallengeModel();
  const challenge = await challengeModel.createChallenge({
    purpose: "register",
    startToken: "start-token-1",
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString()
  });
  const { fetchImpl, calls } = createFakeFetch();
  const botService = createTelegramBotService({
    userModel,
    challengeModel,
    botToken: "token",
    botUsername: "freechat_test_bot",
    fetchImpl
  });

  const result = await botService.handleStart({
    chatId: 1001,
    telegramUserId: 2002,
    username: "ada",
    startToken: "start-token-1"
  });
  const updatedChallenge = await challengeModel.findById(challenge.id);

  assert.equal(result.ok, true);
  assert.equal(updatedChallenge.telegram_user_id, "2002");
  assert.equal(updatedChallenge.telegram_chat_id, "1001");
  assert.match(updatedChallenge.code, /^\d{6}$/);
  assert.equal(calls.length, 1);
  assert.match(calls[0].text, /Код FreeChat:/);
});

test("POST /api/telegram-auth/verify-code rejects wrong code", async () => {
  const userModel = createFakeUserModel();
  const challengeModel = createFakeChallengeModel();
  const challenge = await challengeModel.createChallenge({
    purpose: "register",
    startToken: "start-token-2",
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString()
  });
  await challengeModel.assignTelegramIdentityAndCode(challenge.id, {
    telegramUserId: 2002,
    telegramChatId: 1001,
    telegramUsername: "ada",
    code: "123456"
  });
  const app = createApp({
    userModel,
    telegramChallengeModel: challengeModel,
    telegramConfig: {
      isConfigured: true,
      botUsername: "freechat_test_bot"
    }
  });

  const response = await request(app)
    .post("/api/telegram-auth/verify-code")
    .send({
      challengeId: challenge.id,
      code: "000000"
    });

  assert.equal(response.status, 400);
});

test("POST /api/telegram-auth/complete-registration creates telegram user", async () => {
  const userModel = createFakeUserModel();
  const challengeModel = createFakeChallengeModel();
  const challenge = await challengeModel.createChallenge({
    purpose: "register",
    startToken: "start-token-3",
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString()
  });
  await challengeModel.assignTelegramIdentityAndCode(challenge.id, {
    telegramUserId: 2002,
    telegramChatId: 1001,
    telegramUsername: "ada",
    code: "123456"
  });
  await challengeModel.markVerified(challenge.id);
  const app = createApp({
    userModel,
    telegramChallengeModel: challengeModel,
    telegramConfig: {
      isConfigured: true,
      botUsername: "freechat_test_bot"
    }
  });

  const response = await request(app)
    .post("/api/telegram-auth/complete-registration")
    .send({
      challengeId: challenge.id,
      fullName: "Ada Lovelace",
      birthDate: "1995-12-09",
      password: "123456"
    });

  assert.equal(response.status, 201);
  assert.equal(response.body.user.fullName, "Ada Lovelace");
  assert.equal(response.body.user.authProvider, "telegram");
});

test("telegram login works only for linked telegram account", async () => {
  const passwordHash = await bcrypt.hash("123456", 1);
  const userModel = createFakeUserModel();
  userModel.seed({
    id: "user-1",
    email: null,
    password_hash: passwordHash,
    full_name: "Ada Lovelace",
    birth_date: "1995-12-09",
    is_verified: true,
    verification_code: null,
    telegram_user_id: "2002",
    telegram_chat_id: "1001",
    telegram_username: "ada",
    auth_provider: "telegram",
    created_at: new Date().toISOString()
  });
  const challengeModel = createFakeChallengeModel();
  const challenge = await challengeModel.createChallenge({
    purpose: "login",
    startToken: "start-token-4",
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString()
  });
  await challengeModel.assignTelegramIdentityAndCode(challenge.id, {
    telegramUserId: 2002,
    telegramChatId: 1001,
    telegramUsername: "ada",
    code: "123456"
  });
  await challengeModel.markVerified(challenge.id);
  const app = createApp({
    userModel,
    telegramChallengeModel: challengeModel,
    telegramConfig: {
      isConfigured: true,
      botUsername: "freechat_test_bot"
    }
  });

  const response = await request(app)
    .post("/api/telegram-auth/complete-login")
    .send({
      challengeId: challenge.id,
      password: "123456"
    });

  assert.equal(response.status, 200);
  assert.equal(response.body.user.telegramUsername, "ada");
});

test("migration flow validates legacy user and completes binding", async () => {
  const passwordHash = await bcrypt.hash("123456", 1);
  const userModel = createFakeUserModel();
  userModel.seed({
    id: "legacy-1",
    email: "legacy@example.com",
    password_hash: passwordHash,
    full_name: "Legacy User",
    birth_date: "1990-01-01",
    is_verified: true,
    verification_code: null,
    telegram_user_id: null,
    telegram_chat_id: null,
    telegram_username: null,
    auth_provider: "legacy_email",
    created_at: new Date().toISOString()
  });
  const challengeModel = createFakeChallengeModel();
  const app = createApp({
    userModel,
    telegramChallengeModel: challengeModel,
    telegramConfig: {
      isConfigured: true,
      botUsername: "freechat_test_bot"
    }
  });

  const beginResponse = await request(app)
    .post("/api/telegram-auth/begin-migration")
    .send({
      email: "legacy@example.com",
      password: "123456"
    });

  await challengeModel.assignTelegramIdentityAndCode(beginResponse.body.challengeId, {
    telegramUserId: 2002,
    telegramChatId: 1001,
    telegramUsername: "legacy_user",
    code: "123456"
  });
  await challengeModel.markVerified(beginResponse.body.challengeId);

  const completeResponse = await request(app)
    .post("/api/telegram-auth/complete-migration")
    .send({
      challengeId: beginResponse.body.challengeId
    });

  const updatedUser = await userModel.findById("legacy-1");
  assert.equal(beginResponse.status, 200);
  assert.equal(completeResponse.status, 200);
  assert.equal(updatedUser.auth_provider, "telegram");
  assert.equal(updatedUser.telegram_user_id, "2002");
});

test("telegram already linked to another account is rejected by bot", async () => {
  const passwordHash = await bcrypt.hash("123456", 1);
  const userModel = createFakeUserModel();
  userModel.seed({
    id: "user-1",
    email: null,
    password_hash: passwordHash,
    full_name: "Existing User",
    birth_date: "1995-12-09",
    is_verified: true,
    verification_code: null,
    telegram_user_id: "9999",
    telegram_chat_id: "1111",
    telegram_username: "occupied",
    auth_provider: "telegram",
    created_at: new Date().toISOString()
  });
  const challengeModel = createFakeChallengeModel();
  await challengeModel.createChallenge({
    purpose: "register",
    startToken: "start-token-5",
    expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString()
  });
  const { fetchImpl, calls } = createFakeFetch();
  const botService = createTelegramBotService({
    userModel,
    challengeModel,
    botToken: "token",
    botUsername: "freechat_test_bot",
    fetchImpl
  });

  const result = await botService.handleStart({
    chatId: 1001,
    telegramUserId: 9999,
    username: "occupied",
    startToken: "start-token-5"
  });

  assert.equal(result.ok, false);
  assert.equal(result.reason, "already_linked");
  assert.match(calls[0].text, /уже привязан/);
});
