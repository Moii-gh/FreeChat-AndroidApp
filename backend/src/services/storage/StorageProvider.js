class StorageProvider {
  /**
   * @param {Buffer} buffer 
   * @param {string} filename 
   * @param {boolean} isPrivate 
   * @returns {Promise<void>}
   */
  async save(buffer, filename, isPrivate = false) {
    throw new Error("Method 'save()' must be implemented.");
  }

  /**
   * @param {string} filename 
   * @param {boolean} isPrivate 
   * @returns {Promise<void>}
   */
  async delete(filename, isPrivate = false) {
    throw new Error("Method 'delete()' must be implemented.");
  }

  /**
   * @param {string} filename 
   * @param {boolean} isPrivate 
   * @returns {import('stream').Readable}
   */
  getStream(filename, isPrivate = false) {
    throw new Error("Method 'getStream()' must be implemented.");
  }

  /**
   * @param {import('express').Request} req
   * @param {string} filename 
   * @param {boolean} isPrivate 
   * @returns {string | null}
   */
  getUrl(req, filename, isPrivate = false) {
    throw new Error("Method 'getUrl()' must be implemented.");
  }
}

module.exports = { StorageProvider };
