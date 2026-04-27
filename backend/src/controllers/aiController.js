const { env } = require("../config/env");
const { proxyAiRequest, generateTitle, generateSummary } = require("../services/aiService");
const { isProUser } = require("../services/entitlementService");

const MAX_CHAT_MESSAGES = 60;
const MAX_ARRAY_ITEMS = 128;
const MAX_OBJECT_KEYS = 64;
const MAX_DEPTH = 8;
const MAX_TOTAL_TEXT_CHARS = 160000;
const MAX_STRING_LENGTH = 24000;
const MAX_URL_LENGTH = 4096;
const MAX_DATA_URL_LENGTH = 8 * 1024 * 1024;

function createBadRequestError(message) {
  const error = new Error(message);
  error.statusCode = 400;
  return error;
}

function createQuotaResponse(snapshot) {
  return {
    message: "Ежедневный лимит AI-запросов исчерпан",
    dailyRequestLimit: snapshot.limit,
    remainingDailyRequests: snapshot.remaining,
    dailyQuotaResetsAt: snapshot.resetsAt
  };
}

function getAllowedStringLength(path, value) {
  if (path.endsWith(".image_url.url")) {
    return String(value).startsWith("data:image/")
      ? MAX_DATA_URL_LENGTH
      : MAX_URL_LENGTH;
  }

  return MAX_STRING_LENGTH;
}

function inspectAiValue(value, state, path = "request", depth = 0) {
  if (depth > MAX_DEPTH) {
    throw createBadRequestError("AI request payload is nested too deeply");
  }

  if (value === null) {
    return;
  }

  if (typeof value === "string") {
    const maxLength = getAllowedStringLength(path, value);
    if (value.length > maxLength) {
      throw createBadRequestError(`AI request field ${path} is too large`);
    }

    const isInlineImageUrl = path.endsWith(".image_url.url") && value.startsWith("data:image/");
    if (isInlineImageUrl) {
      return;
    }

    state.totalTextChars += value.length;
    if (state.totalTextChars > MAX_TOTAL_TEXT_CHARS) {
      throw createBadRequestError("AI request payload is too large");
    }
    return;
  }

  if (typeof value === "number" || typeof value === "boolean") {
    return;
  }

  if (Array.isArray(value)) {
    if (value.length > MAX_ARRAY_ITEMS) {
      throw createBadRequestError(`AI request field ${path} contains too many items`);
    }

    value.forEach((item, index) => inspectAiValue(item, state, `${path}[${index}]`, depth + 1));
    return;
  }

  if (typeof value === "object") {
    const entries = Object.entries(value);
    if (entries.length > MAX_OBJECT_KEYS) {
      throw createBadRequestError(`AI request field ${path} contains too many properties`);
    }

    entries.forEach(([key, child]) => inspectAiValue(child, state, `${path}.${key}`, depth + 1));
    return;
  }

  throw createBadRequestError(`AI request field ${path} has unsupported data type`);
}

function validateChatRequestPayload(requestBody) {
  if (!requestBody || typeof requestBody !== "object" || Array.isArray(requestBody)) {
    throw createBadRequestError("AI request payload must be an object");
  }

  if (Array.isArray(requestBody.messages) && requestBody.messages.length > MAX_CHAT_MESSAGES) {
    throw createBadRequestError("Too many chat messages were provided");
  }

  inspectAiValue(requestBody, { totalTextChars: 0 });
}

function createAiController({ aiUsageModel }) {
  return {
    chat: async (req, res, next) => {
      try {
        const user = req.user;
        if (!user) {
          return res.status(404).json({
            message: "User not found"
          });
        }

        const validatedBody = req.validatedBody || req.body;
        const requestBody =
          validatedBody?.request && typeof validatedBody.request === "object"
            ? validatedBody.request
            : validatedBody?.messages || validatedBody?.prompt
              ? validatedBody
              : null;

        if (!requestBody) {
          return res.status(400).json({
            message: "Request payload is missing"
          });
        }

        validateChatRequestPayload(requestBody);

        if (!isProUser(user)) {
          const snapshot = await aiUsageModel.getDailyUsageSnapshot(user.id, {
            limit: env.dailyAiRequestLimit
          });

          if (snapshot.remaining <= 0) {
            return res.status(429).json(createQuotaResponse(snapshot));
          }
        }

        await proxyAiRequest({
          user,
          currentMode: validatedBody.currentMode || null,
          requestBody,
          res,
          onBeforeResponse: async ({ upstreamResponse }) => {
            if (!upstreamResponse.ok || isProUser(user)) {
              return;
            }

            const snapshot = await aiUsageModel.consumeDailyRequest(user.id, {
              limit: env.dailyAiRequestLimit
            });

            if (!snapshot.allowed) {
              try {
                await upstreamResponse.body?.cancel?.();
              } catch (_error) {
                // Ignore stream cancel failures.
              }

              return res.status(429).json(createQuotaResponse(snapshot));
            }

            res.setHeader("X-Daily-Request-Limit", String(snapshot.limit));
            res.setHeader("X-Remaining-Daily-Requests", String(snapshot.remaining));
            res.setHeader("X-Daily-Quota-Resets-At", snapshot.resetsAt);
          }
        });
      } catch (error) {
        return next(error);
      }
    },

    title: async (req, res, next) => {
      try {
        const user = req.user;
        if (!user) {
          return res.status(404).json({
            message: "User not found"
          });
        }

        const firstUserMessage = req.validatedBody?.firstUserMessage?.trim?.() || "";
        if (!firstUserMessage) {
          return res.status(400).json({
            message: "firstUserMessage is required"
          });
        }

        const content = await generateTitle({ user, firstUserMessage });
        return res.status(200).json({ content });
      } catch (error) {
        return next(error);
      }
    },

    summary: async (req, res, next) => {
      try {
        const user = req.user;
        if (!user) {
          return res.status(404).json({
            message: "User not found"
          });
        }

        const promptText = req.validatedBody?.promptText?.trim?.() || "";
        if (!promptText) {
          return res.status(400).json({
            message: "promptText is required"
          });
        }

        const content = await generateSummary({ user, promptText });
        return res.status(200).json({ content });
      } catch (error) {
        return next(error);
      }
    }
  };
}

module.exports = { createAiController };
