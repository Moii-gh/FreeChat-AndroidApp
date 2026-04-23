const test = require("node:test");
const assert = require("node:assert/strict");
const {
  upsertChats,
  upsertMessages,
  getUserMessages
} = require("../models/syncModel");

function createFakeExecutor() {
  return {
    queries: [],
    async query(text, params = []) {
      this.queries.push({ text, params });
      return { rows: [] };
    }
  };
}

test("upsertChats preserves ownership and last-write-wins ordering in SQL", async () => {
  const executor = createFakeExecutor();

  await upsertChats(
    "user-1",
    [
      {
        id: "0f9f657d-558a-4711-91bc-bdc9171b1cb0",
        title: "Secure chat",
        timestamp: 123,
        isPinned: false,
        lastUpdated: 456,
        summary: "summary",
        isDeleted: true
      }
    ],
    executor
  );

  assert.equal(executor.queries.length, 1);
  assert.match(executor.queries[0].text, /WHERE chats\.user_id = EXCLUDED\.user_id/);
  assert.match(executor.queries[0].text, /chats\.last_updated_ms <= EXCLUDED\.last_updated_ms/);
  assert.deepEqual(executor.queries[0].params.slice(0, 2), [
    "0f9f657d-558a-4711-91bc-bdc9171b1cb0",
    "user-1"
  ]);
});

test("upsertMessages only inserts into non-deleted chats owned by the authenticated user", async () => {
  const executor = createFakeExecutor();

  await upsertMessages(
    "user-1",
    [
      {
        syncId: "e3233f2b-0c52-45ec-b77b-ddb43597d29a",
        chatId: "0f9f657d-558a-4711-91bc-bdc9171b1cb0",
        role: "user",
        content: "hello",
        timestamp: 789,
        imageUrl: null
      }
    ],
    executor
  );

  assert.equal(executor.queries.length, 1);
  assert.match(executor.queries[0].text, /user_id = \$7/);
  assert.match(executor.queries[0].text, /is_deleted = false/);
  assert.equal(executor.queries[0].params[6], "user-1");
});

test("getUserMessages excludes tombstoned chats from the sync response", async () => {
  const executor = createFakeExecutor();

  await getUserMessages("user-1", executor);

  assert.equal(executor.queries.length, 1);
  assert.match(executor.queries[0].text, /WHERE c\.user_id = \$1 AND c\.is_deleted = false/);
});
