const { env } = require("../config/env");
const subscriptionModel = require("../models/subscriptionModel");
const billingPaymentModel = require("../models/billingPaymentModel");
const yookassaService = require("./yookassaService");
const { FREE_PLAN_CODE, isProUser, parseDate } = require("./entitlementService");

function addDays(date, days) {
  const copy = new Date(date);
  copy.setUTCDate(copy.getUTCDate() + days);
  return copy;
}

function buildStatusResponse(user, subscription) {
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
    isPro
  };
}

function createBillingService({ userModel }) {
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
      subscription = await subscriptionModel.markExpired(userId);
    }

    return {
      user: updatedUser || user,
      subscription
    };
  }

  async function getBillingStatus(userId) {
    const user = await userModel.findById(userId);
    if (!user) {
      const error = new Error("User not found");
      error.statusCode = 404;
      throw error;
    }

    const subscription = await subscriptionModel.findByUserId(userId);
    const refreshed = await refreshExpiredAccess(userId, user, subscription);
    return buildStatusResponse(refreshed.user, refreshed.subscription);
  }

  async function startCheckout(userId) {
    const user = await userModel.findById(userId);
    if (!user) {
      const error = new Error("User not found");
      error.statusCode = 404;
      throw error;
    }

    const subscription = await subscriptionModel.findByUserId(userId);
    const status = buildStatusResponse(user, subscription);
    if (status.isPro && !status.cancelAtPeriodEnd) {
      const error = new Error("Subscription is already active");
      error.statusCode = 409;
      throw error;
    }

    const payment = await yookassaService.createInitialSubscriptionPayment({
      userId,
      planCode: env.proSubscriptionPlanCode
    });

    await billingPaymentModel.upsertPayment({
      userId,
      providerPaymentId: payment.id,
      amountValue: Number(payment.amount?.value || env.proSubscriptionPriceRub),
      currency: payment.amount?.currency || "RUB",
      kind: "subscription_initial",
      status: payment.status,
      paymentMethodId: payment.payment_method?.id ?? null,
      rawPayload: payment
    });

    await subscriptionModel.upsertPendingSubscription({
      userId,
      planCode: env.proSubscriptionPlanCode,
      status: "pending",
      lastPaymentId: payment.id
    });

    return {
      paymentId: payment.id,
      confirmationUrl: payment.confirmation?.confirmation_url ?? null,
      status: payment.status
    };
  }

  async function applySuccessfulPayment({ userId, kind, payment, planCode }) {
    const existingSubscription = await subscriptionModel.findByUserId(userId);
    const now = new Date();
    const baseStart =
      kind === "subscription_renewal" &&
      existingSubscription?.current_period_end &&
      parseDate(existingSubscription.current_period_end) &&
      parseDate(existingSubscription.current_period_end).getTime() > now.getTime()
        ? parseDate(existingSubscription.current_period_end)
        : now;

    const currentPeriodStart = baseStart.toISOString();
    const currentPeriodEnd = addDays(baseStart, env.proSubscriptionPeriodDays).toISOString();

    await subscriptionModel.activateSubscription({
      userId,
      planCode,
      paymentMethodId: payment.payment_method?.id ?? existingSubscription?.payment_method_id ?? null,
      currentPeriodStart,
      currentPeriodEnd,
      lastPaymentId: payment.id,
      cancelAtPeriodEnd: false,
      status: "active"
    });

    await userModel.updatePlanState(userId, {
      planCode,
      subscriptionStatus: "active",
      planExpiresAt: currentPeriodEnd
    });

    return {
      paymentId: payment.id,
      status: payment.status,
      currentPeriodEnd
    };
  }

  async function handleWebhook(payload) {
    const paymentId = payload?.object?.id || payload?.id;
    if (!paymentId) {
      return { ignored: true, reason: "missing_payment_id" };
    }

    const payment = await yookassaService.getPayment(paymentId);
    const userId = payment?.metadata?.user_id;
    const planCode = payment?.metadata?.plan_code || env.proSubscriptionPlanCode;
    const kind = payment?.metadata?.kind || "subscription_initial";

    if (!userId) {
      return { ignored: true, reason: "missing_user_id", paymentId };
    }

    await billingPaymentModel.upsertPayment({
      userId,
      providerPaymentId: payment.id,
      amountValue: Number(payment.amount?.value || env.proSubscriptionPriceRub),
      currency: payment.amount?.currency || "RUB",
      kind,
      status: payment.status,
      paymentMethodId: payment.payment_method?.id ?? null,
      rawPayload: payment
    });

    if (payment.status === "succeeded") {
      return applySuccessfulPayment({ userId, kind, payment, planCode });
    }

    if (payment.status === "canceled") {
      await subscriptionModel.updateStatus(
        userId,
        kind === "subscription_initial" ? "inactive" : "past_due"
      );

      const user = await userModel.findById(userId);
      if (kind === "subscription_renewal" && user && !isProUser(user)) {
        await userModel.updatePlanState(userId, {
          planCode: FREE_PLAN_CODE,
          subscriptionStatus: "past_due",
          planExpiresAt: null
        });
      }
    }

    return {
      paymentId,
      status: payment.status,
      kind
    };
  }

  async function cancelSubscription(userId) {
    const subscription = await subscriptionModel.findByUserId(userId);
    if (!subscription) {
      const error = new Error("Active subscription not found");
      error.statusCode = 404;
      throw error;
    }

    await subscriptionModel.setCancelAtPeriodEnd(userId, true);
    return getBillingStatus(userId);
  }

  async function processDueRenewals(limit = 20) {
    const dueSubscriptions = await subscriptionModel.findDueForRenewal(limit);
    const results = [];

    for (const subscription of dueSubscriptions) {
      try {
        const payment = await yookassaService.createRenewalPayment({
          userId: subscription.user_id,
          planCode: subscription.plan_code,
          paymentMethodId: subscription.payment_method_id
        });

        await billingPaymentModel.upsertPayment({
          userId: subscription.user_id,
          providerPaymentId: payment.id,
          amountValue: Number(payment.amount?.value || env.proSubscriptionPriceRub),
          currency: payment.amount?.currency || "RUB",
          kind: "subscription_renewal",
          status: payment.status,
          paymentMethodId: payment.payment_method?.id ?? subscription.payment_method_id,
          rawPayload: payment
        });

        if (payment.status === "succeeded") {
          results.push(
            await applySuccessfulPayment({
              userId: subscription.user_id,
              kind: "subscription_renewal",
              payment,
              planCode: subscription.plan_code
            })
          );
        } else {
          await subscriptionModel.updateStatus(subscription.user_id, "past_due");
          await userModel.updatePlanState(subscription.user_id, {
            planCode: FREE_PLAN_CODE,
            subscriptionStatus: "past_due",
            planExpiresAt: null
          });
          results.push({
            paymentId: payment.id,
            status: payment.status
          });
        }
      } catch (error) {
        await subscriptionModel.updateStatus(subscription.user_id, "past_due");
        await userModel.updatePlanState(subscription.user_id, {
          planCode: FREE_PLAN_CODE,
          subscriptionStatus: "past_due",
          planExpiresAt: null
        });

        results.push({
          userId: subscription.user_id,
          status: "error",
          message: error.message
        });
      }
    }

    return {
      processed: dueSubscriptions.length,
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
