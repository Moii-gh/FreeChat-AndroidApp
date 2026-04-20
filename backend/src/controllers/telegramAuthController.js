const bcrypt = require("bcrypt");
const crypto = require("node:crypto");
const { createJwtToken } = require("../utils/createJwtToken");
const { verifyTelegramIdToken } = require("../utils/telegramIdTokenVerifier");
const { verifyTelegramWidgetAuth } = require("../utils/telegramWidgetVerifier");

const CHALLENGE_TTL_MS = 10 * 60 * 1000;
const PURPOSES = {
  REGISTER: "register",
  LOGIN: "login",
  MIGRATE: "migrate"
};

function createTelegramAuthController({ userModel, challengeModel, telegramConfig }) {
  const ensureTelegramConfigured = (res) => {
    if (!telegramConfig.isConfigured || !telegramConfig.botUsername) {
      res.status(503).json({
        message: "Telegram-бот пока не настроен"
      });
      return false;
    }

    return true;
  };

  const ensureTelegramWidgetConfigured = (res) => {
    if (!telegramConfig.isConfigured || !telegramConfig.botUsername || !telegramConfig.botToken) {
      res.status(503).json({
        message: "Telegram Login Widget пока не настроен"
      });
      return false;
    }

    return true;
  };

  const ensureTelegramNativeConfigured = (res) => {
    if (!telegramConfig.loginClientId) {
      res.status(503).json({
        message: "Telegram Native Login не настроен"
      });
      return false;
    }

    return true;
  };

  const buildBotUrl = (startToken) =>
    `https://t.me/${telegramConfig.botUsername}?start=${encodeURIComponent(startToken)}`;

  const createChallengeResponse = (challenge) => ({
    message: "Откройте Telegram и отправьте команду /start",
    challengeId: challenge.id,
    botUrl: buildBotUrl(challenge.start_token),
    expiresAt: challenge.expires_at
  });

  const isExpired = (challenge) =>
    new Date(challenge.expires_at).getTime() <= Date.now();

  const generateStartToken = () => crypto.randomBytes(24).toString("hex");

  const escapeHtml = (value) =>
    String(value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");

  const safeJson = (value) =>
    JSON.stringify(value).replace(/</g, "\\u003c");

  const getRequestBaseUrl = (req) => {
    if (telegramConfig.publicBaseUrl) {
      return telegramConfig.publicBaseUrl.replace(/\/+$/, "");
    }

    const proto = req.get("x-forwarded-proto") || req.protocol;
    const host = req.get("x-forwarded-host") || req.get("host");
    return `${proto}://${host}`;
  };

  const buildWidgetCallbackUrl = (req) =>
    `${getRequestBaseUrl(req)}/api/telegram-auth/widget-callback`;

  const buildWidgetHtml = (req) => {
    const botUsername = escapeHtml(telegramConfig.botUsername);
    const callbackUrl = escapeHtml(buildWidgetCallbackUrl(req));

    return `<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Telegram Login</title>
  <style>
    :root { color-scheme: light dark; }
    body {
      min-height: 100vh;
      margin: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      background: #101010;
      color: #f5f5f5;
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    main {
      width: min(100% - 32px, 360px);
      text-align: center;
    }
    .hint {
      margin: 0 0 22px;
      color: rgba(245, 245, 245, .72);
      font-size: 15px;
      line-height: 1.45;
    }
  </style>
</head>
<body>
  <main>
    <p class="hint">Войдите через официальный Telegram Login Widget.</p>
    <script>
      function notifyAndroid(method, payload) {
        if (window.AndroidTelegramAuth && typeof window.AndroidTelegramAuth[method] === "function") {
          window.AndroidTelegramAuth[method](payload);
        }
      }

      window.onTelegramAuth = function(user) {
        notifyAndroid("onTelegramAuth", JSON.stringify(user || {}));
      };

      window.onTelegramAuthError = function(message) {
        notifyAndroid("onTelegramAuthError", message || "Не удалось загрузить Telegram Login Widget");
      };
    </script>
    <script async src="https://telegram.org/js/telegram-widget.js?22"
      data-telegram-login="${botUsername}"
      data-size="large"
      data-userpic="true"
      data-radius="8"
      data-request-access="write"
      data-auth-url="${callbackUrl}"
      onerror="onTelegramAuthError('Не удалось загрузить Telegram Login Widget')"></script>
    <noscript>Включите JavaScript, чтобы войти через Telegram.</noscript>
  </main>
</body>
</html>`;
  };

  const buildWidgetCallbackHtml = (authData) => `<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Telegram Login</title>
  <style>
    body {
      min-height: 100vh;
      margin: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      background: #101010;
      color: #f5f5f5;
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      text-align: center;
    }
  </style>
</head>
<body>
  <main>Completing Telegram login...</main>
  <script>
    const payload = ${safeJson(authData)};
    if (window.AndroidTelegramAuth && typeof window.AndroidTelegramAuth.onTelegramAuth === "function") {
      window.AndroidTelegramAuth.onTelegramAuth(JSON.stringify(payload));
    } else {
      document.querySelector("main").textContent = "Return to the app to finish login.";
    }
  </script>
</body>
</html>`;

  const fullNameFromTelegram = ({ first_name: firstName, last_name: lastName, username }) => {
    const fullName = [firstName, lastName].filter(Boolean).join(" ").trim();
    if (fullName) {
      return fullName;
    }

    return username ? `@${username}` : `Telegram ${Date.now()}`;
  };

  const toWidgetProfile = (authData) => ({
    fullName: fullNameFromTelegram(authData),
    telegramUserId: authData.id,
    telegramUsername: authData.username || null,
    telegramFirstName: authData.first_name || null,
    telegramLastName: authData.last_name || null,
    telegramPhotoUrl: authData.photo_url || null
  });

  const toNativeProfile = (claims) => ({
    fullName: claims.name || claims.preferred_username || `Telegram ${claims.sub}`,
    telegramUserId: claims.id || claims.sub,
    telegramUsername: claims.preferred_username || null,
    telegramFirstName: claims.name || null,
    telegramLastName: null,
    telegramPhotoUrl: claims.picture || null
  });

  const upsertTelegramUser = async (profile) => {
    const existingUser = await userModel.findByTelegramUserId(profile.telegramUserId);
    const user = existingUser
      ? await userModel.updateTelegramWidgetProfile(existingUser.id, profile)
      : await userModel.createTelegramWidgetUser(profile);

    return { user, existingUser };
  };

  const loadActiveChallenge = async (challengeId, res) => {
    const challenge = await challengeModel.findById(challengeId);
    if (!challenge) {
      res.status(404).json({
        message: "Попытка входа не найдена"
      });
      return null;
    }

    if (challenge.consumed_at) {
      res.status(400).json({
        message: "Эта попытка уже завершена"
      });
      return null;
    }

    if (isExpired(challenge)) {
      res.status(400).json({
        message: "Код истёк. Начните заново"
      });
      return null;
    }

    return challenge;
  };

  const ensureVerifiedChallenge = async (challengeId, expectedPurpose, res) => {
    const challenge = await loadActiveChallenge(challengeId, res);
    if (!challenge) {
      return null;
    }

    if (challenge.purpose !== expectedPurpose) {
      res.status(400).json({
        message: "Сценарий подтверждения не совпадает"
      });
      return null;
    }

    if (!challenge.verified_at) {
      res.status(400).json({
        message: "Сначала подтвердите код из Telegram"
      });
      return null;
    }

    return challenge;
  };

  return {
    widgetPage: async (req, res, next) => {
      try {
        if (!ensureTelegramWidgetConfigured(res)) {
          return;
        }

        res
          .status(200)
          .type("html")
          .send(buildWidgetHtml(req));
      } catch (error) {
        return next(error);
      }
    },

    widgetCallback: async (req, res, next) => {
      try {
        const authData = {
          id: req.query.id,
          first_name: req.query.first_name,
          last_name: req.query.last_name,
          username: req.query.username,
          photo_url: req.query.photo_url,
          auth_date: req.query.auth_date,
          hash: req.query.hash
        };

        res
          .status(200)
          .type("html")
          .send(buildWidgetCallbackHtml(authData));
      } catch (error) {
        return next(error);
      }
    },

    completeWidgetLogin: async (req, res, next) => {
      try {
        if (!ensureTelegramWidgetConfigured(res)) {
          return;
        }

        const authData = req.validatedBody;
        const verification = verifyTelegramWidgetAuth(authData, {
          botToken: telegramConfig.botToken,
          maxAgeSeconds: telegramConfig.widgetMaxAgeSeconds
        });

        if (!verification.ok) {
          return res.status(401).json({
            message: "Не удалось проверить данные Telegram"
          });
        }

        const profile = toWidgetProfile(authData);
        const { user, existingUser } = await upsertTelegramUser(profile);

        return res.status(existingUser ? 200 : 201).json({
          message: existingUser ? "Вход через Telegram выполнен" : "Аккаунт создан через Telegram",
          token: createJwtToken(user),
          user: userModel.toPublicUser(user)
        });
      } catch (error) {
        return next(error);
      }
    },

    completeNativeLogin: async (req, res, next) => {
      try {
        if (!ensureTelegramNativeConfigured(res)) {
          return;
        }

        const claims = await verifyTelegramIdToken(req.validatedBody.idToken, {
          clientId: telegramConfig.loginClientId,
          fetchImpl: telegramConfig.fetchImpl || global.fetch,
          jwks: telegramConfig.jwks || null
        });
        const profile = toNativeProfile(claims);
        const { user, existingUser } = await upsertTelegramUser(profile);

        return res.status(existingUser ? 200 : 201).json({
          message: existingUser ? "Вход через Telegram выполнен" : "Аккаунт создан через Telegram",
          token: createJwtToken(user),
          user: userModel.toPublicUser(user)
        });
      } catch (error) {
        if (
          error.message?.includes("Telegram ID token") ||
          error.message?.includes("Invalid JWT") ||
          error.message?.includes("Unsupported Telegram") ||
          error.message?.includes("Telegram signing key")
        ) {
          return res.status(401).json({
            message: "Не удалось проверить Telegram ID token"
          });
        }

        return next(error);
      }
    },

    beginRegistration: async (_req, res, next) => {
      try {
        if (!ensureTelegramConfigured(res)) {
          return;
        }

        const challenge = await challengeModel.createChallenge({
          purpose: PURPOSES.REGISTER,
          startToken: generateStartToken(),
          expiresAt: new Date(Date.now() + CHALLENGE_TTL_MS).toISOString()
        });

        return res.status(200).json(createChallengeResponse(challenge));
      } catch (error) {
        return next(error);
      }
    },

    beginLogin: async (_req, res, next) => {
      try {
        if (!ensureTelegramConfigured(res)) {
          return;
        }

        const challenge = await challengeModel.createChallenge({
          purpose: PURPOSES.LOGIN,
          startToken: generateStartToken(),
          expiresAt: new Date(Date.now() + CHALLENGE_TTL_MS).toISOString()
        });

        return res.status(200).json(createChallengeResponse(challenge));
      } catch (error) {
        return next(error);
      }
    },

    verifyCode: async (req, res, next) => {
      try {
        const { challengeId, code } = req.validatedBody;
        const challenge = await loadActiveChallenge(challengeId, res);

        if (!challenge) {
          return;
        }

        if (!challenge.telegram_user_id || !challenge.code) {
          return res.status(400).json({
            message: "Сначала откройте Telegram-бота и отправьте /start"
          });
        }

        if (challenge.code !== code) {
          return res.status(400).json({
            message: "Неверный код"
          });
        }

        if (!challenge.verified_at) {
          await challengeModel.markVerified(challenge.id);
        }

        return res.status(200).json({
          message: "Код подтверждён",
          verified: true,
          purpose: challenge.purpose
        });
      } catch (error) {
        return next(error);
      }
    },

    completeRegistration: async (req, res, next) => {
      try {
        const { challengeId, fullName, birthDate, password } = req.validatedBody;
        const challenge = await ensureVerifiedChallenge(challengeId, PURPOSES.REGISTER, res);

        if (!challenge) {
          return;
        }

        const existingLinkedUser = await userModel.findByTelegramUserId(challenge.telegram_user_id);
        if (existingLinkedUser) {
          return res.status(409).json({
            message: "Этот Telegram уже привязан к другому аккаунту"
          });
        }

        const passwordHash = await bcrypt.hash(password, 12);
        const user = await userModel.createTelegramUser({
          passwordHash,
          fullName,
          birthDate,
          telegramUserId: challenge.telegram_user_id,
          telegramChatId: challenge.telegram_chat_id,
          telegramUsername: challenge.telegram_username
        });

        await challengeModel.attachUser(challenge.id, user.id);
        await challengeModel.consumeChallenge(challenge.id);

        return res.status(201).json({
          message: "Аккаунт создан",
          token: createJwtToken(user),
          user: userModel.toPublicUser(user)
        });
      } catch (error) {
        return next(error);
      }
    },

    completeLogin: async (req, res, next) => {
      try {
        const { challengeId, password } = req.validatedBody;
        const challenge = await ensureVerifiedChallenge(challengeId, PURPOSES.LOGIN, res);

        if (!challenge) {
          return;
        }

        const user = await userModel.findByTelegramUserId(challenge.telegram_user_id);
        if (!user) {
          return res.status(404).json({
            message: "Аккаунт для этого Telegram не найден"
          });
        }

        if (!user.password_hash) {
          return res.status(409).json({
            message: "Войдите через Telegram Login Widget"
          });
        }

        const isPasswordValid = await bcrypt.compare(password, user.password_hash);
        if (!isPasswordValid) {
          return res.status(401).json({
            message: "Неверный пароль"
          });
        }

        await challengeModel.attachUser(challenge.id, user.id);
        await challengeModel.consumeChallenge(challenge.id);

        return res.status(200).json({
          message: "Вход выполнен",
          token: createJwtToken(user),
          user: userModel.toPublicUser(user)
        });
      } catch (error) {
        return next(error);
      }
    },

    beginMigration: async (req, res, next) => {
      try {
        if (!ensureTelegramConfigured(res)) {
          return;
        }

        const { email, password } = req.validatedBody;
        const user = await userModel.findByEmail(email);

        if (!user) {
          return res.status(401).json({
            message: "Неверный email или пароль"
          });
        }

        const isPasswordValid = await bcrypt.compare(password, user.password_hash);
        if (!isPasswordValid) {
          return res.status(401).json({
            message: "Неверный email или пароль"
          });
        }

        if (!user.is_verified) {
          return res.status(403).json({
            message: "Старый аккаунт ещё не подтверждён"
          });
        }

        if (user.auth_provider === "telegram" && user.telegram_user_id) {
          return res.status(409).json({
            message: "Этот аккаунт уже переведён на Telegram"
          });
        }

        const challenge = await challengeModel.createChallenge({
          purpose: PURPOSES.MIGRATE,
          userId: user.id,
          startToken: generateStartToken(),
          expiresAt: new Date(Date.now() + CHALLENGE_TTL_MS).toISOString()
        });

        return res.status(200).json(createChallengeResponse(challenge));
      } catch (error) {
        return next(error);
      }
    },

    completeMigration: async (req, res, next) => {
      try {
        const { challengeId } = req.validatedBody;
        const challenge = await ensureVerifiedChallenge(challengeId, PURPOSES.MIGRATE, res);

        if (!challenge) {
          return;
        }

        const user = await userModel.findById(challenge.user_id);
        if (!user) {
          return res.status(404).json({
            message: "Аккаунт не найден"
          });
        }

        const existingLinkedUser = await userModel.findByTelegramUserId(challenge.telegram_user_id);
        if (existingLinkedUser && existingLinkedUser.id !== user.id) {
          return res.status(409).json({
            message: "Этот Telegram уже привязан к другому аккаунту"
          });
        }

        const migratedUser = await userModel.attachTelegramIdentity(user.id, {
          telegramUserId: challenge.telegram_user_id,
          telegramChatId: challenge.telegram_chat_id,
          telegramUsername: challenge.telegram_username,
          authProvider: "telegram"
        });

        await challengeModel.consumeChallenge(challenge.id);

        return res.status(200).json({
          message: "Аккаунт переведён на Telegram",
          token: createJwtToken(migratedUser),
          user: userModel.toPublicUser(migratedUser)
        });
      } catch (error) {
        return next(error);
      }
    }
  };
}

module.exports = { createTelegramAuthController, PURPOSES, CHALLENGE_TTL_MS };
