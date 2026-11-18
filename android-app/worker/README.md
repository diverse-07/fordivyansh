# Muse gateway

Cloudflare Worker for concise Muse Spark answers, validated app actions, and selected-image analysis. No database or uploaded-image storage. Surroundings chat accepts 10–12 time-ordered JPEG frames from a locally recorded video (512 px, 48 kB each). No video or microphone audio accompanies those frames. Legacy still-sweep clients remain supported. It uses Meta’s documented multiple-image input. Each concise answer uses a maximum of 384 output tokens; follow-ups carry at most six history messages. No server session is retained.

## Local development

```sh
npm ci
# Create .dev.vars locally with META_API_KEY and LOCAL_ONLY=true.
npm test
npm run dev
adb reverse tcp:8790 tcp:8790
```

Without a deployed URL, debug builds use `http://127.0.0.1:8790` through ADB. Never commit `.dev.vars`.

## Deploy

```sh
npx wrangler login
npm run deploy
npx wrangler secret put META_API_KEY
```

Deployment automatically writes the public URL to `../gateway.properties` using [Wrangler's structured output](https://developers.cloudflare.com/workers/wrangler/system-environment-variables/). Then rebuild the Android APK from the project root. Users need no URL, token, or Cloudflare account. Release builds require a configured URL; no credentials are bundled.

`APP_ACCESS=public` allows app requests without a shared APK secret. Both configured rate limiters are mandatory; they mitigate abuse but are not identity checks or hard spending caps. Keep `LOCAL_ONLY=false` publicly. For private testing, use `APP_ACCESS=token` and a `LIBREVISION_ACCESS_TOKEN` Worker Secret; connection overrides appear only in debug builds.

Endpoints: `POST /assistant`, `POST /analyze`, `POST /transcribe`, `GET /health`. Audio fallback accepts one gzip-compressed PCM16 mono 16 kHz WAV, 0.1–8 seconds, and calls [Muse Voice Transcribe](https://dev.meta.ai/docs/api-reference/voice/transcribe) with English/Bengali and bounded name hints. No audio is stored by the Worker. Models, payloads and responses are validated; timeouts and rate limits apply. SAM is not enabled.
