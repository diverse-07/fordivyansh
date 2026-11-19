import { test } from "node:test";
import assert from "node:assert/strict";
import { createHandler } from "../src/index.mjs";

test("limiter service failures fail closed before any paid call", async () => {
  let calls = 0;
  const handler = createHandler(async () => { calls++; throw Error("must not call"); });
  for (const broken of ["CLIENT_LIMITER", "GLOBAL_LIMITER"]) {
    const env = { APP_ACCESS: "public", META_API_KEY: "test-only",
      CLIENT_LIMITER: { limit: async () => ({ success: true }) },
      GLOBAL_LIMITER: { limit: async () => ({ success: true }) } };
    env[broken] = { limit: async () => { throw Error("binding unavailable"); } };
    const response = await handler(new Request("https://gateway.example/assistant", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ question: "test", context: "{}", history: [] })
    }), env);
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), { error: "LIMITER_UNAVAILABLE" });
  }
  assert.equal(calls, 0);
});
