const { createJwtToken } = require("../utils/createJwtToken");
const { fetchVkUserInfo } = require("../utils/vkIdUserInfoClient");

function createVkAuthController({ userModel, vkConfig }) {
  const ensureVkConfigured = (res) => {
    if (!vkConfig.isConfigured || !vkConfig.clientId) {
      res.status(503).json({
        message: "VK ID не настроен"
      });
      return false;
    }

    return true;
  };

  const upsertVkUser = async (profile) => {
    const existingUser = await userModel.findByVkUserId(profile.vkUserId);
    const user = existingUser
      ? await userModel.updateVkProfile(existingUser.id, profile)
      : await userModel.createVkUser(profile);

    return { user, existingUser };
  };

  return {
    completeNativeLogin: async (req, res, next) => {
      try {
        if (!ensureVkConfigured(res)) {
          return;
        }

        const profile = await fetchVkUserInfo({
          accessToken: req.validatedBody.accessToken,
          clientId: vkConfig.clientId,
          expectedUserId: req.validatedBody.userId,
          fetchImpl: vkConfig.fetchImpl || global.fetch,
          userInfoUrl: vkConfig.userInfoUrl
        });
        const { user, existingUser } = await upsertVkUser(profile);

        return res.status(existingUser ? 200 : 201).json({
          message: existingUser ? "Вход через VK выполнен" : "Аккаунт создан через VK",
          token: createJwtToken(user),
          user: userModel.toPublicUser(user)
        });
      } catch (error) {
        if (
          error.message?.includes("VK ID") ||
          error.message?.includes("access_token") ||
          error.message?.includes("user_id")
        ) {
          return res.status(401).json({
            message: "Не удалось проверить данные VK"
          });
        }

        return next(error);
      }
    }
  };
}

module.exports = { createVkAuthController };
