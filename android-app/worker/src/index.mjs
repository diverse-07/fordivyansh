import { geminiTransport, GEMINI_MODEL } from "./gemini.mjs";
export { DailyRequestBudget } from "./daily-budget.mjs";
// Documented Meta Chat Completions, selected JPEG and validated app function calls.
// https://dev.meta.ai/docs/image-understanding and /tool-calling
const MODEL = "muse-spark-1.3",
  MAX_BODY = 1200000;
import {
  appTool,
  validAction,
  conciseText,
  assistantPrompt,
  canonicalAction,
} from "./assistant.mjs";
import {
  validSurroundings,
  surroundingsPrompt,
  surroundingsContent,
} from "./surroundings.mjs";
import { transcribeAudio } from "./audio.mjs";
export { validAction, conciseText };
export function validImage(p) {
  if (
    typeof p.image !== "string" ||
    !p.image.startsWith("data:image/jpeg;base64,") ||
    p.image.length > 700000
  )
    return false;
  const base64 = p.image.slice(23);
  if (!/^[A-Za-z0-9+/]*={0,2}$/.test(base64) || base64.length % 4 !== 0)
    return false;
  let image;
  try {
    image = atob(base64);
  } catch {
    return false;
  }
  if (
    image.length > 512000 ||
    image.charCodeAt(0) !== 255 ||
    image.charCodeAt(1) !== 216 ||
    image.charCodeAt(image.length - 2) !== 255 ||
    image.charCodeAt(image.length - 1) !== 217
  )
    return false;
  let i = 2;
  while (i + 9 < image.length) {
    if (image.charCodeAt(i) !== 255) return false;
    const marker = image.charCodeAt(i + 1);
    if (marker === 218 || marker === 217) break;
    const length = (image.charCodeAt(i + 2) << 8) | image.charCodeAt(i + 3);
    if (length < 2 || i + length + 2 > image.length) return false;
    if ([192, 193, 194].includes(marker)) {
      const h = (image.charCodeAt(i + 5) << 8) | image.charCodeAt(i + 6),
        w = (image.charCodeAt(i + 7) << 8) | image.charCodeAt(i + 8);
      return (
        w === p.width &&
        h === p.height &&
        w > 0 &&
        h > 0 &&
        w <= 1024 &&
        h <= 1024
      );
    }
    i += length + 2;
  }
  return false;
}
async function bounded(body, limit) {
  if (!body) throw Error("EMPTY_BODY");
  const reader = body.getReader(),
    chunks = [];
  let total = 0;
  for (;;) {
    const { value, done } = await reader.read();
    if (done) break;
    total += value.length;
    if (total > limit) {
      await reader.cancel();
      throw Error("SIZE_LIMIT");
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.length;
  }
  return new TextDecoder().decode(bytes);
}
const reply = (d, status = 200) =>
  Response.json(d, {
    status,
    headers: {
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
const error = (code, status) => reply({ error: code }, status);
export function createHandler(fetchUpstream = fetch, timeoutMs = 18000) {
  return async (request, env) => {
    const url = new URL(request.url),
      local =
        env.LOCAL_ONLY === "true" &&
        ["127.0.0.1", "localhost"].includes(url.hostname);
    const useGemini = env.AI_PROVIDER === "gemini" || !!env.GEMINI_API_KEY;
    const responseModel = useGemini ? GEMINI_MODEL : MODEL;
    const upstreamFetch = useGemini ? geminiTransport(fetchUpstream, env, local) : fetchUpstream;
    // Public app builds carry only the URL. Private test gateways may still require a token.
    if (
      !local &&
      env.APP_ACCESS !== "public" &&
      (!env.LIBREVISION_ACCESS_TOKEN ||
        request.headers.get("Authorization") !==
          `Bearer ${env.LIBREVISION_ACCESS_TOKEN}`)
    )
      return error("UNAUTHORIZED", 401);
    if (url.pathname === "/health" && request.method === "GET")
      return reply({ protocolVersion: 1, model: responseModel, configured: !!(useGemini ? env.GEMINI_API_KEY : env.META_API_KEY) && (local || !!(env.CLIENT_LIMITER && env.GLOBAL_LIMITER && (!useGemini || env.DAILY_BUDGET))), capabilities: { scene: true, assistant: true, assistantActions: true, transcription: true, guardianNotifications: false, telemetry: false } });
    if (
      !["/analyze", "/assistant", "/transcribe"].includes(url.pathname) ||
      request.method !== "POST"
    )
      return error("NOT_FOUND", 404);
    const audio = url.pathname === "/transcribe";
    const contentType = request.headers.get("Content-Type")?.split(";")[0]?.trim().toLowerCase();
    const supportedContentType = audio
      ? ["application/vnd.netrasaathi.wav+gzip", "application/vnd.librevision.wav+gzip"].includes(contentType)
      : contentType === "application/json";
    if (!supportedContentType)
      return error("CONTENT_TYPE", 415);
    if (!(useGemini ? env.GEMINI_API_KEY : env.META_API_KEY)) return error(useGemini ? "GEMINI_NOT_CONFIGURED" : "META_NOT_CONFIGURED", 503);
    if (useGemini && !local && !env.DAILY_BUDGET) return error("BUDGET_NOT_CONFIGURED", 503);
    if (!local && (!env.CLIENT_LIMITER || !env.GLOBAL_LIMITER))
      return error("LIMITER_NOT_CONFIGURED", 503);
    try {
    const client = request.headers.get("CF-Connecting-IP") ?? "loopback";
    if (
      env.CLIENT_LIMITER &&
      !(await env.CLIENT_LIMITER.limit({ key: client })).success
    )
      return error("RATE_LIMIT", 429);
    if (
      env.GLOBAL_LIMITER &&
      !(await env.GLOBAL_LIMITER.limit({ key: "all" })).success
    )
      return error("RATE_LIMIT", 429);

    } catch { return error("LIMITER_UNAVAILABLE", 503); }
    if (audio)
      return transcribeAudio(
        request,
        env,
        upstreamFetch,
        Math.min(timeoutMs, 12000),
      );
    let p;
    try {
      const raw = await bounded(request.body, MAX_BODY);
      p = JSON.parse(raw);
      if (
        raw.length > 750000 &&
        !(
          url.pathname === "/assistant" &&
          p?.views !== undefined &&
          p?.surroundings !== undefined
        )
      )
        throw Error("SIZE_LIMIT");
    } catch {
      return error("INVALID_OR_OVERSIZE_JSON", 413);
    }
    const chat = url.pathname === "/assistant";
    const allowed = chat
      ? [
          "question",
          "context",
          "history",
          "image",
          "width",
          "height",
          "views",
          "surroundings",
        ]
      : [
          "task",
          "model",
          "image",
          "width",
          "height",
          "routeContext",
          "detections",
          "question",
        ];
    if (
      !p ||
      typeof p !== "object" ||
      Array.isArray(p) ||
      Object.keys(p).some((k) => !allowed.includes(k))
    )
      return error("INVALID_REQUEST", 400);
    const surroundings =
      chat && (p.views !== undefined || p.surroundings !== undefined);
    if (surroundings && !validSurroundings(p, validImage))
      return error("INVALID_SURROUNDINGS", 400);
    if (chat) {
      if (
        typeof p.question !== "string" ||
        !p.question.trim() ||
        p.question.length > 500 ||
        typeof p.context !== "string" ||
        p.context.length > 4000
      )
        return error("INVALID_QUESTION", 400);
      if (
        !Array.isArray(p.history) ||
        p.history.length > 6 ||
        p.history.some(
          (t) =>
            !t ||
            Object.keys(t).sort().join(",") !== "role,text" ||
            !["user", "assistant"].includes(t.role) ||
            typeof t.text !== "string" ||
            t.text.length > 500,
        )
      )
        return error("INVALID_HISTORY", 400);
      if (p.image !== undefined && !validImage(p))
        return error("INVALID_IMAGE", 400);
    } else {
      if (
        !["CONTEXTUALIZE_MANEUVER", "DESCRIBE", "VISUAL_QUESTION"].includes(
          p.task,
        ) ||
        p.model !== MODEL
      )
        return error("UNSUPPORTED_TASK_OR_MODEL", 400);
      if (!validImage(p)) return error("INVALID_IMAGE", 400);
      if (
        p.routeContext !== null &&
        (typeof p.routeContext !== "string" || p.routeContext.length > 300)
      )
        return error("INVALID_ROUTE_CONTEXT", 400);
      if (
        !Array.isArray(p.detections) ||
        p.detections.length > 10 ||
        p.detections.some(
          (d) => typeof d !== "string" || d.length > 40 || !/^[a-z ]+$/.test(d),
        )
      )
        return error("INVALID_DETECTIONS", 400);
      if (
        p.question !== undefined &&
        (typeof p.question !== "string" || p.question.length > 500)
      )
        return error("INVALID_QUESTION", 400);
    }
    const question = chat
      ? p.question
      : p.question ||
        "Describe what is relevant to the supplied walking maneuver in this selected view.";
    const content = surroundings
      ? surroundingsContent(p)
      : [{ type: "text", text: question }];
    if (p.image)
      content.push({ type: "image_url", image_url: { url: p.image } });
    const context = chat
      ? p.context
      : JSON.stringify({
          mappedManeuver: p.routeContext,
          recentDetectionLabels: p.detections,
        });
    const messages = [
      {
        role: "developer",
        content:
          assistantPrompt +
          (surroundings ? surroundingsPrompt(p) : "") +
          " Current app context: " +
          context,
      },
      ...(chat
        ? p.history.map((t) => ({ role: t.role, content: t.text }))
        : []),
      { role: "user", content },
    ];
    const body = {
      model: MODEL,
      reasoning_effort: "minimal",
      max_tokens: surroundings ? 384 : 768,
      messages,
    };
    // A selected image request receives an answer, not another recursive image/tool request.
    if (chat && !p.image && !surroundings) {
      body.tools = [appTool];
      body.parallel_tool_calls = false;
    }
    const abort = new AbortController(),
      timeout = setTimeout(() => abort.abort(), timeoutMs),
      start = Date.now();
    try {
      const upstream = await upstreamFetch(
        "https://api.meta.ai/v1/chat/completions",
        {
          method: "POST",
          signal: abort.signal,
          headers: {
            Authorization: `Bearer ${env.META_API_KEY}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify(body),
        },
      );
      if (!upstream.ok)
        return error(
          upstream.status === 429
            ? "RATE_LIMIT"
            : upstream.status === 401 || upstream.status === 403
              ? (useGemini ? "GEMINI_AUTH_FAILED" : "META_AUTH_FAILED")
              : upstream.status === 404
                ? "MODEL_UNAVAILABLE"
                : upstream.status === 402
                  ? (useGemini ? "GEMINI_BILLING_UNAVAILABLE" : "META_BILLING_UNAVAILABLE")
                  : (useGemini ? "GEMINI_UNAVAILABLE" : "META_UNAVAILABLE"),
          upstream.status === 429 ? 429 : 503,
        );
      const result = JSON.parse(await bounded(upstream.body, 64000)),
        choice = result.choices?.[0];
      let text = null,
        action = null;
      if (
        choice?.finish_reason === "tool_calls" &&
        chat &&
        !p.image &&
        !surroundings
      ) {
        const calls = choice.message?.tool_calls;
        if (
          !Array.isArray(calls) ||
          calls.length !== 1 ||
          calls[0].type !== "function" ||
          calls[0].function?.name !== "app_action" ||
          typeof calls[0].function.arguments !== "string" ||
          calls[0].function.arguments.length > 1000
        )
          return error("INVALID_TOOL_CALL", 502);
        action = canonicalAction(JSON.parse(calls[0].function.arguments));
        if (!validAction(action)) return error("INVALID_TOOL_ARGUMENTS", 502);
      } else if (choice?.finish_reason === "stop") {
        text = conciseText(choice.message?.content);
        if (!text) return error("INVALID_MODEL_RESPONSE", 502);
      } else return error("INCOMPLETE_MODEL_RESPONSE", 502);

      const usage = {};
      const supplied = result.usage;
      for (const [key, value] of Object.entries({
        inputTokens: supplied?.prompt_tokens,
        outputTokens: supplied?.completion_tokens,
        reasoningTokens: supplied?.completion_tokens_details?.reasoning_tokens,
      }))
        if (Number.isSafeInteger(value) && value >= 0) usage[key] = value;
      return reply({
        model: responseModel,
        text,
        action,
        latencyMs: Date.now() - start,
        usage,
      });
    } catch {
      return error(
        abort.signal.aborted
          ? (useGemini ? "GEMINI_TIMEOUT" : "META_TIMEOUT")
          : (useGemini ? "GEMINI_NETWORK_OR_RESPONSE_ERROR" : "META_NETWORK_OR_RESPONSE_ERROR"),
        abort.signal.aborted ? 504 : 502,
      );
    } finally {
      clearTimeout(timeout);
    }
  };
}
const handler = createHandler();
export default { async fetch(request, env) {
  const start = Date.now();
  const response = await handler(request, env);
  // Aggregate operational metadata only: no bodies, images, audio, tokens or coordinates.
  console.log(JSON.stringify({status: response.status, latencyMs: Date.now() - start}));
  return response;
} };
