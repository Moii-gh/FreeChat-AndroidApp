const crypto = require("node:crypto");

const HEX_HASH_REGEX = /^[a-f0-9]{64}$/i;

function buildDataCheckString(authData) {
  return Object.entries(authData)
    .filter(([key, value]) => key !== "hash" && value !== undefined && value !== null && value !== "")
    .map(([key, value]) => [key, String(value)])
    .sort(([left], [right]) => left.localeCompare(right))
    .map(([key, value]) => `${key}=${value}`)
    .join("\n");
}

function timingSafeHexEqual(left, right) {
  if (!HEX_HASH_REGEX.test(left) || !HEX_HASH_REGEX.test(right)) {
    return false;
  }

  const leftBuffer = Buffer.from(left, "hex");
  const rightBuffer = Buffer.from(right, "hex");

  return leftBuffer.length === rightBuffer.length &&
    crypto.timingSafeEqual(leftBuffer, rightBuffer);
}

function signTelegramWidgetData(authData, botToken) {
  const secretKey = crypto.createHash("sha256").update(botToken).digest();
  const dataCheckString = buildDataCheckString(authData);

  return crypto
    .createHmac("sha256", secretKey)
    .update(dataCheckString)
    .digest("hex");
}

function verifyTelegramWidgetAuth(authData, { botToken, maxAgeSeconds = 86400, now = Date.now() }) {
  if (!botToken) {
    return { ok: false, reason: "missing_bot_token" };
  }

  const expectedHash = signTelegramWidgetData(authData, botToken);
  if (!timingSafeHexEqual(expectedHash, authData.hash)) {
    return { ok: false, reason: "invalid_hash" };
  }

  // Telegram provides auth_date as a Unix timestamp. Keep the replay window short.
  const nowSeconds = Math.floor(now / 1000);
  if (authData.auth_date > nowSeconds + 60) {
    return { ok: false, reason: "auth_date_in_future" };
  }

  if (nowSeconds - authData.auth_date > maxAgeSeconds) {
    return { ok: false, reason: "auth_date_expired" };
  }

  return { ok: true };
}

module.exports = {
  buildDataCheckString,
  signTelegramWidgetData,
  verifyTelegramWidgetAuth
};
