import io
from fastapi import APIRouter, File, UploadFile, HTTPException, Form
from pydantic import BaseModel, ConfigDict, Field, StringConstraints
from typing import Annotated, Literal
from PIL import Image, UnidentifiedImageError
from app.services.gemini_service import gemini_service

router=APIRouter()
Question=Annotated[str,StringConstraints(strip_whitespace=True,min_length=1,max_length=500)]
class ChatRequest(BaseModel):
    model_config=ConfigDict(extra="forbid")
    question: Question
class SOSRequest(BaseModel):
    model_config=ConfigDict(extra="forbid",allow_inf_nan=False)
    user_id: str = Field(min_length=1,max_length=100)
    latitude: float = Field(ge=-90,le=90)
    longitude: float = Field(ge=-180,le=180)
    battery_level: float | None = Field(default=None,ge=0,le=100)
    trigger_reason: Literal["manual_triple_tap","fall_detected"] = "manual_triple_tap"

def decode_image(raw: bytes):
    if not raw or len(raw)>512000:raise HTTPException(413,"IMAGE_SIZE_LIMIT")
    try:
        image=Image.open(io.BytesIO(raw))
        if image.width>4096 or image.height>4096 or image.width*image.height>4194304:
            raise HTTPException(413,"IMAGE_PIXEL_LIMIT")
        image.load()
        return image.convert("RGB")
    except (UnidentifiedImageError,OSError,ValueError,Image.DecompressionBombError):
        raise HTTPException(422,"INVALID_IMAGE") from None

async def upload_image(image):
    if not (image.content_type or "").startswith("image/"):raise HTTPException(415,"IMAGE_CONTENT_TYPE")
    return decode_image(await image.read(512001))

@router.post("/scene/analyze")
async def analyze_scene(image: UploadFile=File(...)):
    result=await gemini_service.analyze_scene(await upload_image(image))
    return {"status":"success","data":result["data"],"model":result["model"],"usage":result["usage"]}

@router.post("/ocr/smart-read")
async def smart_ocr(image: UploadFile=File(...),query_type: str=Form("general",max_length=80)):
    result=await gemini_service.smart_ocr(await upload_image(image),query_type)
    return {"status":"success","data":result["data"],"model":result["model"],"usage":result["usage"]}

@router.post("/chat/message")
async def chat_message(request: ChatRequest):
    result=await gemini_service.chat(request.question)
    return {"status":"success","response":result["text"],"model":result["model"],"usage":result["usage"]}

@router.post("/emergency/sos")
async def trigger_sos(sos: SOSRequest):
    # No delivery provider or guardian pairing exists. Never acknowledge a broadcast.
    raise HTTPException(503,"GUARDIAN_NOTIFICATIONS_NOT_CONFIGURED")
