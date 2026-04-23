const test = require("node:test");
const assert = require("node:assert/strict");

const ENV_KEYS = [
  "NODE_ENV",
  "NODE_TEST_CONTEXT",
  "VITEST",
  "DATABASE_URL",
  "JWT_SECRET",
  "VERIFICATION_CODE_SECRET"
];

function loadEnvModule(overrides = {}) {
  const modulePath = require.resolve("../config/env");
  const originalEnv = {};
  const originalArgv = process.argv.slice();

  ENV_KEYS.forEach((key) => {
    originalEnv[key] = process.env[key];
  });

  process.argv = originalArgv.filter((argument) => argument !== "--test");

  ENV_KEYS.forEach((key) => {
    if (Object.prototype.hasOwnProperty.call(overrides, key)) {
      const value = overrides[key];
      if (value === undefined) {
        delete process.env[key];
      } else {
        process.env[key] = value;
      }
      return;
    }

    delete process.env[key];
  });

  delete require.cache[modulePath];
  const loaded = require("../config/env");

  return {
    ...loaded,
    restore() {
      delete require.cache[modulePath];
      process.argv = originalArgv;

      ENV_KEYS.forEach((key) => {
        if (originalEnv[key] === undefined) {
          delete process.env[key];
        } else {
          process.env[key] = originalEnv[key];
        }
      });
    }
  };
}

test("assertServerRuntimeConfig fails fast without DATABASE_URL outside tests", () => {
  const loaded = loadEnvModule({
    NODE_ENV: "production",
    NODE_TEST_CONTEXT: undefined,
    VITEST: undefined,
    DATABASE_URL: "",
    JWT_SECRET: "prod-secret",
    VERIFICATION_CODE_SECRET: "prod-verification-secret"
  });

  try {
    assert.equal(loaded.env.isTest, false);
    assert.throws(
      () => loaded.assertServerRuntimeConfig(),
      /DATABASE_URL must be configured/
    );
  } finally {
    loaded.restore();
  }
});

test("assertServerRuntimeConfig rejects default or empty secrets outside tests", () => {
  const loaded = loadEnvModule({
    NODE_ENV: "production",
    NODE_TEST_CONTEXT: undefined,
    VITEST: undefined,
    DATABASE_URL: "postgres://example.test/chatapp",
    JWT_SECRET: "",
    VERIFICATION_CODE_SECRET: "prod-verification-secret"
  });

  try {
    assert.equal(loaded.env.isTest, false);
    assert.throws(
      () => loaded.assertServerRuntimeConfig(),
      /JWT_SECRET must be configured with a non-default secret/
    );
  } finally {
    loaded.restore();
  }
});

test("test environment receives non-production secret fallbacks", () => {
  const loaded = loadEnvModule({
    NODE_ENV: "test",
    NODE_TEST_CONTEXT: "1",
    DATABASE_URL: "",
    JWT_SECRET: "",
    VERIFICATION_CODE_SECRET: ""
  });

  try {
    assert.equal(loaded.env.isTest, true);
    assert.equal(loaded.env.jwtSecret, "test-jwt-secret");
    assert.equal(
      loaded.env.verificationCodeSecret,
      "test-verification-code-secret"
    );
    assert.doesNotThrow(() => loaded.assertServerRuntimeConfig());
  } finally {
    loaded.restore();
  }
});
