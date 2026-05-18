const test = require("node:test");
const assert = require("node:assert/strict");
const {
  upsertChats,
  upsertMessages,
  getUserChats,
  getUserMessages
} = require("../models/syncModel");
const { syncPayloadSchema } = require("../schemas/syncSchemas");

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
        isDeleted: true,
        isTitleManuallyEdited: true
      }
    ],
    executor
  );

  assert.equal(executor.queries.length, 1);
  assert.match(executor.queries[0].text, /is_title_manually_edited/);
  assert.match(executor.queries[0].text, /WHERE chats\.user_id = EXCLUDED\.user_id/);
  assert.match(executor.queries[0].text, /chats\.last_updated_ms <= EXCLUDED\.last_updated_ms/);
  assert.deepEqual(executor.queries[0].params.slice(0, 2), [
    "0f9f657d-558a-4711-91bc-bdc9171b1cb0",
    "user-1"
  ]);
  assert.equal(executor.queries[0].params[8], true);
});

test("sync chat schema defaults manual title flag for older clients", () => {
  const parsed = syncPayloadSchema.parse({
    chats: [
      {
        id: "0f9f657d-558a-4711-91bc-bdc9171b1cb0",
        title: "Secure chat",
        timestamp: 123,
        isPinned: false,
        lastUpdated: 456,
        summary: "summary",
        isDeleted: false
      }
    ],
    messages: []
  });

  assert.equal(parsed.chats[0].isTitleManuallyEdited, false);
});

test("upsertMessages only inserts into non-deleted chats owned by the authenticated user", async () => {
  const executor = createFakeExecutor();
  const originalNow = Date.now;
  Date.now = () => 123456;

  try {
    await upsertMessages(
      "user-1",
      [
        {
          syncId: "e3233f2b-0c52-45ec-b77b-ddb43597d29a",
          chatId: "0f9f657d-558a-4711-91bc-bdc9171b1cb0",
          role: "user",
          content: "hello",
          timestamp: 789,
          imageUrl: null,
          attachmentData: "SGVsbG8=",
          attachmentMimeType: "text/plain",
          attachmentFileName: "note.txt",
          attachmentContext: "Hello",
          updatedAt: 999999,
          isDeleted: false,
          editRevision: 2
        }
      ],
      executor
    );
  } finally {
    Date.now = originalNow;
  }

  assert.equal(executor.queries.length, 1);
  assert.match(executor.queries[0].text, /user_id = \$14/);
  assert.match(executor.queries[0].text, /is_deleted = false/);
  assert.match(executor.queries[0].text, /ON CONFLICT \(id\) DO UPDATE/);
  assert.match(executor.queries[0].text, /EXCLUDED\.edit_revision > messages\.edit_revision/);
  assert.match(executor.queries[0].text, /EXCLUDED\.updated_at_ms >= messages\.updated_at_ms/);
  assert.equal(executor.queries[0].params[6], "SGVsbG8=");
  assert.equal(executor.queries[0].params[7], "text/plain");
  assert.equal(executor.queries[0].params[8], "note.txt");
  assert.equal(executor.queries[0].params[9], "Hello");
  assert.equal(executor.queries[0].params[10], 123456);
  assert.equal(executor.queries[0].params[11], false);
  assert.equal(executor.queries[0].params[12], 2);
  assert.equal(executor.queries[0].params[13], "user-1");
});

test("upsertMessages persists tombstones with scrubbed content and attachments", async () => {
  const executor = createFakeExecutor();
  const originalNow = Date.now;
  Date.now = () => 456789;

  try {
    await upsertMessages(
      "user-1",
      [
        {
          syncId: "e3233f2b-0c52-45ec-b77b-ddb43597d29a",
          chatId: "0f9f657d-558a-4711-91bc-bdc9171b1cb0",
          role: "assistant",
          content: "old answer",
          timestamp: 789,
          imageUrl: "https://example.com/old.png",
          attachmentData: "SGVsbG8=",
          attachmentMimeType: "text/plain",
          attachmentFileName: "note.txt",
          attachmentContext: "Hello",
          updatedAt: 111,
          isDeleted: true,
          editRevision: 3
        }
      ],
      executor
    );
  } finally {
    Date.now = originalNow;
  }

  assert.equal(executor.queries.length, 1);
  assert.equal(executor.queries[0].params[3], "");
  assert.equal(executor.queries[0].params[5], null);
  assert.equal(executor.queries[0].params[6], null);
  assert.equal(executor.queries[0].params[7], null);
  assert.equal(executor.queries[0].params[8], null);
  assert.equal(executor.queries[0].params[9], null);
  assert.equal(executor.queries[0].params[10], 456789);
  assert.equal(executor.queries[0].params[11], true);
  assert.equal(executor.queries[0].params[12], 3);
});

test("getUserChats returns camelCase fields for Android sync", async () => {
  const executor = createFakeExecutor();

  await getUserChats("user-1", executor);

  assert.equal(executor.queries.length, 1);
  assert.match(executor.queries[0].text, /is_pinned as "isPinned"/);
  assert.match(executor.queries[0].text, /last_updated_ms as "lastUpdated"/);
  assert.match(executor.queries[0].text, /is_deleted as "isDeleted"/);
  assert.match(executor.queries[0].text, /is_title_manually_edited as "isTitleManuallyEdited"/);
});

test("getUserMessages excludes tombstoned chats from the sync response", async () => {
  const executor = createFakeExecutor();

  await getUserMessages("user-1", executor);

  assert.equal(executor.queries.length, 1);
  assert.match(executor.queries[0].text, /WHERE c\.user_id = \$1 AND c\.is_deleted = false/);
  assert.match(executor.queries[0].text, /m\.updated_at_ms as "updatedAt"/);
  assert.match(executor.queries[0].text, /m\.is_deleted as "isDeleted"/);
  assert.match(executor.queries[0].text, /m\.edit_revision as "editRevision"/);
});
