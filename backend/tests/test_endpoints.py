import asyncio
import io
import time
import unittest
from unittest.mock import AsyncMock, patch
from PIL import Image
from starlette.testclient import TestClient
from app.main import app
from app.config import settings
from app.services.gemini_service import gemini_service, ProviderUnavailable, GeminiService, SceneResult
import httpx

RESULT={"text":"An object is visible.","data":{"speech_summary":"An object is visible.","hazards":[],"visibility":"unknown"},"model":"gemini-test","usage":{}}
class APITest(unittest.TestCase):
    def setUp(self):
        self.token=patch.object(settings,"API_ACCESS_TOKEN","test-only-token");self.token.start()
        self.addCleanup(self.token.stop)
        app.middleware_stack=None
        self.client=TestClient(app,headers={"Authorization":"Bearer test-only-token"},raise_server_exceptions=False)
    def image(self):
        buf=io.BytesIO();Image.new("RGB",(64,64),"red").save(buf,format="JPEG")
        return buf.getvalue()
    def test_health_is_not_ready_without_a_real_key(self):
        with patch.object(settings,"GEMINI_API_KEY","your_gemini_api_key_here"):
            d=self.client.get("/health").json()
            self.assertFalse(d["configured"]);self.assertFalse(d["capabilities"]["guardianNotifications"])
    def test_root_endpoint(self):
        self.assertEqual(self.client.get("/").json()["status"],"online")
    def test_chat_endpoint(self):
        with patch.object(gemini_service,"chat",new=AsyncMock(return_value=RESULT)) as call:
            r=self.client.post("/api/v1/chat/message",json={"question":"Describe evidence"})
            self.assertEqual(r.status_code,200);self.assertEqual(r.json()["response"],RESULT["text"])
            call.assert_awaited_once()
    def test_scene_analyze_endpoint(self):
        with patch.object(gemini_service,"analyze_scene",new=AsyncMock(return_value=RESULT)):
            r=self.client.post("/api/v1/scene/analyze",files={"image":("x.jpg",self.image(),"image/jpeg")})
            self.assertEqual(r.status_code,200);self.assertIn("speech_summary",r.json()["data"])
    def test_sos_does_not_claim_notification_delivery(self):
        r=self.client.post("/api/v1/emergency/sos",json={"user_id":"test","latitude":12,"longitude":77})
        self.assertEqual(r.status_code,503);self.assertNotIn("broadcast",r.text.lower())
    def test_invalid_sos_coordinates_rejected(self):
        r=self.client.post("/api/v1/emergency/sos",json={"user_id":"test","latitude":999,"longitude":999})
        self.assertEqual(r.status_code,422)
    def test_access_required(self):
        self.assertEqual(self.client.post("/api/v1/chat/message",json={"question":"hello"},headers={"Authorization":"invalid"}).status_code,401)
        with patch.object(settings,"API_ACCESS_TOKEN",""):
            self.assertEqual(self.client.post("/api/v1/chat/message",json={"question":"hello"}).status_code,503)
    def test_body_and_image_limits(self):
        self.assertEqual(self.client.post("/assistant",content=b"x"*1200001).status_code,413)
        self.assertEqual(self.client.post("/api/v1/scene/analyze",files={"image":("x.jpg",b"bad","image/jpeg")}).status_code,422)
    def test_question_limits(self):
        for text in (""," ","a"*501):
            self.assertEqual(self.client.post("/api/v1/chat/message",json={"question":text}).status_code,422)
    def test_outage_and_ocr_failure_are_explicit(self):
        for method,path,kwargs in [
            ("chat","/api/v1/chat/message",{"json":{"question":"Is it safe?"}}),
            ("smart_ocr","/api/v1/ocr/smart-read",{"files":{"image":("x.jpg",self.image(),"image/jpeg")}})]:
            with patch.object(gemini_service,method,new=AsyncMock(side_effect=ProviderUnavailable("AI_UNAVAILABLE"))):
                r=self.client.post(path,**kwargs)
                self.assertEqual(r.status_code,503);self.assertNotIn("clear",r.text)
    def test_corrupt_json_has_no_internal_exception(self):
        for body in ("{","null","[]"):
            r=self.client.post("/assistant",content=body,headers={"Content-Type":"application/json"})
            self.assertEqual(r.status_code,422)
            self.assertNotIn("Traceback",r.text);self.assertNotIn("NoneType",r.text)
    def test_async_calls_do_not_serialize(self):
        async def run():
            async def delayed(*a,**kw):
                await asyncio.sleep(.2);return RESULT
            with patch.object(gemini_service,"chat",side_effect=delayed):
                async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url="http://test",headers={"Authorization":"Bearer test-only-token"}) as c:
                    start=time.monotonic()
                    res=await asyncio.gather(*[c.post("/api/v1/chat/message",json={"question":"test"}) for _ in range(2)])
                    self.assertTrue(all(x.status_code==200 for x in res))
                    self.assertLess(time.monotonic()-start,.36)
        asyncio.run(run())
    def test_placeholder_key_never_produces_mock_hazards(self):
        with patch.object(settings,"GEMINI_API_KEY","your_gemini_api_key_here"):
            r=self.client.post("/api/v1/chat/message",json={"question":"hello"})
            self.assertEqual(r.status_code,503)
    def test_rate_limit_is_enforced(self):
        with patch.object(gemini_service,"chat",new=AsyncMock(return_value=RESULT)):
            codes=[self.client.post("/api/v1/chat/message",json={"question":"test"}).status_code for _ in range(37)]
            self.assertEqual(codes[-1],429)
    def test_schema_rejects_incomplete_model_response(self):
        from types import SimpleNamespace
        async def run():
            service=GeminiService()
            client=SimpleNamespace(aio=SimpleNamespace(models=SimpleNamespace(generate_content=AsyncMock(return_value=SimpleNamespace(text="{}")))))
            with patch.object(settings,"DEFAULT_GEMINI_MODEL","gemini-test"),patch.object(service,"_client",return_value=client),patch("app.services.gemini_service.reserve_attempt",new=AsyncMock()):
                with self.assertRaises(ProviderUnavailable):
                    await service._generate(["test"],SceneResult)
        asyncio.run(run())
