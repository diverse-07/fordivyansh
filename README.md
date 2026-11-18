# 👁️ Netra Saathi (नेत्र साथी) — Full-Scale AI Assistive Navigation System

[![Android Tests](https://img.shields.io/badge/Android%20Tests-165%20Passing-brightgreen)](android-app)
[![Backend Tests](https://img.shields.io/badge/Backend%20Tests-7%20Passing-brightgreen)](backend)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](android-app/LICENSE)

**Netra Saathi** is an assistive navigation platform engineered for visually impaired pedestrians. It bridges on-device real-time safety, computer vision, spatial sonar audio, high-speed voice controls, emergency safety systems, and multimodal cloud AI.

---

## 🏛️ System Architecture

```
fordivyansh (Netra Saathi Monorepo)
├── 📱 android-app/     # Native Android Application (Kotlin + Jetpack Compose + CameraX)
│                       # • Footpath Detection & Semantic Depth Estimation (ONNX Runtime)
│                       # • Local Voice Assistant ("Hey Saathi") & High-Speed Volume-Up Push-To-Talk
│                       # • Loudspeaker Triple-Click Hardware Emergency Alarm
│                       # • Harvard Mobilio Spatial Sonar (Binaural Ear Panning)
│                       # • High-Contrast Industrial Tactile UI for Low-Vision & Blind Users
│                       # • 165 Passing Unit & Integration Tests
│
├── 🖥️ backend/         # FastAPI Cloud AI Gateway (Python 3.11)
│                       # • Powered by Google Gemini 2.0 / 3 Flash
│                       # • 12-Frame Surroundings Sweep Video & Multimodal Scene Analysis
│                       # • Conversational Companion & Smart Visual OCR
│                       # • Caretaker SOS & Fall Detection Webhooks
│                       # • 7 Passing Integration Tests
│
├── 🌐 dashboard/       # Caretaker Live Guardian Dashboard (Next.js 14 + Tailwind CSS)
│                       # • Real-time GPS Location & Footpath Lock
│                       # • Live 3-Zone Hazard Telemetry Feed
│                       # • Instant SOS Emergency Sirens & Fall Alerts
│
└── 🧠 ml-training/      # Pothole & Road Hazard ML Detection Pipeline
                        # • Custom YOLOv8-Nano INT8 Quantized Models
                        # • Google Colab T4 Training Pipeline & Verification Scripts
```

---

## 🚀 Key Blind-Accessibility Features

1. **Hands-Free & Rapid Voice Control**:
   - Long-press Volume Up (> 2 sec) anywhere to start voice interaction immediately.
   - Offline keyword detection for *"Hey Saathi"* using Silero VAD & local voice models.
2. **Emergency Loudspeaker Alarm**:
   - Triple-click Volume Up immediately triggers a loud distress siren and spoken alert (*"Help, I am blind!"*).
   - Audio route forces output through device speaker at max volume, even when Bluetooth TWS earphones are paired.
3. **Spatial Sonar Audio**:
   - Binaural 3D audio cues dynamically pan from left to right based on obstacle position in camera field of view.
4. **Tactile Industrial Visual UI**:
   - High-contrast, glare-free industrial design with solid tactile buttons and no distracting glassy elements.

---

## 🛠️ Quickstart

### 1. Backend (FastAPI + Google Gemini)
```bash
cd backend
cp .env.example .env
# Set GEMINI_API_KEY in .env
pip install -r requirements.txt
python -m uvicorn app.main:app --reload --port 8000
```
- Swagger API Docs: `http://localhost:8000/docs`
- Run Tests: `python -m unittest discover tests`

### 2. Caretaker Dashboard (Next.js)
```bash
cd dashboard
npm install
npm run dev
```
- Dashboard URL: `http://localhost:3000`

### 3. Android Application (Kotlin)
```bash
cd android-app
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
- Install APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

---

## 📜 Credits & Attribution
- Based on the open-source architecture of **[LibreVision](https://github.com/pritomshad/librevision)** by Pritom Das (`@pritomshad`), licensed under the **Apache License 2.0**.
- Spatial audio guidance inspired by **Harvard Mobilio** (*Nature Biomedical Engineering*).
- Cloud multimodal intelligence powered by **Google DeepMind Gemini**.
