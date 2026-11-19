# Netra Saathi Guardian

This interface has no connected telemetry, guardian identity, notification delivery or
remote-command service. It displays unknown/unavailable states. Alert preview is local
only and does not send or acknowledge a real SOS.

Use Node 22.13+. Run npm ci, npm run lint, npm run typecheck, npm run build, then npm run dev.
No private data should be exposed without an implemented authorization and pairing service.
No new service was added during the bug-fix pass.
