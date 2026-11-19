"""Compatibility entry point; canonical implementation is /ml-training."""
from pathlib import Path
import runpy
if __name__ == "__main__":
    runpy.run_path(str(Path(__file__).resolve().parents[2] / "ml-training" / "verify_tflite.py"), run_name="__main__")
