const { pool, withTransaction } = require("../config/db");

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

function buildSnapshot(dailyLimit, usedToday, bonusRequests, now = new Date(), allowed = true) {
  const normalizedUsedCount = Number(usedToday || 0);
  const normalizedBonus = Number(bonusRequests || 0);
  const baseRemaining = Math.max(dailyLimit - normalizedUsedCount, 0);
  const totalRemaining = baseRemaining + normalizedBonus;
  
  return {
    allowed,
    dailyLimit,
    usedToday: normalizedUsedCount,
    baseRemaining,
    bonusRequests: normalizedBonus,
    totalRemaining,
    resetAt: getNextResetAt(now)
  };
}

async function getDailyUsageSnapshot(userId, { limit, now = new Date() }, executor) {
  const db = getExecutor(executor);
  
  const userRes = await db.query(`SELECT bonus_requests FROM users WHERE id = $1`, [userId]);
  const bonusRequests = userRes.rows[0]?.bonus_requests || 0;

  const result = await db.query(
    `select request_count
     from ai_daily_usage
     where user_id = $1
       and usage_date = $2`,
    [userId, getUsageDate(now)]
  );

  return buildSnapshot(limit, result.rows[0]?.request_count || 0, bonusRequests, now);
}

async function consumeDailyRequest(userId, { limit, now = new Date() }) {
  // Using withTransaction to ensure atomic update of bonus_requests and ai_daily_usage
  return await withTransaction(async (db) => {
    const userRes = await db.query(`SELECT bonus_requests FROM users WHERE id = $1 FOR UPDATE`, [userId]);
    const bonusRequests = userRes.rows[0]?.bonus_requests || 0;

    const usageDate = getUsageDate(now);
    
    // Check daily usage so far
    const usageRes = await db.query(
      `select request_count from ai_daily_usage where user_id = $1 and usage_date = $2`, 
      [userId, usageDate]
    );
    let usedToday = usageRes.rows[0]?.request_count || 0;

    // Deduct logic: first bonus, then daily
    if (bonusRequests > 0) {
      await db.query(`UPDATE users SET bonus_requests = bonus_requests - 1 WHERE id = $1`, [userId]);
      return buildSnapshot(limit, usedToday, bonusRequests - 1, now, true);
    }

    // Bonus is 0, try to deduct from daily limit
    if (usedToday < limit) {
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
        return buildSnapshot(limit, result.rows[0].request_count, 0, now, true);
      }
    }

    // Over limit
    return buildSnapshot(limit, usedToday, 0, now, false);
  });
}

module.exports = {
  getDailyUsageSnapshot,
  consumeDailyRequest,
  getNextResetAt
};
