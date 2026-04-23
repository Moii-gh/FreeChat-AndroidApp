const { pool } = require("../config/db");

async function findByUserId(userId) {
  const result = await pool.query(
    `select *
     from subscriptions
     where user_id = $1`,
    [userId]
  );

  return result.rows[0] || null;
}

async function upsertPendingSubscription({ userId, planCode, status = "pending", lastPaymentId = null }) {
  const result = await pool.query(
    `insert into subscriptions (
       user_id,
       plan_code,
       status,
       last_payment_id,
       updated_at
     )
     values ($1, $2, $3, $4, now())
     on conflict (user_id) do update set
       plan_code = excluded.plan_code,
       status = excluded.status,
       last_payment_id = excluded.last_payment_id,
       updated_at = now()
     returning *`,
    [userId, planCode, status, lastPaymentId]
  );

  return result.rows[0] || null;
}

async function activateSubscription({
  userId,
  planCode,
  paymentMethodId,
  currentPeriodStart,
  currentPeriodEnd,
  lastPaymentId,
  cancelAtPeriodEnd = false,
  status = "active"
}) {
  const result = await pool.query(
    `insert into subscriptions (
       user_id,
       plan_code,
       status,
       payment_method_id,
       cancel_at_period_end,
       current_period_start,
       current_period_end,
       last_payment_id,
       updated_at
     )
     values ($1, $2, $3, $4, $5, $6, $7, $8, now())
     on conflict (user_id) do update set
       plan_code = excluded.plan_code,
       status = excluded.status,
       payment_method_id = coalesce(excluded.payment_method_id, subscriptions.payment_method_id),
       cancel_at_period_end = excluded.cancel_at_period_end,
       current_period_start = excluded.current_period_start,
       current_period_end = excluded.current_period_end,
       last_payment_id = excluded.last_payment_id,
       updated_at = now()
     returning *`,
    [
      userId,
      planCode,
      status,
      paymentMethodId,
      cancelAtPeriodEnd,
      currentPeriodStart,
      currentPeriodEnd,
      lastPaymentId
    ]
  );

  return result.rows[0] || null;
}

async function updateStatus(userId, status) {
  const result = await pool.query(
    `update subscriptions
     set status = $2,
         updated_at = now()
     where user_id = $1
     returning *`,
    [userId, status]
  );

  return result.rows[0] || null;
}

async function setCancelAtPeriodEnd(userId, cancelAtPeriodEnd) {
  const result = await pool.query(
    `update subscriptions
     set cancel_at_period_end = $2,
         status = case
           when status = 'active' and $2 = true then 'canceled'
           when status = 'canceled' and $2 = false then 'active'
           else status
         end,
         updated_at = now()
     where user_id = $1
     returning *`,
    [userId, cancelAtPeriodEnd]
  );

  return result.rows[0] || null;
}

async function markExpired(userId) {
  const result = await pool.query(
    `update subscriptions
     set status = 'expired',
         updated_at = now()
     where user_id = $1
     returning *`,
    [userId]
  );

  return result.rows[0] || null;
}

async function findDueForRenewal(limit = 20) {
  const result = await pool.query(
    `select *
     from subscriptions
     where status in ('active', 'past_due')
       and cancel_at_period_end = false
       and payment_method_id is not null
       and current_period_end is not null
       and current_period_end <= now()
     order by current_period_end asc
     limit $1`,
    [limit]
  );

  return result.rows;
}

module.exports = {
  findByUserId,
  upsertPendingSubscription,
  activateSubscription,
  updateStatus,
  setCancelAtPeriodEnd,
  markExpired,
  findDueForRenewal
};
