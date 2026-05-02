const express = require("express");
const rateLimit = require("express-rate-limit");
const { createVkAuthController } = require("../controllers/vkAuthController");
const { validate } = require("../middleware/validate");
const { vkNativeLoginSchema } = require("../schemas/authSchemas");

function createVkAuthRouter({
  userModel,
  vkConfig,
  rateLimitEnabled = true
}) {
  const router = express.Router();
  const controller = createVkAuthController({
    userModel,
    vkConfig
  });

  if (rateLimitEnabled) {
    router.use(
      rateLimit({
        windowMs: 15 * 60 * 1000,
        limit: 20,
        standardHeaders: true,
        legacyHeaders: false,
        message: {
          message: "Слишком много запросов. Попробуйте позже."
        }
      })
    );
  }

  router.post("/native-login", validate(vkNativeLoginSchema), controller.completeNativeLogin);

  return router;
}

module.exports = { createVkAuthRouter };
