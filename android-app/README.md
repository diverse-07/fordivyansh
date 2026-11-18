# Netra Saathi (नेत्र साथी)
### AI-Powered Assistive Real-Time Spatial Navigation for the Blind & Visually Impaired

<p align="center">
  <img src="app/src/main/res/drawable/netra_saathi_icon.png" width="128" alt="Netra Saathi Logo">
</p>

<p align="center">
  <b>Netra Saathi</b> is an advanced Android accessibility platform designed to provide complete situational awareness, obstacle avoidance, and independent mobility for blind and visually impaired pedestrians.
</p>

---

## 🌟 Key Capabilities

### 1. 3-Zone Vertical Hazard Protection
Unlike traditional walking canes that only detect ground obstacles immediately in front of the foot, Netra Saathi monitors **all three vertical hazard layers** in real-time:
* **Ground Level (Critical Safety):** Deep potholes, road fissures, broken concrete, open or sunken manholes, construction ditches, and descending stairs.
* **Torso Level (Dynamic Traffic):** Approaching bicycles, e-scooters, motorcycles, cars, utility poles, bollards, and pedestrians.
* **Head Height (Overhead Hazards):** Overhanging tree branches, low shop awnings, dangling wires, and protruding commercial signs that white canes miss.

### 2. Dual-Engine Intelligence Architecture
* **Sub-50ms On-Device Reflexes:** Powered by an optimized on-device YOLOv8 ONNX vision model running entirely offline. Delivers instant spatial audio chirps and directional steering cues without requiring an active internet connection.
* **Semantic Cloud AI (Google Gemini 3 Flash):** Connected to a high-speed cloud reasoning gateway ([megalarper on Render](https://megalarper.onrender.com)). Provides 360° surroundings sweeps, street sign OCR, transit schedule interpretation, and conversational voice guidance via **Saathi AI**.

### 3. Tactile & Futuristic User Interface
* **Glowing Radar Viewfinder:** Real-time vertical sweep scanning beam with glowing cyan/green HUD overlays.
* **Bouncy Spring Physics:** Tactile button responses designed specifically for low-vision and blind users.
* **Sinusoidal Breathing Halo:** Dynamic audio feedback indicating active voice listening states.
* **Smooth Screen Navigation:** Kinetic horizontal slide and scale transitions built purely with Jetpack Compose.

---

## 🏗️ Project Architecture & Ecosystem

| Repository | Description | Live Endpoint / Status |
| :--- | :--- | :--- |
| **[netra-saathi](https://github.com/diverse-07/netra-saathi)** | Android mobile app (Kotlin, Jetpack Compose, CameraX, LiteRT, ONNX) | [Download NetraSaathi.apk](https://github.com/diverse-07/netra-saathi/releases) |
| **[megalarper](https://github.com/diverse-07/megalarper)** | Cloud AI Backend (FastAPI, Google Gemini 3 Flash, Docker) | [https://megalarper.onrender.com](https://megalarper.onrender.com) |
| **[netra-saathi-dashboard](https://github.com/diverse-07/netra-saathi-dashboard)** | Caretaker Live Telemetry & Emergency SOS Portal (Next.js 14, Tailwind) | [localhost:3000](http://localhost:3000) |

---

## 🚀 Building & Running the Android App

### Prerequisites
* Android Studio Ladybug / Meerkat or command-line Gradle
* JDK 17+
* Android SDK 36 (Minimum SDK 26 - Android 8.0+)
* Architecture: ARM64-v8a

### Compile APK
```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.netrasaathi/.MainActivity
```

---

## 🎙️ Voice Commands
Say **"Hey Saathi"** or tap the voice button to trigger:
* *"What's around me?"* — Sweeps surroundings and narrates obstacles.
* *"Read text"* — OCR on street signs, store names, prescription labels.
* *"Where am I?"* — Spoken location and landmark updates.
* *"Start scanning"* / *"Stop scanning"* — Controls real-time obstacle perception.

---

## 🛡️ License
Netra Saathi is open-source software licensed under the **Apache License 2.0**.
