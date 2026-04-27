const fileService = require("../services/fileService");

function createFileController() {
  return {
    uploadAvatar: async (req, res, next) => {
      try {
        if (!req.file) {
          return res.status(400).json({ message: "No file uploaded" });
        }
        
        const fileRecord = await fileService.uploadAvatar(req, req.file, req.user.id);
        
        return res.status(200).json({
          message: "Avatar uploaded successfully",
          fileId: fileRecord.id,
          url: fileRecord.url,
          thumbUrl: fileRecord.thumb_url,
          width: fileRecord.width,
          height: fileRecord.height,
          category: fileRecord.file_category
        });
      } catch (error) {
        return next(error);
      }
    },

    uploadImage: async (req, res, next) => {
      try {
        if (!req.file) {
          return res.status(400).json({ message: "No file uploaded" });
        }
        
        const fileRecord = await fileService.processAndUploadImage(req, req.file, req.user.id, "image");
        
        return res.status(200).json({
          message: "Image uploaded successfully",
          fileId: fileRecord.id,
          url: fileRecord.url,
          thumbUrl: fileRecord.thumb_url,
          width: fileRecord.width,
          height: fileRecord.height,
          category: fileRecord.file_category
        });
      } catch (error) {
        return next(error);
      }
    },

    uploadDocument: async (req, res, next) => {
      try {
        if (!req.file) {
          return res.status(400).json({ message: "No file uploaded" });
        }
        
        const fileRecord = await fileService.processAndUploadDocument(req, req.file, req.user.id);
        
        return res.status(200).json({
          message: "Document uploaded successfully",
          fileId: fileRecord.id,
          url: fileRecord.url,
          thumbUrl: fileRecord.thumb_url,
          width: fileRecord.width,
          height: fileRecord.height,
          category: fileRecord.file_category
        });
      } catch (error) {
        return next(error);
      }
    },

    downloadDocument: async (req, res, next) => {
      try {
        const { id } = req.params;
        const { stream, fileRecord } = await fileService.getFileStream(id, req.user.id);
        
        res.setHeader("Content-Type", fileRecord.mime_type);
        res.setHeader("Content-Disposition", `attachment; filename="${encodeURIComponent(fileRecord.original_name)}"`);
        
        stream.pipe(res);
      } catch (error) {
        return next(error);
      }
    },

    deleteFile: async (req, res, next) => {
      try {
        const { id } = req.params;
        await fileService.deleteFile(id, req.user.id);
        
        return res.status(200).json({
          message: "File deleted successfully"
        });
      } catch (error) {
        return next(error);
      }
    }
  };
}

module.exports = { createFileController };
