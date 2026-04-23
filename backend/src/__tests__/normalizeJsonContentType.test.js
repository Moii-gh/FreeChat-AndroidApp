const test = require("node:test");
const assert = require("node:assert/strict");
const express = require("express");
const request = require("supertest");
const { normalizeJsonContentType } = require("../middleware/normalizeJsonContentType");

test("normalizeJsonContentType fixes malformed utf-8 JSON header", async () => {
  const app = express();

  app.use(normalizeJsonContentType);
  app.use(express.json());
  app.post("/echo", (req, res) => {
    res.status(200).json({ body: req.body });
  });

  const response = await request(app)
    .post("/echo")
    .set("Content-Type", "application/json; utf-8")
    .send({
      request: {
        messages: [{ role: "user", content: "hello" }]
      }
    });

  assert.equal(response.status, 200);
  assert.deepEqual(response.body.body, {
    request: {
      messages: [{ role: "user", content: "hello" }]
    }
  });
});
