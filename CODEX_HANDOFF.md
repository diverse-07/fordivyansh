# 🚀 NETRA SAATHI — CODEX DEVELOPER HANDOFF & SYSTEM SPECIFICATION

> **Target Audience:** Divyansh / Codex AI Coding Assistant  
> **Repository:** `https://github.com/diverse-07/fordivyansh.git`  
> **Target Branch:** `main`  
> **Date:** November 2026  
> **Status:** Working Production MVP — 165 Android Tests Passing, 7 Backend Tests Passing

---

## 1. Executive Summary & Mission

**Netra Saathi (नेत्र साथी)** is an assistive pedestrian navigation platform engineered specifically for visually impaired and blind individuals. Unlike generic navigation tools or expensive proprietary wearables ($3,000+ smart glasses), Netra Saathi transforms a standard Android smartphone into a tactile, spatial-audio intelligent guide.

### Core Value Pillars:
1. **On-Device Real-Time Collision Prevention (<35ms):** Offline edge object detection (YOLOv8-Nano) and metric depth estimation (ONNX) running locally without network latency.
2. **Harvard Mobilio Spatial Sonar:** Binaural 3D audio cues panning dynamically across left, center, and right ears based on obstacle azimuth.
3. **100% Eyes-Free Hardware Controls:**
   - **Voice Control:** Long-press Volume-Up (> 2 sec) triggers immediate conversational voice assistant dialogue (*"Hey Saathi"*), eliminating touch screen dependency.
   - **Emergency Siren:** Triple-click Volume-Up immediately forces a high-decibel distress siren and spoken alert (*"Help, I am blind!"*) through device loudspeakers at maximum volume, even if Bluetooth TWS earbuds are paired.
4. **Hybrid Dual-Brain Multimodal AI:** Offline edge computer vision for millisecond obstacle safety, combined with Google Gemini 2.0/3 Flash for 360° surroundings video sweeps and visual Q&A.
5. **Caretaker Live Guardian:** Real-time Next.js web dashboard streaming GPS location, 3-zone hazard feeds, and instant fall/SOS alerts to family members and guardians.

---

## 2. Monorepo Structure & File Sitemap

```
fordivyansh/
├── 📱 android-app/                   # Native Android Application (Kotlin, Jetpack Compose, CameraX)
│   ├── app/src/main/
│   │   ├── assets/                   # ML Models & Manifests
│   │   │   ├── monocular_depth.onnx  # Metric Depth Estimation ONNX model (35.6 MB)
│   │   │   ├── pothole_yolov8.onnx   # Road Damage & Pothole Detection model
│   │   │   ├── english-model.json    # Vosk speech recognition configuration
│   │   │   └── vad-model.json        # Silero Voice Activity Detection v5 configuration
│   │   ├── java/com/librevision/
│   │   │   ├── MainActivity.kt       # App entrypoint, hardware volume button interceptor
│   │   │   ├── LibreVisionViewModel.kt # Central StateFlow orchestrator & blind navigation engine
│   │   │   ├── core/
│   │   │   │   ├── audio/
│   │   │   │   │   ├── SpatialSonarEngine.kt       # Binaural PCM audio panning (Harvard Mobilio)
│   │   │   │   │   ├── AndroidSpeechOutput.kt     # High-speed TTS audio engine
│   │   │   │   │   └── FeedbackCues.kt            # Haptic vibration & acoustic earcons
│   │   │   │   ├── safety/
│   │   │   │   │   └── LoudspeakerEmergencyAlarm.kt # Loudspeaker force-route distress siren
│   │   │   │   └── design/
│   │   │   │       ├── Theme.kt                   # Industrial High-Contrast color system ("Field")
│   │   │   │       ├── Controls.kt                # Tactile large-target buttons (InstrumentKey)
│   │   │   │       ├── GuidanceReadout.kt         # Glare-free text status cards
│   │   │   │       └── LVIcons.kt                 # Accessible vector icons
│   │   │   ├── feature/
│   │   │   │   ├── footpath/
│   │   │   │   │   ├── FootpathScreen.kt          # Primary pedestrian safety radar & controls
│   │   │   │   │   ├── CameraPreview.kt           # CameraX feed + radar sweep + hazard bounding boxes
│   │   │   │   │   └── FootpathStatusBanner.kt    # Obstruction / Safe / Scanning state banner
│   │   │   │   ├── home/
│   │   │   │   │   └── HomeScreen.kt              # Main menu with tactile high-contrast tiles
│   │   │   │   ├── surroundings/
│   │   │   │   │   └── SurroundingsScreen.kt      # 12-frame multimodal video sweep viewer
│   │   │   │   └── settings/
│   │   │   │       └── SettingsScreen.kt          # Gateway URL, wake word, and speech configs
│   │   │   ├── domain/
│   │   │   │   └── hazards/
│   │   │   │       └── ThreeZoneHazardClassifier.kt # Ground (potholes), Torso, Head hazard matrix
│   │   │   └── data/
│   │   │       └── remote/
│   │   │           ├── VisionAssistCloudClient.kt  # Retrofit/OkHttp client for FastAPI gateway
│   │   │           └── GatewayConfiguration.kt     # Gateway endpoint & fallback management
│   │   └── res/                      # Android drawables, layouts, and strings (Branded: Netra Saathi)
│   ├── app/src/test/                 # 165 Passing JVM Unit Tests
│   │   ├── FootpathTest.kt           # Footpath safety state machine tests
│   │   ├── EmergencyAlarmTest.kt     # Hardware triple-click gesture & siren tests
│   │   ├── HandsFreeTest.kt          # Wake word & volume hold tests
│   │   ├── SurroundingsTest.kt       # Video sweep & scene analysis tests
│   │   └── WelcomeTest.kt            # First-run onboarding & TTS greeting tests
│   ├── build.gradle.kts              # Root gradle configuration
│   └── gradlew / gradlew.bat         # Gradle wrapper
│
├── 🖥️ backend/                       # Cloud AI Backend (FastAPI, Python 3.11, Docker)
│   ├── app/
│   │   ├── main.py                   # FastAPI server entrypoint & CORS middleware
│   │   ├── config.py                 # Settings (GEMINI_API_KEY, PORT, ENVIRONMENT)
│   │   ├── api/v1/
│   │   │   ├── endpoints.py          # /api/v1/analyze-sweep, /api/v1/sos, /api/v1/telemetry
│   │   │   └── librevision_gateway.py # Plug-and-play gateway bridge for Android app
│   │   └── services/
│   │       └── gemini_service.py     # Google Gemini 2.0 / 3 Flash Multimodal client
│   ├── tests/                        # 7 Automated Backend Integration Tests
│   │   ├── test_endpoints.py         # REST endpoint & telemetry tests
│   │   └── test_librevision_gateway.py # Video sweep frame analysis tests
│   ├── requirements.txt              # fastapi, uvicorn, google-genai, pydantic, httpx
│   ├── Dockerfile                    # Container definition for cloud deployment
│   ├── render.yaml & railway.json    # One-click deployment templates
│   └── .env.example                  # Environment variable template
│
├── 🌐 dashboard/                     # Caretaker Live Guardian Dashboard (Next.js 14, Tailwind)
│   ├── src/app/
│   │   ├── page.tsx                  # Live map, 3-zone hazard feed, emergency SOS banner
│   │   └── layout.tsx                # Responsive guardian layout
│   ├── package.json                  # Next.js 14, Lucide icons, Tailwind CSS
│   └── vercel.json                   # Vercel deployment configuration
│
└── 🧠 ml-training/                    # Pothole & Hazard Detection Pipeline (YOLOv8 INT8)
    ├── train_pothole_yolov8.ipynb    # Google Colab T4 training notebook
    ├── train_and_export.py           # Training and TFLite/ONNX quantization pipeline
    ├── verify_tflite.py              # Mobile latency & precision benchmark (<35ms)
    └── MODEL_SPEC.md                 # Input/output tensors and INT8 specifications
```

---

## 3. Quickstart & Runbook for Codex

### Clone the Repository
```bash
git clone https://github.com/diverse-07/fordivyansh.git
cd fordivyansh
```

### 1. Android Application
- **Prerequisites:** JDK 17+, Android SDK (API 34+).
- **Run Unit Tests (Mandatory before any commit):**
  ```bash
  cd android-app
  ./gradlew testDebugUnitTest
  ```
  *(Expected result: `BUILD SUCCESSFUL` with 165 tests passing).*
- **Build Debug APK:**
  ```bash
  ./gradlew assembleDebug
  ```
  *(Output APK: `android-app/app/build/outputs/apk/debug/app-debug.apk`)*.
- **Install on Device / Emulator:**
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```

### 2. FastAPI Backend Server
- **Prerequisites:** Python 3.10+ (Recommended Python 3.11).
- **Setup & Run:**
  ```bash
  cd backend
  python -m venv .venv
  # On Windows: .venv\Scripts\activate | On Unix: source .venv/bin/activate
  pip install -r requirements.txt
  cp .env.example .env
  # Set your Google AI Studio GEMINI_API_KEY in .env
  python -m uvicorn app.main:app --reload --port 8000
  ```
- **Run Backend Tests:**
  ```bash
  python -m unittest discover tests
  ```
  *(Expected result: 7 tests passing).*
- **API Swagger Documentation:** `http://localhost:8000/docs`.

### 3. Caretaker Guardian Dashboard
- **Prerequisites:** Node.js 18+ & npm.
- **Setup & Run:**
  ```bash
  cd dashboard
  npm install
  npm run dev
  ```
- **Dashboard URL:** `http://localhost:3000`.

---

## 4. Current State & Technical Achievements

| Area | Status | Verification Evidence |
| :--- | :--- | :--- |
| **Android Tests** | ✅ 100% Passing | **165 tests pass** (`./gradlew testDebugUnitTest`) |
| **Backend Tests** | ✅ 100% Passing | **7 tests pass** (`python -m unittest discover tests`) |
| **Branding** | ✅ Fully Sanitized | Zero user-facing "LibreVision" occurrences; branded as **Netra Saathi** |
| **UI Design** | ✅ Industrial & Tactile | High-contrast "Field" mode, large touch targets, glare-free layout |
| **Hardware Volume Keys** | ✅ Fully Implemented | Intercepted in `MainActivity.kt`: Hold Vol-Up (>2s) for voice; Triple-click Vol-Up for loud siren |
| **Spatial Audio** | ✅ Fully Implemented | Harvard Mobilio binaural audio synthesizer in `SpatialSonarEngine.kt` |
| **SOS Loudspeaker** | ✅ Fully Implemented | `LoudspeakerEmergencyAlarm.kt` overrides Bluetooth audio routing to blast device speaker |
| **APK Build** | ✅ Verified | Ready at `android-app/app/build/outputs/apk/debug/app-debug.apk` (158 MB) |

---

## 5. Architectural Deep Dives for Codex

### A. Hardware Volume Button Gesture Interception
Located in `android-app/app/src/main/java/com/librevision/MainActivity.kt`:
```kotlin
// Volume Up Key Tracking:
// 1. ACTION_DOWN: Starts a 2000ms timer. If held past 2000ms -> Triggers voice assistant.
// 2. Click count: If clicked 3 times within 1200ms -> Triggers Loudspeaker Emergency Alarm.
override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
        // Intercepted for accessibility gestures
        return true
    }
    return super.onKeyDown(keyCode, event)
}
```

### B. Forced Loudspeaker Emergency Alarm
Located in `android-app/app/src/main/java/com/librevision/core/safety/LoudspeakerEmergencyAlarm.kt`:
```kotlin
// Critical Life-Safety Feature:
// When a visually impaired user is in distress, emergency audio MUST NOT stay in their earbuds.
// The alarm forces audio routing through AudioManager.STREAM_ALARM at MAX_VOLUME via the built-in speaker.
// Spoken message: "Emergency alert. Help, I am blind!"
```

### C. Harvard Mobilio Spatial Sonar Audio Engine
Located in `android-app/app/src/main/java/com/librevision/core/audio/SpatialSonarEngine.kt`:
- Synthesizes dynamic stereo PCM audio using binaural interaural time difference (ITD) and interaural level difference (ILD).
- As obstacles move from left to right in the camera frame, the acoustic beacon pans across the corresponding ear, providing natural 3D obstacle perception without looking.

### D. Dual-Brain Cloud Gateway & Video Sweep
Located in `backend/app/services/gemini_service.py` & `GatewaySceneAnalysisProvider.kt`:
- **Offline Mode:** CameraX + YOLOv8 ONNX + Depth ONNX processes frames in `<35ms` for instant pedestrian safety.
- **Online Sweep Mode:** Holding voice button and asking *"What is around me?"* triggers a 12-frame sweep video capture sent to FastAPI `/api/v1/analyze-sweep`, analyzed by Gemini 2.0/3 Flash for deep natural language scene descriptions.

---

## 6. Priority Work Items & Roadmap for Divyansh

Here are the highest-impact areas for Divyansh to pick up immediately:

1. **Hackathon Presentation & Demo Walkthrough:**
   - Prepare a live demonstration using an Android device or emulator with webcam feed.
2. **Cloud Backend Deployment:**
   - Deploy `backend/` to Railway, Render, or Google Cloud Run.
   - Update `gateway.properties` or `SettingsScreen.kt` with the live production URL.
3. **Caretaker Dashboard Deployment:**
   - Deploy `dashboard/` to Vercel (`cd dashboard && vercel deploy`).
   - Configure WebSocket/REST webhook URLs to receive live telemetry from the backend.
4. **Haptic & Acoustic Tuning:**
   - Fine-tune vibration patterns in `FeedbackCues.kt` for intense traffic environments.
   - Calibrate pothole detection confidence thresholds in `ThreeZoneHazardClassifier.kt`.

---

## 7. Strict Codex Behavioral Guidelines

When writing or modifying code in this repository:
1. **Never Break Existing Tests:** Always execute `./gradlew testDebugUnitTest` in `android-app/` after any Kotlin change.
2. **Surgical Changes Only:** Do not rewrite untouched files, clean up unrelated imports, or overhaul architecture.
3. **Preserve Tactile Industrial UI:** Do not re-introduce translucent glassy overlays or unbounded `fillMaxSize()` elements that push camera previews and buttons off-screen.
4. **Preserve Rebranding:** Keep all user-facing strings, spoken prompts, and titles as **Netra Saathi** (never re-introduce "LibreVision").
5. **Security First:** Never commit actual API keys to git. Keep `.env` and `local.properties` in `.gitignore`.
