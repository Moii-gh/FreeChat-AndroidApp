const { env } = require("./config/env");

const useMemoryModels = !env.databaseUrl;
const userModel = useMemoryModels
  ? require("./models/memoryUserModel")
  : require("./models/userModel");
const telegramChallengeModel = useMemoryModels
  ? require("./models/memoryTelegramChallengeModel")
  : require("./models/telegramChallengeModel");

module.exports = { userModel, telegramChallengeModel };
