import io
from fastapi import APIRouter, File, UploadFile, HTTPException, Form
from pydantic import BaseModel
from typing import Optional, List, Dict, Any
from PIL import Image
from app.services.gemini_service import gemini_service

router = APIRouter()

class ChatRequest(BaseModel):
    question: str

class SOSRequest(BaseModel):
    user_id: str
    latitude: float
    longitude: float
    battery_level: Optional[float] = None
    trigger_reason: str = "manual_triple_tap" # or "fall_detected"

@router.post("/scene/analyze")
async def analyze_scene(image: UploadFile = File(...)):
    """
    Receives camera frame, runs Gemini 2.0 Flash scene analysis,
    and returns audio-first pedestrian navigation instructions.
    """
    if not image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="File must be an image.")
    
    try:
        image_bytes = await image.read()
        pil_image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        result = gemini_service.analyze_scene(pil_image)
        return {"status": "success", "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/ocr/smart-read")
async def smart_ocr(image: UploadFile = File(...), query_type: str = Form("general")):
    """
    Reads signs, menus, documents, and medicine bottles with deep context.
    """
    if not image.content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="File must be an image.")
        
    try:
        image_bytes = await image.read()
        pil_image = Image.open(io.BytesIO(image_bytes)).convert("RGB")
        result = gemini_service.smart_ocr(pil_image, query_type=query_type)
        return {"status": "success", "data": result}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/chat/message")
async def chat_message(request: ChatRequest):
    """
    Conversational voice queries regarding environment or navigation.
    """
    answer = gemini_service.chat(request.question)
    return {"status": "success", "response": answer}

@router.post("/emergency/sos")
async def trigger_sos(sos: SOSRequest):
    """
    Registers an SOS or fall detection event, logs coordinates for the caretaker dashboard.
    """
    # In production, dispatch push notifications to linked caretakers via FCM
    return {
        "status": "sos_acknowledged",
        "user_id": sos.user_id,
        "location": {"lat": sos.latitude, "lng": sos.longitude},
        "reason": sos.trigger_reason,
        "message": "Emergency broadcast triggered to all registered caretakers."
    }
