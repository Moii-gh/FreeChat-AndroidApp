const express = require("express");
const rateLimit = require("express-rate-limit");
const { createAuthController } = require("../controllers/authController");
const { authenticate } = require("../middleware/authenticate");
const { validate } = require("../middleware/validate");
const {
  registerSchema,
  loginSchema,
  checkEmailSchema,
  verifyEmailSchema,
  resendCodeSchema,
  changePasswordSchema
} = require("../schemas/authSchemas");

function createAuthRouter({ userModel, emailService, rateLimitEnabled = false }) {
  const router = express.Router();
  const controller = createAuthController({ userModel, emailService });

  if (rateLimitEnabled) {
    router.use(
      rateLimit({
        windowMs: 15 * 60 * 1000,
        limit: 10,
        standardHeaders: true,
        legacyHeaders: false,
        message: {
          message: "Слишком много запросов. Попробуйте позже."
        }
      })
    );
  }

  router.post("/check-email", validate(checkEmailSchema), controller.checkEmail);
  router.post("/register", validate(registerSchema), controller.register);
  router.post("/login", validate(loginSchema), controller.login);
  router.post("/verify-email", validate(verifyEmailSchema), controller.verifyEmail);
  router.post("/resend-code", validate(resendCodeSchema), controller.resendCode);
  router.get("/me", authenticate, controller.me);
  router.post(
    "/change-password",
    authenticate,
    validate(changePasswordSchema),
    controller.changePassword
  );

  return router;
}

module.exports = { createAuthRouter };
