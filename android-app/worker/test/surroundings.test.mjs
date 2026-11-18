import { test } from "node:test";
import assert from "node:assert/strict";
import { createHandler, validImage } from "../src/index.mjs";
import { validSurroundings } from "../src/surroundings.mjs";
const image =
  "data:image/jpeg;base64," +
  Buffer.from([
    255, 216, 255, 192, 0, 17, 8, 0, 2, 0, 3, 3, 1, 17, 0, 2, 17, 0, 3, 17, 0,
    255, 217,
  ]).toString("base64");
const payload = () => ({
  question: "Describe useful surroundings briefly.",
  context: '{"screen":"SCENE"}',
  history: [],
  surroundings: { ageMs: 1000, headingDegrees: 0, initial: true },
  views: Array.from({ length: 12 }, (_, i) => ({
    image,
    width: 3,
    height: 2,
    angleDegrees: i * 30,
    fieldOfViewDegrees: 60,
    ageMs: 2000,
  })),
});
const request = (p) =>
  new Request("http://127.0.0.1/assistant", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(p),
  });
const env = { LOCAL_ONLY: "true", META_API_KEY: "test-only-not-a-key" };
test("overlapping selected stills use documented image blocks and concise answer-only budget", async () => {
  const handler = createHandler(async (url, options) => {
    assert.equal(url, "https://api.meta.ai/v1/chat/completions");
    const p = JSON.parse(options.body);
    assert.equal(p.model, "muse-spark-1.3");
    assert.equal(p.tools, undefined);
    assert.ok(p.max_tokens <= 384);
    assert.match(p.messages[0].content, /useful to a visually impaired/);
    assert.equal(
      p.messages.at(-1).content.filter((x) => x.type === "image_url").length,
      12,
    );
    assert.ok(
      p.messages
        .at(-1)
        .content.some((x) => x.text?.includes("current phone direction")),
    );
    return Response.json({
      choices: [
        {
          finish_reason: "stop",
          message: {
            content:
              "A pharmacy is visible on the left. A parked motorcycle partly blocks the footpath.",
          },
        },
      ],
    });
  });
  const response = await handler(request(payload()), env);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("Cache-Control"), "no-store");
  assert.match((await response.json()).text, /pharmacy/);
});
test("follow-ups retain selected views and bounded conversation history without server sessions", async () => {
  const p = payload();
  p.surroundings.initial = false;
  p.surroundings.headingDegrees = 90;
  p.question = "Where was the pharmacy?";
  p.history = [{ role: "assistant", text: "A pharmacy was visible." }];
  const handler = createHandler(async (_, options) => {
    const body = JSON.parse(options.body);
    assert.equal(body.messages[1].content, p.history[0].text);
    assert.ok(
      body.messages.at(-1).content.some((x) => x.text?.includes("270 degrees")),
    );
    return Response.json({
      choices: [
        {
          finish_reason: "stop",
          message: { content: "It was behind you in these saved views." },
        },
      ],
    });
  });
  assert.equal((await handler(request(p), env)).status, 200);
});
test("rejects missing coverage, too many views, arbitrary URLs, invalid ages and unbounded fields before paid requests", async () => {
  let calls = 0;
  const handler = createHandler(async () => {
    calls++;
    throw Error("must not request");
  });
  const edits = [
    (p) => p.views.splice(0, 4),
    (p) => p.views.push(...p.views, ...p.views),
    (p) => (p.views[0].image = "https://example.com/private"),
    (p) => (p.views[0].angleDegrees = 99),
    (p) => (p.surroundings.ageMs = 300001),
    (p) => (p.views[0].ageMs = -1),
    (p) => (p.surroundings.headingDegrees = -1),
    (p) => (p.views[0].url = "https://example.com"),
    (p) => (p.image = image),
  ];
  for (const edit of edits) {
    const p = payload();
    edit(p);
    assert.equal(validSurroundings(p, validImage), false);
    assert.equal((await handler(request(p), env)).status, 400);
  }
  assert.equal(calls, 0);
});

const videoPayload = (count = 12) => ({
  question: "Describe useful surroundings in this video.",
  context: '{"screen":"SCENE"}',
  history: [],
  surroundings: {
    source: "video",
    durationMs: 12000,
    ageMs: 1000,
    initial: true,
  },
  views: Array.from({ length: count }, (_, i) => ({
    image,
    width: 3,
    height: 2,
    offsetMs: Math.floor(((i + 0.5) * 12000) / count),
    ageMs: 2000,
  })),
});
test("10 or 12 video frames carry timestamps, never made-up angles or coverage", async () => {
  let calls = 0;
  const handler = createHandler(async (_, options) => {
    calls++;
    const body = JSON.parse(options.body);
    assert.equal(body.tools, undefined);
    assert.ok(body.max_tokens <= 384);
    assert.match(
      body.messages[0].content,
      /Camera rotation, coverage and current orientation are UNKNOWN/,
    );
    const content = body.messages.at(-1).content;
    assert.ok(content.filter((x) => x.type === "image_url").length >= 10);
    assert.ok(content.some((x) => x.text?.includes("seconds into the video")));
    assert.ok(!content.some((x) => x.text?.includes("degrees clockwise")));
    return Response.json({
      choices: [
        {
          finish_reason: "stop",
          message: { content: "A pharmacy is visible beside the pavement." },
        },
      ],
    });
  });
  for (const count of [10, 12])
    assert.equal(
      (await handler(request(videoPayload(count)), env)).status,
      200,
    );
  assert.equal(calls, 2);
});
test("video limits reject out-of-order, oversized and invalid requests before upstream", async () => {
  let calls = 0;
  const handler = createHandler(async () => {
    calls++;
    throw Error("must not request");
  });
  const edits = [
    (p) => p.views.splice(0, 3),
    (p) => p.views.push(p.views[0]),
    (p) => (p.views[0].offsetMs = -1),
    (p) => (p.views[3].offsetMs = p.views[2].offsetMs),
    (p) => (p.views[1].angleDegrees = 30),
    (p) => (p.surroundings.durationMs = 32001),
    (p) => (p.surroundings.headingDegrees = 0),
    (p) => (p.views[0].image = "https://example.com/video.mp4"),
    (p) => (p.views[0].width = 513),
    (p) => (p.views[0].offsetMs = 12000),
  ];
  for (const edit of edits) {
    const p = videoPayload();
    edit(p);
    assert.equal((await handler(request(p), env)).status, 400);
  }
  assert.equal(calls, 0);
});
