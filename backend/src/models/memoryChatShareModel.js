const crypto = require("node:crypto");

const links = [];

async function createShare({
  ownerUserId,
  sourceChatId,
  tokenHash,
  title,
  summary,
  snapshot,
  expiresAt
}) {
  const row = {
    id: crypto.randomUUID(),
    ownerUserId,
    sourceChatId,
    tokenHash,
    title,
    summary,
    snapshot,
    createdAt: new Date().toISOString(),
    expiresAt,
    revokedAt: null
  };
  links.push(row);
  return row;
}

async function findActiveByTokenHash(tokenHash) {
  const now = Date.now();
  return links.find((link) => (
    link.tokenHash === tokenHash &&
    !link.revokedAt &&
    new Date(link.expiresAt).getTime() > now
  )) || null;
}

async function revokeByTokenHash(ownerUserId, tokenHash) {
  let count = 0;
  for (const link of links) {
    if (link.ownerUserId === ownerUserId && link.tokenHash === tokenHash && !link.revokedAt) {
      link.revokedAt = new Date().toISOString();
      count += 1;
    }
  }
  return count;
}

async function revokeBySourceChat(ownerUserId, sourceChatId) {
  let count = 0;
  for (const link of links) {
    if (link.ownerUserId === ownerUserId && link.sourceChatId === sourceChatId && !link.revokedAt) {
      link.revokedAt = new Date().toISOString();
      count += 1;
    }
  }
  return count;
}

module.exports = {
  createShare,
  findActiveByTokenHash,
  revokeByTokenHash,
  revokeBySourceChat
};
