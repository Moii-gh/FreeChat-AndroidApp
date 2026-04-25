const { z } = require("zod");

const MAX_MESSAGE_CONTENT_CHARS = 8 * 1024 * 1024;
const MAX_ATTACHMENT_DATA_CHARS = 8 * 1024 * 1024;
const MAX_ATTACHMENT_CONTEXT_CHARS = 160000;

const chatShareMessageSchema = z.object({
  role: z.enum(["user", "assistant", "system"]),
  content: z.string().max(MAX_MESSAGE_CONTENT_CHARS),
  timestamp: z.number().int().nonnegative(),
  imageUrl: z.string().trim().max(MAX_ATTACHMENT_DATA_CHARS).nullable().optional(),
  attachmentData: z.string().max(MAX_ATTACHMENT_DATA_CHARS).nullable().optional(),
  attachmentMimeType: z.string().trim().max(255).nullable().optional(),
  attachmentFileName: z.string().trim().max(255).nullable().optional(),
  attachmentContext: z.string().max(MAX_ATTACHMENT_CONTEXT_CHARS).nullable().optional()
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
