# 📐 VisionAssist Model Specification & Tensor Contract (YOLOv8 Edge)

**Model:** `pothole_yolov8n_int8.tflite`  
**Architecture:** YOLOv8-Nano decoupled detection head  
**Target:** Edge Mobile Inference (<35ms on mobile NPU / CPU)

---

## 1. Input Tensor Specification

| Tensor Index | Name | Shape | Data Type | Value Range | Normalization |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **0** | `serving_default_images:0` | `[1, 320, 320, 3]` | `float32` (or quantized uint8) | `[0.0, 1.0]` | `pixel / 255.0` |

* **Color Format:** RGB (Android camera YUV420 / iOS BGRA must be converted to RGB).
* **Resize Strategy:** Letterbox (maintain aspect ratio with padding color `(114, 114, 114)`).

---

## 2. Output Tensor Specification (Decoupled YOLO Head)

| Tensor Index | Shape | Total Elements | Layout Breakdown |
| :--- | :--- | :--- | :--- |
| **0** | `[1, 7, 2100]` | 14,700 | `[Batch, 4 (box) + 3 (classes), 2100 Anchors]` |

### Anchor Grid Calculation ($320 \times 320$ Input):
- Scale 1 (Stride 8): $40 \times 40 = 1600$ anchors
- Scale 2 (Stride 16): $20 \times 20 = 400$ anchors
- Scale 3 (Stride 32): $10 \times 10 = 100$ anchors
- **Total:** $1600 + 400 + 100 = \mathbf{2100\text{ predictions}}$

---

## 3. Decoding & Non-Maximum Suppression (NMS)

Each anchor $i \in [0, 2099]$ contains 7 values:
```
row 0: Center X (cx) in pixels [0, 320]
row 1: Center Y (cy) in pixels [0, 320]
row 2: Width    (w)  in pixels [0, 320]
row 3: Height   (h)  in pixels [0, 320]
row 4: Score for Class 0 (pothole)
row 5: Score for Class 1 (crack)
row 6: Score for Class 2 (manhole)
```

### Box Conversion:
$$\text{xmin} = \frac{cx - w / 2}{320}, \quad \text{ymin} = \frac{cy - h / 2}{320}$$
$$\text{xmax} = \frac{cx + w / 2}{320}, \quad \text{ymax} = \frac{cy + h / 2}{320}$$

### NMS Parameters for Blind Pedestrian Safety:
- **Score Threshold:** $\ge 0.45$ (filters out background noise)
- **IoU Threshold:** $0.45$ (merges overlapping anchor predictions)
- **Top Detections Retained:** 20 (avoids unnecessary CPU looping on mobile)

---

## 4. Class Label Mapping (`labels.txt`)
- `0`: **`pothole`**
- `1`: **`crack`**
- `2`: **`manhole`**
