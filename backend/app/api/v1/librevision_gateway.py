import base64
import binascii
import time
from typing import Literal
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from app.api.v1.endpoints import Question, decode_image
from app.services.gemini_service import gemini_service

router=APIRouter()
class Strict(BaseModel):
    model_config=ConfigDict(extra="forbid")
class View(Strict):
    image: str = Field(min_length=1,max_length=700000)
    width: int = Field(ge=1,le=1024)
    height: int = Field(ge=1,le=1024)
    offsetMs: int | None = Field(default=None,ge=0)
    ageMs: int | None = Field(default=None,ge=0,le=340000)
class Analyze(View):
    task: Literal["DESCRIBE","VISUAL_QUESTION","CONTEXTUALIZE_MANEUVER"]
    model: Literal["muse-spark-1.3"] | None = None # legacy request protocol identifier only
    question: Question = "Describe visible obstacles without assessing safe passage."
    routeContext: str | None = Field(default=None,max_length=300)
    detections: list[str] = Field(default_factory=list,max_length=10)
class Turn(Strict):
    role: Literal["user","assistant"]
    text: str = Field(min_length=1,max_length=500)
class Surroundings(Strict):
    source: Literal["video"]
    ageMs: int = Field(ge=0,le=300000)
    durationMs: int = Field(ge=750,le=32000)
    initial: bool
class Assistant(Strict):
    question: Question
    context: str = Field(default="",max_length=4000)
    history: list[Turn] = Field(default_factory=list,max_length=6)
    image: str | None = Field(default=None,max_length=700000)
    width: int | None = Field(default=None,ge=1,le=1024)
    height: int | None = Field(default=None,ge=1,le=1024)
    views: list[View] = Field(default_factory=list,max_length=12)
    surroundings: Surroundings | None = None

def decode_view(view):
    prefix="data:image/jpeg;base64,"
    if not view.image.startswith(prefix):raise HTTPException(415,"JPEG_DATA_URI_REQUIRED")
    try: raw=base64.b64decode(view.image[len(prefix):],validate=True)
    except (ValueError,binascii.Error):raise HTTPException(422,"INVALID_BASE64") from None
    if not raw.startswith(b"\xff\xd8") or not raw.endswith(b"\xff\xd9"):raise HTTPException(422,"INVALID_JPEG")
    image=decode_image(raw)
    if image.size!=(view.width,view.height):raise HTTPException(422,"IMAGE_DIMENSION_MISMATCH")
    return image,raw

@router.post("/analyze")
async def analyze_frame(request: Analyze):
    start=time.monotonic()
    image,_=decode_view(request)
    result=await gemini_service.analyze_scene(image,request.question,request.routeContext)
    return {"protocolVersion":1,"model":result["model"],"text":result["data"]["speech_summary"],
        "latencyMs":round((time.monotonic()-start)*1000),"usage":result["usage"]}

@router.post("/assistant")
async def assistant_surroundings(request: Assistant):
    images=[];context=request.context
    if request.views:
        meta=request.surroundings
        if request.image or not meta or not 10<=len(request.views)<=12:
            raise HTTPException(422,"INVALID_SWEEP")
        previous=-1;total=0
        for view in request.views:
            if view.offsetMs is None or view.ageMs is None or not previous<view.offsetMs<meta.durationMs:
                raise HTTPException(422,"INVALID_FRAME_TIMESTAMPS")
            _,raw=decode_view(view)
            if len(raw)>48000 or max(view.width,view.height)>512:raise HTTPException(413,"SWEEP_FRAME_LIMIT")
            previous=view.offsetMs;total+=len(raw);images.append({"jpeg":raw})
        if total>576000:raise HTTPException(413,"SWEEP_SIZE_LIMIT")
        context+="\nSaved video frames. Capture ageMs="+str(meta.ageMs)+". Frame offsetsMs="+str([v.offsetMs for v in request.views])+". Coverage/direction unknown."
    elif request.surroundings:
        raise HTTPException(422,"SWEEP_FRAMES_REQUIRED")
    elif request.image:
        if request.width is None or request.height is None:raise HTTPException(422,"IMAGE_DIMENSIONS_REQUIRED")
        _,raw=decode_view(View(image=request.image,width=request.width,height=request.height))
        images=[{"jpeg":raw}]
    result=await gemini_service.chat(request.question,images,request.history,context)
    return {"protocolVersion":1,"model":result["model"],"text":result["text"],"action":None,"usage":result["usage"]}

@router.post("/transcribe")
async def transcribe_audio():
    raise HTTPException(501,"AUDIO_TRANSCRIPTION_NOT_SUPPORTED")
