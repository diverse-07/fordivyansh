import unittest
import io
from PIL import Image
from starlette.testclient import TestClient
from app.main import app

class TestVisionAssistAPI(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def test_health_check(self):
        response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "healthy")

    def test_root_endpoint(self):
        response = self.client.get("/")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "online")

    def test_scene_analyze_endpoint(self):
        # Create a dummy test image
        img = Image.new("RGB", (320, 320), color=(73, 109, 137))
        img_byte_arr = io.BytesIO()
        img.save(img_byte_arr, format='JPEG')
        img_byte_arr.seek(0)

        response = self.client.post(
            "/api/v1/scene/analyze",
            files={"image": ("test.jpg", img_byte_arr, "image/jpeg")}
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "success")
        self.assertIn("speech_summary", data["data"])

    def test_chat_endpoint(self):
        response = self.client.post(
            "/api/v1/chat/message",
            json={"question": "What is directly in front of me?"}
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "success")
        self.assertIn("response", data)

    def test_sos_endpoint(self):
        response = self.client.post(
            "/api/v1/emergency/sos",
            json={
                "user_id": "test_user_01",
                "latitude": 12.9716,
                "longitude": 77.5946,
                "trigger_reason": "manual_triple_tap"
            }
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "sos_acknowledged")

if __name__ == "__main__":
    unittest.main()
