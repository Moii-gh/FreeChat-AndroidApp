const test = require("node:test");
const assert = require("node:assert/strict");
const crypto = require("node:crypto");

function loadBillingServiceWith(fakeWithTransaction) {
  const dbModulePath = require.resolve("../config/db");
  const billingServicePath = require.resolve("../services/billingService");
  const dbModule = require(dbModulePath);
  const dbCacheEntry = require.cache[dbModulePath];
  const originalExports = dbCacheEntry.exports;

  delete require.cache[billingServicePath];
  dbCacheEntry.exports = {
    ...dbModule,
    withTransaction: fakeWithTransaction
  };

  const loaded = require("../services/billingService");

  return {
    ...loaded,
    restore() {
      delete require.cache[billingServicePath];
      dbCacheEntry.exports = originalExports;
    }
  };
}

test("handleWebhook rejects requests from non-YooKassa IPs before provider access", async () => {
  const loaded = loadBillingServiceWith(async (callback) => callback({}));
  let providerCallCount = 0;

  try {
    const service = loaded.createBillingService({
      userModel: {},
      aiUsageModel: {},
      subscriptionRepo: {},
      paymentRepo: {},
      providerService: {
        async getPayment() {
          providerCallCount += 1;
          return null;
        }
      }
    });

    await assert.rejects(
      () =>
        service.handleWebhook(
          { object: { id: "payment-1" } },
          {
            socket: {
              remoteAddress: "203.0.113.10"
            },
            headers: {}
          }
        ),
      (error) => {
        assert.equal(error.statusCode, 403);
        return true;
      }
    );

    assert.equal(providerCallCount, 0);
  } finally {
    loaded.restore();
  }
});

test("handleWebhook is idempotent for replayed succeeded payments", async () => {
  const loaded = loadBillingServiceWith(async (callback) => callback({}));
  let planStateUpdates = 0;
  let activationCalls = 0;
  let paymentUpserts = 0;

  try {
    const service = loaded.createBillingService({
      userModel: {
        async updatePlanState() {
          planStateUpdates += 1;
          return null;
        }
      },
      aiUsageModel: {},
      subscriptionRepo: {
        async activateSubscription() {
          activationCalls += 1;
          return null;
        }
      },
      paymentRepo: {
        async findByProviderPaymentId() {
          return { status: "succeeded" };
        },
        async upsertPayment() {
          paymentUpserts += 1;
          return null;
        }
      },
      providerService: {
        async getPayment() {
          return {
            id: "payment-1",
            status: "succeeded",
            metadata: {
              user_id: "user-1",
              plan_code: "pro_100",
              kind: "subscription_initial"
            },
            amount: {
              value: "100.00",
              currency: "RUB"
            },
            payment_method: {
              id: "pm-1"
            }
          };
        }
      }
    });

    const result = await service.handleWebhook(
      { object: { id: "payment-1" } },
      {
        socket: {
          remoteAddress: "185.71.76.5"
        },
        headers: {}
      }
    );

    assert.equal(result.idempotent, true);
    assert.equal(result.status, "succeeded");
    assert.equal(paymentUpserts, 1);
    assert.equal(activationCalls, 0);
    assert.equal(planStateUpdates, 0);
  } finally {
    loaded.restore();
  }
});

test("processDueRenewals uses a deterministic idempotence key for each billing period", async () => {
  const loaded = loadBillingServiceWith(async (callback) => callback({}));
  const subscription = {
    user_id: "user-1",
    plan_code: "pro_100",
    payment_method_id: "pm-1",
    current_period_end: new Date("2026-01-15T00:00:00.000Z")
  };
  let claimCount = 0;
  let activationCalls = 0;
  let planStateUpdates = 0;
  let capturedIdempotenceKey = null;

  try {
    const service = loaded.createBillingService({
      userModel: {
        async updatePlanState() {
          planStateUpdates += 1;
          return null;
        }
      },
      aiUsageModel: {},
      subscriptionRepo: {
        async claimDueRenewal() {
          if (claimCount > 0) {
            return null;
          }

          claimCount += 1;
          return subscription;
        },
        async findByUserIdForUpdate() {
          return subscription;
        },
        async findByUserId() {
          return subscription;
        },
        async activateSubscription() {
          activationCalls += 1;
          return null;
        },
        async updateStatus() {
          return null;
        }
      },
      paymentRepo: {
        async findByProviderPaymentId() {
          return null;
        },
        async upsertPayment() {
          return null;
        }
      },
      providerService: {
        async createRenewalPayment({ idempotenceKey }) {
          capturedIdempotenceKey = idempotenceKey;
          return {
            id: "payment-renewal-1",
            status: "succeeded",
            amount: {
              value: "100.00",
              currency: "RUB"
            },
            payment_method: {
              id: "pm-1"
            }
          };
        }
      }
    });

    const result = await service.processDueRenewals(2);
    const expectedIdempotenceKey = crypto
      .createHash("sha256")
      .update(
        `renewal:${subscription.user_id}:${subscription.plan_code}:${subscription.current_period_end.toISOString()}`
      )
      .digest("hex");

    assert.equal(result.processed, 1);
    assert.equal(capturedIdempotenceKey, expectedIdempotenceKey);
    assert.equal(activationCalls, 1);
    assert.equal(planStateUpdates, 1);
  } finally {
    loaded.restore();
  }
});
