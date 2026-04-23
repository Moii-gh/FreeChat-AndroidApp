const { env } = require("../config/env");

const FREE_PLAN_CODE = "free";

function firstConfigured(...values) {
  return values.find((value) => typeof value === "string" && value.trim().length > 0) || "";
}

function parseDate(value) {
  if (!value) {
    return null;
  }

  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

function isProUser(user, now = new Date()) {
  if (!user || !user.plan_code || user.plan_code === FREE_PLAN_CODE) {
    return false;
  }

  const expiresAt = parseDate(user.plan_expires_at);
  return Boolean(expiresAt && expiresAt.getTime() > now.getTime());
}

function getPlanSnapshot(user, now = new Date()) {
  const isPro = isProUser(user, now);
  return {
    planCode: isPro ? user.plan_code : FREE_PLAN_CODE,
    subscriptionStatus: isPro ? (user.subscription_status || "active") : "inactive",
    planExpiresAt: isPro ? user.plan_expires_at : null,
    isPro
  };
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

function getModelConfig(isPro) {
  return {
    text: firstConfigured(isPro ? env.aiProTextModel : "", env.aiTextModel),
    vision: firstConfigured(
      isPro ? env.aiProVisionModel : "",
      env.aiVisionModel,
      isPro ? env.aiProTextModel : "",
      env.aiTextModel
    ),
    search: firstConfigured(
      isPro ? env.aiProSearchModel : "",
      env.aiSearchModel,
      isPro ? env.aiProTextModel : "",
      env.aiTextModel
    ),
    image: firstConfigured(isPro ? env.aiProImageModel : "", env.aiImageModel),
    title: firstConfigured(
      isPro ? env.aiProTitleModel : "",
      env.aiTitleModel,
      isPro ? env.aiProTextModel : "",
      env.aiTextModel
    ),
    summary: firstConfigured(
      isPro ? env.aiProSummaryModel : "",
      env.aiSummaryModel,
      isPro ? env.aiProTextModel : "",
      env.aiTextModel
    )
  };
}

function selectChatModel({ user, currentMode, requestBody }) {
  const snapshot = getPlanSnapshot(user);
  const config = getModelConfig(snapshot.isPro);
  const hasVision = requestHasVision(requestBody);

  if (currentMode === "create_image") {
    return {
      ...snapshot,
      model: config.image,
      upstreamUrl: env.aiImageUrl
    };
  }

  if (currentMode === "search" || currentMode === "shopping") {
    return {
      ...snapshot,
      model: hasVision ? config.vision : config.search,
      upstreamUrl: env.aiChatUrl
    };
  }

  return {
    ...snapshot,
    model: hasVision ? config.vision : config.text,
    upstreamUrl: env.aiChatUrl
  };
}

function selectTitleModel(user) {
  const snapshot = getPlanSnapshot(user);
  return {
    ...snapshot,
    model: getModelConfig(snapshot.isPro).title,
    upstreamUrl: env.aiChatUrl
  };
}

function selectSummaryModel(user) {
  const snapshot = getPlanSnapshot(user);
  return {
    ...snapshot,
    model: getModelConfig(snapshot.isPro).summary,
    upstreamUrl: env.aiChatUrl
  };
}

module.exports = {
  FREE_PLAN_CODE,
  parseDate,
  isProUser,
  getPlanSnapshot,
  selectChatModel,
  selectTitleModel,
  selectSummaryModel
};
