const MODEL = "muse-voice-transcribe-1.0";
const MAX_WAV = 256044,
  MAX_COMPRESSED = 270000;
import { speechKeywords } from "./speech-keywords.mjs";
const reply = (body, status = 200) =>
  Response.json(body, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
const error = (code, status) => reply({ error: code }, status);

async function bytes(stream, limit) {
  if (!stream) throw Error("EMPTY");
  const reader = stream.getReader(),
    chunks = [];
  let size = 0;
  for (;;) {
    const part = await reader.read();
    if (part.done) break;
    size += part.value.length;
    if (size > limit) {
      await reader.cancel();
      throw Error("SIZE");
    }
    chunks.push(part.value);
  }
  const result = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}
/** Only canonical 16 kHz mono PCM16 WAV from the app, no metadata or arbitrary containers. */
export function validWave(wav) {
  if (wav.length < 3244 || wav.length > MAX_WAV) return false;
  const view = new DataView(wav.buffer, wav.byteOffset, wav.byteLength),
    text = (offset, n) =>
      new TextDecoder().decode(wav.subarray(offset, offset + n));
  return (
    text(0, 4) === "RIFF" &&
    text(8, 4) === "WAVE" &&
    text(12, 4) === "fmt " &&
    text(36, 4) === "data" &&
    view.getUint32(4, true) === wav.length - 8 &&
    view.getUint32(16, true) === 16 &&
    view.getUint16(20, true) === 1 &&
    view.getUint16(22, true) === 1 &&
    view.getUint32(24, true) === 16000 &&
    view.getUint32(28, true) === 32000 &&
    view.getUint16(32, true) === 2 &&
    view.getUint16(34, true) === 16 &&
    view.getUint32(40, true) === wav.length - 44 &&
    (wav.length - 44) % 2 === 0
  );
}

// Meta's documented file endpoint accepts PCM WAV, not AAC/Opus. Gzip is app→Worker transport only.
// https://dev.meta.ai/docs/api-reference/voice/transcribe
export async function transcribeAudio(
  request,
  env,
  fetchUpstream,
  timeoutMs = 12000,
) {
  let compressed, wav;
  try {
    compressed = await bytes(request.body, MAX_COMPRESSED);
    const stream = new Blob([compressed])
      .stream()
      .pipeThrough(new DecompressionStream("gzip"));
    wav = await bytes(stream, MAX_WAV);
    if (!validWave(wav)) return error("INVALID_AUDIO", 400);
  } catch (e) {
    return error(
      e.message === "SIZE" ? "AUDIO_TOO_LARGE" : "INVALID_AUDIO",
      e.message === "SIZE" ? 413 : 400,
    );
  }
  const form = new FormData();
  form.append(
    "request",
    new Blob(
      [
        JSON.stringify({
          model: MODEL,
          audioEncoding: "WAV",
          mode: "PUSH_TO_TALK",
          languageBias: ["English", "Bengali"],
          keywords: speechKeywords,
        }),
      ],
      { type: "application/json" },
    ),
  );
  form.append("audio", new Blob([wav], { type: "audio/wav" }), "utterance.wav");
  const abort = new AbortController(),
    timer = setTimeout(() => abort.abort(), timeoutMs),
    start = Date.now();
  try {
    const upstream = await fetchUpstream(
      "https://api.meta.ai/v1/asr/transcribe",
      {
        method: "POST",
        signal: abort.signal,
        headers: {
          Authorization: `Bearer ${env.META_API_KEY}`,
          Accept: "application/json",
        },
        body: form,
      },
    );
    if (!upstream.ok)
      return error(
        upstream.status === 429
          ? "RATE_LIMIT"
          : upstream.status === 401 || upstream.status === 403
            ? "VOICE_ACCESS_UNAVAILABLE"
            : upstream.status === 404
              ? "VOICE_MODEL_UNAVAILABLE"
              : upstream.status === 400
                ? "VOICE_REQUEST_REJECTED"
                : "VOICE_UNAVAILABLE",
        upstream.status === 429 ? 429 : 503,
      );
    const raw = await bytes(upstream.body, 16000),
      result = JSON.parse(new TextDecoder().decode(raw));
    if (typeof result.transcript !== "string")
      return error("INVALID_TRANSCRIPTION", 502);
    const text = result.transcript.replace(/\s+/g, " ").trim();
    if (!text) return error("NO_SPEECH", 422);
    if (text.length > 500 || /[\u0000-\u001f]/.test(text))
      return error("INVALID_TRANSCRIPTION", 502);
    return reply({
      model: MODEL,
      text,
      audioDurationMs: (wav.length - 44) / 32,
      latencyMs: Date.now() - start,
    });
  } catch {
    return error(
      abort.signal.aborted
        ? "VOICE_TIMEOUT"
        : "VOICE_NETWORK_OR_RESPONSE_ERROR",
      abort.signal.aborted ? 504 : 502,
    );
  } finally {
    clearTimeout(timer);
    compressed?.fill(0);
    wav?.fill(0);
  }
}
