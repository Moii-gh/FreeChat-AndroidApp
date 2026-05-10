const express = require("express");
const rateLimit = require("express-rate-limit");
const { createAiController } = require("../controllers/aiController");
const { validate } = require("../middleware/validate");
const { aiChatSchema, aiTitleSchema, aiSummarySchema } = require("../schemas/aiSchemas");

function createAiRouter({ userModel, aiUsageModel, authenticate }) {
  const router = express.Router();
  const controller = createAiController({ userModel, aiUsageModel });

  router.use(authenticate);
  router.get("/models", controller.getModels);
  router.get("/limits", controller.getLimits);
  router.get(
    "/trending",
    rateLimit({
      windowMs: 60 * 1000,
      limit: 10,
      standardHeaders: true,
      legacyHeaders: false,
      message: {
        message: "Слишком много запросов. Попробуйте позже."
      }
    }),
    controller.trending
  );
  router.post(
    "/reward-ad",
    rateLimit({
      windowMs: 30 * 1000, // 30 seconds
      limit: 1, // maximum 1 reward per 30 seconds
      standardHeaders: true,
      legacyHeaders: false,
      message: { message: "Подождите перед получением следующего бонуса." }
    }),
    controller.rewardAd
  );
  router.post("/chat", validate(aiChatSchema), controller.chat);
  router.post(
    "/title",
    rateLimit({
      windowMs: 60 * 1000,
      limit: 30,
      standardHeaders: true,
      legacyHeaders: false,
      message: {
        message: "Слишком много запросов. Попробуйте позже."
      }
    }),
    validate(aiTitleSchema),
    controller.title
  );
  router.post(
    "/summary",
    rateLimit({
      windowMs: 60 * 1000,
      limit: 30,
      standardHeaders: true,
      legacyHeaders: false,
      message: {
        message: "Слишком много запросов. Попробуйте позже."
      }
    }),
    validate(aiSummarySchema),
    controller.summary
  );

  return router;
}

module.exports = { createAiRouter };
