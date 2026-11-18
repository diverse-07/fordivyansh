import { test } from "node:test";
import assert from "node:assert/strict";
import { deploymentUrl } from "../scripts/deployment-url.mjs";

const output = (targets) =>
  JSON.stringify({ type: "deploy", version: 1, targets }) + "\n";
test("uses successful deployment metadata to configure the public Android origin", () => {
  assert.equal(
    deploymentUrl(
      output(["https://librevision-navigation-gateway.example.workers.dev"]),
    ),
    "https://librevision-navigation-gateway.example.workers.dev",
  );
});
test("prefers the stable workers.dev URL over other deployment targets", () => {
  assert.equal(
    deploymentUrl(
      output([
        "https://custom.example",
        "https://gateway.example.workers.dev",
        "schedule: * * * * *",
      ]),
    ),
    "https://gateway.example.workers.dev",
  );
});
test("no address is produced from failed deployment, preview upload or unsafe URL", () => {
  for (const data of [
    "",
    JSON.stringify({ type: "error", message: "failed" }),
    JSON.stringify({
      type: "version-upload",
      targets: ["https://preview.example"],
    }),
    output(["http://worker.example"]),
    output(["https://key@worker.example"]),
    output(["https://worker.example/assistant"]),
    output(["https://worker.example?key=secret"]),
  ]) {
    assert.throws(() => deploymentUrl(data));
  }
});
