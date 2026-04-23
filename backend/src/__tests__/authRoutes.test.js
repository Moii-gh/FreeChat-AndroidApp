const test = require("node:test");
const assert = require("node:assert/strict");
const bcrypt = require("bcrypt");
const request = require("supertest");
const { createApp } = require("../app");
const { createJwtToken } = require("../utils/createJwtToken");
const { createVerificationCodeChallenge } = require("../utils/verificationCode");

function createFakeUserModel() {
  const usersByEmail = new Map();
  const usersById = new Map();
  let idCounter = 1;

  const normalizeEmail = (email) => email.toLowerCase();

  const storeUser = (user) => {
    usersById.set(user.id, user);
    if (user.email) {
      usersByEmail.set(normalizeEmail(user.email), user);
    }
    return user;
  };

  return {
    toPublicUser(user) {
      return {
        id: user.id,
        email: user.email,
        fullName: user.full_name,
        birthDate: user.birth_date,
        isVerified: user.is_verified
      };
    },
    async findById(userId) {
      return usersById.get(userId) || null;
    },
    async findByEmail(email) {
      return usersByEmail.get(normalizeEmail(email)) || null;
    },
    async createUser({
      email,
      passwordHash,
      fullName,
      birthDate,
      verificationCodeHash,
      verificationCodeExpiresAt,
      verificationCodeSentAt
    }) {
      return storeUser({
        id: `user-${idCounter++}`,
        email: normalizeEmail(email),
        password_hash: passwordHash,
        full_name: fullName,
        birth_date: birthDate,
        is_verified: false,
        verification_code_hash: verificationCodeHash,
        verification_code_expires_at: verificationCodeExpiresAt,
        verification_code_sent_at: verificationCodeSentAt,
        verification_attempt_count: 0,
        token_invalid_before: null,
        created_at: new Date().toISOString()
      });
    },
    async updateUnverifiedUser(userId, values) {
      const user = usersById.get(userId);
      Object.assign(user, {
        password_hash: values.passwordHash,
        full_name: values.fullName,
        birth_date: values.birthDate,
        verification_code_hash: values.verificationCodeHash,
        verification_code_expires_at: values.verificationCodeExpiresAt,
        verification_code_sent_at: values.verificationCodeSentAt,
        verification_attempt_count: 0,
        is_verified: false
      });
      return user;
    },
    async updateVerificationChallenge(userId, values) {
      const user = usersById.get(userId);
      Object.assign(user, {
        verification_code_hash: values.verificationCodeHash,
        verification_code_expires_at: values.verificationCodeExpiresAt,
        verification_code_sent_at: values.verificationCodeSentAt,
        verification_attempt_count: 0
      });
      return user;
    },
    async incrementVerificationAttempts(userId) {
      const user = usersById.get(userId);
      user.verification_attempt_count = Number(user.verification_attempt_count || 0) + 1;
      return user;
    },
    async verifyUser(userId) {
      const user = usersById.get(userId);
      Object.assign(user, {
        is_verified: true,
        verification_code_hash: null,
        verification_code_expires_at: null,
        verification_code_sent_at: null,
        verification_attempt_count: 0
      });
      return user;
    },
    async updatePassword(userId, passwordHash) {
      const user = usersById.get(userId);
      user.password_hash = passwordHash;
      user.token_invalid_before = new Date(Date.now() + 1000).toISOString();
      return user;
    },
    seed(user) {
      return storeUser({
        verification_code_hash: null,
        verification_code_expires_at: null,
        verification_code_sent_at: null,
        verification_attempt_count: 0,
        token_invalid_before: null,
        ...user
      });
    }
  };
}

function createFakeEmailService({ isConfigured = true } = {}) {
  return {
    isConfigured,
    sentMessages: [],
    async sendVerificationCode(email, code) {
      this.sentMessages.push({ email, code });
    }
  };
}

test("POST /api/register stores a hashed verification challenge and sends the code", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/register")
    .send({
      email: "new@example.com",
      password: "123456",
      fullName: "Ada Lovelace",
      birthDate: "1995-12-09"
    });

  const storedUser = await userModel.findByEmail("new@example.com");
  assert.equal(response.status, 202);
  assert.equal(response.body.user, undefined);
  assert.equal(emailService.sentMessages.length, 1);
  assert.ok(storedUser.verification_code_hash);
  assert.notEqual(storedUser.verification_code_hash, emailService.sentMessages[0].code);
});

test("POST /api/register returns 503 when SMTP verification is disabled", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService({ isConfigured: false });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/register")
    .send({
      email: "blocked@example.com",
      password: "123456",
      fullName: "Blocked User",
      birthDate: "1995-12-09"
    });

  assert.equal(response.status, 503);
});

test("POST /api/check-email returns only a generic response", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  userModel.seed({
    id: "user-1",
    email: "exists@example.com",
    password_hash: passwordHash,
    full_name: "Existing User",
    birth_date: "1990-01-01",
    is_verified: true,
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/check-email")
    .send({ email: "exists@example.com" });

  assert.equal(response.status, 200);
  assert.equal(response.body.canProceedWithEmail, true);
  assert.equal("user" in response.body, false);
  assert.equal("exists" in response.body, false);
  assert.equal("isVerified" in response.body, false);
});

test("POST /api/register for an existing verified email returns a generic 202 without sending email", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  userModel.seed({
    id: "user-1",
    email: "exists@example.com",
    password_hash: passwordHash,
    full_name: "Existing User",
    birth_date: "1990-01-01",
    is_verified: true,
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/register")
    .send({
      email: "exists@example.com",
      password: "123456",
      fullName: "Ada Lovelace",
      birthDate: "1995-12-09"
    });

  assert.equal(response.status, 202);
  assert.equal(emailService.sentMessages.length, 0);
});

test("POST /api/verify-email returns 400 for a wrong verification code and increments attempts", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  const challenge = createVerificationCodeChallenge();
  userModel.seed({
    id: "user-1",
    email: "verify@example.com",
    password_hash: passwordHash,
    full_name: "Verify User",
    birth_date: "1992-04-10",
    is_verified: false,
    verification_code_hash: challenge.codeHash,
    verification_code_expires_at: challenge.expiresAt,
    verification_code_sent_at: challenge.sentAt,
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/verify-email")
    .send({
      email: "verify@example.com",
      code: "000000"
    });

  const updatedUser = await userModel.findByEmail("verify@example.com");
  assert.equal(response.status, 400);
  assert.equal(updatedUser.verification_attempt_count, 1);
});

test("POST /api/verify-email returns 429 after the max number of failed attempts", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  const challenge = createVerificationCodeChallenge();
  userModel.seed({
    id: "user-1",
    email: "locked@example.com",
    password_hash: passwordHash,
    full_name: "Locked User",
    birth_date: "1992-04-10",
    is_verified: false,
    verification_code_hash: challenge.codeHash,
    verification_code_expires_at: challenge.expiresAt,
    verification_code_sent_at: challenge.sentAt,
    verification_attempt_count: 4,
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/verify-email")
    .send({
      email: "locked@example.com",
      code: "000000"
    });

  assert.equal(response.status, 429);
});

test("POST /api/login returns 403 for an unverified user", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  userModel.seed({
    id: "user-1",
    email: "login@example.com",
    password_hash: passwordHash,
    full_name: "Login User",
    birth_date: "1992-04-10",
    is_verified: false,
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/login")
    .send({
      email: "login@example.com",
      password: "123456"
    });

  assert.equal(response.status, 403);
});

test("POST /api/resend-code is generic and respects resend cooldowns", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  const challenge = createVerificationCodeChallenge();
  userModel.seed({
    id: "user-1",
    email: "resend@example.com",
    password_hash: passwordHash,
    full_name: "Resend User",
    birth_date: "1992-04-10",
    is_verified: false,
    verification_code_hash: challenge.codeHash,
    verification_code_expires_at: challenge.expiresAt,
    verification_code_sent_at: new Date().toISOString(),
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/resend-code")
    .send({ email: "resend@example.com" });

  const updatedUser = await userModel.findByEmail("resend@example.com");
  assert.equal(response.status, 200);
  assert.equal(updatedUser.verification_code_hash, challenge.codeHash);
  assert.equal(emailService.sentMessages.length, 0);
});

test("POST /api/change-password invalidates the previous JWT", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  const user = userModel.seed({
    id: "user-1",
    email: "secure@example.com",
    password_hash: passwordHash,
    full_name: "Secure User",
    birth_date: "1992-04-10",
    is_verified: true,
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });
  const oldToken = createJwtToken(user);

  const changeResponse = await request(app)
    .post("/api/change-password")
    .set("Authorization", `Bearer ${oldToken}`)
    .send({
      currentPassword: "123456",
      newPassword: "654321"
    });

  const meResponse = await request(app)
    .get("/api/me")
    .set("Authorization", `Bearer ${oldToken}`);

  const updatedUser = await userModel.findById("user-1");
  assert.equal(changeResponse.status, 200);
  assert.equal(meResponse.status, 401);
  assert.equal(await bcrypt.compare("654321", updatedUser.password_hash), true);
});

test("auth routes are rate limited by default", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  const challenge = createVerificationCodeChallenge();
  userModel.seed({
    id: "user-1",
    email: "flood@example.com",
    password_hash: passwordHash,
    full_name: "Flood User",
    birth_date: "1992-04-10",
    is_verified: false,
    verification_code_hash: challenge.codeHash,
    verification_code_expires_at: challenge.expiresAt,
    verification_code_sent_at: new Date(0).toISOString(),
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService });

  let lastResponse;
  for (let index = 0; index < 21; index += 1) {
    lastResponse = await request(app)
      .post("/api/resend-code")
      .send({ email: "flood@example.com" });
  }

  assert.equal(lastResponse.status, 429);
});

test("POST /api/check-email returns 400 for malformed JSON instead of 500", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/check-email")
    .set("Content-Type", "application/json")
    .send('{"email":"broken@example.com"');

  assert.equal(response.status, 400);
  assert.equal(response.body.message, "Некорректный JSON в теле запроса");
});
