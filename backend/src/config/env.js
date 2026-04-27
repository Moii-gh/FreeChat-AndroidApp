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
  chatSharePublicBaseUrl: process.env.CHAT_SHARE_PUBLIC_BASE_URL || "",
  chatShareStoreUrl: process.env.CHAT_SHARE_STORE_URL || "",
  aiApiKey: process.env.AI_API_KEY || "",
  aiChatUrl: process.env.AI_CHAT_URL || "",
  aiImageUrl: process.env.AI_IMAGE_URL || "",
  aiTimeoutMs: Number(process.env.AI_TIMEOUT_MS || 120000),
  dailyAiRequestLimit: asNumber(process.env.DAILY_AI_REQUEST_LIMIT, 20),
  aiTextModel: process.env.AI_TEXT_MODEL || "",
  aiVisionModel: process.env.AI_VISION_MODEL || "",
  aiImageModel: process.env.AI_IMAGE_MODEL || "",
  aiSearchModel: process.env.AI_SEARCH_MODEL || "",
  aiTitleModel: process.env.AI_TITLE_MODEL || "",
  aiSummaryModel: process.env.AI_SUMMARY_MODEL || "",
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
