const express = require("express");
const { env } = require("./config/env");
const { errorHandler } = require("./middleware/errorHandler");
const { userModel, telegramChallengeModel } = require("./modelRegistry");
const emailService = require("./utils/emailTransport");
const { createAuthRouter } = require("./routes/authRoutes");
const { createTelegramAuthRouter } = require("./routes/telegramAuthRoutes");
const { createSyncRouter } = require("./routes/syncRoutes");

function createApp(options = {}) {
  const app = express();
  const resolvedUserModel = options.userModel || userModel;
  const resolvedTelegramChallengeModel =
    options.telegramChallengeModel || telegramChallengeModel;
  const resolvedEmailService = options.emailService || emailService;
  const rateLimitEnabled = options.rateLimitEnabled === true;
  const telegramConfig = options.telegramConfig || {
    isConfigured: Boolean(env.telegramBotToken && env.telegramBotUsername),
    botToken: env.telegramBotToken,
    botUsername: env.telegramBotUsername,
    loginClientId: env.telegramLoginClientId,
    publicBaseUrl: env.telegramWidgetPublicBaseUrl,
    widgetMaxAgeSeconds: env.telegramWidgetMaxAgeSeconds
  };

  app.use(express.json());

  app.get("/health", (_req, res) => {
    res.status(200).json({ status: "ok" });
  });

  app.use(
    "/api",
    createAuthRouter({
      userModel: resolvedUserModel,
      emailService: resolvedEmailService,
      rateLimitEnabled
    })
  );

  app.use(
    "/api/telegram-auth",
    createTelegramAuthRouter({
      userModel: resolvedUserModel,
      challengeModel: resolvedTelegramChallengeModel,
      telegramConfig,
      rateLimitEnabled
    })
  );

  app.use("/api/sync", createSyncRouter());

  app.use((_req, res) => {
    res.status(404).json({
      message: "Маршрут не найден"
    });
  });

  app.use(errorHandler);

  return app;
}

module.exports = { createApp };
