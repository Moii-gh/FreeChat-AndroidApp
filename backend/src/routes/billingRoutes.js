const express = require("express");
const { authenticate } = require("../middleware/authenticate");
const { createBillingController } = require("../controllers/billingController");

function createBillingRouter({ userModel }) {
  const router = express.Router();
  const controller = createBillingController({ userModel });

  router.post("/webhook/yookassa", controller.webhook);

  router.use(authenticate);
  router.get("/status", controller.status);
  router.post("/checkout", controller.checkout);
  router.post("/cancel", controller.cancel);

  return router;
}

module.exports = { createBillingRouter };
