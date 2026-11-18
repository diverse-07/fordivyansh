package com.librevision.data.vision.rfdetr

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Normalized boxes refer to the upright, cropped preview image. */
data class Detection(
    val classId: Int,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

object DetectionMath {
    const val SIZE = 384
    const val PROPOSALS = 576
    const val QUERIES = 300
    const val CLASSES = 91

    fun selectReferences(classes: FloatArray, coordinates: FloatArray): FloatArray {
        require(classes.size == PROPOSALS * CLASSES && coordinates.size == PROPOSALS * 4)
        val scores = FloatArray(PROPOSALS)
        for (p in 0 until PROPOSALS) {
            var best = -Float.MAX_VALUE
            for (c in 0 until CLASSES) best = max(best, classes[p * CLASSES + c])
            scores[p] = best
        }
        val order =
            (0 until PROPOSALS).sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
        return FloatArray(QUERIES * 4) { coordinates[order[it / 4] * 4 + it % 4] }
    }

    fun decode(boxes: FloatArray, logits: FloatArray, threshold: Float = .45f): List<Detection> {
        require(boxes.size == QUERIES * 4 && logits.size == QUERIES * CLASSES)
        check(boxes.all { it.isFinite() } && logits.all { it.isFinite() }) {
            "Non-finite model output"
        }
        val candidates = ArrayList<Detection>()
        for (q in 0 until QUERIES) {
            var best = -Float.MAX_VALUE
            var cls = 0
            for (c in 0 until CLASSES) {
                val value = logits[q * CLASSES + c]
                if (value > best) {
                    best = value
                    cls = c
                }
            }
            val score = (1.0 / (1.0 + exp(-best.toDouble()))).toFloat()
            if (score < threshold || CocoLabels.names[cls].isBlank()) continue
            val cx = boxes[q * 4]
            val cy = boxes[q * 4 + 1]
            val w = boxes[q * 4 + 2]
            val h = boxes[q * 4 + 3]
            if (w <= 0 || h <= 0) continue
            candidates += Detection(cls, score, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        }
        val kept = ArrayList<Detection>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (kept.none { it.classId == candidate.classId && iou(it, candidate) > .6f })
                kept += candidate
        }
        return kept.take(30)
    }

    fun iou(a: Detection, b: Detection): Float {
        val intersection =
            max(0f, min(a.right, b.right) - max(a.left, b.left)) *
                max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val union =
            (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) -
                intersection
        return if (union > 0) intersection / union else 0f
    }
}
