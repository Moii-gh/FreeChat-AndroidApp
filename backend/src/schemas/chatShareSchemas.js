const { z } = require("zod");

const chatShareMessageSchema = z.object({
  role: z.enum(["user", "assistant", "system"]),
  content: z.string().max(20000),
  timestamp: z.number().int().nonnegative(),
  imageUrl: z.string().trim().max(4096).nullable().optional()
}).strict();

const createChatShareSchema = z.object({
  sourceChatId: z.string().uuid(),
  title: z.string().trim().min(1).max(200),
  summary: z.string().max(8000).default(""),
  messages: z.array(chatShareMessageSchema).min(1).max(5000),
  expiresInDays: z.number().int().min(1).max(90).default(30)
}).strict();

module.exports = {
  createChatShareSchema
};
