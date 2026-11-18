import { test } from "node:test";
import assert from "node:assert/strict";
import { validAction, assistantPrompt, appTool } from "../src/assistant.mjs";
const control = (value) => ({
  action: "ACTIVATE_CONTROL",
  query: null,
  category: null,
  value,
});
test("assistant controls use bounded semantic ids and a closed schema", () => {
  assert.equal(validAction(control("more_settings")), true);
  assert.equal(
    validAction({ action: "BACK", query: null, category: null, value: null }),
    true,
  );
  for (const value of [
    "",
    "../settings",
    "https://example.com",
    "tap 100 200",
    "a".repeat(41),
  ])
    assert.equal(validAction(control(value)), false);
  assert.equal(validAction({ ...control("go"), query: "execute" }), false);
  assert.equal(appTool.function.parameters.additionalProperties, false);
});
test("general assistance uses current evidence and only advertised controls", () => {
  assert.match(assistantPrompt, /any user question/);
  assert.match(assistantPrompt, /currentPosition/);
  assert.match(assistantPrompt, /exact id from availableControls/);
  assert.match(assistantPrompt, /Never invent position/);
});
