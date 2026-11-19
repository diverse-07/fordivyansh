# Netra Saathi FastAPI backend

Install Python 3.11+ dependencies with python -m pip install -r requirements.lock.
Copy .env.example to .env locally and set API_ACCESS_TOKEN, GEMINI_API_KEY and a
currently supported DEFAULT_GEMINI_MODEL. There is no implicit demo/simulation mode.
The model identifier is selected by the operator; deprecated IDs are not silently remapped.

Start with python -m uvicorn app.main:app --reload --port 8000.
Run python -m unittest discover -s tests -v.

AI routes require Authorization: Bearer YOUR_SERVER_ACCESS_TOKEN. Do not embed that
token or a provider key in a public APK. The existing debug gateway override supports
private testing. Public mobile deployment should use the separately configured Worker.

Routes:

- POST /api/v1/scene/analyze: bounded multipart image
- POST /api/v1/ocr/smart-read: bounded multipart image and query_type
- POST /api/v1/chat/message: bounded question
- POST /analyze: typed selected-image question/maneuver context
- POST /assistant: bounded history, context and selected image or 10–12 video frames
- POST /transcribe: 501, unsupported
- POST /api/v1/emergency/sos: validates input, then 503; no guardian notification service
- GET /health: configuration/capabilities, not proof that an external provider is healthy

Requests are limited to 1.2MB, images to 512KB/4MP, provider concurrency to four,
authenticated requests to 36/min and paid attempts to DAILY_AI_ATTEMPTS/day (UTC).
The attempt counter uses QUOTA_DATABASE; mount durable storage for deployment. A restart
with a fresh/ephemeral database resets this counter. Configure provider-side spending
limits too; separate hosts must share an enforced budget before scaling.

Responses report actual provider/model/usage where available. Missing credentials,
invalid outputs and exhausted fallbacks return explicit errors, never fictional hazards
or reassurance. FastAPI does not execute app actions and declares that capability false.
No telemetry ingestion, live guardian dashboard service or notification delivery was added.

The Docker context excludes .env, virtual environments, runtime quota files and caches.
Provide credentials at runtime. The repository does not deploy these local fixes to an
existing external host.
