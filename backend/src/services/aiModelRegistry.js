const { env } = require("../config/env");

const PROVIDER_OPENAI = "openai";
const PROVIDER_VSEGPT = "vsegpt";
const DEFAULT_PROVIDER = PROVIDER_VSEGPT;
const VSEGPT_DEEPSEEK_MODEL_KEY = "deepseek";
const VSEGPT_DEEPSEEK_MODEL_ID = "deepseek/deepseek-chat";

const DEFAULT_MODEL_BY_PROVIDER = {
  [PROVIDER_OPENAI]: "gpt54",
  [PROVIDER_VSEGPT]: "gemini3"
};

function createModel({
  provider,
  modelKey,
  modelId,
  displayName,
  capabilities,
  configError = ""
}) {
  return {
    provider,
    modelKey,
    modelId,
    displayName,
    capabilities,
    configError
  };
}

function providerConfig(provider) {
  if (provider === PROVIDER_OPENAI) {
    return {
      provider,
      apiKey: env.openAiApiKey,
      chatUrl: env.openAiChatUrl,
      responsesUrl: env.openAiResponsesUrl,
      imageUrl: env.openAiImageUrl,
      imageEditUrl: env.openAiImageEditUrl,
      filesUrl: env.openAiFilesUrl,
      vectorStoresUrl: env.openAiVectorStoresUrl,
      imageModel: env.openAiImageModel,
      missingKeyMessage: "OpenAI API key is not configured on the server."
    };
  }

  if (provider === PROVIDER_VSEGPT) {
    return {
      provider,
      apiKey: env.vsegptApiKey,
      chatUrl: env.vsegptChatUrl,
      responsesUrl: "",
      imageUrl: env.vsegptImageUrl,
      imageEditUrl: env.vsegptImageEditUrl,
      filesUrl: "",
      vectorStoresUrl: "",
      imageModel: env.aiImageModel || "img-openai/gpt-image-2",
      imageEditModel: env.aiImageEditModel || "img2img-openai/gpt-image-2-edit-multi",
      adultTextModel: env.aiAdultTextModel,
      searchModel: env.aiSearchModel,
      titleModel: env.aiTitleModel,
      summaryModel: env.aiSummaryModel,
      missingKeyMessage: "VseGPT API key is not configured on the server"
    };
  }

  return null;
}

function allModelDefinitions() {
  return [
    createModel({
      provider: PROVIDER_OPENAI,
      modelKey: "gpt54",
      modelId: env.openAiGpt54Model,
      displayName: "GPT-5.4",
      capabilities: [
        "text",
        "vision",
        "webSearch",
        "fileSearch",
        "imageGeneration",
        "imageEdit"
      ]
    }),
    createModel({
      provider: PROVIDER_VSEGPT,
      modelKey: "gemini3",
      modelId: env.vsegptGemini3ModelId,
      displayName: "Gemini-3",
      capabilities: ["text", "vision", "webSearch", "imageGeneration", "imageEdit"]
    }),
    createModel({
      provider: PROVIDER_VSEGPT,
      modelKey: VSEGPT_DEEPSEEK_MODEL_KEY,
      modelId: normalizeVseGptModelId(env.vsegptDeepSeekModelId, VSEGPT_DEEPSEEK_MODEL_ID),
      displayName: "DeepSeek",
      capabilities: ["text", "webSearch", "imageGeneration", "imageEdit"]
    })
  ];
}

function publicModels() {
  return allModelDefinitions().map((model) => ({
    provider: model.provider,
    modelKey: model.modelKey,
    displayName: model.displayName,
    isDefault: DEFAULT_MODEL_BY_PROVIDER[model.provider] === model.modelKey,
    capabilities: model.capabilities
  }));
}

function createHttpError(statusCode, message) {
  const error = new Error(message);
  error.statusCode = statusCode;
  error.expose = true;
  return error;
}

function normalizeProvider(provider) {
  const normalized = String(provider || "").trim().toLowerCase();
  return normalized || DEFAULT_PROVIDER;
}

function normalizeModelKey(provider, modelKey) {
  const normalized = String(modelKey || "").trim();
  if (provider === PROVIDER_OPENAI && normalized === "gpt54Mini") {
    return "gpt54";
  }

  if (provider === PROVIDER_VSEGPT && normalized.toLowerCase().includes("deepseek")) {
    return VSEGPT_DEEPSEEK_MODEL_KEY;
  }

  return normalized || DEFAULT_MODEL_BY_PROVIDER[provider] || "";
}

function findModel(provider, modelKey) {
  return allModelDefinitions().find(
    (model) => model.provider === provider && model.modelKey === modelKey
  );
}

function resolveRequestedModel({ provider, modelKey } = {}) {
  const normalizedProvider = normalizeProvider(provider);
  const providerSettings = providerConfig(normalizedProvider);
  if (!providerSettings) {
    throw createHttpError(400, "Unknown AI provider");
  }

  let normalizedModelKey = normalizeModelKey(normalizedProvider, modelKey);
  let model = findModel(normalizedProvider, normalizedModelKey);
  let fallbackReason = "";
  if (!model && normalizedProvider === PROVIDER_VSEGPT) {
    normalizedModelKey = DEFAULT_MODEL_BY_PROVIDER[PROVIDER_VSEGPT];
    model = findModel(normalizedProvider, normalizedModelKey);
    fallbackReason = "legacy_or_unknown_vsegpt_model_key";
  }

  if (!model) {
    throw createHttpError(400, "Unknown AI model");
  }

  return {
    provider: normalizedProvider,
    modelKey: normalizedModelKey,
    model,
    providerSettings,
    fallbackReason
  };
}

function hasCapability(model, capability) {
  return Array.isArray(model?.capabilities) && model.capabilities.includes(capability);
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

function requestHasImageInput(requestBody) {
  if (!requestBody || !Array.isArray(requestBody.messages)) {
    return false;
  }

  return requestBody.messages.some((message) => contentHasImage(message.content));
}

function requestHasImageEditInput(requestBody) {
  if (!requestBody) {
    return false;
  }

  const images = requestBody.images || requestBody.image || requestBody.imageEdit?.images || requestBody.image_edit?.images;
  if (images) {
    if (Array.isArray(images) && images.length > 0) {
      return true;
    }
    if (typeof images === "string" && images.trim()) {
      return true;
    }
    if (typeof images === "object") {
      return true;
    }
  }

  if (Array.isArray(requestBody.messages)) {
    return requestBody.messages.some((message) => contentHasImage(message?.content));
  }

  return false;
}

function requestHasToolInput(requestBody) {
  if (!requestBody || typeof requestBody !== "object" || Array.isArray(requestBody)) {
    return false;
  }

  return [
    "tools",
    "tool_choice",
    "parallel_tool_calls",
    "web_search_options",
    "file_search",
    "image_edit"
  ].some((key) => Object.hasOwn(requestBody, key));
}

function firstConfigured(...values) {
  return values.find((value) => typeof value === "string" && value.trim().length > 0) || "";
}

function normalizeModelId(value) {
  return String(value || "").trim();
}

function isRetiredVseGptGptAlias(value) {
  const normalized = normalizeModelId(value).toLowerCase();
  return (
    /^openai\/gpt-[a-z0-9.-]*nano$/i.test(normalized) ||
    /^gpt-?5[.-]?5$/i.test(normalized)
  );
}

function isRetiredDeepSeekModelId(value) {
  const normalized = normalizeModelId(value).toLowerCase();
  return (
    /^deepseek\/?deepseek-r\d+/i.test(normalized) ||
    (normalized.startsWith("deepseek/") && normalized.includes("flash-alt"))
  );
}

function isRetiredVseGptGrokFastModelId(value) {
  const normalized = normalizeModelId(value).toLowerCase();
  return (
    normalized === "x-ai/grok-4-fast" ||
    normalized === "x-ai/grok-4-fast-thinking"
  );
}

function fallbackVseGptModelId(fallbackModelId) {
  const fallback = normalizeModelId(fallbackModelId);
  if (
    !fallback ||
    isRetiredVseGptGptAlias(fallback) ||
    isRetiredDeepSeekModelId(fallback) ||
    isRetiredVseGptGrokFastModelId(fallback)
  ) {
    return VSEGPT_DEEPSEEK_MODEL_ID;
  }

  return fallback;
}

function normalizeVseGptModelId(modelId, fallbackModelId = "") {
  const normalized = normalizeModelId(modelId);
  if (!normalized) {
    return fallbackVseGptModelId(fallbackModelId);
  }

  if (isRetiredVseGptGptAlias(normalized)) {
    return fallbackVseGptModelId(fallbackModelId);
  }

  if (isRetiredDeepSeekModelId(normalized)) {
    return VSEGPT_DEEPSEEK_MODEL_ID;
  }

  if (isRetiredVseGptGrokFastModelId(normalized)) {
    return fallbackVseGptModelId(fallbackModelId);
  }

  return normalized;
}

function assertSelectionConfigured(selection) {
  const providerSettings = selection.providerSettings;
  if (!providerSettings.apiKey) {
    throw createHttpError(503, providerSettings.missingKeyMessage);
  }

  if (!selection.upstreamUrl) {
    throw createHttpError(503, `${selection.provider} upstream URL is not configured`);
  }

  if (!selection.model) {
    throw createHttpError(503, selection.modelConfigError || "AI model is not configured");
  }
}

function createSelection({ requestSelection, model, upstreamUrl, modelId, modelConfigError = "" }) {
  const normalizedModelId = requestSelection.provider === PROVIDER_VSEGPT
    ? normalizeVseGptModelId(modelId, model.modelId)
    : modelId;

  return {
    provider: requestSelection.provider,
    modelKey: requestSelection.modelKey,
    model: normalizedModelId,
    modelDefinition: model,
    modelConfigError,
    upstreamUrl,
    apiKey: requestSelection.providerSettings.apiKey,
    providerSettings: requestSelection.providerSettings,
    fallbackReason: requestSelection.fallbackReason || ""
  };
}

function selectChatModel({
  provider,
  modelKey,
  currentMode,
  requestBody,
  adultMode = false
} = {}) {
  const requestSelection = resolveRequestedModel({ provider, modelKey });
  const { model, providerSettings } = requestSelection;
  const hasImageInput = requestHasImageInput(requestBody);

  const isImageMode = currentMode === "create_image" || currentMode === "image_edit" || currentMode === "edit_image";
  if (isImageMode) {
    const isEdit = currentMode === "image_edit" || currentMode === "edit_image" || requestHasImageEditInput(requestBody);
    const useEdit = isEdit && requestSelection.provider !== PROVIDER_OPENAI;
    return createSelection({
      requestSelection,
      model,
      upstreamUrl: useEdit && providerSettings.imageEditUrl ? providerSettings.imageEditUrl : providerSettings.imageUrl,
      modelId: useEdit && providerSettings.imageEditModel ? providerSettings.imageEditModel : providerSettings.imageModel
    });
  }

  if (requestSelection.provider === PROVIDER_VSEGPT && adultMode && !hasImageInput) {
    const adultModel = firstConfigured(providerSettings.adultTextModel, model.modelId);
    return createSelection({
      requestSelection,
      model,
      upstreamUrl: providerSettings.chatUrl,
      modelId: adultModel
    });
  }

  if (
    requestSelection.provider === PROVIDER_VSEGPT &&
    (currentMode === "search" || currentMode === "shopping") &&
    !hasImageInput
  ) {
    const searchModel = firstConfigured(providerSettings.searchModel, model.modelId);
    return createSelection({
      requestSelection,
      model,
      upstreamUrl: providerSettings.chatUrl,
      modelId: searchModel
    });
  }

  return createSelection({
    requestSelection,
    model,
    upstreamUrl: providerSettings.chatUrl,
    modelId: model.modelId,
    modelConfigError: model.configError
  });
}

function selectTitleModel({ provider, modelKey } = {}) {
  const requestSelection = resolveTitleRequestedModel({ provider, modelKey });
  const { model, providerSettings } = requestSelection;
  const modelId = firstConfigured(providerSettings.titleModel, model.modelId);

  return createSelection({
    requestSelection,
    model,
    upstreamUrl: providerSettings.chatUrl,
    modelId,
    modelConfigError: model.configError
  });
}

function resolveTitleRequestedModel({ provider, modelKey } = {}) {
  const normalizedProvider = normalizeProvider(provider);
  if (normalizedProvider === PROVIDER_OPENAI) {
    const openAiSettings = providerConfig(PROVIDER_OPENAI);
    const vsegptSettings = providerConfig(PROVIDER_VSEGPT);

    if (!openAiSettings.apiKey && vsegptSettings.apiKey) {
      const requestedModelKey = normalizeModelKey(PROVIDER_VSEGPT, modelKey);
      const fallbackModelKey = findModel(PROVIDER_VSEGPT, requestedModelKey)
        ? requestedModelKey
        : DEFAULT_MODEL_BY_PROVIDER[PROVIDER_VSEGPT];

      return {
        ...resolveRequestedModel({
          provider: PROVIDER_VSEGPT,
          modelKey: fallbackModelKey
        }),
        fallbackReason: "openai_key_missing"
      };
    }
  }

  return resolveRequestedModel({ provider, modelKey });
}

function selectSummaryModel({ provider, modelKey } = {}) {
  const requestSelection = resolveRequestedModel({ provider, modelKey });
  const { model, providerSettings } = requestSelection;
  const modelId = firstConfigured(providerSettings.summaryModel, model.modelId);

  return createSelection({
    requestSelection,
    model,
    upstreamUrl: providerSettings.chatUrl,
    modelId,
    modelConfigError: model.configError
  });
}

function selectImageFallbackModel() {
  const selection = selectChatModel({
    provider: env.aiImageFallbackProvider,
    modelKey: env.aiImageFallbackModelKey,
    currentMode: null,
    requestBody: { messages: [] },
    adultMode: false
  });

  if (
    !hasCapability(selection.modelDefinition, "vision") &&
    !hasCapability(selection.modelDefinition, "imageInput")
  ) {
    throw createHttpError(503, "Image fallback model does not support vision input");
  }

  return selection;
}

module.exports = {
  PROVIDER_OPENAI,
  PROVIDER_VSEGPT,
  DEFAULT_PROVIDER,
  DEFAULT_MODEL_BY_PROVIDER,
  publicModels,
  requestHasImageInput,
  hasCapability,
  selectChatModel,
  selectTitleModel,
  selectSummaryModel,
  selectImageFallbackModel,
  assertSelectionConfigured
};
