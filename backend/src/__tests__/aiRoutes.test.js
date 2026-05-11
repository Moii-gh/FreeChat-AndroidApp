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
    openAiApiKey: env.openAiApiKey,
    openAiChatUrl: env.openAiChatUrl,
    openAiResponsesUrl: env.openAiResponsesUrl,
    openAiImageUrl: env.openAiImageUrl,
    openAiImageEditUrl: env.openAiImageEditUrl,
    openAiFilesUrl: env.openAiFilesUrl,
    openAiVectorStoresUrl: env.openAiVectorStoresUrl,
    openAiGpt54Model: env.openAiGpt54Model,
    openAiImageModel: env.openAiImageModel,
    vsegptApiKey: env.vsegptApiKey,
    vsegptChatUrl: env.vsegptChatUrl,
    vsegptImageUrl: env.vsegptImageUrl,
    vsegptGpt55ModelId: env.vsegptGpt55ModelId,
    vsegptGemini3ModelId: env.vsegptGemini3ModelId,
    vsegptDeepSeekModelId: env.vsegptDeepSeekModelId,
    aiImageFallbackProvider: env.aiImageFallbackProvider,
    aiImageFallbackModelKey: env.aiImageFallbackModelKey,
    aiApiKey: env.aiApiKey,
    aiChatUrl: env.aiChatUrl,
    aiImageUrl: env.aiImageUrl,
    aiTextModel: env.aiTextModel,
    aiTitleModel: env.aiTitleModel,
    aiSummaryModel: env.aiSummaryModel,
    aiAdultTextModel: env.aiAdultTextModel,
    aiImageModel: env.aiImageModel,
    dailyAiRequestLimit: env.dailyAiRequestLimit
  };

  Object.assign(env, {
    openAiApiKey: "openai-test-key",
    openAiChatUrl: "https://openai.example.test/v1/chat/completions",
    openAiResponsesUrl: "https://openai.example.test/v1/responses",
    openAiImageUrl: "https://openai.example.test/v1/images/generations",
    openAiImageEditUrl: "https://openai.example.test/v1/images/edits",
    openAiFilesUrl: "https://openai.example.test/v1/files",
    openAiVectorStoresUrl: "https://openai.example.test/v1/vector_stores",
    openAiGpt54Model: "gpt-5.4-mini",
    openAiImageModel: "gpt-image-1",
    vsegptApiKey: "vsegpt-test-key",
    vsegptChatUrl: "https://vsegpt.example.test/v1/chat/completions",
    vsegptImageUrl: "https://vsegpt.example.test/v1/images/generations",
    vsegptGpt55ModelId: "openai/gpt-5.4-nano",
    vsegptGemini3ModelId: "google/gemma-4-26b-a4b-it",
    vsegptDeepSeekModelId: "deepseek/deepseek-v4-flash-alt",
    aiImageFallbackProvider: "vsegpt",
    aiImageFallbackModelKey: "gemini3",
    aiApiKey: "test-key",
    aiChatUrl: "https://ai.example.test/v1/chat/completions",
    aiImageUrl: "https://ai.example.test/v1/images/generations",
    aiTextModel: "test-model",
    aiTitleModel: "",
    aiSummaryModel: "",
    aiAdultTextModel: "x-ai/grok-4-fast-thinking",
    aiImageModel: "test-image-model",
    dailyAiRequestLimit: 1,
    ...overrides
  });

  return () => Object.assign(env, previous);
}

function createAuthedApp() {
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

  return { app, user };
}

function authHeader(user) {
  return `Bearer ${createJwtToken(user)}`;
}

test("POST /api/ai/chat defaults to OpenAI when provider and model are omitted", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  let authorization = "";
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    authorization = init.headers.Authorization;
    return new Response('data: {"choices":[{"delta":{"content":"Hi"}}]}\n\ndata: [DONE]\n\n', {
      status: 200,
      headers: {
        "content-type": "text/event-stream"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        request: {
          messages: [
            { role: "user", content: "Hello" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.openAiChatUrl);
    assert.equal(authorization, "Bearer openai-test-key");
    assert.equal(upstreamBody.model, "gpt-5.4-mini");
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat maps OpenAI max_tokens to max_completion_tokens", async () => {
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
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        request: {
          stream: true,
          max_tokens: 120,
          messages: [
            { role: "user", content: "Hello" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(Object.hasOwn(upstreamBody, "max_tokens"), false);
    assert.equal(upstreamBody.max_completion_tokens, 120);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/title maps OpenAI max_tokens to max_completion_tokens", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamBody = null;
  global.fetch = async (_url, init) => {
    upstreamBody = JSON.parse(init.body);
    return new Response(JSON.stringify({
      choices: [
        {
          message: {
            content: "Short title"
          }
        }
      ]
    }), {
      status: 200,
      headers: {
        "content-type": "application/json"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/title")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        firstUserMessage: "Hello",
        firstAssistantMessage: "Hi, how can I help?"
      });

    assert.equal(response.status, 200);
    assert.equal(response.body.content, "Short title");
    assert.equal(Object.hasOwn(upstreamBody, "max_tokens"), false);
    assert.equal(upstreamBody.max_completion_tokens, 40);
    assert.match(upstreamBody.messages[1].content, /Первое сообщение пользователя/);
    assert.match(upstreamBody.messages[1].content, /Первый ответ ассистента/);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/title falls back to VseGPT when OpenAI key is missing", async () => {
  const restoreEnv = setAiEnv({
    openAiApiKey: "",
    vsegptApiKey: "vsegpt-test-key",
    dailyAiRequestLimit: 5
  });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  let authorization = "";
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    authorization = init.headers.Authorization;
    return new Response(JSON.stringify({
      choices: [
        {
          message: {
            content: "VseGPT title"
          }
        }
      ]
    }), {
      status: 200,
      headers: {
        "content-type": "application/json"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/title")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        firstUserMessage: "Hello"
      });

    assert.equal(response.status, 200);
    assert.equal(response.body.content, "VseGPT title");
    assert.equal(upstreamUrl, env.vsegptChatUrl);
    assert.equal(authorization, "Bearer vsegpt-test-key");
    assert.equal(upstreamBody.model, "google/gemma-4-26b-a4b-it");
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat routes OpenAI image generation to the Images API", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    return new Response(JSON.stringify({ data: [{ b64_json: "aW1hZ2U=" }] }), {
      status: 200,
      headers: {
        "content-type": "application/json"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        currentMode: "create_image",
        request: {
          prompt: "cat",
          n: 1,
          size: "1024x1024"
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.openAiImageUrl);
    assert.equal(upstreamBody.model, "gpt-image-1");
    assert.equal(upstreamBody.prompt, "cat");
    assert.equal(upstreamBody.size, "1024x1024");
    assert.equal(response.body.data[0].b64_json, "aW1hZ2U=");
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat routes OpenAI web search through Responses API", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    return new Response(JSON.stringify({
      output: [
        {
          type: "message",
          role: "assistant",
          content: [
            {
              type: "output_text",
              text: "Current result"
            }
          ]
        }
      ]
    }), {
      status: 200,
      headers: {
        "content-type": "application/json"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        currentMode: "search",
        request: {
          stream: true,
          messages: [
            { role: "user", content: "latest news" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.openAiResponsesUrl);
    assert.equal(upstreamBody.model, "gpt-5.4-mini");
    assert.deepEqual(upstreamBody.tools, [{ type: "web_search" }]);
    assert.match(JSON.stringify(upstreamBody.input), /latest news/);
    assert.match(response.text, /Current result/);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat routes explicit OpenAI search intent through Responses API", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    return new Response(JSON.stringify({
      output: [
        {
          type: "message",
          role: "assistant",
          content: [
            {
              type: "output_text",
              text: "Fresh answer"
            }
          ]
        }
      ]
    }), {
      status: 200,
      headers: {
        "content-type": "application/json"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        request: {
          stream: true,
          messages: [
            { role: "user", content: "Поищи в интернете свежие новости OpenAI" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.openAiResponsesUrl);
    assert.deepEqual(upstreamBody.tools, [{ type: "web_search" }]);
    assert.match(upstreamBody.instructions, /Server capability context/);
    assert.match(response.text, /Fresh answer/);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat sends OpenAI image input to a vision-capable chat request", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    return new Response('data: {"choices":[{"delta":{"content":"Image answer"}}]}\n\ndata: [DONE]\n\n', {
      status: 200,
      headers: {
        "content-type": "text/event-stream"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        request: {
          stream: true,
          messages: [
            {
              role: "user",
              content: [
                { type: "text", text: "What is in this image?" },
                {
                  type: "image_url",
                  image_url: { url: "data:image/png;base64,aW1hZ2U=" }
                }
              ]
            }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.openAiChatUrl);
    assert.equal(upstreamBody.model, "gpt-5.4-mini");
    assert.match(upstreamBody.messages[0].content, /Server capability context/);
    const userMessage = upstreamBody.messages.find((message) => message.role === "user");
    assert.equal(userMessage.content[1].type, "image_url");
    assert.equal(
      userMessage.content[1].image_url.url,
      "data:image/png;base64,aW1hZ2U="
    );
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat keeps OpenAI capability questions on text chat with skill awareness", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    return new Response('data: {"choices":[{"delta":{"content":"I can search and work with images."}}]}\n\ndata: [DONE]\n\n', {
      status: 200,
      headers: {
        "content-type": "text/event-stream"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        request: {
          stream: true,
          messages: [
            { role: "user", content: "Что ты умеешь? Есть ли у тебя web search и генерация картинок?" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.openAiChatUrl);
    assert.match(upstreamBody.messages[0].content, /Server capability context/);
    assert.match(upstreamBody.messages[0].content, /Web search is available/);
    assert.match(upstreamBody.messages[0].content, /Images can be generated/);
    assert.doesNotMatch(upstreamBody.messages[0].content, /webSearch|imageGeneration|imageEdit/);
    assert.match(response.text, /search/);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat routes natural OpenAI image generation requests to Images API", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    return new Response(JSON.stringify({ data: [{ b64_json: "aW1hZ2U=" }] }), {
      status: 200,
      headers: {
        "content-type": "application/json"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        request: {
          stream: true,
          messages: [
            { role: "user", content: "Нарисуй картинку кота в космосе" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.openAiImageUrl);
    assert.equal(upstreamBody.model, "gpt-image-1");
    assert.equal(upstreamBody.prompt, "Нарисуй картинку кота в космосе");
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat routes natural OpenAI image edit requests with attached images to edit API", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    return new Response(JSON.stringify({ data: [{ b64_json: "ZWRpdA==" }] }), {
      status: 200,
      headers: {
        "content-type": "application/json"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        request: {
          stream: true,
          messages: [
            {
              role: "user",
              content: [
                { type: "text", text: "Отредактируй фото: сделай фон ярче" },
                {
                  type: "image_url",
                  image_url: { url: "data:image/png;base64,aW1hZ2U=" }
                }
              ]
            }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.openAiImageEditUrl);
    assert.equal(upstreamBody.model, "gpt-image-1");
    assert.equal(upstreamBody.prompt, "Отредактируй фото: сделай фон ярче");
    assert.equal(upstreamBody.images[0].image_url, "data:image/png;base64,aW1hZ2U=");
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat routes explicit OpenAI web search tools through backend", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamBody = null;
  global.fetch = async (_url, init) => {
    upstreamBody = JSON.parse(init.body);
    return new Response(JSON.stringify({
      output: [
        {
          type: "message",
          role: "assistant",
          content: [
            {
              type: "output_text",
              text: "Searched"
            }
          ]
        }
      ]
    }), {
      status: 200,
      headers: {
        "content-type": "application/json"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        request: {
          stream: true,
          tools: [{ type: "web_search" }],
          messages: [
            { role: "user", content: "search this" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.deepEqual(upstreamBody.tools, [{ type: "web_search" }]);
    assert.match(response.text, /Searched/);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat routes OpenAI file search through upload and vector store workflow", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  const calls = [];
  global.fetch = async (url, init) => {
    calls.push({ url, init });

    if (url === env.openAiFilesUrl) {
      assert.ok(init.body instanceof FormData);
      return new Response(JSON.stringify({ id: "file_123" }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }

    if (url === env.openAiVectorStoresUrl) {
      const body = JSON.parse(init.body);
      assert.deepEqual(body.file_ids, ["file_123"]);
      return new Response(JSON.stringify({
        id: "vs_123",
        file_counts: { in_progress: 0 }
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }

    if (url === env.openAiResponsesUrl) {
      const body = JSON.parse(init.body);
      assert.equal(body.tools[0].type, "file_search");
      assert.deepEqual(body.tools[0].vector_store_ids, ["vs_123"]);
      return new Response(JSON.stringify({
        output: [
          {
            type: "message",
            role: "assistant",
            content: [
              {
                type: "output_text",
                text: "File answer"
              }
            ]
          }
        ]
      }), {
        status: 200,
        headers: { "content-type": "application/json" }
      });
    }

    throw new Error(`Unexpected URL ${url}`);
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        request: {
          stream: true,
          fileSearchFiles: [
            {
              fileName: "notes.txt",
              mimeType: "text/plain",
              base64: Buffer.from("alpha beta").toString("base64")
            }
          ],
          messages: [
            { role: "user", content: "What does the file say?" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(calls.length, 3);
    assert.match(response.text, /File answer/);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat routes OpenAI image edit to the Images edit API", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  let upstreamUrl = "";
  let upstreamBody = null;
  global.fetch = async (url, init) => {
    upstreamUrl = url;
    upstreamBody = JSON.parse(init.body);
    return new Response(JSON.stringify({ data: [{ b64_json: "ZWRpdA==" }] }), {
      status: 200,
      headers: {
        "content-type": "application/json"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54",
        currentMode: "create_image",
        request: {
          prompt: "Make the background brighter",
          images: [
            {
              image_url: "data:image/png;base64,aW1hZ2U="
            }
          ],
          n: 1,
          size: "1024x1024"
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.openAiImageEditUrl);
    assert.equal(upstreamBody.model, "gpt-image-1");
    assert.equal(upstreamBody.prompt, "Make the background brighter");
    assert.equal(upstreamBody.images[0].image_url, "data:image/png;base64,aW1hZ2U=");
    assert.equal(response.body.data[0].b64_json, "ZWRpdA==");
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat returns a clear error when OpenAI key is missing", async () => {
  const restoreEnv = setAiEnv({ openAiApiKey: "", dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  global.fetch = async () => {
    throw new Error("fetch should not be called without OpenAI key");
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        request: {
          messages: [
            { role: "user", content: "Hello" }
          ]
        }
      });

    assert.equal(response.status, 503);
    assert.equal(response.body.message, "OpenAI API key is not configured on the server.");
    assert.doesNotMatch(JSON.stringify(response.body), /openai-test-key|sk-/);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat returns a clear error when VseGPT key is missing", async () => {
  const restoreEnv = setAiEnv({ vsegptApiKey: "", dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  global.fetch = async () => {
    throw new Error("fetch should not be called without VseGPT key");
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "vsegpt",
        modelKey: "gemini3",
        request: {
          messages: [
            { role: "user", content: "Hello" }
          ]
        }
      });

    assert.equal(response.status, 503);
    assert.match(response.body.message, /VseGPT API key is not configured/);
    assert.doesNotMatch(JSON.stringify(response.body), /vsegpt-test-key|sk-/);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat rejects unsupported image edit capability before wrong endpoint routing", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  global.fetch = async () => {
    throw new Error("fetch should not be called for unsupported VseGPT image edit");
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "vsegpt",
        modelKey: "gemini3",
        currentMode: "image_edit",
        request: {
          prompt: "edit",
          images: [
            {
              image_url: "data:image/png;base64,aW1hZ2U="
            }
          ]
        }
      });

    assert.equal(response.status, 501);
    assert.equal(response.body.message, "Эта функция пока не подключена на сервере.");
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat rejects unknown whitelisted model keys", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const { app, user } = createAuthedApp();

  try {
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "not-a-model",
        request: {
          messages: [
            { role: "user", content: "Hello" }
          ]
        }
      });

    assert.equal(response.status, 400);
    assert.match(response.body.message, /Unknown AI model/);
  } finally {
    restoreEnv();
  }
});

test("GET /api/ai/models returns public model metadata without technical model ids", async () => {
  const restoreEnv = setAiEnv();
  const { app, user } = createAuthedApp();

  try {
    const response = await request(app)
      .get("/api/ai/models")
      .set("Authorization", authHeader(user));

    assert.equal(response.status, 200);
    assert.equal(response.body.defaultProvider, "openai");
    const openAiModels = response.body.models.filter((model) => model.provider === "openai");
    assert.equal(openAiModels.length, 1);
    assert.deepEqual(openAiModels[0], {
      provider: "openai",
      modelKey: "gpt54",
      displayName: "GPT-5.4",
      isDefault: true,
      capabilities: [
        "text",
        "vision",
        "webSearch",
        "fileSearch",
        "imageGeneration",
        "imageEdit"
      ]
    });
    assert.ok(response.body.models.some((model) =>
      model.provider === "vsegpt" &&
      model.modelKey === "gpt55" &&
      model.displayName === "GPT-5.5"
    ));
    assert.ok(response.body.models.some((model) =>
      model.provider === "vsegpt" &&
      model.modelKey === "deepseek" &&
      model.displayName === "DeepSeek"
    ));
    assert.ok(openAiModels[0].capabilities.includes("webSearch"));
    assert.ok(openAiModels[0].capabilities.includes("imageEdit"));
    assert.equal(JSON.stringify(response.body).includes("gpt-5.4-mini"), false);
    assert.equal(JSON.stringify(response.body).includes("openai/gpt-5.4-nano"), false);
    assert.equal(JSON.stringify(response.body).includes("deepseek/deepseek-v4-flash-alt"), false);
    assert.equal(JSON.stringify(response.body).includes("openai-test-key"), false);
  } finally {
    restoreEnv();
  }
});

test("POST /api/ai/chat routes VseGPT GPT-5.5 display slot to openai/gpt-5.4-nano", async () => {
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
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "vsegpt",
        modelKey: "gpt55",
        request: {
          messages: [
            { role: "user", content: "Hello" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamBody.model, "openai/gpt-5.4-nano");
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat converts images to text before sending to VseGPT GPT-5.5 nano slot", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  const calls = [];
  global.fetch = async (url, init) => {
    const body = JSON.parse(init.body);
    calls.push({ url, body });

    if (calls.length === 1) {
      return new Response(JSON.stringify({
        choices: [
          {
            message: {
              content: "The image shows a printed receipt with a total."
            }
          }
        ]
      }), {
        status: 200,
        headers: {
          "content-type": "application/json"
        }
      });
    }

    return new Response('data: {"choices":[{"delta":{"content":"Hi"}}]}\n\ndata: [DONE]\n\n', {
      status: 200,
      headers: {
        "content-type": "text/event-stream"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "vsegpt",
        modelKey: "gpt55",
        request: {
          messages: [
            {
              role: "user",
              content: [
                { type: "text", text: "Read this." },
                {
                  type: "image_url",
                  image_url: { url: "data:image/png;base64,aW1hZ2U=" }
                }
              ]
            }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(calls.length, 2);
    assert.equal(calls[0].body.model, "google/gemma-4-26b-a4b-it");
    assert.equal(calls[1].body.model, "openai/gpt-5.4-nano");
    assert.equal(JSON.stringify(calls[1].body).includes("image_url"), false);
    assert.match(calls[1].body.messages[0].content, /printed receipt/);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat maps legacy OpenAI gpt54Mini key to single public GPT-5.4 model", async () => {
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
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "openai",
        modelKey: "gpt54Mini",
        request: {
          messages: [
            { role: "user", content: "Hello" }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamBody.model, "gpt-5.4-mini");
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

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
        provider: "vsegpt",
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
        provider: "vsegpt",
        currentMode: "create_image",
        request: {
          prompt: "cat",
          n: 1,
          size: "1024x1024"
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamUrl, env.vsegptImageUrl);
    assert.equal(upstreamBody.model, "img-flux/flux-2-klein-4b");
    assert.equal(upstreamBody.aspect_ratio, "1:1");
    assert.equal(Object.hasOwn(upstreamBody, "size"), false);
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat sends images directly to VseGPT Gemini-3", async () => {
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
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "vsegpt",
        modelKey: "gemini3",
        request: {
          messages: [
            {
              role: "user",
              content: [
                { type: "text", text: "What is in this image?" },
                {
                  type: "image_url",
                  image_url: { url: "data:image/png;base64,aW1hZ2U=" }
                }
              ]
            }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(upstreamBody.model, "google/gemma-4-26b-a4b-it");
    assert.equal(upstreamBody.messages[0].content[1].type, "image_url");
  } finally {
    global.fetch = originalFetch;
    restoreEnv();
  }
});

test("POST /api/ai/chat converts images to text before sending to VseGPT DeepSeek", async () => {
  const restoreEnv = setAiEnv({ dailyAiRequestLimit: 5 });
  const originalFetch = global.fetch;
  const calls = [];
  global.fetch = async (url, init) => {
    const body = JSON.parse(init.body);
    calls.push({ url, body });

    if (calls.length === 1) {
      return new Response(JSON.stringify({
        choices: [
          {
            message: {
              content: "The image shows a red warning sign with visible text."
            }
          }
        ]
      }), {
        status: 200,
        headers: {
          "content-type": "application/json"
        }
      });
    }

    return new Response('data: {"choices":[{"delta":{"content":"Hi"}}]}\n\ndata: [DONE]\n\n', {
      status: 200,
      headers: {
        "content-type": "text/event-stream"
      }
    });
  };

  try {
    const { app, user } = createAuthedApp();
    const response = await request(app)
      .post("/api/ai/chat")
      .set("Authorization", authHeader(user))
      .send({
        provider: "vsegpt",
        modelKey: "deepseek",
        request: {
          messages: [
            {
              role: "user",
              content: [
                { type: "text", text: "Explain this." },
                {
                  type: "image_url",
                  image_url: { url: "data:image/png;base64,aW1hZ2U=" }
                }
              ]
            }
          ]
        }
      });

    assert.equal(response.status, 200);
    assert.equal(calls.length, 2);
    assert.equal(calls[0].body.model, "google/gemma-4-26b-a4b-it");
    assert.equal(calls[0].body.messages[1].content[1].type, "image_url");
    assert.equal(calls[1].body.model, "deepseek/deepseek-v4-flash-alt");
    assert.equal(JSON.stringify(calls[1].body).includes("image_url"), false);
    assert.match(
      calls[1].body.messages[0].content,
      /red warning sign/
    );
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

test("AI helper endpoints remain rate limited", async () => {
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
  const app = createApp({ userModel, aiUsageModel });

  let lastResponse;
  for (let index = 0; index < 31; index += 1) {
    lastResponse = await request(app)
      .post("/api/ai/title")
      .set("Authorization", `Bearer ${createJwtToken(user)}`)
      .send({});
  }

  assert.equal(lastResponse.status, 429);
});
