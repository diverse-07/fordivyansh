# Netra Saathi

Android assistive navigation app, AI adapters and a guardian dashboard interface.

## Current behavior

- Android uses RF-DETR/COCO for supported general objects, with speech, haptics and
  obstacle-side sonar. Ground damage, hanging wires and overhead hazards are not
  reliably covered by this detector. Monocular outdoor distance is unvalidated.
- Offline wake phrase: **Hey Vision**, as supported by the bundled English model.
  Netra Saathi remains the product name. Hold Volume Up for two seconds for voice;
  three releases within 1.2 seconds activate the local distress alarm.
- The emergency alarm is local only. It does not notify guardians. Audio routing and
  behavior on physical phones still require device testing.
- Walking search/routing uses network services. Navigation/scanning stops when the
  activity enters the background.
- Cloud assistant, images, surroundings frames and speech fallback use the configured
  Cloudflare Worker with Gemini 3.5 Flash-Lite. The APK bundles its HTTPS origin only;
  the provider key stays in a Worker secret. Production rate limits and a shared
  durable quota allow at most 100 upstream requests per UTC day.
- FastAPI validates evidence and returns explicit failures. It requires a server access
  token, Gemini credentials and an explicitly selected current model.
- Guardian notifications, pairing, telemetry, fall detection and remote settings are
  unavailable. The dashboard displays this state; its alert preview is local only.

## Development

See [Windows setup](SETUP_WINDOWS.md), [Android](android-app/README.md),
[backend](backend/README.md), [dashboard](dashboard/README.md) and
[Worker](android-app/worker/README.md). Root GitHub Actions cover the build and tests.

The historical CODEX_HANDOFF.md describes the original handoff, not the current
verification status. Consult the bug-fix report delivered with the APK for current results.

## Credits

Based on LibreVision by Pritom Das, under Apache 2.0. Preserve android-app/LICENSE,
NOTICE and third-party notices. See model metadata for upstream sources and checksums.
