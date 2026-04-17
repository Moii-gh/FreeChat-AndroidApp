const { upsertChats, upsertMessages, getUserChats, getUserMessages } = require("../models/syncModel");

async function syncData(req, res, next) {
  try {
    const userId = req.user.id;
    const { chats, messages } = req.body;

    // 1. Process pushed updates
    if (chats && chats.length > 0) {
      await upsertChats(userId, chats);
    }
    
    if (messages && messages.length > 0) {
      await upsertMessages(userId, messages);
    }

    // 2. Fetch all latest user data to return
    const remoteChats = await getUserChats(userId);
    const remoteMessages = await getUserMessages(userId);

    return res.status(200).json({
      chats: remoteChats,
      messages: remoteMessages
    });
  } catch (error) {
    return next(error);
  }
}

module.exports = { syncData };
