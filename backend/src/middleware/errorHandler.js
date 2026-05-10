function errorHandler(error, req, res, next) {
  if (res.headersSent) {
    return next(error);
  }

  if (error?.type === "entity.parse.failed") {
    return res.status(400).json({
      message: "Некорректный JSON в теле запроса"
    });
  }

  const status =
    Number.isInteger(error?.statusCode) ? error.statusCode :
    Number.isInteger(error?.status) ? error.status :
    500;
  if (status >= 500) {
    console.error("[server-error]", {
      method: req.method,
      path: req.originalUrl,
      message: error?.message,
      stack: error?.stack
    });
  }
  const message =
    status >= 500 && !error?.expose
      ? "Внутренняя ошибка сервера"
      : error?.message || "Не удалось обработать запрос";

  return res.status(status).json({ message });
}

module.exports = { errorHandler };
