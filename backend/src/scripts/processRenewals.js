const { userModel } = require("../modelRegistry");
const { createBillingService } = require("../services/billingService");

async function main() {
  const billingService = createBillingService({ userModel });
  const result = await billingService.processDueRenewals();
  console.log(JSON.stringify(result));
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
