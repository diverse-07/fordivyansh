#!/usr/bin/env python3
"""
VisionAssist - Production Training & INT8 Quantization Script for YOLOv8
Target: Edge mobile inference (Flutter TFLite) for blind pedestrian safety.
"""

import os
import sys
import argparse
import logging
from pathlib import Path
import shutil

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("VisionAssist-Trainer")

def verify_environment():
    try:
        import torch
        import ultralytics
        import tensorflow as tf
        logger.info(f"PyTorch version: {torch.__version__} (CUDA available: {torch.cuda.is_available()})")
        logger.info(f"Ultralytics version: {ultralytics.__version__}")
        logger.info(f"TensorFlow version: {tf.__version__}")
    except ImportError as e:
        logger.error(f"Missing dependency: {e}")
        logger.error("Run: pip install ultralytics tensorflow torch")
        sys.exit(1)

def train_model(data_yaml: str, epochs: int = 50, imgsz: int = 320, batch: int = 32):
    from ultralytics import YOLO
    import torch

    logger.info("Initializing YOLOv8n backbone...")
    model = YOLO("yolov8n.pt")

    logger.info(f"Starting training for {epochs} epochs at imgsz={imgsz}...")
    results = model.train(
        data=data_yaml,
        epochs=epochs,
        imgsz=imgsz,
        batch=batch,
        patience=12,
        save=True,
        save_period=10,
        device=0 if torch.cuda.is_available() else "cpu",
        workers=4,
        project="runs_vision_assist",
        name="pothole_detector",
        exist_ok=True,
        optimizer="AdamW",
        lr0=0.001,
        lrf=0.01,
        warmup_epochs=3,
        plots=True
    )
    
    best_weights = Path("runs_vision_assist/pothole_detector/weights/best.pt")
    if not best_weights.exists():
        raise FileNotFoundError(f"Expected best weights at {best_weights}, but not found.")
    
    logger.info(f"Training completed successfully! Best weights: {best_weights}")
    return best_weights

def export_to_int8_tflite(model_path: Path, imgsz: int = 320, data_yaml: str = "data.yaml"):
    """
    Exports PyTorch model to TFLite format with INT8 quantization.
    Ultralytics handles representative dataset sampling using the data.yaml calibration images.
    """
    from ultralytics import YOLO

    logger.info(f"Loading trained weights from {model_path} for export...")
    model = YOLO(str(model_path))

    logger.info("Exporting to TFLite with INT8 quantization & representative calibration...")
    # int8=True invokes representative dataset calibration against the validation/train set in data.yaml
    export_result = model.export(
        format="tflite",
        int8=True,
        imgsz=imgsz,
        data=data_yaml,
        optimize=True
    )
    
    logger.info(f"Export successful. Artifact generated at: {export_result}")
    return export_result

def generate_labels_file(output_dir: Path, model_path: Path):
    from ultralytics import YOLO
    names = YOLO(str(model_path)).names
    labels = [names[i] for i in range(len(names))] if isinstance(names, dict) else list(names)
    if not labels or any(not isinstance(label, str) or not label.strip() for label in labels):
        raise ValueError("Invalid trained class mapping")
    labels_file = output_dir / "labels.txt"
    with open(labels_file, "w", encoding="utf-8") as f:
        for label in labels:
            f.write(f"{label}\n")
    logger.info(f"Labels specification written to: {labels_file}")

def main():
    parser = argparse.ArgumentParser(description="Train and export VisionAssist Pothole Detector")
    parser.add_argument("--data", type=str, default="data.yaml", help="Path to data.yaml dataset config")
    parser.add_argument("--epochs", type=int, default=50, help="Number of training epochs")
    parser.add_argument("--imgsz", type=int, default=320, help="Inference image size (320 recommended for edge)")
    parser.add_argument("--batch", type=int, default=32, help="Batch size")
    parser.add_argument("--skip-train", action="store_true", help="Skip training and export existing best.pt")
    args = parser.parse_args()

    verify_environment()

    if not args.skip_train:
        if not os.path.exists(args.data):
            logger.error(f"Cannot find dataset configuration file: {args.data}")
            logger.info("Ensure you have downloaded the dataset or generated data.yaml.")
            sys.exit(1)
        best_pt = train_model(args.data, epochs=args.epochs, imgsz=args.imgsz, batch=args.batch)
    else:
        best_pt = Path("runs_vision_assist/pothole_detector/weights/best.pt")
        if not best_pt.exists():
            logger.error(f"Cannot skip train: {best_pt} does not exist.")
            sys.exit(1)

    export_path = export_to_int8_tflite(best_pt, imgsz=args.imgsz, data_yaml=args.data)
    # Export the Android ONNX contract from the same weights and preserve labels/checksum.
    from ultralytics import YOLO
    import hashlib
    import json
    onnx_path = Path(YOLO(str(best_pt)).export(format="onnx", imgsz=args.imgsz, opset=17, dynamic=False))
    manifest = {"format": "onnx", "inputSize": args.imgsz,
                "sha256": hashlib.sha256(onnx_path.read_bytes()).hexdigest(),
                "validatedForNavigation": False}
    onnx_path.with_suffix(".json").write_text(json.dumps(manifest, indent=2))
    artifact = Path(export_path)
    generate_labels_file(artifact if artifact.is_dir() else artifact.parent, best_pt)
    generate_labels_file(onnx_path.parent, best_pt)

    logger.info("All steps complete. Model is ready for model evaluation; it is not automatically enabled in the Android app.")

if __name__ == "__main__":
    main()
