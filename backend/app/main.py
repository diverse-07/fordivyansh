import logging
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.config import settings
from app.api.v1.endpoints import router as api_v1_router
from app.api.v1.librevision_gateway import router as librevision_router

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("NetraSaathi-Main")

app = FastAPI(
    title=settings.PROJECT_NAME,
    version=settings.VERSION,
    description="Cloud AI Backend for Netra Saathi Blind Navigation Platform (Powered by Google Gemini 3 Flash)"
)

# Setup CORS for mobile app & caretaker dashboard
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.CORS_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Mount Netra Saathi Native API Router (/api/v1/...)
app.include_router(api_v1_router, prefix=settings.API_V1_PREFIX)

# Mount Netra Saathi Gateway Endpoints at root (/analyze, /assistant, /transcribe)
app.include_router(librevision_router)

@app.get("/")
def root():
    return {
        "service": settings.PROJECT_NAME,
        "version": settings.VERSION,
        "status": "online",
        "cloud_ai": "Google Gemini 3 Flash",
        "docs": "/docs"
    }

@app.get("/health")
def health_check():
    return {
        "status": "healthy",
        "gemini_configured": bool(settings.GEMINI_API_KEY),
        "gateway_compatibility": "Netra Saathi Gateway v1.0"
    }
