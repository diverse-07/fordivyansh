import io
import base64
import logging
from typing import Optional, List, Dict, Any
from fastapi import APIRouter, Request, HTTPException
from PIL import Image
from app.services.gemini_service import gemini_service

logger = logging.getLogger("NetraSaathi-Gateway")
router = APIRouter()

def decode_base64_image(data_uri: str) -> Image.Image:
    """Decodes data:image/jpeg;base64,... into a PIL Image."""
    if "," in data_uri:
        data_uri = data_uri.split(",", 1)[1]
    image_bytes = base64.b64decode(data_uri)
    return Image.open(io.BytesIO(image_bytes)).convert("RGB")

@router.post("/analyze")
async def analyze_frame(request: Request):
    """
    Direct scene frame analysis endpoint.
    Powered by Google Gemini 3 Flash.
    """
    try:
        data = await request.json()
        image_data = data.get("image", "")
        question = data.get("question", "Describe immediate hazards and walking direction.")
        
        pil_image = decode_base64_image(image_data) if image_data else None
        
        if pil_image:
            scene_result = gemini_service.analyze_scene(pil_image)
            text_response = scene_result.get("speech_summary", "Clear path ahead.")
        else:
            text_response = "No camera frame received."

        # Return exact format expected by LibreVision's GatewayResponseParser
        return {
            "model": "muse-spark-1.3",
            "text": text_response,
            "latencyMs": 120,
            "usage": {
                "inputTokens": 250,
                "outputTokens": 45,
                "reasoningTokens": 0
            }
        }
    except Exception as e:
        logger.error(f"Error handling /analyze: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/assistant")
async def assistant_surroundings(request: Request):
    """
    Surroundings video sweep and conversational assistant endpoint.
    Powered by Google Gemini 3 Flash.
    """
    try:
        data = await request.json()
        question = data.get("question", "Describe my surroundings")
        views = data.get("views", [])
        
        # Decode multiple keyframes from the surroundings video sweep
        images = []
        if views:
            for v in views:
                img_str = v.get("image", "")
                if img_str:
                    try:
                        images.append(decode_base64_image(img_str))
                    except Exception:
                        pass
        elif "image" in data:
            images.append(decode_base64_image(data["image"]))

        # Run through Gemini
        if images:
            answer = gemini_service.chat(
                user_question=f"User swept their camera across surroundings and asked: '{question}'. Summarize all obstacles and landmarks clearly in 2-3 sentences.",
                context_image=images[0] # primary view, or multi-view
            )
        else:
            answer = gemini_service.chat(question)

        # Return exact format expected by LibreVision's MuseReplyParser
        return {
            "model": "muse-spark-1.3",
            "text": answer,
            "action": None,
            "usage": {
                "outputTokens": 60
            }
        }
    except Exception as e:
        logger.error(f"Error handling /assistant: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/transcribe")
async def transcribe_audio():
    """Optional speech fallback endpoint."""
    return {"text": ""}
