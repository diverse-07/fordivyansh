# 👁️ Netra Saathi Cloud AI Backend (FastAPI + Google Gemini 3 Flash)

High-performance cloud intelligence gateway powering 360° surroundings analysis, smart OCR, and conversational spatial assistance for the **Netra Saathi** mobile platform and caretaker dashboard.

---

## ⚡ Features
- **Pedestrian Scene Intelligence**: Powered by Google Gemini 3 Flash (`gemini-3-flash-preview`) with automated multi-tier fallback.
- **Strict Audio-First Navigation**: Outputs directionally calibrated, non-visual speech alerts specifically tailored for blind pedestrians.
- **Smart OCR**: Context-aware reading for street signs, shop boards, menus, and medicine bottles.
- **Conversational Companion**: "Saathi AI" answers natural voice questions regarding the user's surroundings.
- **Emergency Telemetry**: Logs real-time coordinates, hazard telemetry, and SOS alerts for the caretaker dashboard.
- **Live Deployment**: Hosted on Render at [https://megalarper.onrender.com](https://megalarper.onrender.com).

---

## 🚀 Running Locally

### 1. Install Dependencies
```bash
pip install -r requirements.txt
```

### 2. Configure Environment
Set your Gemini API key in `.env`:
```env
GEMINI_API_KEY=your_gemini_api_key_here
DEFAULT_GEMINI_MODEL=gemini-3-flash-preview
```

### 3. Start the Server
```bash
uvicorn app.main:app --reload --port 8000
```
Swagger API docs available at: `http://localhost:8000/docs`

---

## 🛡️ License
Netra Saathi Cloud AI is licensed under the Apache License 2.0.
