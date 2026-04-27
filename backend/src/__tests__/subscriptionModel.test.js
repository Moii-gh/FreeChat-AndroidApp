const test = require("node:test");
const assert = require("node:assert/strict");
const {
  findByUserIdForUpdate,
  claimDueRenewal
} = require("../models/subscriptionModel");

function createFakeExecutor() {
  return {
    queries: [],
    async query(text, params = []) {
      this.queries.push({ text, params });
      return { rows: [] };
    }
  };
}

test("findByUserIdForUpdate locks the subscription row", async () => {
  const executor = createFakeExecutor();

  await findByUserIdForUpdate("user-1", executor);

  assert.equal(executor.queries.length, 1);
  assert.match(executor.queries[0].text, /for update/i);
  assert.deepEqual(executor.queries[0].params, ["user-1"]);
});

test("claimDueRenewal uses skip-locked row claiming for concurrent renewal workers", async () => {
  const executor = createFakeExecutor();

  await claimDueRenewal(executor);

  assert.equal(executor.queries.length, 1);
  assert.match(executor.queries[0].text, /for update skip locked/i);
});
