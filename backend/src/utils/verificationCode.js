const crypto = require("node:crypto");
const { env } = require("../config/env");

function generateVerificationCode() {
  return crypto.randomInt(100000, 1000000).toString();
}

function hashVerificationCode(code) {
  return crypto
    .createHmac("sha256", env.verificationCodeSecret)
    .update(String(code))
    .digest("hex");
}

function constantTimeEquals(left, right) {
  if (!left || !right) {
    return false;
  }

  const leftBuffer = Buffer.from(String(left), "hex");
  const rightBuffer = Buffer.from(String(right), "hex");

  return leftBuffer.length === rightBuffer.length &&
    crypto.timingSafeEqual(leftBuffer, rightBuffer);
}

function verifyVerificationCode(code, storedHash) {
  return constantTimeEquals(hashVerificationCode(code), storedHash);
}

function createVerificationCodeChallenge(code = generateVerificationCode()) {
  const expiresAt = new Date(
    Date.now() + env.verificationCodeTtlMinutes * 60 * 1000
  ).toISOString();

  return {
    code,
    codeHash: hashVerificationCode(code),
    expiresAt,
    sentAt: new Date().toISOString()
  };
}

module.exports = {
  generateVerificationCode,
  hashVerificationCode,
  verifyVerificationCode,
  createVerificationCodeChallenge
};
