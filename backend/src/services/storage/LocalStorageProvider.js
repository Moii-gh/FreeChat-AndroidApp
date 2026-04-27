const fs = require("fs");
const fsPromises = require("fs/promises");
const path = require("path");
const { StorageProvider } = require("./StorageProvider");

class LocalStorageProvider extends StorageProvider {
  constructor() {
    super();
    this.baseDir = path.resolve(__dirname, "../../../../uploads");
    this.publicDir = path.join(this.baseDir, "public");
    this.privateDir = path.join(this.baseDir, "private");

    // Ensure directories exist
    fs.mkdirSync(this.publicDir, { recursive: true });
    fs.mkdirSync(this.privateDir, { recursive: true });
  }

  _getFilePath(filename, isPrivate) {
    const dir = isPrivate ? this.privateDir : this.publicDir;
    return path.join(dir, filename);
  }

  async save(buffer, filename, isPrivate = false) {
    const filePath = this._getFilePath(filename, isPrivate);
    await fsPromises.writeFile(filePath, buffer);
  }

  async delete(filename, isPrivate = false) {
    const filePath = this._getFilePath(filename, isPrivate);
    try {
      await fsPromises.unlink(filePath);
    } catch (error) {
      if (error.code !== "ENOENT") {
        console.error(`Failed to delete file ${filePath}:`, error);
      }
    }
  }

  getStream(filename, isPrivate = false) {
    const filePath = this._getFilePath(filename, isPrivate);
    if (!fs.existsSync(filePath)) {
      throw new Error(`File not found: ${filename}`);
    }
    return fs.createReadStream(filePath);
  }

  getUrl(req, filename, isPrivate = false) {
    if (isPrivate) {
      return null;
    }
    const protocol = req.headers["x-forwarded-proto"] || req.protocol;
    const host = req.headers["x-forwarded-host"] || req.get("host");
    return `${protocol}://${host}/uploads/public/${filename}`;
  }
}

module.exports = { LocalStorageProvider };
