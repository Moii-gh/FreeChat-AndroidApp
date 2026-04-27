const { env } = require("../config/env");

function firstConfigured(...values) {
  return values.find((value) => typeof value === "string" && value.trim().length > 0) || "";
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

function requestHasVision(requestBody) {
  if (!requestBody || !Array.isArray(requestBody.messages)) {
    return false;
  }

  return requestBody.messages.some((message) => contentHasImage(message.content));
}

function getModelConfig() {
  return {
    text: firstConfigured(env.aiTextModel),
    vision: firstConfigured(env.aiVisionModel, env.aiTextModel),
    search: firstConfigured(env.aiSearchModel, env.aiTextModel),
    image: firstConfigured(env.aiImageModel),
    title: firstConfigured(env.aiTitleModel, env.aiTextModel),
    summary: firstConfigured(env.aiSummaryModel, env.aiTextModel)
  };
}

function selectChatModel({ currentMode, requestBody }) {
  const config = getModelConfig();
  const hasVision = requestHasVision(requestBody);

  if (currentMode === "create_image") {
    return {
      model: config.image,
      upstreamUrl: env.aiImageUrl
    };
  }

  if (currentMode === "search" || currentMode === "shopping") {
    return {
      model: hasVision ? config.vision : config.search,
      upstreamUrl: env.aiChatUrl
    };
  }

  return {
    model: hasVision ? config.vision : config.text,
    upstreamUrl: env.aiChatUrl
  };
}

function selectTitleModel() {
  return {
    model: getModelConfig().title,
    upstreamUrl: env.aiChatUrl
  };
}

function selectSummaryModel() {
  return {
    model: getModelConfig().summary,
    upstreamUrl: env.aiChatUrl
  };
}

module.exports = {
  selectChatModel,
  selectTitleModel,
  selectSummaryModel
};
