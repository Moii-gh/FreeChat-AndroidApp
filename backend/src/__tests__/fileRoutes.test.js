const { test, describe, before, after, mock } = require("node:test");
const assert = require("node:assert");
const request = require("supertest");
const { createApp } = require("../app");
const { createJwtToken } = require("../utils/createJwtToken");
const fileModel = require("../models/fileModel");
const { storageProvider } = require("../services/storage");

function createFakeUserModel() {
  const users = new Map();
  return {
    async findById(userId) { return users.get(userId) || null; },
    seed(user) { users.set(user.id, user); return user; }
  };
}

describe("File Routes", () => {
  let app;
  let userToken;
  let otherUserToken;

  before(() => {
    const userModel = createFakeUserModel();
    const user1 = userModel.seed({ id: "user-1", email: "test1@test.com", full_name: "Test 1", is_verified: true });
    const user2 = userModel.seed({ id: "user-2", email: "test2@test.com", full_name: "Test 2", is_verified: true });

    userToken = createJwtToken(user1);
    otherUserToken = createJwtToken(user2);

    app = createApp({ userModel, rateLimitEnabled: false });

    mock.method(fileModel, "createFile", async (data) => ({ ...data, id: "fake-file-id" }));
    mock.method(fileModel, "getFileById", async (id) => {
      if (id === "fake-file-id") {
        return {
          id: "fake-file-id",
          user_id: "user-1",
          file_category: "document",
          original_name: "secret.txt",
          filename: "secret-uuid.txt",
          mime_type: "text/plain",
          size: 100,
          storage_type: "local",
          url: null,
          thumb_url: null,
          width: null,
          height: null
        };
      }
      return null;
    });

    mock.method(storageProvider, "save", async () => {});
    mock.method(storageProvider, "getStream", () => {
      const { Readable } = require("stream");
      return Readable.from(["this is a private doc"]);
    });
    mock.method(storageProvider, "getUrl", () => null);
  });

  after(() => {
    mock.restoreAll();
  });

  test("Should fail to upload spoofed image", async () => {
    const fakeImageBuffer = Buffer.from("this is not an image");
    const response = await request(app)
      .post("/api/files/upload/image")
      .set("Authorization", `Bearer ${userToken}`)
      .attach("file", fakeImageBuffer, "fake.png");
    assert.equal(response.status, 400);
    assert.match(response.body.message, /Invalid image file/);
  });

  test("Should upload document successfully", async () => {
    const docBuffer = Buffer.from("this is a test document content");
    const response = await request(app)
      .post("/api/files/upload/document")
      .set("Authorization", `Bearer ${userToken}`)
      .attach("file", docBuffer, { filename: "test.txt", contentType: "text/plain" });
    assert.equal(response.status, 200);
    assert.equal(response.body.message, "Document uploaded successfully");
    assert.equal(response.body.fileId, "fake-file-id");
  });

  test("Should download document if owner", async () => {
    const downloadRes = await request(app)
      .get("/api/files/fake-file-id/download")
      .set("Authorization", `Bearer ${userToken}`);
    assert.equal(downloadRes.status, 200);
    assert.equal(downloadRes.text, "this is a private doc");
  });

  test("Should not download document if not owner", async () => {
    const downloadRes = await request(app)
      .get("/api/files/fake-file-id/download")
      .set("Authorization", `Bearer ${otherUserToken}`);
    assert.equal(downloadRes.status, 403);
    assert.match(downloadRes.body.message, /Forbidden/);
  });
});
