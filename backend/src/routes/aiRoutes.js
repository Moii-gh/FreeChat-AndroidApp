const express = require("express");
const { authenticate } = require("../middleware/authenticate");
const { createAiController } = require("../controllers/aiController");

function createAiRouter({ userModel }) {
  const router = express.Router();
  const controller = createAiController({ userModel });

  router.use(authenticate);
  router.post("/chat", controller.chat);
  router.post("/title", controller.title);
  router.post("/summary", controller.summary);

  return router;
}

module.exports = { createAiRouter };
