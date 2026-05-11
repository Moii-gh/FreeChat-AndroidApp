const { env } = require("../config/env");
const {
  proxyAiRequest,
  generateTitle,
  generateSummary,
  generateTrendingQueries
} = require("../services/aiService");
const { publicModels } = require("../services/aiModelRegistry");

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
    message: "Лимит AI-запросов исчерпан",
    dailyLimit: snapshot.dailyLimit,
    usedToday: snapshot.usedToday,
    bonusRequests: snapshot.bonusRequests,
    baseRemaining: snapshot.baseRemaining,
    totalRemaining: snapshot.totalRemaining,
    remaining: snapshot.totalRemaining,
    resetAt: snapshot.resetAt
  };
}

function getAllowedStringLength(path, value) {
  if (path.endsWith(".image_url.url")) {
    return String(value).startsWith("data:image/")
      ? MAX_DATA_URL_LENGTH
      : MAX_URL_LENGTH;
  }

  if (isInlineFileContentPath(path)) {
    return MAX_DATA_URL_LENGTH;
  }

  return MAX_STRING_LENGTH;
}

function isInlineFileContentPath(path) {
  return /\.(fileSearchFiles|file_search_files)\[\d+\]\.(base64|data|content|dataUrl|data_url)$/.test(path) ||
    /\.(fileSearch|file_search)\.files\[\d+\]\.(base64|data|content|dataUrl|data_url)$/.test(path);
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
    if (isInlineImageUrl || isInlineFileContentPath(path)) {
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

        const snapshot = await aiUsageModel.getDailyUsageSnapshot(user.id, {
          limit: env.dailyAiRequestLimit
        });

        if (snapshot.totalRemaining <= 0) {
          return res.status(429).json(createQuotaResponse(snapshot));
        }

        await proxyAiRequest({
          user,
          provider: validatedBody.provider,
          modelKey: validatedBody.modelKey,
          currentMode: validatedBody.currentMode || null,
          adultMode: validatedBody.adultMode === true,
          requestBody,
          res,
          onBeforeResponse: async ({ upstreamResponse }) => {
            if (!upstreamResponse.ok) {
              return;
            }

            const updatedSnapshot = await aiUsageModel.consumeDailyRequest(user.id, {
              limit: env.dailyAiRequestLimit
            });

            if (!updatedSnapshot.allowed) {
              try {
                await upstreamResponse.body?.cancel?.();
              } catch (_error) {
                // Ignore stream cancel failures.
              }

              return res.status(429).json(createQuotaResponse(updatedSnapshot));
            }

            res.setHeader("X-Daily-Request-Limit", String(updatedSnapshot.dailyLimit));
            res.setHeader("X-Remaining-Requests", String(updatedSnapshot.totalRemaining));
            res.setHeader("X-Daily-Quota-Resets-At", updatedSnapshot.resetAt);
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
        const firstAssistantMessage = req.validatedBody?.firstAssistantMessage?.trim?.() || "";
        if (!firstUserMessage) {
          return res.status(400).json({
            message: "firstUserMessage is required"
          });
        }

        const content = await generateTitle({
          user,
          firstUserMessage,
          firstAssistantMessage,
          provider: req.validatedBody?.provider,
          modelKey: req.validatedBody?.modelKey
        });
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

        const content = await generateSummary({
          user,
          promptText,
          provider: req.validatedBody?.provider,
          modelKey: req.validatedBody?.modelKey
        });
        return res.status(200).json({ content });
      } catch (error) {
        return next(error);
      }
    },

    trending: async (req, res, next) => {
      try {
        const user = req.user;
        if (!user) {
          return res.status(404).json({
            message: "User not found"
          });
        }

        const locale = String(req.query.locale || "ru").trim().slice(0, 16) || "ru";
        const queries = await generateTrendingQueries({ user, locale });
        return res.status(200).json({ queries });
      } catch (error) {
        return next(error);
      }
    },

    getModels: async (_req, res) => {
      return res.status(200).json({
        providers: ["openai", "vsegpt"],
        defaultProvider: "openai",
        models: publicModels()
      });
    },

    getLimits: async (req, res, next) => {
      try {
        const user = req.user;
        if (!user) {
          return res.status(404).json({ message: "User not found" });
        }

        const snapshot = await aiUsageModel.getDailyUsageSnapshot(user.id, {
          limit: env.dailyAiRequestLimit
        });

        return res.status(200).json({
          dailyLimit: snapshot.dailyLimit,
          usedToday: snapshot.usedToday,
          bonusRequests: snapshot.bonusRequests,
          baseRemaining: snapshot.baseRemaining,
          totalRemaining: snapshot.totalRemaining,
          resetAt: snapshot.resetAt
        });
      } catch (error) {
        return next(error);
      }
    },

    rewardAd: async (req, res, next) => {
      try {
        const user = req.user;
        if (!user) {
          return res.status(404).json({ message: "User not found" });
        }

        const { userModel } = require("../modelRegistry");
        await userModel.addBonusRequests(user.id, 5);

        const snapshot = await aiUsageModel.getDailyUsageSnapshot(user.id, {
          limit: env.dailyAiRequestLimit
        });

        return res.status(200).json({
          dailyLimit: snapshot.dailyLimit,
          usedToday: snapshot.usedToday,
          bonusRequests: snapshot.bonusRequests,
          baseRemaining: snapshot.baseRemaining,
          totalRemaining: snapshot.totalRemaining,
          resetAt: snapshot.resetAt
        });
      } catch (error) {
        return next(error);
      }
    }
  };
}

module.exports = { createAiController };
