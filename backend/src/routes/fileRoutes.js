const express = require("express");
const { createFileController } = require("../controllers/fileController");
const { upload } = require("../middleware/uploadMiddleware");

function createFileRouter({ authenticate }) {
  const router = express.Router();
  const controller = createFileController();

  router.use(authenticate);

  router.post("/avatar", upload.single("file"), controller.uploadAvatar);
  router.post("/upload/image", upload.single("file"), controller.uploadImage);
  router.post("/upload/document", upload.single("file"), controller.uploadDocument);
  
  router.get("/:id/download", controller.downloadDocument);
  router.delete("/:id", controller.deleteFile);

  return router;
}

module.exports = { createFileRouter };
