const test = require("node:test");
const assert = require("node:assert/strict");
const request = require("supertest");
const { createApp } = require("../app");
const { createJwtToken } = require("../utils/createJwtToken");
const memoryChatShareModel = require("../models/memoryChatShareModel");

function createFakeUserModel() {
  const users = new Map();

  return {
    async findById(userId) {
      return users.get(userId) || null;
    },
    seed(user) {
      users.set(user.id, user);
      return user;
    }
  };
}

function seedUser(userModel) {
  return userModel.seed({
    id: "user-1",
    email: "share@example.com",
    full_name: "Share User",
    is_verified: true,
    token_invalid_before: null
  });
}

test("chat share links expose an immutable snapshot and can be revoked", async () => {
  const userModel = createFakeUserModel();
  const user = seedUser(userModel);
  const app = createApp({
    userModel,
    chatShareModel: memoryChatShareModel,
    rateLimitEnabled: false
  });

  const createResponse = await request(app)
    .post("/api/chat-shares")
    .set("Authorization", `Bearer ${createJwtToken(user)}`)
    .send({
      sourceChatId: "0f9f657d-558a-4711-91bc-bdc9171b1cb0",
      title: "Shared chat",
      summary: "Old context",
      messages: [
        {
          role: "user",
          content: "Hello",
          timestamp: 1000,
          imageUrl: null
        },
        {
          role: "assistant",
          content: "Hi",
          timestamp: 1001,
          imageUrl: null
        }
      ],
      expiresInDays: 30
    });

  assert.equal(createResponse.status, 201);
  assert.match(createResponse.body.shareUrl, /\/share\//);

  const token = createResponse.body.token;
  const getResponse = await request(app).get(`/api/chat-shares/${token}`);
  assert.equal(getResponse.status, 200);
  assert.equal(getResponse.body.title, "Shared chat");
  assert.deepEqual(
    getResponse.body.messages.map((message) => message.content),
    ["Hello", "Hi"]
  );

  const revokeResponse = await request(app)
    .delete(`/api/chat-shares/${token}`)
    .set("Authorization", `Bearer ${createJwtToken(user)}`);

  assert.equal(revokeResponse.status, 200);
  assert.equal(revokeResponse.body.revoked, true);

  const afterRevokeResponse = await request(app).get(`/api/chat-shares/${token}`);
  assert.equal(afterRevokeResponse.status, 404);
});

test("chat share links preserve generated images and attachment metadata", async () => {
  const userModel = createFakeUserModel();
  const user = seedUser(userModel);
  const app = createApp({
    userModel,
    chatShareModel: memoryChatShareModel,
    rateLimitEnabled: false
  });
  const imageUrl = "data:image/png;base64,iVBORw0KGgo=";

  const createResponse = await request(app)
    .post("/api/chat-shares")
    .set("Authorization", `Bearer ${createJwtToken(user)}`)
    .send({
      sourceChatId: "0f9f657d-558a-4711-91bc-bdc9171b1cb0",
      title: "Image chat",
      summary: "",
      messages: [
        {
          role: "assistant",
          content: "",
          timestamp: 2000,
          imageUrl,
          attachmentData: "iVBORw0KGgo=",
          attachmentMimeType: "image/png",
          attachmentFileName: "generated.png"
        }
      ],
      expiresInDays: 30
    });

  assert.equal(createResponse.status, 201);

  const getResponse = await request(app).get(`/api/chat-shares/${createResponse.body.token}`);
  assert.equal(getResponse.status, 200);
  assert.equal(getResponse.body.messages[0].imageUrl, imageUrl);
  assert.equal(getResponse.body.messages[0].attachmentData, "iVBORw0KGgo=");
  assert.equal(getResponse.body.messages[0].attachmentMimeType, "image/png");
  assert.equal(getResponse.body.messages[0].attachmentFileName, "generated.png");
});
