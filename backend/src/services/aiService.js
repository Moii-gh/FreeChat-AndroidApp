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

function debugAiLog(event, details = {}) {
  if (env.isTest) {
    return;
  }

  const detailText = Object.entries(details)
    .map(([key, value]) => `${key}=${value}`)
    .join(" ");
  console.debug(`[ai/${event}] ${detailText}`);
}

const ADULT_MODE_SYSTEM_PROMPT =
  "18+ style mode is enabled. Reply in a direct adult conversational tone. " +
  "Use strong language and profanity naturally when it fits the user's tone, " +
  "but keep the answer useful and do not target protected groups or encourage harm.";

const SERVER_FEATURE_NOT_CONNECTED_MESSAGE = "Эта функция пока не подключена на сервере.";
const OPENAI_SEARCH_MODES = new Set(["search", "shopping", "web_search"]);
const OPENAI_FILE_SEARCH_MODES = new Set(["file_search"]);
const OPENAI_IMAGE_EDIT_MODES = new Set(["edit_image", "image_edit"]);

const CHAT_COMPLETION_BACKEND_ONLY_FIELDS = [
  "fileSearchFiles",
  "file_search_files",
  "fileSearch",
  "file_search",
  "images",
  "image",
  "imageEdit",
  "image_edit",
  "mask",
  "tools",
  "tool_choice",
  "parallel_tool_calls",
  "web_search_options"
];

const OPENAI_CAPABILITY_PROMPT_MARKER = "Server capability context for this chat:";

const CAPABILITY_QUESTION_PATTERN =
  /(what can you do|do you have|can you|are you able|support|tools?|skills?|что ты умеешь|что можешь|какие у тебя|есть ли у тебя|ты умеешь|можешь ли|поддерживаешь|инструменты|скилл|скилы|тулзы)/iu;
const GENERAL_CAPABILITY_QUESTION_PATTERN =
  /(what can you do|what are your capabilities|что ты умеешь|что можешь|какие у тебя возможности|какие у тебя инструменты|какие у тебя скилл|какие у тебя скил)/iu;
const CAPABILITY_SUBJECT_PATTERN =
  /(web\s*search|search|browse|internet|online|file|document|image|picture|photo|vision|generate|draw|edit|поиск|интернет|сети|файл|документ|картин|изображ|фото|генер|нарис|редакт|измен)/iu;
const EXPLICIT_WEB_SEARCH_PATTERN =
  /(web\s*search|search the web|search online|browse the web|look up online|google it|найди в интернете|поищи в интернете|поиск в интернете|поиск в сети|посмотри в интернете|загугли|погугли|поищи в сети|найди актуальн|найди свеж)/iu;
const CURRENT_INFO_PATTERN =
  /(latest|current|today|right now|up to date|recent news|breaking news|weather|forecast|price|stock price|exchange rate|schedule|последн|актуальн|свеж|сегодня|сейчас|на данный момент|новост|погода|прогноз|цена|курс|расписан)/iu;
const IMAGE_GENERATION_PATTERN =
  /(generate|create|draw|make|render|сгенерируй|создай|нарисуй|изобрази|сделай|сгенерировать|создать|нарисовать).{0,120}(image|picture|photo|illustration|art|logo|avatar|wallpaper|картин|изображ|фото|иллюстрац|арт|логотип|аватар|обои)/iu;
const IMAGE_GENERATION_OBJECT_PATTERN =
  /(image|picture|photo|illustration|art|logo|avatar|wallpaper|картин|изображ|фото|иллюстрац|арт|логотип|аватар|обои).{0,120}(generate|create|draw|make|render|сгенерируй|создай|нарисуй|изобрази|сделай|сгенерировать|создать|нарисовать)/iu;
const IMAGE_EDIT_PATTERN =
  /(edit|change|modify|remove|delete|replace|add|enhance|retouch|upscale|crop|make .{0,40} brighter|background|отредактируй|измени|исправь|убери|удали|замени|добавь|улучши|ретуш|обрежь|сделай .{0,40} ярче|фон)/iu;

function createHttpError(statusCode, message) {
  const error = new Error(message);
  error.statusCode = statusCode;
  error.expose = true;
  return error;
}

function normalizeMode(currentMode) {
  return String(currentMode || "").trim();
}

function assertCapability(selection, capability) {
  if (!hasCapability(selection.modelDefinition, capability)) {
    throw createHttpError(501, SERVER_FEATURE_NOT_CONNECTED_MESSAGE);
  }
}

function assertProviderUrl(url, providerName, featureName) {
  if (!url) {
    throw createHttpError(503, `${providerName} ${featureName} endpoint is not configured`);
  }
}

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

async function readJsonResponse(response) {
  const text = await response.text();
  if (!text) {
    return null;
  }

  try {
    return JSON.parse(text);
  } catch (_error) {
    return null;
  }
}

async function fetchOpenAiJson({ selection, url, body, headers = {} }) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${selection.apiKey}`,
      ...headers
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(env.aiTimeoutMs)
  });

  const data = await readJsonResponse(response);
  if (!response.ok) {
    const error = new Error(data?.error?.message || data?.message || "OpenAI request failed");
    error.statusCode = response.status;
    error.payload = data;
    throw error;
  }

  return { response, data };
}

async function fetchOpenAiMultipart({ selection, url, formData }) {
  const response = await fetch(url, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${selection.apiKey}`
    },
    body: formData,
    signal: AbortSignal.timeout(env.aiTimeoutMs)
  });

  const data = await readJsonResponse(response);
  if (!response.ok) {
    const error = new Error(data?.error?.message || data?.message || "OpenAI request failed");
    error.statusCode = response.status;
    error.payload = data;
    throw error;
  }

  return { response, data };
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
  CHAT_COMPLETION_BACKEND_ONLY_FIELDS.forEach((field) => {
    delete normalized[field];
  });
  return normalized;
}

function normalizeProviderRequestBody(requestBody, selection) {
  const normalized = { ...(requestBody || {}) };

  CHAT_COMPLETION_BACKEND_ONLY_FIELDS.forEach((field) => {
    delete normalized[field];
  });

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

function requestTools(requestBody) {
  return Array.isArray(requestBody?.tools) ? requestBody.tools : [];
}

function requestHasWebSearchTool(requestBody) {
  return (
    Object.hasOwn(requestBody || {}, "web_search_options") ||
    requestTools(requestBody).some((tool) => {
      const type = String(tool?.type || "").trim();
      return type === "web_search" || type === "web_search_preview";
    })
  );
}

function requestHasFileSearchTool(requestBody) {
  return (
    Boolean(requestBody?.fileSearch || requestBody?.file_search) ||
    requestTools(requestBody).some((tool) => String(tool?.type || "").trim() === "file_search")
  );
}

function asArray(value) {
  return Array.isArray(value) ? value : value ? [value] : [];
}

function extractVectorStoreIds(requestBody) {
  const fileSearch = requestBody?.fileSearch || requestBody?.file_search || {};
  return asArray(fileSearch.vectorStoreIds || fileSearch.vector_store_ids)
    .map((value) => String(value || "").trim())
    .filter(Boolean)
    .slice(0, 8);
}

function extractFileSearchFiles(requestBody) {
  const fileSearch = requestBody?.fileSearch || requestBody?.file_search || {};
  return [
    ...asArray(requestBody?.fileSearchFiles),
    ...asArray(requestBody?.file_search_files),
    ...asArray(fileSearch.files)
  ].filter((file) => file && typeof file === "object");
}

function requestHasFileSearchInput(requestBody) {
  return extractVectorStoreIds(requestBody).length > 0 || extractFileSearchFiles(requestBody).length > 0;
}

function extractImageEditReferences(requestBody) {
  return [
    ...asArray(requestBody?.images),
    ...asArray(requestBody?.image),
    ...asArray(requestBody?.imageEdit?.images),
    ...asArray(requestBody?.image_edit?.images),
    ...collectRequestImages(requestBody)
  ].filter(Boolean);
}

function requestHasImageEditInput(requestBody) {
  return extractImageEditReferences(requestBody).length > 0;
}

function determineOpenAiRoute({ currentMode, requestBody }) {
  const mode = normalizeMode(currentMode);
  const promptText = normalizeIntentText(extractPromptText(requestBody));
  const asksAboutCapabilities = requestAsksAboutCapabilities(promptText);
  const hasImageInput = requestHasImageInput(requestBody);

  if (OPENAI_IMAGE_EDIT_MODES.has(mode) || requestBody?.imageEdit || requestBody?.image_edit) {
    return "imageEdit";
  }

  if (mode === "create_image") {
    return requestHasImageEditInput(requestBody) ? "imageEdit" : "imageGeneration";
  }

  if (!asksAboutCapabilities && hasImageInput && requestHasImageEditIntent(promptText)) {
    return "imageEdit";
  }

  if (!asksAboutCapabilities && requestHasImageGenerationIntent(promptText)) {
    return hasImageInput ? "imageEdit" : "imageGeneration";
  }

  if (OPENAI_FILE_SEARCH_MODES.has(mode) || requestHasFileSearchTool(requestBody) || requestHasFileSearchInput(requestBody)) {
    return "fileSearch";
  }

  if (
    OPENAI_SEARCH_MODES.has(mode) ||
    requestHasWebSearchTool(requestBody) ||
    (!asksAboutCapabilities && requestHasWebSearchIntent(promptText))
  ) {
    return "webSearch";
  }

  if (hasImageInput) {
    return "vision";
  }

  return "text";
}

function makeChatCompletionChunk(content) {
  return {
    choices: [
      {
        delta: {
          content
        }
      }
    ]
  };
}

function sendSyntheticChatStream(res, content) {
  res.status(200);
  res.setHeader("Content-Type", "text/event-stream; charset=utf-8");
  res.write(`data: ${JSON.stringify(makeChatCompletionChunk(content || ""))}\n\n`);
  res.write("data: [DONE]\n\n");
  res.end();
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

function extractPromptText(requestBody) {
  if (typeof requestBody?.prompt === "string" && requestBody.prompt.trim()) {
    return requestBody.prompt.trim();
  }

  const lastUserMessage = Array.isArray(requestBody?.messages)
    ? [...requestBody.messages].reverse().find((message) => message?.role === "user")
    : null;

  return extractTextFromContent(lastUserMessage?.content || "").trim();
}

function normalizeIntentText(value) {
  return String(value || "").toLowerCase().replace(/\s+/g, " ").trim();
}

function requestAsksAboutCapabilities(text) {
  if (!text) {
    return false;
  }

  if (GENERAL_CAPABILITY_QUESTION_PATTERN.test(text)) {
    return true;
  }

  return CAPABILITY_QUESTION_PATTERN.test(text) && CAPABILITY_SUBJECT_PATTERN.test(text);
}

function requestHasWebSearchIntent(text) {
  if (!text) {
    return false;
  }

  return EXPLICIT_WEB_SEARCH_PATTERN.test(text) || CURRENT_INFO_PATTERN.test(text);
}

function requestHasImageGenerationIntent(text) {
  if (!text) {
    return false;
  }

  return IMAGE_GENERATION_PATTERN.test(text) || IMAGE_GENERATION_OBJECT_PATTERN.test(text);
}

function requestHasImageEditIntent(text) {
  if (!text) {
    return false;
  }

  return IMAGE_EDIT_PATTERN.test(text);
}

function buildOpenAiCapabilityPrompt(selection) {
  const capabilities = new Set(selection?.modelDefinition?.capabilities || []);
  const lines = [
    OPENAI_CAPABILITY_PROMPT_MARKER,
    "The server can route this selected model through the app capabilities listed below. Do not claim these abilities are unavailable when they are listed here."
  ];

  if (capabilities.has("text")) {
    lines.push("- Normal text chat is available.");
  }
  if (capabilities.has("vision")) {
    lines.push("- Attached images can be understood and discussed.");
  }
  if (capabilities.has("webSearch")) {
    lines.push("- Web search is available when the user explicitly asks for search or needs current information.");
  }
  if (capabilities.has("fileSearch")) {
    lines.push("- Uploaded files can be searched when file content is attached to the request.");
  }
  if (capabilities.has("imageGeneration")) {
    lines.push("- Images can be generated from a prompt.");
  }
  if (capabilities.has("imageEdit")) {
    lines.push("- Images can be edited when the user provides a source image and an edit instruction.");
  }

  lines.push(
    "When the user asks what you can do, describe these abilities naturally in the user's language.",
    "Do not expose API keys, Authorization headers, endpoint URLs, provider internals, raw model IDs, or internal capability labels.",
    "If a requested ability requires an uploaded file or image and none is present, ask the user to attach the needed file or image."
  );

  return lines.join("\n");
}

function hasOpenAiCapabilityPrompt(requestBody) {
  if (!Array.isArray(requestBody?.messages)) {
    return false;
  }

  return requestBody.messages.some((message) => {
    const role = String(message?.role || "").trim();
    return (
      (role === "system" || role === "developer") &&
      extractTextFromContent(message.content).includes(OPENAI_CAPABILITY_PROMPT_MARKER)
    );
  });
}

function withOpenAiCapabilityPrompt(requestBody, selection) {
  const prompt = buildOpenAiCapabilityPrompt(selection);
  if (!prompt || hasOpenAiCapabilityPrompt(requestBody)) {
    return requestBody;
  }

  if (Array.isArray(requestBody?.messages)) {
    return {
      ...requestBody,
      messages: [
        createMessagePayload("system", prompt),
        ...requestBody.messages
      ]
    };
  }

  const promptText = extractPromptText(requestBody);
  if (!promptText) {
    return requestBody;
  }

  const { prompt: _prompt, ...rest } = requestBody || {};
  return {
    ...rest,
    messages: [
      createMessagePayload("system", prompt),
      createMessagePayload("user", promptText)
    ]
  };
}

function convertContentToResponsesInput(content) {
  if (typeof content === "string") {
    return content;
  }

  if (!Array.isArray(content)) {
    const text = extractTextFromContent(content).trim();
    return text || String(content || "");
  }

  return content
    .map((part) => {
      if (typeof part === "string") {
        return {
          type: "input_text",
          text: part
        };
      }

      if (!part || typeof part !== "object") {
        return null;
      }

      if (part.type === "text") {
        return {
          type: "input_text",
          text: String(part.text || "")
        };
      }

      if (part.type === "image_url") {
        const imageUrl = part.image_url?.url || part.image_url;
        if (!imageUrl) {
          return null;
        }
        return {
          type: "input_image",
          image_url: imageUrl
        };
      }

      const text = extractTextFromContent(part).trim();
      return text
        ? {
            type: "input_text",
            text
          }
        : null;
    })
    .filter(Boolean);
}

function buildResponsesInputAndInstructions(requestBody) {
  const instructions = [];
  const input = [];

  if (!Array.isArray(requestBody?.messages)) {
    return {
      instructions: "",
      input: extractPromptText(requestBody)
    };
  }

  requestBody.messages.forEach((message) => {
    const role = String(message?.role || "user").trim();
    if (role === "system" || role === "developer") {
      const text = extractTextFromContent(message.content).trim();
      if (text) {
        instructions.push(text);
      }
      return;
    }

    input.push({
      role: role === "assistant" ? "assistant" : "user",
      content: convertContentToResponsesInput(message.content)
    });
  });

  return {
    instructions: instructions.join("\n\n"),
    input: input.length > 0 ? input : extractPromptText(requestBody)
  };
}

function buildOpenAiResponsesBody({ selection, requestBody, tools, include }) {
  const { instructions, input } = buildResponsesInputAndInstructions(requestBody);
  const body = {
    model: selection.model,
    input,
    tools
  };

  if (instructions) {
    body.instructions = instructions;
  }

  if (include?.length) {
    body.include = include;
  }

  const maxTokens =
    requestBody?.max_output_tokens ||
    requestBody?.max_completion_tokens ||
    requestBody?.max_tokens;
  if (maxTokens !== undefined) {
    body.max_output_tokens = maxTokens;
  }

  if (requestBody?.temperature !== undefined) {
    body.temperature = requestBody.temperature;
  }

  return body;
}

function collectOutputTextParts(value, parts = []) {
  if (!value || typeof value !== "object") {
    return parts;
  }

  if (Array.isArray(value)) {
    value.forEach((item) => collectOutputTextParts(item, parts));
    return parts;
  }

  if (value.type === "output_text" && typeof value.text === "string") {
    parts.push(value.text);
    return parts;
  }

  if (value.type === "message" && Array.isArray(value.content)) {
    collectOutputTextParts(value.content, parts);
    return parts;
  }

  Object.values(value).forEach((child) => collectOutputTextParts(child, parts));
  return parts;
}

function collectUrlCitations(value, citations = []) {
  if (!value || typeof value !== "object") {
    return citations;
  }

  if (Array.isArray(value)) {
    value.forEach((item) => collectUrlCitations(item, citations));
    return citations;
  }

  if (value.type === "url_citation" && value.url) {
    citations.push({
      title: String(value.title || value.url).trim(),
      url: String(value.url).trim()
    });
  }

  Object.values(value).forEach((child) => collectUrlCitations(child, citations));
  return citations;
}

function appendCitationsIfMissing(text, data) {
  const citations = collectUrlCitations(data)
    .filter((citation) => citation.url)
    .filter((citation, index, list) =>
      list.findIndex((candidate) => candidate.url === citation.url) === index
    )
    .slice(0, 8);

  if (citations.length === 0 || citations.some((citation) => text.includes(citation.url))) {
    return text;
  }

  const sourceLines = citations.map((citation) => `- [${citation.title || citation.url}](${citation.url})`);
  return `${text.trim()}\n\nИсточники:\n${sourceLines.join("\n")}`.trim();
}

function extractResponsesText(data) {
  const sdkText = typeof data?.output_text === "string" ? data.output_text : "";
  const text = sdkText || collectOutputTextParts(data?.output || []).join("");
  return appendCitationsIfMissing(text.trim(), data);
}

function normalizeImageReference(reference) {
  if (typeof reference === "string") {
    return reference.startsWith("file-")
      ? { file_id: reference }
      : { image_url: reference };
  }

  if (!reference || typeof reference !== "object") {
    return null;
  }

  const fileId = reference.file_id || reference.fileId;
  if (typeof fileId === "string" && fileId.trim()) {
    return { file_id: fileId.trim() };
  }

  const imageUrl =
    reference.image_url?.url ||
    reference.image_url ||
    reference.imageUrl ||
    reference.url ||
    reference.dataUrl;
  if (typeof imageUrl === "string" && imageUrl.trim()) {
    return { image_url: imageUrl.trim() };
  }

  const base64 = reference.base64 || reference.b64_json || reference.data;
  const mimeType = reference.mimeType || reference.mime_type || "image/png";
  if (typeof base64 === "string" && base64.trim()) {
    const compact = base64.startsWith("data:")
      ? base64.trim()
      : `data:${mimeType};base64,${base64.replace(/\s+/g, "")}`;
    return { image_url: compact };
  }

  return null;
}

function buildOpenAiImageBody({ selection, requestBody, edit = false }) {
  const prompt = extractPromptText(requestBody);
  if (!prompt) {
    throw createHttpError(400, "Image prompt is required");
  }

  const body = {
    model: selection.providerSettings.imageModel,
    prompt,
    n: Number.isInteger(requestBody?.n) ? requestBody.n : 1
  };

  [
    "size",
    "quality",
    "background",
    "moderation",
    "output_format",
    "output_compression",
    "input_fidelity"
  ].forEach((field) => {
    if (requestBody?.[field] !== undefined) {
      body[field] = requestBody[field];
    }
  });

  if (edit) {
    const images = extractImageEditReferences(requestBody)
      .map(normalizeImageReference)
      .filter(Boolean);

    if (images.length === 0) {
      throw createHttpError(400, "Image edit requires a source image");
    }

    body.images = images.slice(0, 16);
    const mask = normalizeImageReference(requestBody?.mask);
    if (mask) {
      body.mask = mask;
    }
  }

  return body;
}

function parseDataUrl(value) {
  const match = String(value || "").match(/^data:([^;,]+)?;base64,(.+)$/s);
  if (!match) {
    return null;
  }

  return {
    mimeType: match[1] || "application/octet-stream",
    base64: match[2]
  };
}

function decodeFileSearchFile(file) {
  const dataUrl = file.dataUrl || file.data_url || file.url;
  const parsedDataUrl = parseDataUrl(dataUrl);
  const base64 = parsedDataUrl?.base64 || file.base64 || file.data || file.content;
  if (typeof base64 !== "string" || !base64.trim()) {
    throw createHttpError(400, "File search requires uploaded file content");
  }

  const buffer = Buffer.from(base64.replace(/\s+/g, ""), "base64");
  if (buffer.length === 0) {
    throw createHttpError(400, "File search uploaded file is empty");
  }

  return {
    buffer,
    mimeType:
      parsedDataUrl?.mimeType ||
      file.mimeType ||
      file.mime_type ||
      "application/octet-stream",
    fileName:
      String(file.fileName || file.filename || file.name || "attachment.txt")
        .replace(/[\\/:*?"<>|]+/g, "_")
        .slice(0, 160) || "attachment.txt"
  };
}

async function uploadOpenAiFile(selection, file) {
  assertProviderUrl(selection.providerSettings.filesUrl, "OpenAI", "files");
  const decoded = decodeFileSearchFile(file);
  const formData = new FormData();
  formData.append("purpose", "assistants");
  formData.append(
    "file",
    new Blob([decoded.buffer], { type: decoded.mimeType }),
    decoded.fileName
  );

  const { data } = await fetchOpenAiMultipart({
    selection,
    url: selection.providerSettings.filesUrl,
    formData
  });

  if (!data?.id) {
    throw createHttpError(502, "OpenAI file upload did not return a file id");
  }

  return data.id;
}

async function createOpenAiVectorStore(selection, fileIds) {
  assertProviderUrl(selection.providerSettings.vectorStoresUrl, "OpenAI", "vector stores");
  const { data } = await fetchOpenAiJson({
    selection,
    url: selection.providerSettings.vectorStoresUrl,
    body: {
      name: "chatapp-request-files",
      file_ids: fileIds,
      expires_after: {
        anchor: "last_active_at",
        days: 1
      }
    }
  });

  if (!data?.id) {
    throw createHttpError(502, "OpenAI vector store creation did not return an id");
  }

  return data;
}

async function getOpenAiJson(selection, url) {
  const response = await fetch(url, {
    method: "GET",
    headers: {
      Authorization: `Bearer ${selection.apiKey}`
    },
    signal: AbortSignal.timeout(env.aiTimeoutMs)
  });

  const data = await readJsonResponse(response);
  if (!response.ok) {
    const error = new Error(data?.error?.message || data?.message || "OpenAI request failed");
    error.statusCode = response.status;
    error.payload = data;
    throw error;
  }

  return data;
}

async function waitForOpenAiVectorStore(selection, vectorStoreId) {
  const baseUrl = selection.providerSettings.vectorStoresUrl.replace(/\/+$/, "");
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const data = await getOpenAiJson(
      selection,
      `${baseUrl}/${encodeURIComponent(vectorStoreId)}`
    );
    const inProgress = Number(data?.file_counts?.in_progress || 0);
    if (inProgress <= 0) {
      return data;
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }

  throw createHttpError(504, "OpenAI file search index was not ready in time");
}

async function createVectorStoreForRequestFiles(selection, requestBody) {
  const files = extractFileSearchFiles(requestBody);
  if (files.length === 0) {
    return null;
  }

  const fileIds = [];
  for (const file of files.slice(0, 8)) {
    fileIds.push(await uploadOpenAiFile(selection, file));
  }

  const vectorStore = await createOpenAiVectorStore(selection, fileIds);
  if (Number(vectorStore?.file_counts?.in_progress || 0) > 0) {
    await waitForOpenAiVectorStore(selection, vectorStore.id);
  }

  return vectorStore.id;
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

async function maybeConsumeQuota({ upstreamResponse, selection, res, onBeforeResponse }) {
  if (!onBeforeResponse) {
    return false;
  }

  await onBeforeResponse({
    upstreamResponse,
    selection
  });

  return res.headersSent;
}

async function proxyOpenAiResponsesRequest({
  selection,
  requestBody,
  res,
  onBeforeResponse,
  tools,
  include
}) {
  assertProviderUrl(selection.providerSettings.responsesUrl, "OpenAI", "responses");
  const { response, data } = await fetchOpenAiJson({
    selection,
    url: selection.providerSettings.responsesUrl,
    body: buildOpenAiResponsesBody({
      selection,
      requestBody,
      tools,
      include
    })
  });

  if (await maybeConsumeQuota({ upstreamResponse: response, selection, res, onBeforeResponse })) {
    return;
  }

  const content = extractResponsesText(data);
  sendSyntheticChatStream(res, content);
}

async function proxyOpenAiWebSearch({ selection, requestBody, res, onBeforeResponse }) {
  assertCapability(selection, "webSearch");
  await proxyOpenAiResponsesRequest({
    selection,
    requestBody,
    res,
    onBeforeResponse,
    tools: [
      {
        type: "web_search"
      }
    ]
  });
}

async function proxyOpenAiFileSearch({ selection, requestBody, res, onBeforeResponse }) {
  assertCapability(selection, "fileSearch");

  const vectorStoreIds = extractVectorStoreIds(requestBody);
  const createdVectorStoreId = await createVectorStoreForRequestFiles(selection, requestBody);
  if (createdVectorStoreId) {
    vectorStoreIds.push(createdVectorStoreId);
  }

  if (vectorStoreIds.length === 0) {
    throw createHttpError(400, "File search requires an uploaded file or vector store");
  }

  await proxyOpenAiResponsesRequest({
    selection,
    requestBody,
    res,
    onBeforeResponse,
    tools: [
      {
        type: "file_search",
        vector_store_ids: vectorStoreIds.slice(0, 8),
        max_num_results: 20
      }
    ],
    include: ["file_search_call.results"]
  });
}

async function proxyOpenAiImageRequest({
  selection,
  requestBody,
  res,
  onBeforeResponse,
  edit
}) {
  assertCapability(selection, edit ? "imageEdit" : "imageGeneration");
  assertProviderUrl(
    edit ? selection.providerSettings.imageEditUrl : selection.providerSettings.imageUrl,
    "OpenAI",
    edit ? "image edit" : "image generation"
  );

  if (!selection.providerSettings.imageModel) {
    throw createHttpError(503, "OpenAI image model is not configured");
  }

  const { response, data } = await fetchOpenAiJson({
    selection,
    url: edit ? selection.providerSettings.imageEditUrl : selection.providerSettings.imageUrl,
    body: buildOpenAiImageBody({
      selection,
      requestBody,
      edit
    })
  });

  if (await maybeConsumeQuota({ upstreamResponse: response, selection, res, onBeforeResponse })) {
    return;
  }

  res.status(200).json(data);
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

  const requestWithSystemPrompts = withAdultModePrompt(requestBody, adultMode);

  if (selection.provider === PROVIDER_OPENAI) {
    const route = determineOpenAiRoute({
      currentMode,
      requestBody: requestWithSystemPrompts
    });

    if (route === "imageGeneration" || route === "imageEdit") {
      await proxyOpenAiImageRequest({
        selection,
        requestBody: requestWithSystemPrompts,
        res,
        onBeforeResponse,
        edit: route === "imageEdit"
      });
      return;
    }

    const openAiAwareRequestBody = withOpenAiCapabilityPrompt(
      requestWithSystemPrompts,
      selection
    );
    const preparedOpenAiRequestBody = await prepareRequestBodyForSelection(
      openAiAwareRequestBody,
      selection
    );

    if (route === "webSearch") {
      await proxyOpenAiWebSearch({
        selection,
        requestBody: preparedOpenAiRequestBody,
        res,
        onBeforeResponse
      });
      return;
    }

    if (route === "fileSearch") {
      await proxyOpenAiFileSearch({
        selection,
        requestBody: preparedOpenAiRequestBody,
        res,
        onBeforeResponse
      });
      return;
    }

    if (route === "vision") {
      assertCapability(selection, "vision");
    } else {
      assertCapability(selection, "text");
    }

    const upstreamRequestBody = {
      ...normalizeProviderRequestBody(preparedOpenAiRequestBody, selection),
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

    if (await maybeConsumeQuota({ upstreamResponse, selection, res, onBeforeResponse })) {
      return;
    }

    const contentType = upstreamResponse.headers.get("content-type") || "application/json; charset=utf-8";
    res.status(upstreamResponse.status);
    res.setHeader("Content-Type", contentType);

    if (!upstreamResponse.body) {
      res.end();
      return;
    }

    Readable.fromWeb(upstreamResponse.body).pipe(res);
    return;
  }

  const nonOpenAiMode = normalizeMode(currentMode);
  if (
    OPENAI_IMAGE_EDIT_MODES.has(nonOpenAiMode) ||
    requestWithSystemPrompts?.imageEdit ||
    requestWithSystemPrompts?.image_edit ||
    (nonOpenAiMode === "create_image" && requestHasImageEditInput(requestWithSystemPrompts))
  ) {
    assertCapability(selection, "imageEdit");
  }

  if (OPENAI_FILE_SEARCH_MODES.has(nonOpenAiMode) || requestHasFileSearchTool(requestWithSystemPrompts)) {
    assertCapability(selection, "fileSearch");
  }

  if (requestHasWebSearchTool(requestWithSystemPrompts)) {
    assertCapability(selection, "webSearch");
  }

  if (nonOpenAiMode === "create_image") {
    assertCapability(selection, "imageGeneration");
  }

  if (OPENAI_SEARCH_MODES.has(nonOpenAiMode)) {
    assertCapability(selection, "webSearch");
  }

  const preparedRequestBody = await prepareRequestBodyForSelection(
    requestWithSystemPrompts,
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

  if (await maybeConsumeQuota({ upstreamResponse, selection, res, onBeforeResponse })) {
    return;
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

function trimPromptPart(value, maxLength = 4000) {
  const text = String(value || "").trim();
  return text.length > maxLength ? `${text.slice(0, maxLength).trim()}...` : text;
}

function buildTitlePrompt({ firstUserMessage, firstAssistantMessage }) {
  const parts = [
    "Сгенерируй короткое название для этого чата на основе первого сообщения пользователя и ответа ассистента.",
    "Верни только название, 2-5 слов, без кавычек и без точки.",
    "Название должно быть на языке пользователя.",
    "Не пиши \"Новый чат\", если можно определить тему.",
    "",
    "Первое сообщение пользователя:",
    trimPromptPart(firstUserMessage, 4000)
  ];

  const assistantText = trimPromptPart(firstAssistantMessage, 4000);
  if (assistantText) {
    parts.push("", "Первый ответ ассистента:", assistantText);
  }

  return parts.join("\n");
}

function sanitizeGeneratedTitle(value) {
  const firstLine = String(value || "")
    .trim()
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)[0] || "";

  return firstLine
    .replace(/^(title|название)\s*[:：-]\s*/i, "")
    .replace(/^["'«“„]+|["'»”]+$/g, "")
    .replace(/\s+/g, " ")
    .replace(/[.。]+$/g, "")
    .trim()
    .slice(0, 80)
    .trim();
}

async function generateTitle({ user, firstUserMessage, firstAssistantMessage, provider, modelKey }) {
  debugAiLog("title:start", {
    provider: provider || "default",
    modelKey: modelKey || "default",
    hasAssistantMessage: Boolean(firstAssistantMessage)
  });

  const selection = selectTitleModel({ user, provider, modelKey });
  debugAiLog("title:selected", {
    provider: selection.provider,
    modelKey: selection.modelKey,
    fallback: selection.fallbackReason || "none"
  });
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
          "You generate concise chat titles. Return only the title, no explanation."
        ),
        createMessagePayload("user", buildTitlePrompt({
          firstUserMessage,
          firstAssistantMessage
        }))
      ]
    }
  });

  const title = sanitizeGeneratedTitle(extractChoiceContent(data));
  debugAiLog("title:result", {
    hasTitle: Boolean(title),
    length: title.length
  });
  return title;
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
