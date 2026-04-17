const jwt = require("jsonwebtoken");
const { env } = require("../config/env");

function authenticate(req, res, next) {
  const authorization = req.headers.authorization || "";
  const [scheme, token] = authorization.split(" ");

  if (scheme !== "Bearer" || !token) {
    return res.status(401).json({
      message: "Требуется авторизация"
    });
  }

  try {
    req.auth = jwt.verify(token, env.jwtSecret);
    return next();
  } catch (_error) {
    return res.status(401).json({
      message: "Сессия истекла. Войдите снова"
    });
  }
}

module.exports = { authenticate };
