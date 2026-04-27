const express = require("express");
const { syncData } = require("../controllers/syncController");
const { validate } = require("../middleware/validate");
const { syncPayloadSchema } = require("../schemas/syncSchemas");

function createSyncRouter({ authenticate }) {
  const router = express.Router();

  router.use(authenticate);
  router.post("/", validate(syncPayloadSchema), syncData);

  return router;
}

module.exports = { createSyncRouter };
