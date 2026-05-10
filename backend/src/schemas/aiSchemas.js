const { z } = require("zod");

const aiCurrentModeSchema = z.string().trim().min(1).max(64).optional().nullable();
const aiProviderSchema = z.enum(["openai", "vsegpt"]).optional();
const aiModelKeySchema = z.string().trim().min(1).max(64).optional();

const aiChatSchema = z.object({
  provider: aiProviderSchema,
  modelKey: aiModelKeySchema,
  currentMode: aiCurrentModeSchema,
  adultMode: z.boolean().optional(),
  request: z.record(z.any()).optional(),
  messages: z.array(z.any()).optional(),
  prompt: z.any().optional()
}).strict().superRefine((value, context) => {
  const hasWrappedRequest = value.request && typeof value.request === "object";
  const hasDirectRequest = Array.isArray(value.messages) || value.prompt !== undefined;

  if (!hasWrappedRequest && !hasDirectRequest) {
    context.addIssue({
      code: z.ZodIssueCode.custom,
      message: "AI request payload is missing",
      path: ["request"]
    });
  }
});

const aiTitleSchema = z.object({
  provider: aiProviderSchema,
  modelKey: aiModelKeySchema,
  firstUserMessage: z.string().trim().min(1).max(4000)
}).strict();

const aiSummarySchema = z.object({
  provider: aiProviderSchema,
  modelKey: aiModelKeySchema,
  promptText: z.string().trim().min(1).max(16000)
}).strict();

module.exports = {
  aiChatSchema,
  aiTitleSchema,
  aiSummarySchema
};
