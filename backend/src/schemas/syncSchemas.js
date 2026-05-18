const { z } = require("zod");

const MAX_MESSAGE_CONTENT_CHARS = 8 * 1024 * 1024;
const MAX_ATTACHMENT_DATA_CHARS = 8 * 1024 * 1024;
const MAX_ATTACHMENT_CONTEXT_CHARS = 160000;

const syncChatSchema = z.object({
  id: z.string().uuid(),
  title: z.string().trim().min(1).max(200),
  timestamp: z.number().int().nonnegative(),
  isPinned: z.boolean(),
  lastUpdated: z.number().int().nonnegative(),
  summary: z.string().max(8000).default(""),
  isDeleted: z.boolean().default(false),
  isTitleManuallyEdited: z.boolean().default(false)
}).strict();

const syncMessageSchema = z.object({
  syncId: z.string().uuid(),
  chatId: z.string().uuid(),
  role: z.enum(["user", "assistant", "system"]),
  content: z.string().max(MAX_MESSAGE_CONTENT_CHARS),
  timestamp: z.number().int().nonnegative(),
  imageUrl: z.string().trim().max(MAX_ATTACHMENT_DATA_CHARS).nullable().optional(),
  attachmentData: z.string().max(MAX_ATTACHMENT_DATA_CHARS).nullable().optional(),
  attachmentMimeType: z.string().trim().max(255).nullable().optional(),
  attachmentFileName: z.string().trim().max(255).nullable().optional(),
  attachmentContext: z.string().max(MAX_ATTACHMENT_CONTEXT_CHARS).nullable().optional(),
  updatedAt: z.number().int().nonnegative().default(0),
  isDeleted: z.boolean().default(false),
  editRevision: z.number().int().nonnegative().default(0)
}).strict();

const syncPayloadSchema = z.object({
  chats: z.array(syncChatSchema).max(500).default([]),
  messages: z.array(syncMessageSchema).max(5000).default([])
}).strict();

module.exports = {
  syncPayloadSchema
};
