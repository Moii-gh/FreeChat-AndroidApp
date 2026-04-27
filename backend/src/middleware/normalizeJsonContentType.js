function normalizeJsonContentType(req, _res, next) {
  const contentType = req.headers["content-type"];

  if (
    typeof contentType === "string" &&
    /^application\/json\s*;\s*utf-8$/i.test(contentType)
  ) {
    req.headers["content-type"] = "application/json; charset=utf-8";
  }

  next();
}

module.exports = { normalizeJsonContentType };
