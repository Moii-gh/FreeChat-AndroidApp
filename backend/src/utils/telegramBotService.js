const { env } = require("../config/env");
const { generateVerificationCode } = require("./verificationCode");

const TELEGRAM_API_BASE = "https://api.telegram.org";

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function extractStartToken(text) {
  const trimmed = (text || "").trim();
  if (!trimmed.startsWith("/start")) {
    return null;
  }

  const [, payload] = trimmed.split(/\s+/, 2);
  return payload || "";
}

function createTelegramBotService({
  challengeModel,
  userModel,
  botToken = env.telegramBotToken,
  botUsername = env.telegramBotUsername,
  fetchImpl = global.fetch,
  logger = console
}) {
  const isConfigured = Boolean(botToken && botUsername && fetchImpl);
  let isRunning = false;
  let offset = 0;

  const apiUrl = (method) => `${TELEGRAM_API_BASE}/bot${botToken}/${method}`;

  const callTelegram = async (method, payload) => {
    if (!isConfigured) {
      return null;
    }

    const response = await fetchImpl(apiUrl(method), {
      method: "POST",
      headers: {
        "Content-Type": "application/json"
      },
      body: JSON.stringify(payload)
    });

    if (!response.ok) {
      const body = await response.text();
      throw new Error(`Telegram API ${method} failed: ${response.status} ${body}`);
    }

    return response.json();
  };

  const sendMessage = async (chatId, text) => {
    await callTelegram("sendMessage", {
      chat_id: chatId,
      text
    });
  };

  const isExpired = (challenge) =>
    new Date(challenge.expires_at).getTime() <= Date.now();

  const handleStart = async ({ chatId, telegramUserId, username, startToken }) => {
    if (!startToken) {
      await sendMessage(
        chatId,
        "Откройте ссылку из приложения и запустите команду /start ещё раз."
      );
      return { ok: true };
    }

    const challenge = await challengeModel.findByStartToken(startToken);
    if (!challenge) {
      await sendMessage(chatId, "Ссылка недействительна. Начните вход заново в приложении.");
      return { ok: false, reason: "not_found" };
    }

    if (challenge.consumed_at || isExpired(challenge)) {
      await sendMessage(chatId, "Эта попытка уже истекла. Вернитесь в приложение и начните заново.");
      return { ok: false, reason: "expired" };
    }

    const linkedUser = await userModel.findByTelegramUserId(telegramUserId);
    if (challenge.purpose === "register" && linkedUser) {
      await sendMessage(chatId, "Этот Telegram уже привязан к другому аккаунту.");
      return { ok: false, reason: "already_linked" };
    }

    if (challenge.purpose === "login" && !linkedUser) {
      await sendMessage(chatId, "Этот Telegram ещё не привязан к аккаунту.");
      return { ok: false, reason: "not_linked" };
    }

    if (challenge.purpose === "migrate" && linkedUser && linkedUser.id !== challenge.user_id) {
      await sendMessage(chatId, "Этот Telegram уже привязан к другому аккаунту.");
      return { ok: false, reason: "already_linked" };
    }

    const code = generateVerificationCode();
    await challengeModel.assignTelegramIdentityAndCode(challenge.id, {
      telegramUserId,
      telegramChatId: chatId,
      telegramUsername: username || null,
      code
    });

    await sendMessage(
      chatId,
      `Код подтверждения: ${code}\nВведите его в приложении в течение 10 минут.`
    );

    return { ok: true, code };
  };

  const processUpdate = async (update) => {
    const message = update.message;
    if (!message?.text) {
      return;
    }

    const startToken = extractStartToken(message.text);
    if (startToken === null) {
      return;
    }

    await handleStart({
      chatId: message.chat.id,
      telegramUserId: message.from?.id,
      username: message.from?.username || null,
      startToken
    });
  };

  const pollOnce = async () => {
    const result = await callTelegram("getUpdates", {
      offset,
      timeout: 25,
      allowed_updates: ["message"]
    });

    for (const update of result?.result || []) {
      offset = update.update_id + 1;
      await processUpdate(update);
    }
  };

  const start = () => {
    if (!isConfigured || isRunning) {
      return;
    }

    isRunning = true;

    const loop = async () => {
      while (isRunning) {
        try {
          await pollOnce();
        } catch (error) {
          logger.error("Telegram bot polling error", error);
          await delay(3000);
        }
      }
    };

    loop();
  };

  const stop = () => {
    isRunning = false;
  };

  return {
    isConfigured,
    botUsername,
    start,
    stop,
    handleStart
  };
}

module.exports = { createTelegramBotService };
