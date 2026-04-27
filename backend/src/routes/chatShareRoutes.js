const express = require("express");
const rateLimit = require("express-rate-limit");
const { createChatShareController } = require("../controllers/chatShareController");
const { validate } = require("../middleware/validate");
const { createChatShareSchema } = require("../schemas/chatShareSchemas");

function createChatShareRouter({ authenticate, chatShareModel, rateLimitEnabled = true }) {
  const router = express.Router();
  const controller = createChatShareController({ chatShareModel });

  router.get("/:token", controller.get);

  router.use(authenticate);
  const createMiddlewares = [];
  if (rateLimitEnabled) {
    createMiddlewares.push(rateLimit({
      windowMs: 60 * 1000,
      limit: 20,
      standardHeaders: true,
      legacyHeaders: false,
      message: {
        message: "Too many share links. Try again later."
      }
    }));
  }
  createMiddlewares.push(
    validate(createChatShareSchema),
    controller.create
  );

  router.get("/", controller.listMyShares);
  router.post("/", ...createMiddlewares);
  router.post("/chats/:chatId/revoke", controller.revokeChat);
  router.delete("/:token", controller.revokeToken);

  return router;
}

function createPublicShareRouter({ chatShareModel }) {
  const router = express.Router();
  const controller = createChatShareController({ chatShareModel });

  router.get("/:token", controller.landing);

  return router;
}

module.exports = {
  createChatShareRouter,
  createPublicShareRouter
};
