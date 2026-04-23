const { proxyAiRequest, generateTitle, generateSummary } = require("../services/aiService");

function createAiController({ userModel }) {
  async function getAuthenticatedUser(userId) {
    return userModel.findById(userId);
  }

  return {
    chat: async (req, res, next) => {
      try {
        const user = await getAuthenticatedUser(req.auth.sub);
        if (!user) {
          return res.status(404).json({
            message: "User not found"
          });
        }

        const requestBody =
          req.body?.request && typeof req.body.request === "object"
            ? req.body.request
            : req.body?.messages || req.body?.prompt
              ? req.body
              : null;

        if (!requestBody) {
          return res.status(400).json({
            message: "Request payload is missing"
          });
        }

        await proxyAiRequest({
          user,
          currentMode: req.body.currentMode || null,
          requestBody,
          res
        });
      } catch (error) {
        return next(error);
      }
    },

    title: async (req, res, next) => {
      try {
        const user = await getAuthenticatedUser(req.auth.sub);
        if (!user) {
          return res.status(404).json({
            message: "User not found"
          });
        }

        const firstUserMessage = req.body?.firstUserMessage?.trim?.() || "";
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
        const user = await getAuthenticatedUser(req.auth.sub);
        if (!user) {
          return res.status(404).json({
            message: "User not found"
          });
        }

        const promptText = req.body?.promptText?.trim?.() || "";
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
