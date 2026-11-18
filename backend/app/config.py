import os
from typing import List
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()

class Settings:
    PROJECT_NAME: str = "Netra Saathi Cloud AI"
    VERSION: str = "1.0.0"
    API_V1_PREFIX: str = "/api/v1"
    
    # Gemini API Configuration
    GEMINI_API_KEY: str = os.getenv("GEMINI_API_KEY", "")
    _configured_model: str = os.getenv("DEFAULT_GEMINI_MODEL", "gemini-3-flash-preview")
    # Automatically remap deprecated model names to active preview model
    DEFAULT_GEMINI_MODEL: str = "gemini-3-flash-preview" if "2.0-flash" in _configured_model or "1.5-flash" in _configured_model else _configured_model
    FALLBACK_GEMINI_MODEL: str = os.getenv("FALLBACK_GEMINI_MODEL", "gemini-2.5-flash")
    PRO_GEMINI_MODEL: str = os.getenv("PRO_GEMINI_MODEL", "gemini-2.5-pro")
    
    # CORS
    CORS_ORIGINS: List[str] = ["*"]

settings = Settings()
