const { Readable } = require("node:stream");
const { env } = require("../config/env");
const {
  PROVIDER_OPENAI,
  PROVIDER_VSEGPT,
  assertSelectionConfigured,
  hasCapability,
  requestHasImageInput,
  selectChatModel,
  selectImageFallbackModel,
  selectTitleModel,
  selectSummaryModel
} = require("./aiModelRegistry");

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

async function callAiJson({ selection, body }) {
  const response = await fetch(selection.upstreamUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${selection.apiKey}`
    },
    body: JSON.stringify(normalizeProviderRequestBody(body, selection)),
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

function normalizeProviderRequestBody(requestBody, selection) {
  const normalized = { ...(requestBody || {}) };

  if (
    selection?.provider === PROVIDER_OPENAI &&
    Object.hasOwn(normalized, "max_tokens")
  ) {
    if (!Object.hasOwn(normalized, "max_completion_tokens")) {
      normalized.max_completion_tokens = normalized.max_tokens;
    }
    delete normalized.max_tokens;
  }

  return normalized;
}

function contentHasImage(content) {
  if (Array.isArray(content)) {
    return content.some((part) => contentHasImage(part));
  }

  if (content && typeof content === "object") {
    if (content.type === "image_url") {
      return true;
    }

    return Object.values(content).some((value) => contentHasImage(value));
  }

  return false;
}

function collectImageParts(content, images = []) {
  if (Array.isArray(content)) {
    content.forEach((part) => collectImageParts(part, images));
    return images;
  }

  if (!content || typeof content !== "object") {
    return images;
  }

  if (content.type === "image_url") {
    const imageUrl = content.image_url?.url || content.image_url;
    if (typeof imageUrl === "string" && imageUrl.trim()) {
      images.push(imageUrl.trim());
    }
    return images;
  }

  Object.values(content).forEach((value) => collectImageParts(value, images));
  return images;
}

function extractTextFromContent(content) {
  if (typeof content === "string") {
    return content;
  }

  if (Array.isArray(content)) {
    return content
      .map((part) => extractTextFromContent(part))
      .filter(Boolean)
      .join("\n");
  }

  if (content && typeof content === "object") {
    if (content.type === "image_url") {
      return "";
    }

    if (content.type === "text" && typeof content.text === "string") {
      return content.text;
    }

    return Object.values(content)
      .map((value) => extractTextFromContent(value))
      .filter(Boolean)
      .join("\n");
  }

  return "";
}

function collectRequestImages(requestBody) {
  if (!requestBody || !Array.isArray(requestBody.messages)) {
    return [];
  }

  return requestBody.messages.flatMap((message) => collectImageParts(message.content));
}

function buildImageDescriptionMessages(requestBody) {
  const images = collectRequestImages(requestBody);
  const content = [
    {
      type: "text",
      text:
        "Extract visible text and describe the important visual details from the attached image(s). " +
        "Return concise plain text that can be inserted into another model prompt."
    },
    ...images.map((url) => ({
      type: "image_url",
      image_url: { url }
    }))
  ];

  return [
    createMessagePayload(
      "system",
      "You convert image inputs into a faithful text description for a text-only model."
    ),
    createMessagePayload("user", content)
  ];
}

function replaceImageInputsWithDescription(requestBody, imageDescription) {
  if (!requestBody || !Array.isArray(requestBody.messages)) {
    return requestBody;
  }

  return {
    ...requestBody,
    messages: requestBody.messages.map((message) => {
      const hasImage = contentHasImage(message.content);
      const text = extractTextFromContent(message.content).trim();
      if (!hasImage) {
        return {
          ...message,
          content: text || message.content
        };
      }

      return {
        ...message,
        content: [
          text,
          "Attached image description for the selected text-only model:",
          imageDescription
        ].filter(Boolean).join("\n\n")
      };
    })
  };
}

async function describeImagesForTextFallback(requestBody) {
  const fallbackSelection = selectImageFallbackModel();
  assertSelectionConfigured(fallbackSelection);

  const data = await callAiJson({
    selection: fallbackSelection,
    body: {
      model: fallbackSelection.model,
      stream: false,
      temperature: 0,
      max_tokens: 800,
      messages: buildImageDescriptionMessages(requestBody)
    }
  });

  const description = extractChoiceContent(data);
  if (!description) {
    const error = new Error("Image fallback did not return a text description");
    error.statusCode = 502;
    throw error;
  }

  return description;
}

async function prepareRequestBodyForSelection(requestBody, selection) {
  const modelSupportsImage =
    hasCapability(selection.modelDefinition, "vision") ||
    hasCapability(selection.modelDefinition, "imageInput");

  if (!requestHasImageInput(requestBody) || modelSupportsImage) {
    return requestBody;
  }

  const imageDescription = await describeImagesForTextFallback(requestBody);
  return replaceImageInputsWithDescription(requestBody, imageDescription);
}

async function proxyAiRequest({
  user,
  provider,
  modelKey,
  currentMode,
  adultMode = false,
  requestBody,
  res,
  onBeforeResponse
}) {
  const selection = selectChatModel({
    user,
    provider,
    modelKey,
    currentMode,
    requestBody,
    adultMode
  });

  assertSelectionConfigured(selection);

  const preparedRequestBody = await prepareRequestBodyForSelection(
    withAdultModePrompt(requestBody, adultMode),
    selection
  );

  const upstreamRequestBody = {
    ...normalizeProviderRequestBody(
      normalizeImageRequestBody(preparedRequestBody, selection.model),
      selection
    ),
    model: selection.model
  };

  const upstreamResponse = await fetch(selection.upstreamUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${selection.apiKey}`
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

async function generateTitle({ user, firstUserMessage, provider, modelKey }) {
  const selection = selectTitleModel({ user, provider, modelKey });
  assertSelectionConfigured(selection);

  const data = await callAiJson({
    selection,
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

async function generateSummary({ user, promptText, provider, modelKey }) {
  const selection = selectSummaryModel({ user, provider, modelKey });
  assertSelectionConfigured(selection);

  const data = await callAiJson({
    selection,
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
    provider: PROVIDER_VSEGPT,
    modelKey: "gemini3",
    currentMode: "search",
    requestBody: { messages: [] }
  });
  assertSelectionConfigured(selection);

  const data = await callAiJson({
    selection,
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
