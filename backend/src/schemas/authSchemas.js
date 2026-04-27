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

const telegramIdSchema = z.union([z.string(), z.number()]).transform((value) => String(value));
const telegramOptionalString = z.string().trim().min(1).max(2048).optional();

const telegramWidgetAuthSchema = z.object({
  id: telegramIdSchema.refine((value) => /^\d+$/.test(value), "Некорректный Telegram id"),
  first_name: z.string().trim().min(1, "Telegram не вернул имя").max(256),
  last_name: telegramOptionalString,
  username: telegramOptionalString,
  photo_url: z.string().trim().url("Некорректный URL фото").max(2048).optional(),
  auth_date: z.union([z.string(), z.number()])
    .transform((value) => Number(value))
    .refine(Number.isInteger, "Некорректная дата авторизации"),
  hash: z.string().regex(/^[a-f0-9]{64}$/i, "Некорректная подпись Telegram")
}).strict();

const telegramNativeLoginSchema = z.object({
  idToken: z.string().min(1, "Telegram ID token отсутствует")
}).strict();

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
  telegramCompleteMigrationSchema,
  telegramWidgetAuthSchema,
  telegramNativeLoginSchema
};
