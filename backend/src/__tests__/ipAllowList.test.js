const test = require("node:test");
const assert = require("node:assert/strict");
const {
  extractRequestIp,
  isYookassaWebhookIp
} = require("../utils/ipAllowList");

test("extractRequestIp prefers the forwarded public address when the socket is private", () => {
  const ip = extractRequestIp({
    socket: {
      remoteAddress: "::ffff:127.0.0.1"
    },
    headers: {
      "x-forwarded-for": "185.71.76.5, 10.0.0.2"
    }
  });

  assert.equal(ip, "185.71.76.5");
});

test("isYookassaWebhookIp accepts official YooKassa ranges and rejects others", () => {
  assert.equal(isYookassaWebhookIp("185.71.76.5"), true);
  assert.equal(isYookassaWebhookIp("2a02:5180::1"), true);
  assert.equal(isYookassaWebhookIp("203.0.113.10"), false);
  assert.equal(isYookassaWebhookIp("10.0.0.1"), false);
});
