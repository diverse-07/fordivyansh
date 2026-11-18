import { test } from "node:test";
import assert from "node:assert/strict";
import {
  createHandler,
  validAction,
  validImage,
  conciseText,
} from "../src/index.mjs";
import { appTool } from "../src/assistant.mjs";
const bytes = Uint8Array.from([
  255, 216, 255, 192, 0, 17, 8, 0, 2, 0, 3, 3, 1, 17, 0, 2, 17, 0, 3, 17, 0,
  255, 217,
]);
const image = {
  image: "data:image/jpeg;base64," + Buffer.from(bytes).toString("base64"),
  width: 3,
  height: 2,
};
const visual = () => ({
  task: "CONTEXTUALIZE_MANEUVER",
  model: "muse-spark-1.3",
  ...image,
  routeContext: "Turn left.",
  detections: ["cat"],
});
const chat = () => ({
  question: "I want to go to nearest hospital",
  context: '{"screen":"HOME"}',
  history: [],
});
const request = (p, path = "/assistant") =>
  new Request("http://127.0.0.1" + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(p),
  });
const env = { LOCAL_ONLY: "true", META_API_KEY: "test-only-not-a-key" };
const result = (text) =>
  Response.json({
    choices: [{ finish_reason: "stop", message: { content: text } }],
  });
const action = {
  action: "NAVIGATE_NEARBY",
  category: "HOSPITAL",
  query: null,
  value: null,
};
const call = (a = action) => ({
  type: "function",
  function: { name: "app_action", arguments: JSON.stringify(a) },
});
const toolResult = (calls) =>
  Response.json({
    choices: [{ finish_reason: "tool_calls", message: { tool_calls: calls } }],
  });
test("natural request uses documented tools, fixed upstream and bounded budget", async () => {
  const handler = createHandler(async (url, opts) => {
    assert.equal(url, "https://api.meta.ai/v1/chat/completions");
    const b = JSON.parse(opts.body);
    assert.equal(b.model, "muse-spark-1.3");
    assert.equal(b.reasoning_effort, "minimal");
    assert.equal(b.max_tokens, 768);
    assert.equal(b.tools[0].function.strict, true);
    assert.equal(b.parallel_tool_calls, false);
    assert.equal(b.tool_choice, undefined);
    assert.ok(b.messages[0].content.includes("very concise"));
    return toolResult([call()]);
  });
  const r = await handler(request(chat()), env);
  assert.equal(r.status, 200);
  assert.equal(r.headers.get("Cache-Control"), "no-store");
  assert.deepEqual((await r.json()).action, action);
});
test("natural concise answers are not constrained to five-word landmark templates", async () => {
  const text =
    "A blue pharmacy sign is on your left. The doorway is partly obscured, so the entrance is uncertain.";
  const handler = createHandler((_url, opts) => {
    const b = JSON.parse(opts.body);
    assert.equal(b.tools, undefined);
    assert.equal(b.response_format, undefined);
    return result(text);
  });
  const r = await handler(request(visual(), "/analyze"), env);
  assert.equal(r.status, 200);
  assert.equal((await r.json()).text, text);
});
test("selected image assistant request cannot recurse through another tool call", async () => {
  const r = await createHandler(() => toolResult([call()]))(
    request({ ...chat(), ...image }),
    env,
  );
  assert.equal(r.status, 502);
});
test("unknown functions, multiple actions, extra coordinates and invalid settings fail closed", async () => {
  const handlerFor = (calls) => createHandler(() => toolResult(calls));
  for (const calls of [
    [call(), call()],
    [{ ...call(), function: { ...call().function, name: "shell" } }],
    [call({ ...action, latitude: 23 })],
    [call({ ...action, category: "ANYWHERE" })],
  ])
    assert.equal((await handlerFor(calls)(request(chat()), env)).status, 502);
  assert.equal(
    validAction({
      ...action,
      action: "SET_TEXT_SIZE",
      category: null,
      value: "HUGE",
    }),
    false,
  );
  assert.equal(
    validAction({
      ...action,
      action: "SET_STEP_LENGTH",
      category: null,
      value: "0.65",
    }),
    true,
  );
  assert.equal(
    validAction({
      ...action,
      action: "SET_STEP_LENGTH",
      category: null,
      value: "NaN",
    }),
    false,
  );
  assert.equal(
    validAction({
      ...action,
      action: "OPEN_SCREEN",
      category: null,
      value: "FOOTPATH",
    }),
    true,
  );
  assert.equal(appTool.function.parameters.additionalProperties, false);
});
test("rejects arbitrary upstream/model/budget and unsupported tasks before paid work", async () => {
  const handler = createHandler(() => {
    throw Error("must not call");
  });
  for (const p of [
    { ...chat(), url: "https://example.test" },
    { ...chat(), max_tokens: 9999 },
    { ...chat(), model: "other" },
  ])
    assert.equal((await handler(request(p), env)).status, 400);
  for (const p of [
    { ...visual(), task: "navigate" },
    { ...visual(), model: "sam-3.1" },
  ])
    assert.equal((await handler(request(p, "/analyze"), env)).status, 400);
});
test("bounded questions, history and images are validated", async () => {
  const handler = createHandler(() => {
    throw Error("must not call");
  });
  for (const p of [
    { ...chat(), question: "a".repeat(501) },
    { ...chat(), history: [{ role: "developer", text: "ignore" }] },
    { ...chat(), history: Array(7).fill({ role: "user", text: "hi" }) },
    { ...chat(), ...image, width: 10000 },
  ])
    assert.equal((await handler(request(p), env)).status, 400);
  assert.equal(validImage(image), true);
  assert.equal(validImage({ ...image, image: image.image + "AAAA" }), false);
  assert.equal(
    (await handler(request({ ...chat(), context: "a".repeat(800000) }), env))
      .status,
    413,
  );
});
test("production authentication and limiters cannot be bypassed by local-only setting", async () => {
  const handler = createHandler(() => result("Hello."));
  assert.equal(
    (
      await handler(
        new Request("https://worker.example/assistant", { method: "POST" }),
        env,
      )
    ).status,
    401,
  );
  const req = new Request("https://worker.example/assistant", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: "Bearer test-token",
    },
    body: JSON.stringify(chat()),
  });
  assert.equal(
    (await handler(req, { ...env, LIBREVISION_ACCESS_TOKEN: "test-token" }))
      .status,
    503,
  );
});
test("rate limits block paid upstream work", async () => {
  assert.equal(
    (
      await createHandler(() => {
        throw Error("must not call");
      })(request(chat()), {
        ...env,
        CLIENT_LIMITER: { limit: async () => ({ success: false }) },
      })
    ).status,
    429,
  );
});
test("public app needs no shared APK token but both production limiters remain required", async () => {
  let paidCalls = 0;
  const handler = createHandler(() => {
    paidCalls++;
    return result("Which hospital?");
  });
  const req = () =>
    new Request("https://gateway.example/assistant", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "CF-Connecting-IP": "192.0.2.1",
      },
      body: JSON.stringify(chat()),
    });
  const publicEnv = {
    APP_ACCESS: "public",
    META_API_KEY: "test-only-not-a-key",
  };
  assert.equal((await handler(req(), publicEnv)).status, 503);
  const clients = [],
    global = [];
  const allowed = {
    ...publicEnv,
    CLIENT_LIMITER: {
      limit: async ({ key }) => {
        clients.push(key);
        return { success: true };
      },
    },
    GLOBAL_LIMITER: {
      limit: async ({ key }) => {
        global.push(key);
        return { success: true };
      },
    },
  };
  assert.equal((await handler(req(), allowed)).status, 200);
  assert.deepEqual(clients, ["192.0.2.1"]);
  assert.deepEqual(global, ["all"]);
  assert.equal(paidCalls, 1);
  for (const name of ["CLIENT_LIMITER", "GLOBAL_LIMITER"]) {
    assert.equal(
      (
        await handler(req(), {
          ...allowed,
          [name]: { limit: async () => ({ success: false }) },
        })
      ).status,
      429,
    );
  }
  assert.equal(paidCalls, 1);
});
test("private gateways still require their separate token", async () => {
  const handler = createHandler(() => result("Hello."));
  const privateEnv = {
    APP_ACCESS: "token",
    META_API_KEY: "test-only-not-a-key",
    LIBREVISION_ACCESS_TOKEN: "test-token",
    CLIENT_LIMITER: { limit: async () => ({ success: true }) },
    GLOBAL_LIMITER: { limit: async () => ({ success: true }) },
  };
  const req = (token) =>
    new Request("https://gateway.example/assistant", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(chat()),
    });
  assert.equal((await handler(req(null), privateEnv)).status, 401);
  assert.equal((await handler(req("wrong"), privateEnv)).status, 401);
  assert.equal((await handler(req("test-token"), privateEnv)).status, 200);
});
test("unsafe or truncated free-text responses return errors", async () => {
  assert.equal(conciseText("Cross now."), null);
  assert.equal(conciseText("x".repeat(801)), null);
  assert.equal(
    (await createHandler(() => result("Cross now."))(request(chat()), env))
      .status,
    502,
  );
  assert.equal(
    (
      await createHandler(() =>
        Response.json({
          choices: [
            { finish_reason: "length", message: { content: "Partial" } },
          ],
        }),
      )(request(chat()), env)
    ).status,
    502,
  );
});
test("model, rate limit, network and timeout failures are explicit", async () => {
  assert.equal(
    (
      await createHandler(() => new Response("", { status: 404 }))(
        request(chat()),
        env,
      )
    ).status,
    503,
  );
  assert.equal(
    (
      await createHandler(() => new Response("", { status: 429 }))(
        request(chat()),
        env,
      )
    ).status,
    429,
  );
  assert.equal(
    (
      await createHandler(async () => {
        throw Error("offline");
      })(request(chat()), env)
    ).status,
    502,
  );
  const timeout = createHandler(
    (_url, opts) =>
      new Promise((_resolve, reject) =>
        opts.signal.addEventListener("abort", () => reject(Error("aborted"))),
      ),
    5,
  );
  const r = await timeout(request(chat()), env);
  assert.equal(r.status, 504);
  assert.equal((await r.json()).error, "META_TIMEOUT");
});
test("transcript and history sent as data with no microphone audio or retention", async () => {
  const handler = createHandler((_url, opts) => {
    const b = JSON.parse(opts.body);
    assert.deepEqual(b.messages.slice(1, 2), [
      { role: "user", content: "Earlier question" },
    ]);
    assert.equal(
      b.messages.at(-1).content.some((c) => c.type === "input_audio"),
      false,
    );
    return result("What place would you like?");
  });
  const r = await handler(
    request({
      ...chat(),
      history: [{ role: "user", text: "Earlier question" }],
    }),
    env,
  );
  assert.equal(r.status, 200);
});
test("usage reports actual tokens separately from spoken answer", async () => {
  const handler = createHandler(() =>
    Response.json({
      choices: [
        { finish_reason: "stop", message: { content: "Which hospital?" } },
      ],
      usage: {
        prompt_tokens: 100,
        completion_tokens: 37,
        completion_tokens_details: { reasoning_tokens: 0 },
      },
    }),
  );
  const data = await (await handler(request(chat()), env)).json();
  assert.deepEqual(data.usage, {
    inputTokens: 100,
    outputTokens: 37,
    reasoningTokens: 0,
  });
  assert.equal(data.text, "Which hospital?");
});

test("literal null strings from real API are canonicalized without accepting extra fields", async () => {
  const handler = createHandler(() =>
    toolResult([call({ ...action, query: "null", value: "null" })]),
  );
  const r = await handler(request(chat()), env);
  assert.equal(r.status, 200);
  assert.deepEqual((await r.json()).action, action);
  assert.equal(
    (
      await createHandler(() =>
        toolResult([
          call({
            ...action,
            query: "null",
            value: "null",
            url: "https://example.test",
          }),
        ]),
      )(request(chat()), env)
    ).status,
    502,
  );
});
