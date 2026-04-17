function validate(schema) {
  return (req, res, next) => {
    const result = schema.safeParse(req.body);

    if (!result.success) {
      const errors = {};
      for (const issue of result.error.issues) {
        const key = issue.path[0];
        if (key && !errors[key]) {
          errors[key] = issue.message;
        }
      }

      return res.status(400).json({
        message: "Некорректные входные данные",
        errors
      });
    }

    req.validatedBody = result.data;
    return next();
  };
}

module.exports = { validate };
