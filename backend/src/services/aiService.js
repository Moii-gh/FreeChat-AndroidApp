const { Readable } = require("node:stream");
const { env } = require("../config/env");
const { selectChatModel, selectTitleModel, selectSummaryModel } = require("./entitlementService");

function createMessagePayload(role, content) {
  return { role, content };
}

const ADULT_MODE_SYSTEM_PROMPT =
  "18+ style mode is enabled. Reply in a direct adult conversational tone. " +
  "Use strong language and profanity naturally when it fits the user's tone, " +
  "but keep the answer useful and do not target protected groups or encourage harm.";

function withAdultModePrompt(requestBody, adultMode) {
  if (!adultMode || !requestBody || !Array.isArray(requestBody.messages)) {
    return requestBody;
  }

  return {
    ...requestBody,
    messages: [
      createMessagePayload("system", ADULT_MODE_SYSTEM_PROMPT),
      ...requestBody.messages
    ]
  };
}

async function callAiJson({ upstreamUrl, body }) {
  const response = await fetch(upstreamUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${env.aiApiKey}`
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(env.aiTimeoutMs)
  });

  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch (_error) {
      data = null;
    }
  }

  if (!response.ok) {
    const error = new Error(data?.error?.message || data?.message || "AI provider request failed");
    error.statusCode = response.status;
    error.payload = data || text;
    throw error;
  }

  return data;
}

function assertConfigured(upstreamUrl, model, message) {
  if (!env.aiApiKey || !upstreamUrl || !model) {
    const error = new Error(message);
    error.statusCode = 503;
    throw error;
  }
}

function gcd(left, right) {
  let a = Math.abs(left);
  let b = Math.abs(right);
  while (b) {
    const next = a % b;
    a = b;
    b = next;
  }
  return a || 1;
}

function aspectRatioFromSize(size) {
  if (typeof size !== "string") {
    return "";
  }

  const match = size.trim().match(/^(\d+)\s*x\s*(\d+)$/i);
  if (!match) {
    return "";
  }

  const width = Number(match[1]);
  const height = Number(match[2]);
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return "";
  }

  const divisor = gcd(width, height);
  return `${width / divisor}:${height / divisor}`;
}

function normalizeImageRequestBody(requestBody, model) {
  if (!model?.startsWith?.("img-flux/")) {
    return requestBody || {};
  }

  const normalized = { ...(requestBody || {}) };
  if (!normalized.aspect_ratio) {
    normalized.aspect_ratio = aspectRatioFromSize(normalized.size) || "1:1";
  }
  delete normalized.size;
  return normalized;
}

async function proxyAiRequest({ user, currentMode, adultMode = false, requestBody, res, onBeforeResponse }) {
  const selection = selectChatModel({
    user,
    currentMode,
    requestBody,
    adultMode
  });

  assertConfigured(selection.upstreamUrl, selection.model, "AI service is not configured on the server");

  const upstreamRequestBody = {
    ...normalizeImageRequestBody(withAdultModePrompt(requestBody, adultMode), selection.model),
    model: selection.model
  };

  const upstreamResponse = await fetch(selection.upstreamUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${env.aiApiKey}`
    },
    body: JSON.stringify(upstreamRequestBody),
    signal: AbortSignal.timeout(env.aiTimeoutMs)
  });

  if (onBeforeResponse) {
    await onBeforeResponse({
      upstreamResponse,
      selection
    });

    if (res.headersSent) {
      return;
    }
  }

  const contentType = upstreamResponse.headers.get("content-type") || "application/json; charset=utf-8";
  res.status(upstreamResponse.status);
  res.setHeader("Content-Type", contentType);

  if (!upstreamResponse.body) {
    res.end();
    return;
  }

  Readable.fromWeb(upstreamResponse.body).pipe(res);
}

function extractChoiceContent(data) {
  return data?.choices?.[0]?.message?.content?.trim?.() || "";
}

async function generateTitle({ user, firstUserMessage }) {
  const selection = selectTitleModel(user);
  assertConfigured(selection.upstreamUrl, selection.model, "AI title generation is not configured");

  const data = await callAiJson({
    upstreamUrl: selection.upstreamUrl,
    body: {
      model: selection.model,
      stream: false,
      max_tokens: 40,
      temperature: 0.7,
      messages: [
        createMessagePayload(
          "system",
          "Generate one short chat title without quotes or explanation."
        ),
        createMessagePayload("user", firstUserMessage)
      ]
    }
  });

  return extractChoiceContent(data).replace(/^"+|"+$/g, "").replace(/\.$/, "");
}

async function generateSummary({ user, promptText }) {
  const selection = selectSummaryModel(user);
  assertConfigured(selection.upstreamUrl, selection.model, "AI summarization is not configured");

  const data = await callAiJson({
    upstreamUrl: selection.upstreamUrl,
    body: {
      model: selection.model,
      stream: false,
      max_tokens: 600,
      messages: [
        createMessagePayload("system", "Refresh the running summary with the new information."),
        createMessagePayload("user", promptText)
      ]
    }
  });

  return extractChoiceContent(data);
}

function parseJsonArrayText(value) {
  const text = String(value || "").trim();
  if (!text) {
    return [];
  }

  const fencedMatch = text.match(/```(?:json)?\s*([\s\S]*?)```/i);
  const candidate = fencedMatch ? fencedMatch[1].trim() : text;
  const arrayText = candidate.startsWith("[")
    ? candidate
    : candidate.slice(candidate.indexOf("["), candidate.lastIndexOf("]") + 1);

  try {
    const parsed = JSON.parse(arrayText);
    return Array.isArray(parsed) ? parsed : [];
  } catch (_error) {
    return [];
  }
}

async function generateTrendingQueries({ user, locale = "ru" }) {
  const selection = selectChatModel({
    user,
    currentMode: "search",
    requestBody: { messages: [] }
  });
  assertConfigured(selection.upstreamUrl, selection.model, "AI trending search is not configured");

  const data = await callAiJson({
    upstreamUrl: selection.upstreamUrl,
    body: {
      model: selection.model,
      stream: false,
      temperature: 0.2,
      max_tokens: 300,
      messages: [
        createMessagePayload(
          "system",
          "You are an online search assistant. Find current popular news/search topics right now. Return only a JSON array of 4 short search queries, no prose."
        ),
        createMessagePayload(
          "user",
          `Locale: ${locale}. Prefer concise queries users can tap to search.`
        )
      ]
    }
  });

  return parseJsonArrayText(extractChoiceContent(data))
    .map((item) => String(item || "").trim())
    .filter(Boolean)
    .slice(0, 4);
}

module.exports = {
  proxyAiRequest,
  generateTitle,
  generateSummary,
  generateTrendingQueries
};
