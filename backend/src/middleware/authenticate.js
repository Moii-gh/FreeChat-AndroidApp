const jwt = require("jsonwebtoken");
const { env } = require("../config/env");

function createAuthenticate({ userModel }) {
  return async function authenticate(req, res, next) {
    const authorization = req.headers.authorization || "";
    const [scheme, token] = authorization.split(" ");

    if (scheme !== "Bearer" || !token) {
      return res.status(401).json({
        message: "Требуется авторизация"
      });
    }

    try {
      const auth = jwt.verify(token, env.jwtSecret, {
        algorithms: ["HS256"]
      });
      const user = await userModel.findById(auth.sub);

      if (!user) {
        return res.status(401).json({
          message: "Сессия истекла. Войдите снова"
        });
      }

      if (user.token_invalid_before && auth.iat) {
        const invalidBefore = new Date(user.token_invalid_before).getTime();
        if (Number.isFinite(invalidBefore) && auth.iat * 1000 < invalidBefore) {
          return res.status(401).json({
            message: "Сессия истекла. Войдите снова"
          });
        }
      }

      req.auth = auth;
      req.user = user;
      return next();
    } catch (_error) {
      return res.status(401).json({
        message: "Сессия истекла. Войдите снова"
      });
    }
  };
}

module.exports = { createAuthenticate };
