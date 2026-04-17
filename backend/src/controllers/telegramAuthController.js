const bcrypt = require("bcrypt");
const crypto = require("node:crypto");
const { createJwtToken } = require("../utils/createJwtToken");

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
