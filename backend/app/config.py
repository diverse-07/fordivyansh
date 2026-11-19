import json
import os
from dotenv import load_dotenv
load_dotenv()

class Settings:
    PROJECT_NAME = "Netra Saathi Cloud AI"
    VERSION = "1.1.0"
    API_V1_PREFIX = "/api/v1"
    GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")
    DEFAULT_GEMINI_MODEL = os.getenv("DEFAULT_GEMINI_MODEL", "")
    FALLBACK_GEMINI_MODEL = os.getenv("FALLBACK_GEMINI_MODEL", "")
    API_ACCESS_TOKEN = os.getenv("API_ACCESS_TOKEN", "")
    CORS_ORIGINS = json.loads(os.getenv("CORS_ORIGINS", '["http://localhost:3000"]'))
    MAX_BODY_BYTES = 1200000
    PROVIDER_TIMEOUT_SECONDS = 15
    DAILY_AI_ATTEMPTS = int(os.getenv("DAILY_AI_ATTEMPTS", "100"))
    QUOTA_DATABASE = os.getenv("QUOTA_DATABASE", ".runtime/quota.sqlite3")
    @property
    def gemini_configured(self):
        return bool(self.GEMINI_API_KEY and self.GEMINI_API_KEY != "your_gemini_api_key_here" and self.DEFAULT_GEMINI_MODEL)
settings = Settings()
