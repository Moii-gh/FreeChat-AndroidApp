const multer = require("multer");

// Use memory storage because we process files with Sharp/file-type 
// before persisting them to the disk or cloud.
const storage = multer.memoryStorage();

const upload = multer({
  storage,
  limits: {
    fileSize: 20 * 1024 * 1024 // 20 MB limit per file
  }
});

module.exports = { upload };
