const { withTransaction } = require("../config/db");
const { upsertChats, upsertMessages, getUserChats, getUserMessages } = require("../models/syncModel");

async function syncData(req, res, next) {
  try {
    const userId = req.user.id;
    const { chats, messages, sinceLastUpdatedMs } = req.validatedBody || req.body;
    const since = sinceLastUpdatedMs || 0;

    const { remoteChats, remoteMessages } = await withTransaction(async (executor) => {
      if (chats && chats.length > 0) {
        await upsertChats(userId, chats, executor);
      }

      if (messages && messages.length > 0) {
        await upsertMessages(userId, messages, executor);
      }

      return {
        remoteChats: await getUserChats(userId, since, executor),
        remoteMessages: await getUserMessages(userId, since, executor)
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
