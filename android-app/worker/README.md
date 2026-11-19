# Netra Saathi cloud gateway

Cloudflare Worker for the existing assistant, selected-image descriptions, validated app controls, surroundings frames and speech transcription. Deployment uses Gemini 3.5 Flash-Lite through the native Interactions API with `store:false`. No provider key is bundled in Android. Images, utterances and history are not stored by the Worker.

Use Node 22 or newer. Run `npm ci` and `npm test`. Local development uses `npm run dev`; keep `GEMINI_API_KEY`, `AI_PROVIDER=gemini` and `LOCAL_ONLY=true` in an ignored `.dev.vars`. Debug Android clients can connect through `adb reverse tcp:8790 tcp:8790`.

For deployment, authorize Wrangler with account read, user read, Workers write and Worker Scripts write. Run `npm run deploy` and `npx wrangler secret put GEMINI_API_KEY`. Deployment writes only the public origin to `../gateway.properties`. Rebuild Android after deploying. Release builds require a configured HTTPS origin.

Endpoints: `GET /health`, `POST /assistant`, `POST /analyze`, `POST /transcribe`. Existing protocol model identifiers remain accepted for compatibility; responses report the actual Gemini model. Images are validated JPEGs. Surroundings requests contain 10–12 time-ordered stills, not video. Speech accepts one gzip-compressed PCM16 mono 16 kHz WAV, 0.1–8 seconds. Speech fallback requires consent; the app does not upload idle conversation.

Public access needs no shared APK token. Both rate limit bindings are mandatory: 12 requests per minute per IP and 36 per minute for the shared edge limiter. These control abuse, not identity. A separate SQLite Durable Object atomically reserves at most 100 upstream requests per UTC day across all locations. Failed upstream attempts also consume reservations. `DAILY_AI_LIMIT` accepts 1–1000. This caps requests, not currency; provider billing settings still apply. Private deployments can use `APP_ACCESS=token` and a `LIBREVISION_ACCESS_TOKEN` secret.

Operational logs contain status and latency only. Never log credentials, request bodies, frames, audio or location. Health reports configuration, not proof of provider availability. Tests cover validation, Gemini conversion and concurrent quota enforcement against a real local SQLite Durable Object.
