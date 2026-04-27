const crypto = require("node:crypto");
const { env } = require("../config/env");
const { withTransaction } = require("../config/db");
const subscriptionModel = require("../models/subscriptionModel");
const billingPaymentModel = require("../models/billingPaymentModel");
const yookassaService = require("./yookassaService");
const { FREE_PLAN_CODE, isProUser, parseDate } = require("./entitlementService");
const { extractRequestIp, isYookassaWebhookIp } = require("../utils/ipAllowList");

function addDays(date, days) {
  const copy = new Date(date);
  copy.setUTCDate(copy.getUTCDate() + days);
  return copy;
}

function buildRenewalIdempotenceKey(subscription) {
  return crypto
    .createHash("sha256")
    .update(
      `renewal:${subscription.user_id}:${subscription.plan_code}:${subscription.current_period_end?.toISOString?.() || subscription.current_period_end}`
    )
    .digest("hex");
}

function buildStatusResponse(user, subscription, dailyQuota) {
  const isPro = isProUser(user);
  const currentPeriodEnd = subscription?.current_period_end ?? user.plan_expires_at ?? null;
  return {
    planCode: isPro ? user.plan_code : FREE_PLAN_CODE,
    subscriptionStatus: subscription?.status ?? user.subscription_status ?? "inactive",
    planExpiresAt: isPro ? user.plan_expires_at : null,
    currentPeriodEnd,
    cancelAtPeriodEnd: Boolean(subscription?.cancel_at_period_end),
    hasPaymentMethod: Boolean(subscription?.payment_method_id),
    priceRub: env.proSubscriptionPriceRub,
    isPro,
    dailyRequestLimit: dailyQuota.dailyRequestLimit,
    remainingDailyRequests: dailyQuota.remainingDailyRequests,
    dailyQuotaResetsAt: dailyQuota.dailyQuotaResetsAt
  };
}

function createWebhookIpError() {
  const error = new Error("Webhook source is not allowed");
  error.statusCode = 403;
  return error;
}

function createNotFoundError(message) {
  const error = new Error(message);
  error.statusCode = 404;
  return error;
}

function createConflictError(message) {
  const error = new Error(message);
  error.statusCode = 409;
  return error;
}

function createBillingService({
  userModel,
  aiUsageModel,
  subscriptionRepo = subscriptionModel,
  paymentRepo = billingPaymentModel,
  providerService = yookassaService
}) {
  async function getDailyQuotaStatus(user) {
    if (!user || isProUser(user)) {
      return {
        dailyRequestLimit: null,
        remainingDailyRequests: null,
        dailyQuotaResetsAt: null
      };
    }

    const snapshot = await aiUsageModel.getDailyUsageSnapshot(user.id, {
      limit: env.dailyAiRequestLimit
    });

    return {
      dailyRequestLimit: snapshot.limit,
      remainingDailyRequests: snapshot.remaining,
      dailyQuotaResetsAt: snapshot.resetsAt
    };
  }

  async function refreshExpiredAccess(userId, user, subscription) {
    const expiresAt = parseDate(user?.plan_expires_at);
    if (!user || !expiresAt || expiresAt.getTime() > Date.now()) {
      return { user, subscription };
    }

    const nextSubscriptionStatus =
      subscription?.status === "past_due" ? "past_due" :
      subscription?.status === "canceled" ? "expired" :
      "expired";

    const updatedUser = await userModel.updatePlanState(userId, {
      planCode: FREE_PLAN_CODE,
      subscriptionStatus: nextSubscriptionStatus,
      planExpiresAt: null
    });

    if (subscription && subscription.status !== "expired" && subscription.current_period_end) {
      subscription = await subscriptionRepo.markExpired(userId);
    }

    return {
      user: updatedUser || user,
      subscription
    };
  }

  async function getBillingStatus(userId) {
    const user = await userModel.findById(userId);
    if (!user) {
      throw createNotFoundError("User not found");
    }

    const subscription = await subscriptionRepo.findByUserId(userId);
    const refreshed = await refreshExpiredAccess(userId, user, subscription);
    const dailyQuota = await getDailyQuotaStatus(refreshed.user);

    return buildStatusResponse(refreshed.user, refreshed.subscription, dailyQuota);
  }

  async function startCheckout(userId) {
    const user = await userModel.findById(userId);
    if (!user) {
      throw createNotFoundError("User not found");
    }

    const subscription = await subscriptionRepo.findByUserId(userId);
    const dailyQuota = await getDailyQuotaStatus(user);
    const status = buildStatusResponse(user, subscription, dailyQuota);
    if (status.isPro && !status.cancelAtPeriodEnd) {
      throw createConflictError("Subscription is already active");
    }

    const payment = await providerService.createInitialSubscriptionPayment({
      userId,
      planCode: env.proSubscriptionPlanCode
    });

    await withTransaction(async (executor) => {
      await paymentRepo.upsertPayment({
        userId,
        providerPaymentId: payment.id,
        amountValue: Number(payment.amount?.value || env.proSubscriptionPriceRub),
        currency: payment.amount?.currency || "RUB",
        kind: "subscription_initial",
        status: payment.status,
        paymentMethodId: payment.payment_method?.id ?? null,
        rawPayload: payment
      }, executor);

      await subscriptionRepo.upsertPendingSubscription({
        userId,
        planCode: env.proSubscriptionPlanCode,
        status: "pending",
        lastPaymentId: payment.id
      }, executor);
    });

    return {
      paymentId: payment.id,
      confirmationUrl: payment.confirmation?.confirmation_url ?? null,
      status: payment.status
    };
  }

  async function applySuccessfulPayment({ userId, kind, payment, planCode, executor }) {
    const existingSubscription =
      await subscriptionRepo.findByUserIdForUpdate(userId, executor) ||
      await subscriptionRepo.findByUserId(userId, executor);
    const now = new Date();
    const existingPeriodEnd = parseDate(existingSubscription?.current_period_end);
    const baseStart =
      kind === "subscription_renewal" &&
      existingPeriodEnd &&
      existingPeriodEnd.getTime() > now.getTime()
        ? existingPeriodEnd
        : now;

    const currentPeriodStart = baseStart.toISOString();
    const currentPeriodEnd = addDays(baseStart, env.proSubscriptionPeriodDays).toISOString();

    await subscriptionRepo.activateSubscription({
      userId,
      planCode,
      paymentMethodId: payment.payment_method?.id ?? existingSubscription?.payment_method_id ?? null,
      currentPeriodStart,
      currentPeriodEnd,
      lastPaymentId: payment.id,
      cancelAtPeriodEnd: false,
      status: "active"
    }, executor);

    await userModel.updatePlanState(userId, {
      planCode,
      subscriptionStatus: "active",
      planExpiresAt: currentPeriodEnd
    }, executor);

    return {
      paymentId: payment.id,
      status: payment.status,
      currentPeriodEnd
    };
  }

  async function applyCanceledPayment({ userId, kind, executor }) {
    await subscriptionRepo.updateStatus(
      userId,
      kind === "subscription_initial" ? "inactive" : "past_due",
      executor
    );

    const user = await userModel.findById(userId, executor);
    if (kind === "subscription_renewal" && user && !isProUser(user)) {
      await userModel.updatePlanState(userId, {
        planCode: FREE_PLAN_CODE,
        subscriptionStatus: "past_due",
        planExpiresAt: null
      }, executor);
    }
  }

  async function handleWebhook(payload, req) {
    const sourceIp = extractRequestIp(req);
    if (!isYookassaWebhookIp(sourceIp)) {
      throw createWebhookIpError();
    }

    const paymentId = payload?.object?.id || payload?.id;
    if (!paymentId) {
      return { ignored: true, reason: "missing_payment_id" };
    }

    const payment = await providerService.getPayment(paymentId);
    const userId = payment?.metadata?.user_id;
    const planCode = payment?.metadata?.plan_code || env.proSubscriptionPlanCode;
    const kind = payment?.metadata?.kind || "subscription_initial";

    if (!userId) {
      return { ignored: true, reason: "missing_user_id", paymentId };
    }

    return withTransaction(async (executor) => {
      const existingPayment = await paymentRepo.findByProviderPaymentId(payment.id, executor);

      await paymentRepo.upsertPayment({
        userId,
        providerPaymentId: payment.id,
        amountValue: Number(payment.amount?.value || env.proSubscriptionPriceRub),
        currency: payment.amount?.currency || "RUB",
        kind,
        status: payment.status,
        paymentMethodId: payment.payment_method?.id ?? null,
        rawPayload: payment
      }, executor);

      if (payment.status === "succeeded" && existingPayment?.status !== "succeeded") {
        return applySuccessfulPayment({
          userId,
          kind,
          payment,
          planCode,
          executor
        });
      }

      if (payment.status === "canceled" && existingPayment?.status !== "canceled") {
        await applyCanceledPayment({ userId, kind, executor });
      }

      return {
        paymentId,
        status: payment.status,
        kind,
        idempotent: true
      };
    });
  }

  async function cancelSubscription(userId) {
    const subscription = await subscriptionRepo.findByUserId(userId);
    if (!subscription) {
      throw createNotFoundError("Active subscription not found");
    }

    await subscriptionRepo.setCancelAtPeriodEnd(userId, true);
    return getBillingStatus(userId);
  }

  async function processDueRenewals(limit = 20) {
    const results = [];

    for (let index = 0; index < limit; index += 1) {
      let claimedSubscription = null;

      try {
        const result = await withTransaction(async (executor) => {
          claimedSubscription = await subscriptionRepo.claimDueRenewal(executor);
          if (!claimedSubscription) {
            return null;
          }

          const payment = await providerService.createRenewalPayment({
            userId: claimedSubscription.user_id,
            planCode: claimedSubscription.plan_code,
            paymentMethodId: claimedSubscription.payment_method_id,
            idempotenceKey: buildRenewalIdempotenceKey(claimedSubscription)
          });

          const existingPayment = await paymentRepo.findByProviderPaymentId(payment.id, executor);

          await paymentRepo.upsertPayment({
            userId: claimedSubscription.user_id,
            providerPaymentId: payment.id,
            amountValue: Number(payment.amount?.value || env.proSubscriptionPriceRub),
            currency: payment.amount?.currency || "RUB",
            kind: "subscription_renewal",
            status: payment.status,
            paymentMethodId: payment.payment_method?.id ?? claimedSubscription.payment_method_id,
            rawPayload: payment
          }, executor);

          if (payment.status === "succeeded" && existingPayment?.status !== "succeeded") {
            return applySuccessfulPayment({
              userId: claimedSubscription.user_id,
              kind: "subscription_renewal",
              payment,
              planCode: claimedSubscription.plan_code,
              executor
            });
          }

          await subscriptionRepo.updateStatus(claimedSubscription.user_id, "past_due", executor);
          await userModel.updatePlanState(claimedSubscription.user_id, {
            planCode: FREE_PLAN_CODE,
            subscriptionStatus: "past_due",
            planExpiresAt: null
          }, executor);

          return {
            paymentId: payment.id,
            status: payment.status,
            userId: claimedSubscription.user_id
          };
        });

        if (!result) {
          break;
        }

        results.push(result);
      } catch (error) {
        if (claimedSubscription?.user_id) {
          await withTransaction(async (executor) => {
            await subscriptionRepo.updateStatus(claimedSubscription.user_id, "past_due", executor);
            await userModel.updatePlanState(claimedSubscription.user_id, {
              planCode: FREE_PLAN_CODE,
              subscriptionStatus: "past_due",
              planExpiresAt: null
            }, executor);
          });
        }

        results.push({
          userId: claimedSubscription?.user_id || null,
          status: "error",
          message: error.message
        });
      }
    }

    return {
      processed: results.length,
      results
    };
  }

  return {
    getBillingStatus,
    startCheckout,
    handleWebhook,
    cancelSubscription,
    processDueRenewals
  };
}

module.exports = { createBillingService };
