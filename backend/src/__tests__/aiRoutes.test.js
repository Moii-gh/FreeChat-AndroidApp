const test = require("node:test");
const assert = require("node:assert/strict");
const request = require("supertest");
const { createApp } = require("../app");
const { createJwtToken } = require("../utils/createJwtToken");
const { env } = require("../config/env");

function createFakeUserModel() {
  const users = new Map();

  return {
    async findById(userId) {
      return users.get(userId) || null;
    },
    seed(user) {
      users.set(user.id, user);
      return user;
    }
  };
}

function createFakeAiUsageModel() {
  const counts = new Map();

  return {
    async getDailyUsageSnapshot(userId, { limit }) {
      const usedCount = counts.get(userId) || 0;
      return {
        allowed: usedCount < limit,
        dailyLimit: limit,
        usedToday: usedCount,
        bonusRequests: 0,
        baseRemaining: Math.max(limit - usedCount, 0),
        totalRemaining: Math.max(limit - usedCount, 0),
        resetAt: new Date(Date.now() + 60_000).toISOString()
      };
    },
    async consumeDailyRequest(userId, { limit }) {
      const current = counts.get(userId) || 0;
      if (current >= limit) {
        return {
          allowed: false,
          dailyLimit: limit,
          usedToday: current,
          bonusRequests: 0,
          baseRemaining: 0,
          totalRemaining: 0,
          resetAt: new Date(Date.now() + 60_000).toISOString()
        };
      }

      const next = current + 1;
      counts.set(userId, next);
      return {
        allowed: true,
        dailyLimit: limit,
        usedToday: next,
        bonusRequests: 0,
        baseRemaining: Math.max(limit - next, 0),
        totalRemaining: Math.max(limit - next, 0),
        resetAt: new Date(Date.now() + 60_000).toISOString()
      };
    }
  };
}

function setAiEnv(overrides = {}) {
  const previous = {
    aiApiKey: env.aiApiKey,
    aiChatUrl: env.aiChatUrl,
    aiImageUrl: env.aiImageUrl,
    aiTextModel: env.aiTextModel,
    aiAdultTextModel: env.aiAdultTextModel,
    aiImageModel: env.aiImageModel,
    dailyAiRequestLimit: env.dailyAiRequestLimit
  };

  Object.assign(env, {
    aiApiKey: "test-key",
    aiChatUrl: "https://ai.example.test/v1/chat/completions",
    aiImageUrl: "https://ai.example.test/v1/images/generations",
    aiTextModel: "test-model",
    aiAdultTextModel: "x-ai/grok-4-fast-thinking",
    aiImageModel: "test-image-model",
    dailyAiRequestLimit: 1,
    ...overrides
  });

  return () => Object.assign(env, previous);
}

test("POST /api/ai/chat enforces the server-side daily quota for free users", async () => {
  const restoreEnv = setAiEnv();
  const originalFetch = global.fetch;
  global.fetch = async () =>
    new Response('data: {"choices":[{"delta":{"content":"Hi"}}]}\n\ndata: [DONE]\n\n', {
      status: 200,
      headers: {
        "content-type": "text/event-stream"
      }
    });

  try {
    const userModel = createFakeUserModel();
    const aiUsageModel = createFakeAiUsageModel();
    const user = userModel.seed({
      id: "user-1",
      email: "free@example.com",
      full_name: "Free User",
      is_verified: true,
      bonus_requests: 0,
      token_invalid_before: null
    });
    const app = createApp({ userModel, aiUsageModel, rateLimitEnabled: false });

    const payload = {
      currentMode: null,
      request: {
        messages: [
          { role: "user", content: "Hello" }
        ]
      }
    };

    const firstResponse = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", `Bearer ${createJwtToken(user)}`)
      .send(payload);

    const secondResponse = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", `Bearer ${createJwtToken(user)}`)
      .send(payload);

    assert.equal(firstResponse.status, 200);
    assert.equal(secondResponse.status, 429);
    assert.equal(secondResponse.body.remaining, 0);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat adult mode selects Grok and prepends style prompt", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamBody = null;
  global.fetch = async (_url, init) => {
    upstreamBody = JSON.parse(init.body);
    return new Response('data: {"choices":[{"delta":{"content":"Hi"}}]}\n\ndata: [DONE]\n\n', {
      status: 200,
      headers: {
        "content-type": "text/event-stream"
      }
    });
  };

  try {
    const userModel = createFakeUserModel();
    const aiUsageModel = createFakeAiUsageModel();
    const user = userModel.seed({
      id: "user-1",
      email: "free@example.com",
      full_name: "Free User",
      is_verified: true,
      bonus_requests: 0,
      token_invalid_before: null
    });
    const app = createApp({ userModel, aiUsageModel, rateLimitEnabled: false });

    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", `Bearer ${createJwtToken(user)}`)
      .send({
        currentMode: null,
        adultMode: true,
        request: {
          messages: [
            { role: "user", content: "Hello" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamBody.model, "x-ai/grok-4-fast-thinking");
    assert.equal(upstreamBody.messages[0].role, "system");
    assert.match(upstreamBody.messages[0].content, /18\+ style mode/);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat maps img-flux image size to aspect_ratio", async () => {
  const restoreEnv = setAiEnv({
    aiImageModel: "img-flux/flux-2-klein-4b",
    dailyAiRequestLimit: 5
  });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    return new Response(JSON.stringify({ data: [{ url: "https://example.test/image.png" }] }), {
      status: 200,
      headers: {
        "content-type": "application/json"
      }
    });
  };

  try {
    const userModel = createFakeUserModel();
    const aiUsageModel = createFakeAiUsageModel();
    const user = userModel.seed({
      id: "user-1",
      email: "free@example.com",
      full_name: "Free User",
      is_verified: true,
      bonus_requests: 0,
      token_invalid_before: null
    });
    const app = createApp({ userModel, aiUsageModel, rateLimitEnabled: false });

    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", `Bearer ${createJwtToken(user)}`)
      .send({
        currentMode: "create_image",
        request: {
          prompt: "cat",
          n: 1,
          size: "1024x1024"
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.aiImageUrl);
    assert.equal(upstreamBody.model, "img-flux/flux-2-klein-4b");
    assert.equal(upstreamBody.aspect_ratio, "1:1");
    assert.equal(Object.hasOwn(upstreamBody, "size"), false);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat rejects oversized payloads", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  global.fetch = async () =>
    new Response('data: {"choices":[{"delta":{"content":"Hi"}}]}\n\ndata: [DONE]\n\n', {
      status: 200,
      headers: {
        "content-type": "text/event-stream"
      }
    });

  try {
    const userModel = createFakeUserModel();
    const aiUsageModel = createFakeAiUsageModel();
    const user = userModel.seed({
      id: "user-1",
      email: "free@example.com",
      full_name: "Free User",
      is_verified: true,
      bonus_requests: 0,
      token_invalid_before: null
    });
    const app = createApp({ userModel, aiUsageModel, rateLimitEnabled: false });

    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", `Bearer ${createJwtToken(user)}`)
      .send({
        currentMode: null,
        request: {
          messages: [
            { role: "user", content: "a".repeat(30000) }
          ]
        }
      });

    assert.equal(response.status, 400);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});
