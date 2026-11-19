import logging
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from app.config import settings
from app.security import SecurityMiddleware
from app.services.gemini_service import ProviderUnavailable, gemini_service
from app.api.v1.endpoints import router
from app.api.v1.librevision_gateway import router as gateway

app=FastAPI(title=settings.PROJECT_NAME,version=settings.VERSION)
app.add_middleware(SecurityMiddleware)
app.add_middleware(CORSMiddleware,allow_origins=settings.CORS_ORIGINS,allow_credentials=False,
    allow_methods=["GET","POST","OPTIONS"],allow_headers=["Content-Type","Authorization"])
app.include_router(router,prefix=settings.API_V1_PREFIX)
app.include_router(gateway)
@app.exception_handler(ProviderUnavailable)
async def provider_error(request: Request,error: ProviderUnavailable):
    return JSONResponse({"detail":str(error)},status_code=503,headers={"Cache-Control":"no-store"})
@app.exception_handler(Exception)
async def internal_error(request: Request,error: Exception):
    logging.getLogger("NetraSaathi").exception("Request failed")
    return JSONResponse({"detail":"INTERNAL_ERROR"},status_code=500)
@app.get("/")
def root():
    return {"service":settings.PROJECT_NAME,"version":settings.VERSION,"status":"online","docs":"/docs"}
@app.get("/health")
def health():
    configured=gemini_service.has_key and bool(settings.API_ACCESS_TOKEN)
    return {"status":"ready" if configured else "unavailable","configured":configured,
        "protocolVersion":1,"provider":"gemini",
        "capabilities":{"scene":configured,"assistant":configured,"assistantActions":False,
            "transcription":False,"guardianNotifications":False,"telemetry":False}}
