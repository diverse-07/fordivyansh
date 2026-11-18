package com.librevision.data.vision.rfdetr

import org.junit.Assert.*
import org.junit.Test

class DetectionMathTest {
    @Test
    fun querySelectionGathersRawCoordinatesInScoreOrder() {
        val classes = FloatArray(576 * 91) { -20f }
        val coordinates = FloatArray(576 * 4) { it.toFloat() }
        classes[513 * 91 + 2] = 8f
        classes[42 * 91 + 10] = 9f
        val result = DetectionMath.selectReferences(classes, coordinates)
        assertArrayEquals(floatArrayOf(168f, 169f, 170f, 171f), result.copyOfRange(0, 4), 0f)
        assertArrayEquals(floatArrayOf(2052f, 2053f, 2054f, 2055f), result.copyOfRange(4, 8), 0f)
    }

    @Test
    fun suppressionPreservesOverlappingDifferentClassesAndSkipsUnusedIds() {
        val boxes = FloatArray(300 * 4)
        val scores = FloatArray(300 * 91) { -20f }
        for (q in 0..3) {
            boxes[q * 4] = .5f
            boxes[q * 4 + 1] = .5f
            boxes[q * 4 + 2] = .4f
            boxes[q * 4 + 3] = .4f
        }
        scores[1] = 9f
        scores[91 + 1] = 8f
        scores[2 * 91 + 3] = 8f
        scores[3 * 91 + 12] = 20f // Unassigned COCO id, not class 12 in an 80-entry array.
        val results = DetectionMath.decode(boxes, scores)
        assertEquals(2, results.size)
        assertEquals(setOf(1, 3), results.map { it.classId }.toSet())
        assertEquals(.3f, results.first().left, .00001f)
        assertEquals(.7f, results.first().bottom, .00001f)
    }

    @Test
    fun invalidNumericalOutputIsRejected() {
        val boxes = FloatArray(300 * 4)
        boxes[0] = Float.NaN
        assertThrows(IllegalStateException::class.java) {
            DetectionMath.decode(boxes, FloatArray(300 * 91))
        }
    }
}
