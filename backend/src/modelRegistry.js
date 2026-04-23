const { env } = require("./config/env");

const useMemoryModels = !env.databaseUrl;
const userModel = useMemoryModels
  ? require("./models/memoryUserModel")
  : require("./models/userModel");
const telegramChallengeModel = useMemoryModels
  ? require("./models/memoryTelegramChallengeModel")
  : require("./models/telegramChallengeModel");
const authNonceModel = useMemoryModels
  ? require("./models/memoryAuthNonceModel")
  : require("./models/authNonceModel");
const aiUsageModel = useMemoryModels
  ? require("./models/memoryAiUsageModel")
  : require("./models/aiUsageModel");

if (useMemoryModels && !env.isTest) {
  throw new Error("DATABASE_URL must be configured outside the automated test environment");
}

module.exports = { userModel, telegramChallengeModel, authNonceModel, aiUsageModel };
