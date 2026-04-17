const { createApp } = require("./app");
const { env } = require("./config/env");
const { userModel, telegramChallengeModel } = require("./modelRegistry");
const { createTelegramBotService } = require("./utils/telegramBotService");

const telegramConfig = {
  isConfigured: Boolean(env.telegramBotToken && env.telegramBotUsername),
  botUsername: env.telegramBotUsername
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
