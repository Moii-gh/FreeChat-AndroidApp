const express = require("express");
const { env } = require("./config/env");
const { errorHandler } = require("./middleware/errorHandler");
const { normalizeJsonContentType } = require("./middleware/normalizeJsonContentType");
const { createAuthenticate } = require("./middleware/authenticate");
const {
  userModel,
  telegramChallengeModel,
  authNonceModel,
  aiUsageModel,
  chatShareModel
} = require("./modelRegistry");
const emailService = require("./utils/emailTransport");
const { createAuthRouter } = require("./routes/authRoutes");
const { createTelegramAuthRouter } = require("./routes/telegramAuthRoutes");
const { createSyncRouter } = require("./routes/syncRoutes");
const { createBillingRouter } = require("./routes/billingRoutes");
const { createAiRouter } = require("./routes/aiRoutes");
const { createChatShareRouter, createPublicShareRouter } = require("./routes/chatShareRoutes");

function createApp(options = {}) {
  const app = express();
  app.set("trust proxy", 1);
  const resolvedUserModel = options.userModel || userModel;
  const resolvedTelegramChallengeModel =
    options.telegramChallengeModel || telegramChallengeModel;
  const resolvedAuthNonceModel = options.authNonceModel || authNonceModel;
  const resolvedAiUsageModel = options.aiUsageModel || aiUsageModel;
  const resolvedChatShareModel = options.chatShareModel || chatShareModel;
  const resolvedEmailService = options.emailService || emailService;
  const rateLimitEnabled = options.rateLimitEnabled !== false;
  const authenticate = createAuthenticate({ userModel: resolvedUserModel });
  const telegramConfig = options.telegramConfig || {
    isConfigured: Boolean(env.telegramBotToken && env.telegramBotUsername),
    botToken: env.telegramBotToken,
    botUsername: env.telegramBotUsername,
    loginClientId: env.telegramLoginClientId,
    publicBaseUrl: env.telegramWidgetPublicBaseUrl,
    widgetMaxAgeSeconds: env.telegramWidgetMaxAgeSeconds
  };

  app.use(normalizeJsonContentType);
  app.use(express.json({ limit: env.jsonBodyLimit }));

  app.get("/health", (_req, res) => {
    res.status(200).json({ status: "ok" });
  });

  app.use(
    "/api",
    createAuthRouter({
      userModel: resolvedUserModel,
      emailService: resolvedEmailService,
      authenticate,
      rateLimitEnabled
    })
  );

  app.use(
    "/api/telegram-auth",
    createTelegramAuthRouter({
      userModel: resolvedUserModel,
      challengeModel: resolvedTelegramChallengeModel,
      authNonceModel: resolvedAuthNonceModel,
      telegramConfig,
      rateLimitEnabled
    })
  );

  app.use("/api/sync", createSyncRouter({ authenticate }));

  app.use(
    "/api/chat-shares",
    createChatShareRouter({
      authenticate,
      chatShareModel: resolvedChatShareModel,
      rateLimitEnabled
    })
  );

  app.use("/share", createPublicShareRouter({ chatShareModel: resolvedChatShareModel }));

  app.use(
    "/api/billing",
    createBillingRouter({
      userModel: resolvedUserModel,
      aiUsageModel: resolvedAiUsageModel,
      authenticate
    })
  );

  app.use(
    "/api/ai",
    createAiRouter({
      userModel: resolvedUserModel,
      aiUsageModel: resolvedAiUsageModel,
      authenticate
    })
  );

  app.use((_req, res) => {
    res.status(404).json({
      message: "Маршрут не найден"
    });
  });

  app.use(errorHandler);

  return app;
}

module.exports = { createApp };
