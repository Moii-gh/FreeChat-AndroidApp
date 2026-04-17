const { z } = require("zod");

const isoBirthDate = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, "Дата рождения должна быть в формате YYYY-MM-DD");

const registerSchema = z.object({
  email: z.string().email("Введите корректный email"),
  password: z.string().min(6, "Пароль должен содержать минимум 6 символов"),
  fullName: z.string().trim().min(1, "Укажите полное имя"),
  birthDate: isoBirthDate
});

const loginSchema = z.object({
  email: z.string().email("Введите корректный email"),
  password: z.string().min(6, "Введите пароль")
});

const checkEmailSchema = z.object({
  email: z.string().email("Введите корректный email")
});

const verifyEmailSchema = z.object({
  email: z.string().email("Введите корректный email"),
  code: z.string().regex(/^\d{6}$/, "Введите 6-значный код")
});

const resendCodeSchema = z.object({
  email: z.string().email("Введите корректный email")
});

const changePasswordSchema = z.object({
  currentPassword: z.string().min(1, "Введите текущий пароль"),
  newPassword: z.string().min(6, "Пароль должен содержать минимум 6 символов")
});

const emptyBodySchema = z.object({}).strict().optional().transform(() => ({}));

const challengeIdSchema = z.string().uuid("Некорректный идентификатор попытки");

const telegramVerifyCodeSchema = z.object({
  challengeId: challengeIdSchema,
  code: z.string().regex(/^\d{6}$/, "Введите 6-значный код")
});

const telegramCompleteRegistrationSchema = z.object({
  challengeId: challengeIdSchema,
  fullName: z.string().trim().min(1, "Укажите полное имя"),
  birthDate: isoBirthDate,
  password: z.string().min(6, "Пароль должен содержать минимум 6 символов")
});

const telegramCompleteLoginSchema = z.object({
  challengeId: challengeIdSchema,
  password: z.string().min(6, "Введите пароль")
});

const telegramBeginMigrationSchema = z.object({
  email: z.string().email("Введите корректный email"),
  password: z.string().min(6, "Введите пароль")
});

const telegramCompleteMigrationSchema = z.object({
  challengeId: challengeIdSchema
});

module.exports = {
  registerSchema,
  loginSchema,
  checkEmailSchema,
  verifyEmailSchema,
  resendCodeSchema,
  changePasswordSchema,
  emptyBodySchema,
  telegramVerifyCodeSchema,
  telegramCompleteRegistrationSchema,
  telegramCompleteLoginSchema,
  telegramBeginMigrationSchema,
  telegramCompleteMigrationSchema
};
