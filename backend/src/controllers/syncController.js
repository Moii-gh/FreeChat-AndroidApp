const { withTransaction } = require("../config/db");
const { upsertChats, upsertMessages, getUserChats, getUserMessages } = require("../models/syncModel");

async function syncData(req, res, next) {
  try {
    const userId = req.user.id;
    const { chats, messages } = req.validatedBody || req.body;

    const { remoteChats, remoteMessages } = await withTransaction(async (executor) => {
      if (chats && chats.length > 0) {
        await upsertChats(userId, chats, executor);
      }

      if (messages && messages.length > 0) {
        await upsertMessages(userId, messages, executor);
      }

      return {
        remoteChats: await getUserChats(userId, executor),
        remoteMessages: await getUserMessages(userId, executor)
      };
    });

    return res.status(200).json({
      chats: remoteChats,
      messages: remoteMessages
    });
  } catch (error) {
    return next(error);
  }
}

module.exports = { syncData };
