const { pool } = require("../config/db");

function getExecutor(executor) {
  return executor || pool;
}

async function findByProviderPaymentId(providerPaymentId, executor) {
  const result = await getExecutor(executor).query(
    `select *
     from billing_payments
     where provider_payment_id = $1`,
    [providerPaymentId]
  );

  return result.rows[0] || null;
}

async function upsertPayment({
  userId,
  provider = "yookassa",
  providerPaymentId,
  amountValue,
  currency = "RUB",
  kind,
  status,
  paymentMethodId = null,
  rawPayload = null
}, executor) {
  const result = await getExecutor(executor).query(
    `insert into billing_payments (
       user_id,
       provider,
       provider_payment_id,
       amount_value,
       currency,
       kind,
       status,
       payment_method_id,
       raw_payload,
       updated_at
     )
     values ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb, now())
     on conflict (provider_payment_id) do update set
       user_id = excluded.user_id,
       provider = excluded.provider,
       amount_value = excluded.amount_value,
       currency = excluded.currency,
       kind = excluded.kind,
       status = excluded.status,
       payment_method_id = excluded.payment_method_id,
       raw_payload = excluded.raw_payload,
       updated_at = now()
     returning *`,
    [
      userId,
      provider,
      providerPaymentId,
      amountValue,
      currency,
      kind,
      status,
      paymentMethodId,
      rawPayload ? JSON.stringify(rawPayload) : null
    ]
  );

  return result.rows[0] || null;
}

module.exports = {
  findByProviderPaymentId,
  upsertPayment
};
