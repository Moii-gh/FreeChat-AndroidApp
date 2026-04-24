const crypto = require("node:crypto");
const { env } = require("../config/env");

const TOKEN_BYTES = 32;
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{32,128}$/;

function createToken() {
  return crypto.randomBytes(TOKEN_BYTES).toString("base64url");
}

function hashToken(token) {
  return crypto.createHash("sha256").update(token).digest("hex");
}

function normalizeBaseUrl(value) {
  return String(value || "").trim().replace(/\/+$/, "");
}

function getRequestBaseUrl(req) {
  return `${req.protocol}://${req.get("host")}`;
}

function buildShareUrl(req, token) {
  const baseUrl = normalizeBaseUrl(env.chatSharePublicBaseUrl) || getRequestBaseUrl(req);
  return `${baseUrl}/share/${encodeURIComponent(token)}`;
}

function buildAppSchemeUrl(token) {
  return `freechat://share/${encodeURIComponent(token)}`;
}

function escapeHtml(value) {
  return String(value || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function assertValidToken(token) {
  if (!token || !TOKEN_PATTERN.test(token)) {
    const error = new Error("Invalid share token");
    error.statusCode = 400;
    throw error;
  }
}

function toSnapshotResponse(row, token) {
  const snapshot = row.snapshot || {};
  return {
    token,
    title: snapshot.title || row.title,
    summary: snapshot.summary || row.summary || "",
    messages: Array.isArray(snapshot.messages) ? snapshot.messages : [],
    createdAt: snapshot.createdAt || row.createdAt,
    expiresAt: row.expiresAt
  };
}

function createChatShareController({ chatShareModel }) {
  return {
    create: async (req, res, next) => {
      try {
        const userId = req.user.id;
        const {
          sourceChatId,
          title,
          summary,
          messages,
          expiresInDays
        } = req.validatedBody || req.body;

        const token = createToken();
        const tokenHash = hashToken(token);
        const createdAt = new Date();
        const expiresAt = new Date(createdAt.getTime() + expiresInDays * 24 * 60 * 60 * 1000);
        const snapshot = {
          sourceChatId,
          title,
          summary: summary || "",
          createdAt: createdAt.toISOString(),
          messages
        };

        await chatShareModel.createShare({
          ownerUserId: userId,
          sourceChatId,
          tokenHash,
          title,
          summary: summary || "",
          snapshot,
          expiresAt: expiresAt.toISOString()
        });

        return res.status(201).json({
          token,
          shareUrl: buildShareUrl(req, token),
          expiresAt: expiresAt.toISOString()
        });
      } catch (error) {
        return next(error);
      }
    },

    get: async (req, res, next) => {
      try {
        const token = req.params.token || "";
        assertValidToken(token);

        const row = await chatShareModel.findActiveByTokenHash(hashToken(token));
        if (!row) {
          return res.status(404).json({
            message: "Share link is unavailable or expired"
          });
        }

        return res.status(200).json(toSnapshotResponse(row, token));
      } catch (error) {
        return next(error);
      }
    },

    revokeToken: async (req, res, next) => {
      try {
        const token = req.params.token || "";
        assertValidToken(token);

        const revokedCount = await chatShareModel.revokeByTokenHash(
          req.user.id,
          hashToken(token)
        );
        return res.status(200).json({
          revoked: revokedCount > 0,
          revokedCount
        });
      } catch (error) {
        return next(error);
      }
    },

    revokeChat: async (req, res, next) => {
      try {
        const sourceChatId = req.params.chatId || "";
        const revokedCount = await chatShareModel.revokeBySourceChat(req.user.id, sourceChatId);
        return res.status(200).json({
          revoked: revokedCount > 0,
          revokedCount
        });
      } catch (error) {
        return next(error);
      }
    },

    landing: async (req, res, next) => {
      try {
        const token = req.params.token || "";
        assertValidToken(token);

        const row = await chatShareModel.findActiveByTokenHash(hashToken(token));
        if (!row) {
          return res.status(404).type("html").send(`<!doctype html>
<html lang="en">
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FreeChat</title></head>
<body style="font-family:system-ui,sans-serif;background:#111;color:#fff;padding:32px">
<h1>Share link unavailable</h1>
<p>This chat link has expired or was disabled by its owner.</p>
</body>
</html>`);
        }

        const openUrl = buildAppSchemeUrl(token);
        const storeUrl = normalizeBaseUrl(env.chatShareStoreUrl);
        const escapedTitle = escapeHtml(row.title || "Shared chat");
        const installLink = storeUrl
          ? `<a style="color:#fff" href="${escapeHtml(storeUrl)}">Install FreeChat</a>`
          : "";
        const storeRedirectScript = storeUrl
          ? `setTimeout(function () { window.location.href = ${JSON.stringify(storeUrl)}; }, 1600);`
          : "";

        return res.status(200).type("html").send(`<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Open FreeChat</title>
</head>
<body style="font-family:system-ui,sans-serif;background:#111;color:#fff;padding:32px">
  <h1>${escapedTitle}</h1>
  <p>Open this frozen chat copy in FreeChat to continue it as a separate session.</p>
  <p><a style="display:inline-block;background:#fff;color:#111;padding:12px 16px;border-radius:8px;text-decoration:none" href="${openUrl}">Open in FreeChat</a></p>
  ${installLink}
  <script>
    setTimeout(function () {
      window.location.href = ${JSON.stringify(openUrl)};
    }, 250);
    ${storeRedirectScript}
  </script>
</body>
</html>`);
      } catch (error) {
        return next(error);
      }
    }
  };
}

module.exports = {
  createChatShareController,
  hashToken
};
