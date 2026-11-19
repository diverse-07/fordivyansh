import { test } from "node:test";
import assert from "node:assert/strict";
import { validAction, canonicalAction, assistantPrompt, appTool } from "../src/assistant.mjs";
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
test("omitted nullable tool fields keep their meaning without weakening action validation", () => {
  const larger = { action: "SET_TEXT_SIZE", value: "LARGE" };
  assert.deepEqual(canonicalAction(larger), { ...larger, query: null, category: null });
  assert.equal(validAction(canonicalAction(larger)), true);
  assert.equal(validAction(canonicalAction({ action: "SET_TEXT_SIZE" })), false);
  assert.equal(validAction(canonicalAction({ ...larger, extra: "other command" })), false);
  assert.equal(validAction(canonicalAction({ ...larger, query: "unexpected" })), false);
});
