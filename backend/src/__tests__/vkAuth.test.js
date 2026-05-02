const test = require("node:test");
const assert = require("node:assert/strict");
const request = require("supertest");
const { createApp } = require("../app");

function createFakeUserModel() {
  const users = new Map();
  let idCounter = 1;

  const toPublicUser = (user) => ({
    id: user.id,
    email: user.email ?? null,
    fullName: user.full_name,
    birthDate: user.birth_date,
    isVerified: user.is_verified,
    telegramId: user.telegram_user_id ? String(user.telegram_user_id) : null,
    telegramUsername: user.telegram_username ?? null,
    telegramFirstName: user.telegram_first_name ?? null,
    telegramLastName: user.telegram_last_name ?? null,
    telegramPhotoUrl: user.telegram_photo_url ?? null,
    vkId: user.vk_user_id ? String(user.vk_user_id) : null,
    vkFirstName: user.vk_first_name ?? null,
    vkLastName: user.vk_last_name ?? null,
    vkPhotoUrl: user.vk_photo_url ?? null,
    vkEmail: user.vk_email ?? null,
    authProvider: user.auth_provider
  });

  return {
    toPublicUser,
    async findById(userId) {
      return users.get(userId) || null;
    },
    async findByVkUserId(vkUserId) {
      return [...users.values()].find((item) => item.vk_user_id === String(vkUserId)) || null;
    },
    async createVkUser({
      fullName,
      vkUserId,
      vkFirstName,
      vkLastName,
      vkPhotoUrl,
      vkEmail
    }) {
      const user = {
        id: `user-${idCounter++}`,
        email: null,
        password_hash: null,
        full_name: fullName,
        birth_date: null,
        is_verified: true,
        telegram_user_id: null,
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
        created_at: new Date().toISOString()
      };
      users.set(user.id, user);
      return user;
    },
    async updateVkProfile(userId, { vkFirstName, vkLastName, vkPhotoUrl, vkEmail }) {
      const user = users.get(userId);
      user.vk_first_name = vkFirstName || null;
      user.vk_last_name = vkLastName || null;
      user.vk_photo_url = vkPhotoUrl || null;
      user.vk_email = vkEmail || null;
      user.auth_provider = "vk";
      user.is_verified = true;
      return user;
    },
    seed(user) {
      users.set(user.id, user);
    }
  };
}

function createUserInfoFetch(payload, { ok = true, status = 200 } = {}) {
  const calls = [];
  const fetchImpl = async (url, options) => {
    calls.push({
      url,
      method: options.method,
      body: options.body.toString()
    });

    return {
      ok,
      status,
      async json() {
        return payload;
      },
      async text() {
        return JSON.stringify(payload);
      }
    };
  };

  return { fetchImpl, calls };
}

test("POST /api/vk-auth/native-login verifies access token and creates a VK user", async () => {
  const userModel = createFakeUserModel();
  const { fetchImpl, calls } = createUserInfoFetch({
    user: {
      user_id: "777",
      first_name: "Ada",
      last_name: "Lovelace",
      avatar: "https://vk.test/ada.jpg",
      email: "ada@vk.test"
    }
  });
  const app = createApp({
    userModel,
    vkConfig: {
      isConfigured: true,
      clientId: "123456",
      fetchImpl
    }
  });

  const response = await request(app)
    .post("/api/vk-auth/native-login")
    .send({
      accessToken: "vk-access-token",
      userId: "777"
    });

  assert.equal(response.status, 201);
  assert.equal(response.body.user.fullName, "Ada Lovelace");
  assert.equal(response.body.user.vkId, "777");
  assert.equal(response.body.user.vkEmail, "ada@vk.test");
  assert.equal(response.body.user.authProvider, "vk");
  assert.ok(response.body.token);
  assert.equal(calls.length, 1);
  assert.match(calls[0].url, /client_id=123456/);
  assert.equal(calls[0].body, "access_token=vk-access-token");
});

test("POST /api/vk-auth/native-login updates an existing VK user", async () => {
  const userModel = createFakeUserModel();
  userModel.seed({
    id: "vk-user-1",
    email: null,
    password_hash: null,
    full_name: "Existing VK User",
    birth_date: null,
    is_verified: true,
    telegram_user_id: null,
    telegram_username: null,
    telegram_first_name: null,
    telegram_last_name: null,
    telegram_photo_url: null,
    vk_user_id: "777",
    vk_first_name: "Old",
    vk_last_name: "Name",
    vk_photo_url: null,
    vk_email: null,
    auth_provider: "vk",
    created_at: new Date().toISOString()
  });
  const { fetchImpl } = createUserInfoFetch({
    user: {
      user_id: "777",
      first_name: "Ada",
      last_name: "Lovelace",
      avatar: "https://vk.test/ada-new.jpg",
      email: "ada@vk.test"
    }
  });
  const app = createApp({
    userModel,
    vkConfig: {
      isConfigured: true,
      clientId: "123456",
      fetchImpl
    }
  });

  const response = await request(app)
    .post("/api/vk-auth/native-login")
    .send({
      accessToken: "vk-access-token",
      userId: "777"
    });

  assert.equal(response.status, 200);
  assert.equal(response.body.user.id, "vk-user-1");
  assert.equal(response.body.user.fullName, "Existing VK User");
  assert.equal(response.body.user.vkFirstName, "Ada");
  assert.equal(response.body.user.vkPhotoUrl, "https://vk.test/ada-new.jpg");
});

test("POST /api/vk-auth/native-login rejects token owner mismatch", async () => {
  const userModel = createFakeUserModel();
  const { fetchImpl } = createUserInfoFetch({
    user: {
      user_id: "777",
      first_name: "Ada",
      last_name: "Lovelace"
    }
  });
  const app = createApp({
    userModel,
    vkConfig: {
      isConfigured: true,
      clientId: "123456",
      fetchImpl
    }
  });

  const response = await request(app)
    .post("/api/vk-auth/native-login")
    .send({
      accessToken: "vk-access-token",
      userId: "888"
    });

  assert.equal(response.status, 401);
});
