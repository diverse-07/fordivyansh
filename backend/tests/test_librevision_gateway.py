import unittest
import base64
import io
from PIL import Image
from starlette.testclient import TestClient
from app.main import app

class TestLibreVisionGateway(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.client = TestClient(app)

    def _create_sample_base64_image(self):
        img = Image.new("RGB", (64, 64), color=(255, 0, 0))
        buffer = io.BytesIO()
        img.save(buffer, format="JPEG")
        encoded = base64.b64encode(buffer.getvalue()).decode("utf-8")
        return f"data:image/jpeg;base64,{encoded}"

    def test_librevision_analyze_endpoint(self):
        img_b64 = self._create_sample_base64_image()
        response = self.client.post(
            "/analyze",
            json={
                "task": "DESCRIBE",
                "model": "muse-spark-1.3",
                "image": img_b64,
                "question": "What hazards are in front of me?",
                "width": 64,
                "height": 64
            }
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["model"], "muse-spark-1.3")
        self.assertIn("text", data)
        print("\n[Analyze Output from Gemini Backend]:", data["text"])

    def test_librevision_assistant_surroundings_endpoint(self):
        img_b64 = self._create_sample_base64_image()
        response = self.client.post(
            "/assistant",
            json={
                "question": "Describe my surroundings",
                "views": [
                    {"image": img_b64, "width": 64, "height": 64},
                    {"image": img_b64, "width": 64, "height": 64}
                ],
                "history": []
            }
        )
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["model"], "muse-spark-1.3")
        self.assertIn("text", data)
        print("\n[Assistant Output from Gemini Backend]:", data["text"])

if __name__ == "__main__":
    unittest.main()
