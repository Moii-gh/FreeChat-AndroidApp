const crypto = require("node:crypto");
const { env } = require("../config/env");

const BASE_URL = "https://api.yookassa.ru/v3";

function isConfigured() {
  return Boolean(env.yookassaShopId && env.yookassaSecretKey);
}

function buildAuthHeader() {
  return `Basic ${Buffer.from(`${env.yookassaShopId}:${env.yookassaSecretKey}`).toString("base64")}`;
}

async function request(path, { method = "GET", body, idempotenceKey } = {}) {
  if (!isConfigured()) {
    const error = new Error("YooKassa is not configured on the server");
    error.statusCode = 503;
    throw error;
  }

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: {
      Authorization: buildAuthHeader(),
      "Content-Type": "application/json",
      ...(idempotenceKey ? { "Idempotence-Key": idempotenceKey } : {})
    },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(env.aiTimeoutMs)
  });

  const text = await response.text();
  let data = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch (_error) {
      data = null;
    }
  }

  if (!response.ok) {
    const error = new Error(data?.description || data?.error || "YooKassa request failed");
    error.statusCode = response.status;
    error.payload = data || text;
    throw error;
  }

  return data;
}

function normalizePrice() {
  return Number(env.proSubscriptionPriceRub || 100).toFixed(2);
}

async function createInitialSubscriptionPayment({ userId, planCode }) {
  const body = {
    amount: {
      value: normalizePrice(),
      currency: "RUB"
    },
    capture: true,
    confirmation: {
      type: "redirect",
      ...(env.yookassaReturnUrl ? { return_url: env.yookassaReturnUrl } : {})
    },
    save_payment_method: true,
    description: "Pro subscription for 30 days",
    metadata: {
      user_id: String(userId),
      plan_code: String(planCode),
      kind: "subscription_initial"
    }
  };

  return request("/payments", {
    method: "POST",
    body,
    idempotenceKey: crypto.randomUUID()
  });
}

async function createRenewalPayment({ userId, planCode, paymentMethodId }) {
  const body = {
    amount: {
      value: normalizePrice(),
      currency: "RUB"
    },
    capture: true,
    payment_method_id: paymentMethodId,
    description: "Pro subscription renewal for 30 days",
    metadata: {
      user_id: String(userId),
      plan_code: String(planCode),
      kind: "subscription_renewal"
    }
  };

  return request("/payments", {
    method: "POST",
    body,
    idempotenceKey: crypto.randomUUID()
  });
}

async function getPayment(paymentId) {
  return request(`/payments/${paymentId}`);
}

module.exports = {
  isConfigured,
  createInitialSubscriptionPayment,
  createRenewalPayment,
  getPayment
};
