const bcrypt = require("bcrypt");
const { createJwtToken } = require("../utils/createJwtToken");
const { generateVerificationCode } = require("../utils/generateVerificationCode");

function createAuthController({ userModel, emailService }) {
  return {
    checkEmail: async (req, res, next) => {
      try {
        const { email } = req.validatedBody;
        const user = await userModel.findByEmail(email);

        if (!user) {
          return res.status(200).json({
            message: "Почта свободна для новой регистрации",
            exists: false,
            isVerified: false,
            user: null
          });
        }

        if (user.is_verified) {
          return res.status(200).json({
            message: "Аккаунт найден. Введите пароль",
            exists: true,
            isVerified: true,
            user: userModel.toPublicUser(user)
          });
        }

        return res.status(200).json({
          message: "Аккаунт не завершен. Продолжите регистрацию",
          exists: true,
          isVerified: false,
          user: userModel.toPublicUser(user)
        });
      } catch (error) {
        return next(error);
      }
    },

    register: async (req, res, next) => {
      try {
        const { email, password, fullName, birthDate } = req.validatedBody;
        const existingUser = await userModel.findByEmail(email);

        if (existingUser?.is_verified) {
          return res.status(409).json({
            message: "Пользователь с таким email уже зарегистрирован"
          });
        }

        const passwordHash = await bcrypt.hash(password, 12);
        const verificationCode = generateVerificationCode();

        const savedUser = existingUser
          ? await userModel.updateUnverifiedUser(existingUser.id, {
              passwordHash,
              fullName,
              birthDate,
              verificationCode
            })
          : await userModel.createUser({
              email,
              passwordHash,
              fullName,
              birthDate,
              verificationCode
            });

        await emailService.sendVerificationCode(email, verificationCode);
        const message = emailService.isConfigured
          ? "Код подтверждения отправлен на почту"
          : `Почтовый сервис не настроен. Используйте код подтверждения: ${verificationCode}`;

        return res.status(201).json({
          message,
          user: userModel.toPublicUser(savedUser)
        });
      } catch (error) {
        return next(error);
      }
    },

    login: async (req, res, next) => {
      try {
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

        if (!user) {
          return res.status(404).json({
            message: "Пользователь не найден"
          });
        }

        if (user.is_verified) {
          return res.status(400).json({
            message: "Почта уже подтверждена"
          });
        }

        if (user.verification_code !== code) {
          return res.status(400).json({
            message: "Неверный код подтверждения"
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
        const { email } = req.validatedBody;
        const user = await userModel.findByEmail(email);

        if (!user) {
          return res.status(404).json({
            message: "Пользователь не найден"
          });
        }

        if (user.is_verified) {
          return res.status(400).json({
            message: "Почта уже подтверждена"
          });
        }

        const verificationCode = generateVerificationCode();
        await userModel.updateVerificationCode(user.id, verificationCode);
        await emailService.sendVerificationCode(email, verificationCode);
        const message = emailService.isConfigured
          ? "Код подтверждения отправлен повторно"
          : `Почтовый сервис не настроен. Используйте код подтверждения: ${verificationCode}`;

        return res.status(200).json({
          message
        });
      } catch (error) {
        return next(error);
      }
    },

    changePassword: async (req, res, next) => {
      try {
        const { currentPassword, newPassword } = req.validatedBody;
        const user = await userModel.findById(req.auth.sub);

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
        const user = await userModel.findById(req.auth.sub);
        if (!user) {
          return res.status(404).json({
            message: "РџРѕР»СЊР·РѕРІР°С‚РµР»СЊ РЅРµ РЅР°Р№РґРµРЅ"
          });
        }

        return res.status(200).json({
          message: "РџСЂРѕС„РёР»СЊ Р·Р°РіСЂСѓР¶РµРЅ",
          user: userModel.toPublicUser(user)
        });
      } catch (error) {
        return next(error);
      }
    }
  };
}

module.exports = { createAuthController };
