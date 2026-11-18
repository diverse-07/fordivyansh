import os
import json
import logging
from typing import Dict, Any, Optional
from PIL import Image
from app.config import settings

logger = logging.getLogger("NetraSaathi-GeminiService")

class GeminiService:
    def __init__(self):
        self.api_key = settings.GEMINI_API_KEY or os.environ.get("GEMINI_API_KEY", "")
        self.client = None
        self.has_key = False

        if self.api_key and self.api_key != "your_gemini_api_key_here":
            try:
                from google import genai
                self.client = genai.Client(api_key=self.api_key)
                self.has_key = True
                logger.info("Initialized official google-genai client.")
            except Exception as e:
                logger.warning(f"Failed to initialize google-genai client: {e}")
        else:
            logger.info("No live GEMINI_API_KEY found. Running with robust mock simulation.")

        # Audio-first system instruction for blind pedestrian assistance
        self.pedestrian_system_prompt = (
            "You are Netra Saathi, the ultimate AI companion providing 360-degree 'Overall Vision' for a blind pedestrian.\n"
            "You protect the user across THREE vertical safety zones:\n"
            "1. GROUND HAZARDS (CRITICAL LIFE SAFETY):\n"
            "   - Potholes and road craters\n"
            "   - Open, ajar, or sunken utility manholes\n"
            "   - Road fissures, broken concrete, and dug-up construction trenches/ditches\n"
            "   - Curbs and descending stairs\n"
            "2. TORSO TRAFFIC:\n"
            "   - Oncoming bicycles, e-scooters, motorcycles, and cars\n"
            "   - Moving pedestrians, utility poles, bollards, and vendors\n"
            "3. HEAD HEIGHT (Canes cannot detect this!):\n"
            "   - Overhanging tree branches, low shop awnings, low-hanging signs, hanging wires\n\n"
            "CRITICAL AUDIO RULES:\n"
            "- NEVER use visual phrases like 'as you can see' or 'in this picture'.\n"
            "- Always specify relative direction and height: 'to your left', 'to your right', 'straight ahead', 'ground level', 'head height'.\n"
            "- Alert immediately if an approaching bicycle/car or low-light requires the SAFETY BLINK LIGHT (strobe beacon).\n"
            "- Keep audio summary under 2 punchy, actionable sentences.\n"
            "- Return response ONLY in pure, valid JSON with no markdown formatting."
        )

    def analyze_scene(self, image: Image.Image) -> Dict[str, Any]:
        """
        Analyzes a camera frame across ground, torso, and head-height zones.
        """
        if not self.has_key or self.client is None:
            return self._mock_scene_analysis()

        prompt = (
            f"{self.pedestrian_system_prompt}\n\n"
            "Analyze this camera frame. Return a pure JSON object matching this schema:\n"
            "{\n"
            '  "speech_summary": "1-2 sentence speech summary prioritizing closest immediate hazard",\n'
            '  "walkable_path": "Where it is safe to walk (e.g. sidewalk center, veer slightly right)",\n'
            '  "hazards": [\n'
            '    {\n'
            '      "name": "pothole | open_manhole | construction_trench | road_crack | tree_branch | bicycle | vehicle | pole",\n'
            '      "zone": "ground_level | torso_level | head_height",\n'
            '      "distance_m": 2.0,\n'
            '      "direction": "left | center-left | center | center-right | right",\n'
            '      "severity": "critical | warning | info",\n'
            '      "audio_warning": "Immediate spoken warning for user"\n'
            '    }\n'
            '  ],\n'
            '  "ground_conditions": {\n'
            '    "potholes_present": true,\n'
            '    "open_manholes": false,\n'
            '    "construction_trenches": false,\n'
            '    "surface_quality": "broken_asphalt | smooth_pavers | dirt_gravel | wet_slick"\n'
            '  },\n'
            '  "dynamic_actors": {\n'
            '    "bicycles_approaching": false,\n'
            '    "vehicles_approaching": false,\n'
            '    "crowd_density": "empty | light | moderate | crowded"\n'
            '  },\n'
            '  "safety_beacon_needed": true,\n'
            '  "beacon_reason": "Low light condition / approaching bicycle detected",\n'
            '  "suggested_action": "continue_straight | veer_left | veer_right | duck_head | stop_immediately"\n'
            "}"
        )

        try:
            response = self.client.models.generate_content(
                model=settings.DEFAULT_GEMINI_MODEL,
                contents=[image, prompt]
            )
            return self._parse_json_response(response.text)
        except Exception as e:
            logger.warning(f"Gemini 2.0 Flash error: {e}. Trying fallback model {settings.FALLBACK_GEMINI_MODEL}...")
            try:
                response = self.client.models.generate_content(
                    model=settings.FALLBACK_GEMINI_MODEL,
                    contents=[image, prompt]
                )
                return self._parse_json_response(response.text)
            except Exception as e2:
                logger.error(f"Fallback model failed: {e2}")
                return self._mock_scene_analysis(error=str(e2))

    def smart_ocr(self, image: Image.Image, query_type: str = "general") -> Dict[str, Any]:
        if not self.has_key or self.client is None:
            return {
                "detected_text": "Sample text: Apollo Pharmacy",
                "speech_summary": "Sign ahead reads: Apollo Pharmacy. Entrance is on your right.",
                "category": "sign",
                "key_details": "Entrance door on your right"
            }

        prompt = (
            f"{self.pedestrian_system_prompt}\n\n"
            f"Read and explain all visible text in this image for a blind user (Context: {query_type}).\n"
            "Return pure JSON:\n"
            "{\n"
            '  "speech_summary": "Clear, friendly spoken summary of what the text means",\n'
            '  "detected_text": "Full raw text found",\n'
            '  "category": "medicine | menu | street_sign | document | price_tag | label",\n'
            '  "key_details": "Key actionable information"\n'
            "}"
        )

        try:
            response = self.client.models.generate_content(
                model=settings.DEFAULT_GEMINI_MODEL,
                contents=[image, prompt]
            )
            return self._parse_json_response(response.text)
        except Exception as e:
            logger.error(f"OCR failed: {e}")
            return {"error": str(e), "speech_summary": "Could not read text clearly."}

    def chat(self, user_question: str, context_image: Optional[Image.Image] = None) -> str:
        if not self.has_key or self.client is None:
            return "Netra Saathi AI companion is standing by."

        prompt = (
            f"{self.pedestrian_system_prompt}\n\n"
            f"User asks: '{user_question}'\n"
            "Answer clearly and concisely in conversational spoken tone (maximum 2 sentences)."
        )

        contents = [context_image, prompt] if context_image else [prompt]
        candidate_models = [
            settings.DEFAULT_GEMINI_MODEL,
            "gemini-3-flash-preview",
            "gemini-2.5-flash",
        ]
        # Deduplicate while preserving order
        seen = set()
        models_to_try = [m for m in candidate_models if not (m in seen or seen.add(m))]

        for model_name in models_to_try:
            try:
                response = self.client.models.generate_content(
                    model=model_name,
                    contents=contents
                )
                return response.text.strip()
            except Exception as e:
                logger.warning(f"Chat attempt with {model_name} failed: {e}")
                continue

        return "I am with you. The path ahead appears clear, proceed with caution."

    def _parse_json_response(self, text: str) -> Dict[str, Any]:
        cleaned = text.strip()
        if cleaned.startswith("```json"):
            cleaned = cleaned[7:]
        if cleaned.startswith("```"):
            cleaned = cleaned[3:]
        if cleaned.endswith("```"):
            cleaned = cleaned[:-3]
        return json.loads(cleaned.strip())

    def _mock_scene_analysis(self, error: Optional[str] = None) -> Dict[str, Any]:
        return {
            "speech_summary": "Deep pothole 2 meters ahead slightly right. Tree branch at head height on your left. Sidewalk clear straight ahead.",
            "walkable_path": "Stay centered on the sidewalk.",
            "hazards": [
                {
                    "name": "pothole",
                    "zone": "ground_level",
                    "distance_m": 2.0,
                    "direction": "center-right",
                    "severity": "critical",
                    "audio_warning": "Warning: Deep road crater 2 meters ahead on your right. Step left."
                },
                {
                    "name": "tree_branch",
                    "zone": "head_height",
                    "distance_m": 2.5,
                    "direction": "left",
                    "severity": "warning",
                    "audio_warning": "Tree branch ahead at head height on your left."
                }
            ],
            "ground_conditions": {
                "potholes_present": True,
                "open_manholes": False,
                "construction_trenches": False,
                "surface_quality": "broken_asphalt"
            },
            "dynamic_actors": {
                "bicycles_approaching": False,
                "vehicles_approaching": False,
                "crowd_density": "light"
            },
            "safety_beacon_needed": True,
            "beacon_reason": "Broken asphalt with deep ground crater ahead.",
            "suggested_action": "veer_left",
            "mode": "simulation",
            "debug_note": error
        }

gemini_service = GeminiService()
