function errorHandler(error, req, res, next) {
  if (res.headersSent) {
    return next(error);
  }

  return res.status(500).json({
    message: error.message || "Внутренняя ошибка сервера"
  });
}

module.exports = { errorHandler };
