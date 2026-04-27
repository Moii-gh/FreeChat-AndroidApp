const { pool } = require("../config/db");

function getExecutor(executor) {
  return executor || pool;
}

async function consumeNonce({ kind, nonceHash, expiresAt }, executor) {
  const result = await getExecutor(executor).query(
    `insert into auth_nonces (kind, nonce_hash, expires_at, consumed_at)
     values ($1, $2, $3, now())
     on conflict (kind, nonce_hash) do nothing
     returning kind, nonce_hash`,
    [kind, nonceHash, expiresAt]
  );

  return result.rowCount > 0;
}

module.exports = {
  consumeNonce
};
