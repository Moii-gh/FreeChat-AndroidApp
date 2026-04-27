const { pool, withTransaction } = require("../config/db");

async function createFile(fileData, executor = pool) {
  const result = await executor.query(
    `INSERT INTO files (
      user_id, file_category, original_name, filename, mime_type, 
      size, storage_type, url, thumb_url, width, height
    ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) RETURNING *`,
    [
      fileData.userId,
      fileData.fileCategory,
      fileData.originalName,
      fileData.filename,
      fileData.mimeType,
      fileData.size,
      fileData.storageType || "local",
      fileData.url || null,
      fileData.thumbUrl || null,
      fileData.width || null,
      fileData.height || null
    ]
  );
  return result.rows[0];
}

async function getFileById(id, executor = pool) {
  const result = await executor.query(`SELECT * FROM files WHERE id = $1`, [id]);
  return result.rows[0];
}

async function getFileByFilename(filename, executor = pool) {
  const result = await executor.query(`SELECT * FROM files WHERE filename = $1`, [filename]);
  return result.rows[0];
}

async function deleteFileById(id, executor = pool) {
  await executor.query(`DELETE FROM files WHERE id = $1`, [id]);
}

async function updateAvatarTransaction(userId, newFileId) {
  return await withTransaction(async (executor) => {
    // Get current avatar file id
    const userResult = await executor.query(`SELECT avatar_file_id FROM users WHERE id = $1`, [userId]);
    const oldAvatarFileId = userResult.rows[0]?.avatar_file_id;

    // Set new avatar file id
    await executor.query(`UPDATE users SET avatar_file_id = $1 WHERE id = $2`, [newFileId, userId]);

    let oldFilename = null;
    if (oldAvatarFileId) {
      const oldFileResult = await executor.query(`SELECT filename FROM files WHERE id = $1`, [oldAvatarFileId]);
      oldFilename = oldFileResult.rows[0]?.filename;
      
      // Delete old file record
      await executor.query(`DELETE FROM files WHERE id = $1`, [oldAvatarFileId]);
    }

    return oldFilename;
  });
}

module.exports = {
  createFile,
  getFileById,
  getFileByFilename,
  deleteFileById,
  updateAvatarTransaction
};
