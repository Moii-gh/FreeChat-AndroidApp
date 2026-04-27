const sharp = require("sharp");
const fileType = require("file-type");
const { v4: uuidv4 } = require("uuid");
const path = require("path");
const fileModel = require("../models/fileModel");
const { storageProvider } = require("./storage");

const ALLOWED_IMAGE_TYPES = ["image/jpeg", "image/png", "image/webp"];
const ALLOWED_DOC_TYPES = [
  "application/pdf",
  "text/plain",
  "application/msword",
  "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
];

function isPrivateCategory(category) {
  return category === "document";
}

async function validateAndProcessImage(buffer) {
  try {
    const metadata = await sharp(buffer).metadata();
    if (!ALLOWED_IMAGE_TYPES.includes(`image/${metadata.format}`)) {
      throw new Error(`Unsupported image format: ${metadata.format}`);
    }

    // Convert to webp and resize if too large
    const optimizedBuffer = await sharp(buffer)
      .resize(1920, 1920, { fit: "inside", withoutEnlargement: true })
      .webp({ quality: 80 })
      .toBuffer();

    const optimizedMetadata = await sharp(optimizedBuffer).metadata();

    // Generate thumbnail
    const thumbBuffer = await sharp(optimizedBuffer)
      .resize(256, 256, { fit: "cover" })
      .webp({ quality: 70 })
      .toBuffer();

    return {
      buffer: optimizedBuffer,
      thumbBuffer,
      mimeType: "image/webp",
      extension: ".webp",
      width: optimizedMetadata.width,
      height: optimizedMetadata.height,
      size: optimizedBuffer.length
    };
  } catch (error) {
    const err = new Error("Invalid image file or unsupported format");
    err.statusCode = 400;
    throw err;
  }
}

async function validateDocument(buffer, originalMimeType) {
  const type = await fileType.fromBuffer(buffer);
  
  // If file-type doesn't recognize it, but we allow text/plain, we do a basic check
  if (!type && originalMimeType === "text/plain") {
    // Simple ASCII/UTF-8 check could be done here, assuming valid if small
    return { mimeType: "text/plain", extension: ".txt" };
  }

  if (!type) {
    const err = new Error("Could not determine file type");
    err.statusCode = 400;
    throw err;
  }

  if (!ALLOWED_DOC_TYPES.includes(type.mime)) {
    const err = new Error(`Unsupported document format: ${type.mime}`);
    err.statusCode = 400;
    throw err;
  }

  return { mimeType: type.mime, extension: `.${type.ext}` };
}

class FileService {
  async processAndUploadImage(req, file, userId, category) {
    const { buffer, thumbBuffer, mimeType, extension, width, height, size } = await validateAndProcessImage(file.buffer);
    
    const isPrivate = isPrivateCategory(category);
    const baseFilename = uuidv4();
    const filename = `${baseFilename}${extension}`;
    const thumbFilename = `${baseFilename}_thumb${extension}`;

    await storageProvider.save(buffer, filename, isPrivate);
    await storageProvider.save(thumbBuffer, thumbFilename, isPrivate);

    const url = storageProvider.getUrl(req, filename, isPrivate);
    const thumbUrl = storageProvider.getUrl(req, thumbFilename, isPrivate);

    const fileData = {
      userId,
      fileCategory: category,
      originalName: file.originalname,
      filename,
      mimeType,
      size,
      storageType: "local",
      url,
      thumbUrl,
      width,
      height
    };

    return await fileModel.createFile(fileData);
  }

  async processAndUploadDocument(req, file, userId) {
    const { mimeType, extension } = await validateDocument(file.buffer, file.mimetype);
    
    const isPrivate = true; // Documents are private
    const baseFilename = uuidv4();
    const filename = `${baseFilename}${extension}`;

    await storageProvider.save(file.buffer, filename, isPrivate);

    const fileData = {
      userId,
      fileCategory: "document",
      originalName: file.originalname,
      filename,
      mimeType,
      size: file.buffer.length,
      storageType: "local",
      url: null, // Private documents don't have public URLs
      thumbUrl: null,
      width: null,
      height: null
    };

    return await fileModel.createFile(fileData);
  }

  async uploadAvatar(req, file, userId) {
    // 1. Process and save new avatar
    const newFileRecord = await this.processAndUploadImage(req, file, userId, "avatar");

    // 2. Transaction to update user and delete old avatar record
    const oldFilename = await fileModel.updateAvatarTransaction(userId, newFileRecord.id);

    // 3. Delete old file physically if it exists
    if (oldFilename) {
      await storageProvider.delete(oldFilename, false);
      const ext = path.extname(oldFilename);
      const base = path.basename(oldFilename, ext);
      await storageProvider.delete(`${base}_thumb${ext}`, false);
    }

    return newFileRecord;
  }

  async deleteFile(fileId, userId) {
    const fileRecord = await fileModel.getFileById(fileId);
    if (!fileRecord) {
      const err = new Error("File not found");
      err.statusCode = 404;
      throw err;
    }

    if (fileRecord.user_id !== userId) {
      const err = new Error("Forbidden: You do not own this file");
      err.statusCode = 403;
      throw err;
    }

    const isPrivate = isPrivateCategory(fileRecord.file_category);
    await storageProvider.delete(fileRecord.filename, isPrivate);
    
    if (fileRecord.thumb_url) {
      const ext = path.extname(fileRecord.filename);
      const base = path.basename(fileRecord.filename, ext);
      await storageProvider.delete(`${base}_thumb${ext}`, isPrivate);
    }

    await fileModel.deleteFileById(fileId);
  }

  async getFileStream(fileId, userId) {
    const fileRecord = await fileModel.getFileById(fileId);
    if (!fileRecord) {
      const err = new Error("File not found");
      err.statusCode = 404;
      throw err;
    }

    // Access control for private files
    if (isPrivateCategory(fileRecord.file_category) && fileRecord.user_id !== userId) {
      const err = new Error("Forbidden: You do not have access to this file");
      err.statusCode = 403;
      throw err;
    }

    const isPrivate = isPrivateCategory(fileRecord.file_category);
    const stream = storageProvider.getStream(fileRecord.filename, isPrivate);
    return { stream, fileRecord };
  }
}

module.exports = new FileService();
