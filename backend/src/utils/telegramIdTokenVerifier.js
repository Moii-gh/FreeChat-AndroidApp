const crypto = require("node:crypto");

const TELEGRAM_ISSUER = "https://oauth.telegram.org";
const TELEGRAM_JWKS_URL = "https://oauth.telegram.org/.well-known/jwks.json";
const JWKS_CACHE_TTL_MS = 60 * 60 * 1000;

let cachedJwks = null;
let cachedAt = 0;

function base64UrlDecode(value) {
  return Buffer.from(value, "base64url").toString("utf8");
}

function parseJwt(idToken) {
  const parts = idToken.split(".");
  if (parts.length !== 3) {
    throw new Error("Invalid JWT format");
  }

  return {
    header: JSON.parse(base64UrlDecode(parts[0])),
    payload: JSON.parse(base64UrlDecode(parts[1])),
    signingInput: `${parts[0]}.${parts[1]}`,
    signature: Buffer.from(parts[2], "base64url")
  };
}

async function fetchTelegramJwks(fetchImpl = global.fetch, now = Date.now()) {
  if (cachedJwks && now - cachedAt < JWKS_CACHE_TTL_MS) {
    return cachedJwks;
  }

  if (!fetchImpl) {
    throw new Error("Fetch API is not available");
  }

  const response = await fetchImpl(TELEGRAM_JWKS_URL, {
    headers: {
      Accept: "application/json"
    }
  });

  if (!response.ok) {
    throw new Error(`Telegram JWKS request failed: ${response.status}`);
  }

  cachedJwks = await response.json();
  cachedAt = now;
  return cachedJwks;
}

function verifyJwtSignature({ header, signingInput, signature }, jwks) {
  if (header.alg !== "RS256") {
    throw new Error("Unsupported Telegram ID token algorithm");
  }

  const jwk = jwks.keys?.find((key) => key.kid === header.kid);
  if (!jwk) {
    throw new Error("Telegram signing key not found");
  }

  const publicKey = crypto.createPublicKey({
    key: jwk,
    format: "jwk"
  });

  const valid = crypto.verify(
    "RSA-SHA256",
    Buffer.from(signingInput),
    publicKey,
    signature
  );

  if (!valid) {
    throw new Error("Invalid Telegram ID token signature");
  }
}

function verifyClaims(payload, { clientId, now = Date.now() }) {
  if (payload.iss !== TELEGRAM_ISSUER) {
    throw new Error("Invalid Telegram ID token issuer");
  }

  const audiences = Array.isArray(payload.aud) ? payload.aud.map(String) : [String(payload.aud)];
  if (!clientId || !audiences.includes(String(clientId))) {
    throw new Error("Invalid Telegram ID token audience");
  }

  const nowSeconds = Math.floor(now / 1000);
  if (!Number.isInteger(payload.exp) || payload.exp <= nowSeconds) {
    throw new Error("Telegram ID token expired");
  }

  if (payload.iat && payload.iat > nowSeconds + 60) {
    throw new Error("Telegram ID token issued in the future");
  }
}

async function verifyTelegramIdToken(
  idToken,
  { clientId, fetchImpl = global.fetch, now = Date.now(), jwks = null }
) {
  const parsed = parseJwt(idToken);
  const resolvedJwks = jwks || await fetchTelegramJwks(fetchImpl, now);
  verifyJwtSignature(parsed, resolvedJwks);
  verifyClaims(parsed.payload, { clientId, now });
  return parsed.payload;
}

function clearTelegramJwksCache() {
  cachedJwks = null;
  cachedAt = 0;
}

module.exports = {
  TELEGRAM_ISSUER,
  TELEGRAM_JWKS_URL,
  verifyTelegramIdToken,
  clearTelegramJwksCache
};
