const { z } = require("zod");

const syncChatSchema = z.object({
  id: z.string().uuid(),
  title: z.string().trim().min(1).max(200),
  timestamp: z.number().int().nonnegative(),
  isPinned: z.boolean(),
  lastUpdated: z.number().int().nonnegative(),
  summary: z.string().max(8000).default(""),
  isDeleted: z.boolean().default(false)
}).strict();

const syncMessageSchema = z.object({
  syncId: z.string().uuid(),
  chatId: z.string().uuid(),
  role: z.enum(["user", "assistant", "system"]),
  content: z.string().max(20000),
  timestamp: z.number().int().nonnegative(),
  imageUrl: z.string().trim().url().max(2048).nullable().optional()
}).strict();

const syncPayloadSchema = z.object({
  chats: z.array(syncChatSchema).max(500).default([]),
  messages: z.array(syncMessageSchema).max(5000).default([])
}).strict();

module.exports = {
  syncPayloadSchema
};
