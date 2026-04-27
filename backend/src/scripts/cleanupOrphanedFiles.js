const fs = require("fs");
const fsPromises = require("fs/promises");
const path = require("path");
const { pool } = require("../config/db");

async function cleanupDir(dirPath, dbFilesSet) {
  if (!fs.existsSync(dirPath)) return;

  const files = await fsPromises.readdir(dirPath);
  let deletedCount = 0;

  for (const file of files) {
    // skip hidden files or directories
    if (file.startsWith(".")) continue;
    
    const stats = await fsPromises.stat(path.join(dirPath, file));
    if (stats.isDirectory()) continue;

    // Remove _thumb to check against DB
    let baseFileForCheck = file;
    if (file.includes("_thumb")) {
      baseFileForCheck = file.replace("_thumb", "");
    }

    if (!dbFilesSet.has(baseFileForCheck)) {
      console.log(`Deleting orphaned file: ${file}`);
      await fsPromises.unlink(path.join(dirPath, file));
      deletedCount++;
    }
  }

  return deletedCount;
}

async function runCleanup() {
  console.log("Starting cleanup of orphaned files...");
  
  try {
    const result = await pool.query("SELECT filename FROM files");
    const dbFilesSet = new Set(result.rows.map(r => r.filename));

    const publicDir = path.resolve(__dirname, "../../../uploads/public");
    const privateDir = path.resolve(__dirname, "../../../uploads/private");

    const publicDeleted = await cleanupDir(publicDir, dbFilesSet);
    const privateDeleted = await cleanupDir(privateDir, dbFilesSet);

    console.log(`Cleanup finished. Deleted ${publicDeleted} public files and ${privateDeleted} private files.`);
  } catch (error) {
    console.error("Error during cleanup:", error);
  } finally {
    pool.end();
  }
}

if (require.main === module) {
  runCleanup();
}

module.exports = { runCleanup };
