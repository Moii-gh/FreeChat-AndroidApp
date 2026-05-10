const path = require("path");
const dotenv = require("dotenv");

const backendDir = path.resolve(__dirname, "../..");
const repoRoot = path.resolve(backendDir, "..");

dotenv.config({ path: path.join(backendDir, ".env") });
dotenv.config({ path: path.join(repoRoot, ".env"), override: false });

function asNumber(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function asBoolean(value, fallback = false) {
  if (value === undefined) {
    return fallback;
  }

  return String(value).trim().toLowerCase() === "true";
}

const UNLIMITED_DAILY_AI_REQUEST_LIMIT = 2_147_483_647;

function asDailyAiRequestLimit(value, fallback) {
  if (value === undefined) {
    return fallback;
  }

  const normalized = String(value).trim().toLowerCase();
  if (
    normalized === "" ||
    normalized === "0" ||
    normalized === "-1" ||
    normalized === "false" ||
    normalized === "off" ||
    normalized === "none" ||
    normalized === "unlimited"
  ) {
    return UNLIMITED_DAILY_AI_REQUEST_LIMIT;
  }

  const parsed = Number(normalized);
  if (!Number.isFinite(parsed) || parsed <= 0) {
    return fallback;
  }

  return Math.min(Math.floor(parsed), UNLIMITED_DAILY_AI_REQUEST_LIMIT);
}

const env = {
  nodeEnv: process.env.NODE_ENV || "",
  isTest:
    process.env.NODE_ENV === "test" ||
    process.argv.includes("--test") ||
    process.env.VITEST === "true" ||
    Boolean(process.env.NODE_TEST_CONTEXT),
  port: Number(process.env.PORT || 4000),
  jsonBodyLimit: process.env.JSON_BODY_LIMIT || "10mb",
  databaseUrl: process.env.DATABASE_URL || "",
  jwtSecret: process.env.JWT_SECRET || "",
  jwtExpiresIn: process.env.JWT_EXPIRES_IN || "7d",
  verificationCodeSecret: process.env.VERIFICATION_CODE_SECRET || "",
  verificationCodeTtlMinutes: asNumber(process.env.VERIFICATION_CODE_TTL_MINUTES, 10),
  verificationCodeResendCooldownSeconds: asNumber(
    process.env.VERIFICATION_CODE_RESEND_COOLDOWN_SECONDS,
    60
  ),
  verificationCodeMaxAttempts: asNumber(process.env.VERIFICATION_CODE_MAX_ATTEMPTS, 5),
  mailFrom: process.env.MAIL_FROM || "no-reply@example.local",
  smtpHost: process.env.SMTP_HOST || "",
  smtpPort: Number(process.env.SMTP_PORT || 587),
  smtpSecure: asBoolean(process.env.SMTP_SECURE),
  smtpUser: process.env.SMTP_USER || "",
  smtpPass: process.env.SMTP_PASS || "",
  telegramBotToken: process.env.TELEGRAM_BOT_TOKEN || "",
  telegramBotUsername: process.env.TELEGRAM_BOT_USERNAME || "",
  telegramWidgetPublicBaseUrl: process.env.TELEGRAM_WIDGET_PUBLIC_BASE_URL || "",
  telegramWidgetMaxAgeSeconds: Number(process.env.TELEGRAM_WIDGET_MAX_AGE_SECONDS || 300),
  telegramLoginClientId: process.env.TELEGRAM_LOGIN_CLIENT_ID || "",
  vkIdClientId: process.env.VKID_CLIENT_ID || "",
  vkIdUserInfoUrl: process.env.VKID_USER_INFO_URL || "https://id.vk.ru/oauth2/user_info",
  chatSharePublicBaseUrl: process.env.CHAT_SHARE_PUBLIC_BASE_URL || "",
  chatShareStoreUrl: process.env.CHAT_SHARE_STORE_URL || "",
  openAiApiKey: process.env.OPENAI_API_KEY || "",
  openAiChatUrl: process.env.OPENAI_CHAT_URL || "https://api.openai.com/v1/chat/completions",
  openAiImageUrl: process.env.OPENAI_IMAGE_URL || "https://api.openai.com/v1/images/generations",
  openAiGpt54Model:
    process.env.OPENAI_GPT54_MODEL ||
    process.env.OPENAI_GPT54_MINI_MODEL ||
    "gpt-5.4-mini",
  openAiImageModel: process.env.OPENAI_IMAGE_MODEL || "gpt-image-1",
  vsegptApiKey: process.env.VSEGPT_API_KEY || process.env.AI_API_KEY || "",
  vsegptChatUrl:
    process.env.VSEGPT_CHAT_URL ||
    process.env.AI_CHAT_URL ||
    "https://api.vsegpt.ru/v1/chat/completions",
  vsegptImageUrl:
    process.env.VSEGPT_IMAGE_URL ||
    process.env.AI_IMAGE_URL ||
    "https://api.vsegpt.ru/v1/images/generations",
  vsegptGpt55ModelId: process.env.VSEGPT_GPT55_MODEL_ID || "openai/gpt-5.4-nano",
  vsegptGemini3ModelId:
    process.env.VSEGPT_GEMINI3_MODEL_ID || "google/gemma-4-26b-a4b-it",
  vsegptDeepSeekModelId:
    process.env.VSEGPT_DEEPSEEK_MODEL_ID || "deepseek/deepseek-v4-flash-alt",
  aiImageFallbackProvider: process.env.AI_IMAGE_FALLBACK_PROVIDER || "vsegpt",
  aiImageFallbackModelKey: process.env.AI_IMAGE_FALLBACK_MODEL_KEY || "gemini3",
  aiApiKey: process.env.AI_API_KEY || "",
  aiChatUrl: process.env.AI_CHAT_URL || "",
  aiImageUrl: process.env.AI_IMAGE_URL || "",
  aiTimeoutMs: Number(process.env.AI_TIMEOUT_MS || 120000),
  dailyAiRequestLimit: asDailyAiRequestLimit(process.env.DAILY_AI_REQUEST_LIMIT, 20),
  aiTextModel: process.env.AI_TEXT_MODEL || "",
  aiVisionModel: process.env.AI_VISION_MODEL || "",
  aiImageModel: process.env.AI_IMAGE_MODEL || "",
  aiSearchModel: process.env.AI_SEARCH_MODEL || "",
  aiTitleModel: process.env.AI_TITLE_MODEL || "",
  aiSummaryModel: process.env.AI_SUMMARY_MODEL || "",
  aiAdultTextModel: process.env.AI_ADULT_TEXT_MODEL || "x-ai/grok-4-fast-thinking",
  aiProTextModel: process.env.AI_PRO_TEXT_MODEL || "",
  aiProVisionModel: process.env.AI_PRO_VISION_MODEL || "",
  aiProImageModel: process.env.AI_PRO_IMAGE_MODEL || "",
  aiProSearchModel: process.env.AI_PRO_SEARCH_MODEL || "",
  aiProTitleModel: process.env.AI_PRO_TITLE_MODEL || "",
  aiProSummaryModel: process.env.AI_PRO_SUMMARY_MODEL || ""
};

if (env.isTest) {
  env.jwtSecret = env.jwtSecret || "test-jwt-secret";
  env.verificationCodeSecret =
    env.verificationCodeSecret || "test-verification-code-secret";
}

function assertRequiredSecret(name, value) {
  if (!value || value === "change-me") {
    throw new Error(`${name} must be configured with a non-default secret`);
  }
}

function assertServerRuntimeConfig() {
  if (env.isTest) {
    return;
  }

  if (!env.databaseUrl) {
    throw new Error("DATABASE_URL must be configured");
  }

  assertRequiredSecret("JWT_SECRET", env.jwtSecret);
  assertRequiredSecret("VERIFICATION_CODE_SECRET", env.verificationCodeSecret);
}

module.exports = { env, assertServerRuntimeConfig };
