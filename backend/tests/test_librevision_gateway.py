import base64
import unittest
from unittest.mock import AsyncMock,patch
import test_endpoints as base
from test_endpoints import RESULT
from app.services.gemini_service import gemini_service

class GatewayTest(unittest.TestCase):
    setUp = base.APITest.setUp
    image = base.APITest.image
    def view(self,offset=0):
        return {"image":"data:image/jpeg;base64,"+base64.b64encode(self.image()).decode(),"width":64,"height":64,"offsetMs":offset,"ageMs":1000}
    def test_analyze_forwards_question_and_context(self):
        request=self.view();request.update(task="VISUAL_QUESTION",model="muse-spark-1.3",question="What is written?",routeContext="Mapped turn",detections=[])
        with patch.object(gemini_service,"analyze_scene",new=AsyncMock(return_value=RESULT)) as call:
            r=self.client.post("/analyze",json=request)
            self.assertEqual(r.status_code,200);self.assertEqual(r.json()["model"],"gemini-test")
            self.assertEqual(call.call_args.args[1: ],("What is written?","Mapped turn"))
            self.assertNotIn("latencyMs",r.json()["usage"])
    def test_sweep_forwards_all_frames_and_history(self):
        request={"question":"Describe saved views","context":"SCENE","history":[{"role":"user","text":"previous"}],
            "views":[self.view(i*100) for i in range(10)],
            "surroundings":{"source":"video","ageMs":1000,"durationMs":2000,"initial":True}}
        with patch.object(gemini_service,"chat",new=AsyncMock(return_value=RESULT)) as call:
            r=self.client.post("/assistant",json=request)
            self.assertEqual(r.status_code,200)
            self.assertEqual(len(call.call_args.args[1]),10)
            self.assertEqual(call.call_args.args[2][0].text,"previous")
            self.assertIn("SCENE",call.call_args.args[3])
        request["views"][1]["offsetMs"]=0
        self.assertEqual(self.client.post("/assistant",json=request).status_code,422)
    def test_invalid_sweep_is_not_text_only_fallback(self):
        self.assertEqual(self.client.post("/assistant",json={"question":"test","views":[self.view()]}).status_code,422)
    def test_transcription_is_explicitly_unsupported(self):
        self.assertEqual(self.client.post("/transcribe",content=b"audio").status_code,501)
