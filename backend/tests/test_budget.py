import asyncio
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, patch
from app.config import settings
from app.security import reserve_attempt
from app.services.gemini_service import GeminiService

class BudgetTest(unittest.TestCase):
    def test_paid_attempt_limit_survives_reopening_database(self):
        with tempfile.TemporaryDirectory() as folder:
            with patch.object(settings,"QUOTA_DATABASE",str(Path(folder)/"quota.db")),patch.object(settings,"DAILY_AI_ATTEMPTS",1):
                asyncio.run(reserve_attempt())
                with self.assertRaisesRegex(RuntimeError,"AI_BUDGET_EXHAUSTED"):
                    asyncio.run(reserve_attempt())
    def test_provider_concurrency_is_bounded(self):
        async def run():
            active=0;maximum=0
            async def generate(**kwargs):
                nonlocal active,maximum
                active+=1;maximum=max(maximum,active)
                try:
                    await asyncio.sleep(.02)
                    return SimpleNamespace(text="Evidence is unavailable.",usage_metadata=None)
                finally:active-=1
            client=SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=generate)))
            service=GeminiService()
            with patch.object(settings,"DEFAULT_GEMINI_MODEL","gemini-test"),patch.object(settings,"FALLBACK_GEMINI_MODEL",""),patch.object(service,"_client",return_value=client),patch("app.services.gemini_service.reserve_attempt",new=AsyncMock()):
                await asyncio.gather(*[service._generate(["test"]) for _ in range(8)])
            self.assertEqual(maximum,4)
        asyncio.run(run())
