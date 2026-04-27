const { pool } = require("../config/db");

function getExecutor(executor) {
  return executor || pool;
}

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

async function getDailyUsageSnapshot(userId, { limit, now = new Date() }, executor) {
  const result = await getExecutor(executor).query(
    `select request_count
     from ai_daily_usage
     where user_id = $1
       and usage_date = $2`,
    [userId, getUsageDate(now)]
  );

  return buildSnapshot(limit, result.rows[0]?.request_count || 0, now);
}

async function consumeDailyRequest(userId, { limit, now = new Date() }, executor) {
  const usageDate = getUsageDate(now);
  const db = getExecutor(executor);
  const result = await db.query(
    `insert into ai_daily_usage (
       user_id,
       usage_date,
       request_count,
       updated_at
     )
     values ($1, $2, 1, now())
     on conflict (user_id, usage_date) do update set
       request_count = ai_daily_usage.request_count + 1,
       updated_at = now()
     where ai_daily_usage.request_count < $3
     returning request_count`,
    [userId, usageDate, limit]
  );

  if (result.rows[0]) {
    return buildSnapshot(limit, result.rows[0].request_count, now, true);
  }

  const fallback = await db.query(
    `select request_count
     from ai_daily_usage
     where user_id = $1
       and usage_date = $2`,
    [userId, usageDate]
  );

  return buildSnapshot(limit, fallback.rows[0]?.request_count || limit, now, false);
}

module.exports = {
  getDailyUsageSnapshot,
  consumeDailyRequest,
  getNextResetAt
};
