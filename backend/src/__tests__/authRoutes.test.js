const test = require("node:test");
const assert = require("node:assert/strict");
const bcrypt = require("bcrypt");
const request = require("supertest");
const { createApp } = require("../app");
const { createJwtToken } = require("../utils/createJwtToken");

function createFakeUserModel() {
  const users = new Map();
  let idCounter = 1;

  const normalize = (email) => email.toLowerCase();

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
      return [...users.values()].find((item) => item.id === userId) || null;
    },
    async findByEmail(email) {
      return users.get(normalize(email)) || null;
    },
    async createUser({ email, passwordHash, fullName, birthDate, verificationCode }) {
      const user = {
        id: `user-${idCounter++}`,
        email: normalize(email),
        password_hash: passwordHash,
        full_name: fullName,
        birth_date: birthDate,
        is_verified: false,
        verification_code: verificationCode,
        created_at: new Date().toISOString()
      };
      users.set(user.email, user);
      return user;
    },
    async updateUnverifiedUser(userId, { passwordHash, fullName, birthDate, verificationCode }) {
      const user = [...users.values()].find((item) => item.id === userId);
      user.password_hash = passwordHash;
      user.full_name = fullName;
      user.birth_date = birthDate;
      user.verification_code = verificationCode;
      user.is_verified = false;
      return user;
    },
    async updateVerificationCode(userId, verificationCode) {
      const user = [...users.values()].find((item) => item.id === userId);
      user.verification_code = verificationCode;
      return user;
    },
    async verifyUser(userId) {
      const user = [...users.values()].find((item) => item.id === userId);
      user.is_verified = true;
      user.verification_code = null;
      return user;
    },
    async updatePassword(userId, passwordHash) {
      const user = [...users.values()].find((item) => item.id === userId);
      user.password_hash = passwordHash;
      return user;
    },
    seed(user) {
      users.set(normalize(user.email), user);
    }
  };
}

function createFakeEmailService() {
  return {
    isConfigured: true,
    sentMessages: [],
    async sendVerificationCode(email, code) {
      this.sentMessages.push({ email, code });
    }
  };
}

test("POST /api/register creates user and sends code", async () => {
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

  assert.equal(response.status, 201);
  assert.equal(response.body.user.email, "new@example.com");
  assert.equal(emailService.sentMessages.length, 1);
});

test("POST /api/check-email returns existing verified account status", async () => {
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
    verification_code: null,
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/check-email")
    .send({
      email: "exists@example.com"
    });

  assert.equal(response.status, 200);
  assert.equal(response.body.exists, true);
  assert.equal(response.body.isVerified, true);
  assert.equal(response.body.user.email, "exists@example.com");
});

test("POST /api/register returns 409 for already verified email", async () => {
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
    verification_code: null,
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

  assert.equal(response.status, 409);
});

test("POST /api/verify-email returns 400 for wrong code", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  userModel.seed({
    id: "user-1",
    email: "verify@example.com",
    password_hash: passwordHash,
    full_name: "Verify User",
    birth_date: "1992-04-10",
    is_verified: false,
    verification_code: "123456",
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/verify-email")
    .send({
      email: "verify@example.com",
      code: "000000"
    });

  assert.equal(response.status, 400);
});

test("POST /api/login returns 403 for unverified user", async () => {
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
    verification_code: "123456",
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

test("POST /api/resend-code updates code only for unverified user", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  userModel.seed({
    id: "user-1",
    email: "resend@example.com",
    password_hash: passwordHash,
    full_name: "Resend User",
    birth_date: "1992-04-10",
    is_verified: false,
    verification_code: "123456",
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/resend-code")
    .send({
      email: "resend@example.com"
    });

  const updatedUser = await userModel.findByEmail("resend@example.com");
  assert.equal(response.status, 200);
  assert.notEqual(updatedUser.verification_code, "123456");
  assert.equal(emailService.sentMessages.length, 1);
});

test("POST /api/change-password updates the password for the current user", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  const user = {
    id: "user-1",
    email: "secure@example.com",
    password_hash: passwordHash,
    full_name: "Secure User",
    birth_date: "1992-04-10",
    is_verified: true,
    verification_code: null,
    created_at: new Date().toISOString()
  };
  userModel.seed(user);
  const app = createApp({ userModel, emailService, rateLimitEnabled: false });

  const response = await request(app)
    .post("/api/change-password")
    .set("Authorization", `Bearer ${createJwtToken(user)}`)
    .send({
      currentPassword: "123456",
      newPassword: "654321"
    });

  const updatedUser = await userModel.findById("user-1");
  assert.equal(response.status, 200);
  assert.equal(response.body.message, "Пароль успешно изменен");
  assert.equal(await bcrypt.compare("654321", updatedUser.password_hash), true);
});

test("auth routes do not return 429 by default after many requests", async () => {
  const userModel = createFakeUserModel();
  const emailService = createFakeEmailService();
  const passwordHash = await bcrypt.hash("123456", 1);
  userModel.seed({
    id: "user-1",
    email: "flood@example.com",
    password_hash: passwordHash,
    full_name: "Flood User",
    birth_date: "1992-04-10",
    is_verified: false,
    verification_code: "123456",
    created_at: new Date().toISOString()
  });
  const app = createApp({ userModel, emailService });

  let lastResponse;
  for (let index = 0; index < 20; index += 1) {
    lastResponse = await request(app)
      .post("/api/resend-code")
      .send({
        email: "flood@example.com"
      });
  }

  assert.equal(lastResponse.status, 200);
});
