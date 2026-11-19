# 🧠 VisionAssist ML Training Pipeline

This folder contains the complete pipeline to train, calibrate, and export the custom road hazard detection model.

---

## Files Overview

| File | Purpose |
| :--- | :--- |
| **`train_pothole_yolov8.ipynb`** | Standalone interactive Google Colab notebook (one-click GPU training). |
| **`train_and_export.py`** | Production Python training script with representative INT8 calibration. |
| **`verify_tflite.py`** | Verification and benchmark tool for testing latency and tensor contracts. |
| **`MODEL_SPEC.md`** | Strict tensor contract specification for Flutter mobile integration. |

---

## How to Run (100% Free on Google Colab)

1. Open [Google Colab](https://colab.research.google.com).
2. Click **File > Upload Notebook** and select [`train_pothole_yolov8.ipynb`](./train_pothole_yolov8.ipynb).
3. Set runtime to **T4 GPU**:
   - `Runtime > Change runtime type > T4 GPU > Save`.
4. Run all cells (`Runtime > Run all`).
5. In ~25 minutes:
   - The model trains on road hazards.
   - It performs INT8 representative calibration.
   - It tests benchmark latency.
   - It downloads `best_int8.tflite` and `labels.txt` directly to your computer.

---

## Verifying Locally

Once you have the `.tflite` file:
```bash
python verify_tflite.py --model path/to/best_int8.tflite --runs 100
```
Expected output:
- **Model Size:** ~3.5MB to 4.0MB
- **Latency on Modern CPU/NPU:** ~18ms – 32ms
- **Memory Footprint:** < 25MB RAM


## Bug-fix pass

Canonical scripts are in this root ml-training directory; android-app/ml-training contains
compatibility entry points. Export derives labels from the trained model and emits ONNX
alongside TFLite with a checksum manifest. Models are not automatically enabled in Android.
The verifier requires a real image, matching labels.txt, valid tensor contracts and a 35ms
inference/decode budget. It does not validate outdoor accuracy or full camera-to-speech latency.
Unit fixtures cover CPU selection, class-aware NMS and trained label ordering. No new training
run or ground-truth evaluation was performed during this bug-fix pass.
