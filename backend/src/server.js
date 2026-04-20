const { createApp } = require("./app");
const { env } = require("./config/env");
const { userModel, telegramChallengeModel } = require("./modelRegistry");
const { createTelegramBotService } = require("./utils/telegramBotService");

const telegramConfig = {
  isConfigured: Boolean(env.telegramBotToken && env.telegramBotUsername),
  botToken: env.telegramBotToken,
  botUsername: env.telegramBotUsername,
  loginClientId: env.telegramLoginClientId,
  publicBaseUrl: env.telegramWidgetPublicBaseUrl,
  widgetMaxAgeSeconds: env.telegramWidgetMaxAgeSeconds
};

const app = createApp({ telegramConfig });
const telegramBotService = createTelegramBotService({
  userModel,
  challengeModel: telegramChallengeModel
});

app.listen(env.port, () => {
  console.log(`Auth backend listening on port ${env.port}`);
  telegramBotService.start();
});
