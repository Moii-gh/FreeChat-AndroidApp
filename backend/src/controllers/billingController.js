const { createBillingService } = require("../services/billingService");

function createBillingController({ userModel, aiUsageModel }) {
  const billingService = createBillingService({ userModel, aiUsageModel });

  return {
    status: async (req, res, next) => {
      try {
        const status = await billingService.getBillingStatus(req.auth.sub);
        return res.status(200).json(status);
      } catch (error) {
        return next(error);
      }
    },

    checkout: async (req, res, next) => {
      try {
        const result = await billingService.startCheckout(req.auth.sub);
        return res.status(200).json(result);
      } catch (error) {
        return next(error);
      }
    },

    cancel: async (req, res, next) => {
      try {
        const result = await billingService.cancelSubscription(req.auth.sub);
        return res.status(200).json(result);
      } catch (error) {
        return next(error);
      }
    },

    webhook: async (req, res, next) => {
      try {
        await billingService.handleWebhook(req.body, req);
        return res.status(204).send();
      } catch (error) {
        return next(error);
      }
    }
  };
}

module.exports = { createBillingController };
