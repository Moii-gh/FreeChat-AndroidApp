const bcrypt = require("bcrypt");
const { env } = require("../config/env");
const { createJwtToken } = require("../utils/createJwtToken");
const {
  createVerificationCodeChallenge,
  verifyVerificationCode
} = require("../utils/verificationCode");

const GENERIC_EMAIL_CHECK_MESSAGE =
  "Если email доступен для входа или регистрации, продолжайте соответствующий сценарий.";
const GENERIC_CODE_SENT_MESSAGE =
  "Если email подходит для подтверждения, код отправлен на почту.";
const INVALID_CODE_MESSAGE = "Неверный или истекший код подтверждения";

function isVerificationCodeExpired(user) {
  if (!user?.verification_code_expires_at) {
    return true;
  }

  return new Date(user.verification_code_expires_at).getTime() <= Date.now();
}

function isResendCooldownActive(user) {
  if (!user?.verification_code_sent_at) {
    return false;
  }

  const sentAt = new Date(user.verification_code_sent_at).getTime();
  return Number.isFinite(sentAt) &&
    Date.now() - sentAt < env.verificationCodeResendCooldownSeconds * 1000;
}

function ensureEmailVerificationEnabled(emailService) {
  if (!emailService.isConfigured) {
    const error = new Error("Email verification is not configured on the server");
    error.statusCode = 503;
    throw error;
  }
}

function createAuthController({ userModel, emailService }) {
  return {
    checkEmail: async (req, res, next) => {
      try {
        return res.status(200).json({
          message: GENERIC_EMAIL_CHECK_MESSAGE,
          canProceedWithEmail: true
        });
      } catch (error) {
        return next(error);
      }
    },

    register: async (req, res, next) => {
      try {
        ensureEmailVerificationEnabled(emailService);

        const { email, password, fullName, birthDate } = req.validatedBody;
        const existingUser = await userModel.findByEmail(email);

        if (existingUser?.is_verified) {
          return res.status(202).json({
            message: GENERIC_CODE_SENT_MESSAGE
          });
        }

        const passwordHash = await bcrypt.hash(password, 12);
        const verificationChallenge = createVerificationCodeChallenge();

        const savedUser = existingUser
          ? await userModel.updateUnverifiedUser(existingUser.id, {
              passwordHash,
              fullName,
              birthDate,
              verificationCodeHash: verificationChallenge.codeHash,
              verificationCodeExpiresAt: verificationChallenge.expiresAt,
              verificationCodeSentAt: verificationChallenge.sentAt
            })
          : await userModel.createUser({
              email,
              passwordHash,
              fullName,
              birthDate,
              verificationCodeHash: verificationChallenge.codeHash,
              verificationCodeExpiresAt: verificationChallenge.expiresAt,
              verificationCodeSentAt: verificationChallenge.sentAt
            });

        await emailService.sendVerificationCode(email, verificationChallenge.code);

        return res.status(savedUser ? 202 : 200).json({
          message: GENERIC_CODE_SENT_MESSAGE
        });
      } catch (error) {
        return next(error);
      }
    },

    login: async (req, res, next) => {
      try {
        const { email, password } = req.validatedBody;
        const user = await userModel.findByEmail(email);

        if (!user || !user.password_hash) {
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
            message: "Подтвердите почту перед входом"
          });
        }

        return res.status(200).json({
          message: "Вход выполнен",
          token: createJwtToken(user),
          user: userModel.toPublicUser(user)
        });
      } catch (error) {
        return next(error);
      }
    },

    verifyEmail: async (req, res, next) => {
      try {
        const { email, code } = req.validatedBody;
        const user = await userModel.findByEmail(email);

        if (!user || user.is_verified) {
          return res.status(400).json({
            message: INVALID_CODE_MESSAGE
          });
        }

        if (Number(user.verification_attempt_count || 0) >= env.verificationCodeMaxAttempts) {
          return res.status(429).json({
            message: "Лимит попыток исчерпан. Запросите новый код"
          });
        }

        if (
          isVerificationCodeExpired(user) ||
          !user.verification_code_hash ||
          !verifyVerificationCode(code, user.verification_code_hash)
        ) {
          const updatedUser = await userModel.incrementVerificationAttempts(user.id);
          const nextAttempts = Number(updatedUser?.verification_attempt_count || 0);

          return res.status(nextAttempts >= env.verificationCodeMaxAttempts ? 429 : 400).json({
            message:
              nextAttempts >= env.verificationCodeMaxAttempts
                ? "Лимит попыток исчерпан. Запросите новый код"
                : INVALID_CODE_MESSAGE
          });
        }

        const verifiedUser = await userModel.verifyUser(user.id);

        return res.status(200).json({
          message: "Почта успешно подтверждена",
          token: createJwtToken(verifiedUser),
          user: userModel.toPublicUser(verifiedUser)
        });
      } catch (error) {
        return next(error);
      }
    },

    resendCode: async (req, res, next) => {
      try {
        ensureEmailVerificationEnabled(emailService);

        const { email } = req.validatedBody;
        const user = await userModel.findByEmail(email);

        if (!user || user.is_verified || isResendCooldownActive(user)) {
          return res.status(200).json({
            message: GENERIC_CODE_SENT_MESSAGE
          });
        }

        const verificationChallenge = createVerificationCodeChallenge();
        await userModel.updateVerificationChallenge(user.id, {
          verificationCodeHash: verificationChallenge.codeHash,
          verificationCodeExpiresAt: verificationChallenge.expiresAt,
          verificationCodeSentAt: verificationChallenge.sentAt
        });
        await emailService.sendVerificationCode(email, verificationChallenge.code);

        return res.status(200).json({
          message: GENERIC_CODE_SENT_MESSAGE
        });
      } catch (error) {
        return next(error);
      }
    },

    changePassword: async (req, res, next) => {
      try {
        const { currentPassword, newPassword } = req.validatedBody;
        const user = req.user;

        if (!user) {
          return res.status(404).json({
            message: "Пользователь не найден"
          });
        }

        const isPasswordValid = await bcrypt.compare(currentPassword, user.password_hash);
        if (!isPasswordValid) {
          return res.status(401).json({
            message: "Текущий пароль указан неверно"
          });
        }

        const isSamePassword = await bcrypt.compare(newPassword, user.password_hash);
        if (isSamePassword) {
          return res.status(400).json({
            message: "Новый пароль должен отличаться от текущего"
          });
        }

        const passwordHash = await bcrypt.hash(newPassword, 12);
        await userModel.updatePassword(user.id, passwordHash);

        return res.status(200).json({
          message: "Пароль успешно изменен"
        });
      } catch (error) {
        return next(error);
      }
    },

    me: async (req, res, next) => {
      try {
        if (!req.user) {
          return res.status(404).json({
            message: "Пользователь не найден"
          });
        }

        return res.status(200).json({
          message: "Профиль загружен",
          user: userModel.toPublicUser(req.user)
        });
      } catch (error) {
        return next(error);
      }
    }
  };
}

module.exports = { createAuthController };
