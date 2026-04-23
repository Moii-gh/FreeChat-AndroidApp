const { Readable } = require("node:stream");
const { env } = require("../config/env");
const { selectChatModel, selectTitleModel, selectSummaryModel } = require("./entitlementService");

function createMessagePayload(role, content) {
  return { role, content };
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

async function proxyAiRequest({ user, currentMode, requestBody, res }) {
  const selection = selectChatModel({
    user,
    currentMode,
    requestBody
  });

  assertConfigured(selection.upstreamUrl, selection.model, "AI service is not configured on the server");

  const upstreamRequestBody = {
    ...(requestBody || {}),
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

module.exports = {
  proxyAiRequest,
  generateTitle,
  generateSummary
};
