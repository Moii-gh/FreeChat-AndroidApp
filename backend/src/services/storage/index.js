const { LocalStorageProvider } = require("./LocalStorageProvider");

// You can implement conditional logic here to return an S3 provider if configured
const storageProvider = new LocalStorageProvider();

module.exports = { storageProvider };
