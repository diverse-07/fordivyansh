import { test } from "node:test";
import assert from "node:assert/strict";
import { gzipSync } from "node:zlib";
import { createHandler } from "../src/index.mjs";
import { validWave } from "../src/audio.mjs";

const env = { LOCAL_ONLY: "true", META_API_KEY: "test-only-not-a-key" };
function wave(samples = 16000) {
  const result = Buffer.alloc(44 + samples * 2);
  result.write("RIFF");
  result.writeUInt32LE(result.length - 8, 4);
  result.write("WAVEfmt ", 8);
  result.writeUInt32LE(16, 16);
  result.writeUInt16LE(1, 20);
  result.writeUInt16LE(1, 22);
  result.writeUInt32LE(16000, 24);
  result.writeUInt32LE(32000, 28);
  result.writeUInt16LE(2, 32);
  result.writeUInt16LE(16, 34);
  result.write("data", 36);
  result.writeUInt32LE(samples * 2, 40);
  return result;
}
const request = (data, contentType = "application/vnd.netrasaathi.wav+gzip") =>
  new Request("http://127.0.0.1/transcribe", {
    method: "POST",
    headers: { "Content-Type": contentType },
    body: data,
  });
test("decompresses one utterance and uses only documented fixed Meta voice fields", async () => {
  const original = wave();
  const handler = createHandler(async (url, options) => {
    assert.equal(url, "https://api.meta.ai/v1/asr/transcribe");
    assert.equal(options.headers.Authorization, "Bearer test-only-not-a-key");
    const settings = JSON.parse(await options.body.get("request").text());
    assert.equal(settings.model, "muse-voice-transcribe-1.0");
    assert.equal(settings.audioEncoding, "WAV");
    assert.equal(settings.mode, "PUSH_TO_TALK");
    assert.deepEqual(settings.languageBias, ["English", "Bengali"]);
    assert.ok(settings.keywords.includes("Square Hospital"));
    assert.ok(settings.keywords.every((word) => word.length <= 30));
    assert.deepEqual(
      Buffer.from(await options.body.get("audio").arrayBuffer()),
      original,
    );
    return Response.json({
      transcript: " Navigate to Square Hospital. ",
      audioDurationMs: 1000,
    });
  });
  const result = await handler(request(gzipSync(original)), env);
  assert.equal(result.status, 200);
  assert.equal(result.headers.get("Cache-Control"), "no-store");
  const data = await result.json();
  assert.equal(data.text, "Navigate to Square Hospital.");
  assert.equal(data.model, "muse-voice-transcribe-1.0");
  assert.equal(data.audioDurationMs, 1000);
});
test("older LibreVision clients can still send the same WAV format", async () => {
  const handler = createHandler(() => Response.json({ transcript: "Open Settings." }));
  const result = await handler(request(gzipSync(wave()), "application/vnd.librevision.wav+gzip"), env);
  assert.equal(result.status, 200);
});
test("rejects corrupt, oversized, non-mono, wrong-rate and non-PCM input before paid work", async () => {
  const handler = createHandler(() => {
    throw Error("must not call");
  });
  const stereo = wave();
  stereo.writeUInt16LE(2, 22);
  const wrongRate = wave();
  wrongRate.writeUInt32LE(44100, 24);
  for (const invalid of [
    Buffer.from("not gzip"),
    gzipSync(stereo),
    gzipSync(wrongRate),
    gzipSync(Buffer.alloc(44)),
    gzipSync(Buffer.from("not a wave file")),
  ]) {
    assert.equal((await handler(request(invalid), env)).status, 400);
  }
  assert.equal(
    (await handler(request(gzipSync(wave(128001))), env)).status,
    413,
  );
  assert.equal((await handler(request(Buffer.alloc(270001)), env)).status, 413);
  assert.equal(validWave(wave()), true);
});
test("voice endpoint still requires production rate limiters and cannot accept arbitrary model/url", async () => {
  const handler = createHandler(() => {
    throw Error("must not call");
  });
  const publicRequest = () =>
    new Request("https://gateway.example/transcribe", {
      method: "POST",
      headers: { "Content-Type": "application/vnd.librevision.wav+gzip" },
      body: gzipSync(wave()),
    });
  assert.equal(
    (
      await handler(publicRequest(), {
        APP_ACCESS: "public",
        META_API_KEY: "test-only",
      })
    ).status,
    503,
  );
  const blocked = {
    APP_ACCESS: "public",
    META_API_KEY: "test-only",
    CLIENT_LIMITER: { limit: async () => ({ success: false }) },
    GLOBAL_LIMITER: { limit: async () => ({ success: true }) },
  };
  assert.equal((await handler(publicRequest(), blocked)).status, 429);
  assert.equal(
    (
      await handler(
        new Request("http://127.0.0.1/transcribe", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            model: "other",
            url: "https://elsewhere.example",
          }),
        }),
        env,
      )
    ).status,
    415,
  );
});
test("empty and invalid transcripts are explicit failures", async () => {
  for (const [payload, status] of [
    [{ transcript: "" }, 422],
    [{ transcript: "a".repeat(501) }, 502],
    [{ transcript: null }, 502],
  ]) {
    assert.equal(
      (
        await createHandler(() => Response.json(payload))(
          request(gzipSync(wave())),
          env,
        )
      ).status,
      status,
    );
  }
});
test("unavailable voice model, rate limits, network failures and timeout fail gracefully", async () => {
  for (const [upstream, status] of [
    [404, 503],
    [403, 503],
    [429, 429],
  ])
    assert.equal(
      (
        await createHandler(() => new Response("", { status: upstream }))(
          request(gzipSync(wave())),
          env,
        )
      ).status,
      status,
    );
  assert.equal(
    (
      await createHandler(() => {
        throw Error("offline");
      })(request(gzipSync(wave())), env)
    ).status,
    502,
  );
  const timeout = createHandler(
    (_url, options) =>
      new Promise((_resolve, reject) =>
        options.signal.addEventListener("abort", () =>
          reject(Error("timeout")),
        ),
      ),
    5,
  );
  assert.equal((await timeout(request(gzipSync(wave())), env)).status, 504);
});
