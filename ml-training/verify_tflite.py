#!/usr/bin/env python3
"""
VisionAssist - Verification & Decoding Benchmark Script
Validates the exported YOLOv8 TFLite model:
- Checks tensor contracts ([1, 7, 2100])
- Executes box decoding [cx, cy, w, h] -> [x1, y1, x2, y2]
- Runs Non-Maximum Suppression (NMS)
- Benchmarks latency over N iterations
"""

import sys
import time
import argparse
import numpy as np
from pathlib import Path

def nms(boxes, scores, iou_threshold=0.45):
    """Standard Greedy Non-Maximum Suppression (NMS)."""
    if len(boxes) == 0:
        return []
    
    x1 = boxes[:, 0]
    y1 = boxes[:, 1]
    x2 = boxes[:, 2]
    y2 = boxes[:, 3]
    areas = (x2 - x1) * (y2 - y1)
    order = scores.argsort()[::-1]

    keep = []
    while order.size > 0:
        i = order[0]
        keep.append(i)
        if order.size == 1:
            break
        xx1 = np.maximum(x1[i], x1[order[1:]])
        yy1 = np.maximum(y1[i], y1[order[1:]])
        xx2 = np.minimum(x2[i], x2[order[1:]])
        yy2 = np.minimum(y2[i], y2[order[1:]])

        w = np.maximum(0.0, xx2 - xx1)
        h = np.maximum(0.0, yy2 - yy1)
        inter = w * h
        ovr = inter / np.maximum(areas[i] + areas[order[1:]] - inter, 1e-12)

        inds = np.where(ovr <= iou_threshold)[0]
        order = order[inds + 1]

    return keep

def decode_yolov8_output(raw_output, conf_threshold=0.45, iou_threshold=0.45, input_dim=320):
    """
    Decodes YOLOv8 TFLite raw output tensor of shape [1, 4 + classes, 2100]
    or [1, 2100, 4 + classes].
    """
    output = np.squeeze(raw_output)  # Shape: [7, 2100] or [2100, 7]
    if output.shape[0] < output.shape[1]:
        output = output.T  # Transpose to [2100, 7]

    boxes = []
    scores = []
    class_ids = []

    for row in output:
        if not np.all(np.isfinite(row)): continue
        cx, cy, w, h = row[:4]
        if w <= 0 or h <= 0: continue
        class_scores = row[4:]
        best_class = np.argmax(class_scores)
        best_score = class_scores[best_class]

        if best_score >= conf_threshold:
            x1 = (cx - w / 2) / input_dim
            y1 = (cy - h / 2) / input_dim
            x2 = (cx + w / 2) / input_dim
            y2 = (cy + h / 2) / input_dim
            boxes.append([x1, y1, x2, y2])
            scores.append(best_score)
            class_ids.append(best_class)

    if not boxes:
        return [], [], []

    boxes = np.array(boxes)
    scores = np.array(scores)
    class_ids = np.array(class_ids)

    indices = []
    for class_id in np.unique(class_ids):
        selected = np.flatnonzero(class_ids == class_id)
        indices.extend(selected[nms(boxes[selected], scores[selected], iou_threshold)].tolist())
    indices = sorted(indices, key=lambda i: float(scores[i]), reverse=True)[:20]
    return boxes[indices], scores[indices], class_ids[indices]

def test_tflite_model(model_path: str, image_path: str = None, runs: int = 50):
    try:
        import tensorflow as tf
    except ImportError:
        print("TensorFlow not installed. Run: pip install tensorflow")
        sys.exit(1)

    print(f"\n==================================================")
    print(f"🔍 Validating TFLite Model: {model_path}")
    print(f"==================================================")

    if not Path(model_path).exists():
        print(f"❌ Error: Model file not found at {model_path}")
        sys.exit(1)

    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()

    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    print("\n--- Tensor Contract ---")
    print(f"Input  Shape: {input_details[0]['shape']}, Dtype: {input_details[0]['dtype']}")
    print(f"Output Shape: {output_details[0]['shape']}, Dtype: {output_details[0]['dtype']}")

    assert len(input_details) == 1 and len(output_details) == 1, "Expected one input/output"
    input_shape = input_details[0]['shape']
    assert list(input_shape) == [1, 320, 320, 3], "Unsupported input tensor contract"
    label_path = Path(model_path).parent / "labels.txt"
    if not label_path.is_file(): raise ValueError("labels.txt must accompany the trained model")
    labels = [x.strip() for x in label_path.read_text(encoding="utf-8").splitlines() if x.strip()]
    assert labels, "Empty label mapping"
    assert list(output_details[0]["shape"]) in ([1, 4+len(labels), 2100], [1, 2100, 4+len(labels)]), "Unsupported output tensor contract"
    if not image_path or not Path(image_path).is_file():
        raise ValueError("A real evaluation image is required; random inputs cannot verify a detector")
    if runs < 1: raise ValueError("runs must be positive")
    input_dtype = input_details[0]['dtype']
    assert input_dtype in (np.float32, np.uint8, np.int8), "Unsupported input dtype"
    assert output_details[0]["dtype"] in (np.float32, np.uint8, np.int8), "Unsupported output dtype"
    if output_details[0]["dtype"] in (np.uint8, np.int8) and output_details[0]["quantization"][0] <= 0:
        raise ValueError("Invalid output quantization scale")
    input_dim = input_shape[1]

    if image_path and Path(image_path).exists():
        from PIL import Image
        img = Image.open(image_path).convert('RGB')
        scale = min(input_dim / img.width, input_dim / img.height)
        resized = img.resize((round(img.width * scale), round(img.height * scale)))
        padded = Image.new('RGB', (input_dim, input_dim), (114, 114, 114))
        padded.paste(resized, ((input_dim-resized.width)//2, (input_dim-resized.height)//2))
        img = padded
        input_data = np.array(img, dtype=np.float32) / 255.0
        input_data = np.expand_dims(input_data, axis=0)
    else:
        print("No image provided. Using simulated camera frame...")
        input_data = np.random.uniform(0, 1.0, size=input_shape).astype(np.float32)

    # De-quantize logic if model requires integer input
    if input_dtype in (np.uint8, np.int8):
        scale, zp = input_details[0]['quantization']
        if scale <= 0: raise ValueError("Invalid input quantization scale")
        input_data = np.clip(np.rint(input_data / scale + zp), np.iinfo(input_dtype).min, np.iinfo(input_dtype).max).astype(input_dtype)

    # Warmup
    for _ in range(3):
        interpreter.set_tensor(input_details[0]['index'], input_data)
        interpreter.invoke()

    # Latency Benchmark
    latencies = []
    last_output = None
    for _ in range(runs):
        t0 = time.perf_counter()
        interpreter.set_tensor(input_details[0]['index'], input_data)
        interpreter.invoke()
        last_output = interpreter.get_tensor(output_details[0]['index'])
        latencies.append((time.perf_counter() - t0) * 1000)

    # De-quantize output if quantized
    out_scale, out_zp = output_details[0]['quantization']
    if out_scale > 0:
        last_output = (last_output.astype(np.float32) - out_zp) * out_scale

    # Test Decoding + NMS
    t_decode_start = time.perf_counter()
    boxes, scores, classes = decode_yolov8_output(last_output, input_dim=input_dim)
    decode_time_ms = (time.perf_counter() - t_decode_start) * 1000

    avg_latency = np.mean(latencies)
    print("\n--- Benchmark & Decode Performance ---")
    print(f"Inference Latency: {avg_latency:.2f} ms")
    print(f"NMS Decoding Time: {decode_time_ms:.2f} ms")
    print(f"Total Pipeline:    {avg_latency + decode_time_ms:.2f} ms")
    print(f"Detections Found:  {len(boxes)}")

    for i in range(len(boxes)):
        lbl = labels[classes[i]] if classes[i] < len(labels) else f"class_{classes[i]}"
        print(f"  [{i+1}] {lbl.upper()}: {scores[i]:.2%} at {np.round(boxes[i], 3)}")

    if (avg_latency + decode_time_ms) <= 35.0:
        print("\n✅ PASS: Tensor and inference/decode benchmark under 35ms. Detection accuracy and real camera latency are not validated.")
    else:
        raise RuntimeError("Inference/decode exceeds the 35ms benchmark budget")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify VisionAssist YOLOv8 TFLite Model")
    parser.add_argument("--model", type=str, required=True, help="Path to .tflite model")
    parser.add_argument("--image", type=str, default=None, help="Path to optional test image")
    parser.add_argument("--runs", type=int, default=50, help="Benchmark iterations")
    args = parser.parse_args()

    test_tflite_model(args.model, args.image, args.runs)
