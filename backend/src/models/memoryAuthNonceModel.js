const nonces = new Set();

async function consumeNonce({ kind, nonceHash }) {
  const key = `${kind}:${nonceHash}`;
  if (nonces.has(key)) {
    return false;
  }

  nonces.add(key);
  return true;
}

module.exports = {
  consumeNonce
};
