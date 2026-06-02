const usageByKey = new Map();

function startOfUtcDay(now = new Date()) {
  return new Date(Date.UTC(
    now.getUTCFullYear(),
    now.getUTCMonth(),
    now.getUTCDate(),
    0,
    0,
    0,
    0
  ));
}

function getUsageDate(now = new Date()) {
  return startOfUtcDay(now).toISOString().slice(0, 10);
}

function getNextResetAt(now = new Date()) {
  const start = startOfUtcDay(now);
  start.setUTCDate(start.getUTCDate() + 1);
  return start.toISOString();
}

function buildSnapshot(limit, usedCount, now = new Date(), allowed = true) {
  const normalizedUsedCount = Number(usedCount || 0);
  return {
    allowed,
    limit,
    usedCount: normalizedUsedCount,
    remaining: Math.max(limit - normalizedUsedCount, 0),
    resetsAt: getNextResetAt(now)
  };
}

function keyFor(userId, now = new Date()) {
  return `${userId}:${getUsageDate(now)}`;
}

const imageUsageByKey = new Map();

function imageKeyFor(userId, now = new Date()) {
  return `${userId}:image:${getUsageDate(now)}`;
}

function buildImageSnapshot(dailyImageLimit, usedToday, now = new Date(), allowed = true) {
  const normalizedUsedCount = Number(usedToday || 0);
  const imageRemaining = Math.max(dailyImageLimit - normalizedUsedCount, 0);

  return {
    allowed,
    dailyImageLimit,
    imageUsedToday: normalizedUsedCount,
    imageRemaining,
    resetAt: getNextResetAt(now)
  };
}

async function getDailyUsageSnapshot(userId, { limit, now = new Date() }) {
  return buildSnapshot(limit, usageByKey.get(keyFor(userId, now)) || 0, now);
}

async function consumeDailyRequest(userId, { limit, now = new Date() }) {
  const key = keyFor(userId, now);
  const current = Number(usageByKey.get(key) || 0);
  if (current >= limit) {
    return buildSnapshot(limit, current, now, false);
  }

  const next = current + 1;
  usageByKey.set(key, next);
  return buildSnapshot(limit, next, now, true);
}

async function getDailyImageUsageSnapshot(userId, { limit, now = new Date() }) {
  const key = imageKeyFor(userId, now);
  const usedToday = imageUsageByKey.get(key) || 0;
  return buildImageSnapshot(limit, usedToday, now);
}

async function consumeDailyImageRequest(userId, { limit, now = new Date() }) {
  const key = imageKeyFor(userId, now);
  const usedToday = Number(imageUsageByKey.get(key) || 0);

  if (usedToday < limit) {
    const next = usedToday + 1;
    imageUsageByKey.set(key, next);
    return buildImageSnapshot(limit, next, now, true);
  }

  return buildImageSnapshot(limit, usedToday, now, false);
}

module.exports = {
  getDailyUsageSnapshot,
  consumeDailyRequest,
  getDailyImageUsageSnapshot,
  consumeDailyImageRequest,
  getNextResetAt
};
