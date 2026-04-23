const { createBillingService } = require("../services/billingService");

function createBillingController({ userModel }) {
  const billingService = createBillingService({ userModel });

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
        const result = await billingService.handleWebhook(req.body);
        return res.status(200).json({
          ok: true,
          result
        });
      } catch (error) {
        return next(error);
      }
    }
  };
}

module.exports = { createBillingController };
