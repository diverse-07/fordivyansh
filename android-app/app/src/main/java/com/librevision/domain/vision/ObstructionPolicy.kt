package com.librevision.domain.vision

/** A vocabulary is not model coverage. Unsupported examples remain explicitly separate. */
object ObstructionCatalog {
    val supported =
        setOf(
            "person",
            "bicycle",
            "car",
            "motorcycle",
            "airplane",
            "bus",
            "train",
            "truck",
            "boat",
            "fire hydrant",
            "parking meter",
            "bench",
            "bird",
            "cat",
            "dog",
            "horse",
            "sheep",
            "cow",
            "elephant",
            "bear",
            "zebra",
            "giraffe",
            "backpack",
            "umbrella",
            "handbag",
            "suitcase",
            "frisbee",
            "skis",
            "snowboard",
            "sports ball",
            "kite",
            "baseball bat",
            "baseball glove",
            "skateboard",
            "surfboard",
            "tennis racket",
            "bottle",
            "wine glass",
            "cup",
            "fork",
            "knife",
            "spoon",
            "bowl",
            "chair",
            "couch",
            "potted plant",
            "bed",
            "dining table",
            "toilet",
            "tv",
            "laptop",
            "mouse",
            "remote",
            "keyboard",
            "cell phone",
            "microwave",
            "oven",
            "toaster",
            "sink",
            "refrigerator",
            "book",
            "clock",
            "vase",
            "scissors",
            "teddy bear",
            "hair drier",
            "toothbrush",
        )
    val specializedHazards =
        setOf(
            "pothole",
            "crater",
            "manhole",
            "open_manhole",
            "trench",
            "construction_trench",
            "crack",
            "drop-off",
            "open drain",
            "tree branch",
            "tree_branch",
            "branch",
            "awning",
            "hanging_wire",
            "overhanging_sign",
        )
    val unsupported =
        setOf(
            "door",
            "crosswalk",
            "tactile paving",
            "rickshaw",
            "auto rickshaw",
            "road barrier",
            "construction debris",
            "brick pile",
            "sand pile",
            "utility pole",
            "rope",
            "tree trunk",
            "street vendor cart",
            "wheelchair",
            "stroller",
            "bollard",
            "broken pavement",
            "uneven pavement",
            "mud",
            "standing water",
            "fence",
            "gate",
        )
}

data class NormalizedBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun valid() =
        listOf(left, top, right, bottom).all { it.isFinite() } && right > left && bottom > top

    fun overlapsCautionRegion(): Boolean {
        if (!valid()) return false
        // Three full-height vertical strips: 25% | 50% | 25%.
        if (right <= 0f || left >= 1f || bottom <= 0f || top >= 1f) return false
        val center = (left + right) / 2f
        return center >= .25f && center < .75f
    }

    fun intersectsCautionRegion(): Boolean =
        valid() && right > .25f && left < .75f && bottom > 0f && top < 1f
}

object TrafficObjects {
    val labels = setOf("bicycle", "motorcycle", "car", "bus", "truck", "train")

    fun contains(label: String) = label in labels
}

/**
 * Reporting policy is deliberately narrower than detector coverage. These are tunable heuristics.
 */
data class ObstructionRule(
    val minimumArea: Float,
    val minimumHeight: Float,
    val minimumBottom: Float,
    val importance: Float,
    val minimumConfidence: Float = .60f,
)

class ObstructionPolicy {
    companion object {
        val rules: Map<String, ObstructionRule> = buildMap {
            // A small, distant vehicle must warn as soon as RF-DETR detects it in the corridor.
            // Area, vertical position and indoor monocular depth are not stopping-distance
            // measures.
            val traffic = ObstructionRule(0f, 0f, 0f, 3f, .45f)
            TrafficObjects.labels.forEach { put(it, traffic) }
            val pedestrian = ObstructionRule(.035f, .24f, .60f, 2.5f)
            put("person", pedestrian)
            val animal = ObstructionRule(.025f, .16f, .60f, 2.5f)
            listOf("cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe")
                .forEach { put(it, animal) }
            val furniture = ObstructionRule(.08f, .25f, .65f, 2f)
            listOf(
                    "bench",
                    "chair",
                    "couch",
                    "bed",
                    "dining table",
                    "potted plant",
                    "fire hydrant",
                    "parking meter",
                    "refrigerator",
                )
                .forEach { put(it, furniture) }
            val bulky = ObstructionRule(.05f, .20f, .70f, 1.5f)
            listOf("backpack", "suitcase", "skateboard").forEach { put(it, bulky) }
            // Road & Ground Hazards: Potholes, road craters, open manholes, construction trenches, cracks
            val groundHazard = ObstructionRule(0.005f, 0.04f, 0.35f, 3.8f, 0.25f)
            listOf(
                "pothole", "crater", "manhole", "open_manhole", "trench",
                "construction_trench", "crack", "drop-off", "open drain"
            ).forEach { put(it, groundHazard) }
            // Head-Height Hazards: Overhanging tree branches, low signs, awnings, hanging wires
            val headHazard = ObstructionRule(0.005f, 0.04f, 0.15f, 3.2f, 0.25f)
            listOf(
                "tree branch", "tree_branch", "branch", "awning", "hanging_wire", "overhanging_sign"
            ).forEach { put(it, headHazard) }
        }
    }

    fun select(detections: List<Detection>): List<Detection> =
        detections
            .mapNotNull { detection ->
                val rule = rules[detection.label] ?: return@mapNotNull null
                val box = detection.bounds ?: return@mapNotNull null
                val inCorridor =
                    if (TrafficObjects.contains(detection.label)) box.intersectsCautionRegion()
                    else box.overlapsCautionRegion()
                if (
                    !detection.confidence.isFinite() ||
                        detection.confidence < rule.minimumConfidence ||
                        !inCorridor
                )
                    return@mapNotNull null
                val width =
                    (box.right.coerceIn(0f, 1f) - box.left.coerceIn(0f, 1f)).coerceAtLeast(0f)
                val height =
                    (box.bottom.coerceIn(0f, 1f) - box.top.coerceIn(0f, 1f)).coerceAtLeast(0f)
                val area = width * height
                if (
                    area <= 0f ||
                        area < rule.minimumArea ||
                        height < rule.minimumHeight ||
                        box.bottom.coerceIn(0f, 1f) < rule.minimumBottom
                )
                    return@mapNotNull null
                detection to area * rule.importance
            }
            .sortedWith(
                compareByDescending<Pair<Detection, Float>> {
                        TrafficObjects.contains(it.first.label)
                    }
                    .thenByDescending { it.second }
                    .thenByDescending { it.first.confidence }
                    .thenBy { it.first.label }
            )
            .map { it.first }
            .distinctBy { it.label }

    fun stopMessage(detections: List<Detection>): String? =
        select(detections).firstOrNull()?.let { "Stop, ${it.label} ahead." }
}
