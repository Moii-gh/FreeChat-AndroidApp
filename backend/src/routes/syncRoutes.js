const express = require("express");
const { authenticate } = require("../middleware/authenticate");
const { syncData } = require("../controllers/syncController");

function createSyncRouter() {
  const router = express.Router();
  
  // All sync routes require authentication.
  router.use(authenticate);
  
  router.post("/", syncData);
  
  return router;
}

module.exports = { createSyncRouter };
