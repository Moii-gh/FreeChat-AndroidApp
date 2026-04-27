const express = require("express");
const rateLimit = require("express-rate-limit");
const { createTelegramAuthController } = require("../controllers/telegramAuthController");
const { validate } = require("../middleware/validate");
const {
  emptyBodySchema,
  telegramVerifyCodeSchema,
  telegramCompleteRegistrationSchema,
  telegramCompleteLoginSchema,
  telegramBeginMigrationSchema,
  telegramCompleteMigrationSchema,
  telegramWidgetAuthSchema,
  telegramNativeLoginSchema
} = require("../schemas/authSchemas");

function createTelegramAuthRouter({
  userModel,
  challengeModel,
  authNonceModel,
  telegramConfig,
  rateLimitEnabled = true
}) {
  const router = express.Router();
  const controller = createTelegramAuthController({
    userModel,
    challengeModel,
    authNonceModel,
    telegramConfig
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

  router.get("/widget", controller.widgetPage);
  router.get("/widget-callback", controller.widgetCallback);
  router.post("/widget-login", validate(telegramWidgetAuthSchema), controller.completeWidgetLogin);
  router.post("/native-login", validate(telegramNativeLoginSchema), controller.completeNativeLogin);
  router.post("/begin-registration", validate(emptyBodySchema), controller.beginRegistration);
  router.post("/begin-login", validate(emptyBodySchema), controller.beginLogin);
  router.post("/verify-code", validate(telegramVerifyCodeSchema), controller.verifyCode);
  router.post(
    "/complete-registration",
    validate(telegramCompleteRegistrationSchema),
    controller.completeRegistration
  );
  router.post(
    "/complete-login",
    validate(telegramCompleteLoginSchema),
    controller.completeLogin
  );
  router.post(
    "/begin-migration",
    validate(telegramBeginMigrationSchema),
    controller.beginMigration
  );
  router.post(
    "/complete-migration",
    validate(telegramCompleteMigrationSchema),
    controller.completeMigration
  );

  return router;
}

module.exports = { createTelegramAuthRouter };
