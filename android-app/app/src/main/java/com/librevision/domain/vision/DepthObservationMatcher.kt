package com.librevision.domain.vision

import kotlin.math.*

/** Bounded exact-frame pairing; never sample an old depth map with a newer object's box. */
class DepthObservationMatcher(private val maximumAgeMs: Long = 2200) {
    private val observations = LinkedHashMap<Long, Observation>()

    fun record(observation: Observation) {
        observations[observation.timestampMs] = observation
        observations.entries.removeAll { observation.timestampMs - it.key > 4000 }
        while (observations.size > 4) observations.remove(observations.keys.first())
    }

    fun estimate(frame: DepthFrame?, current: Detection, now: Long): DepthDistance? {
        frame ?: return null
        if (now - frame.timestampMs !in 0..maximumAgeMs) return null
        val observation = observations[frame.timestampMs] ?: return null
        val latest = observations.values.lastOrNull() ?: return null
        if (now - latest.timestampMs !in 0..maximumAgeMs) return null
        val original =
            observation.detections
                .filter { it.label == current.label }
                .maxByOrNull { iou(it.bounds, current.bounds) } ?: return null
        if (iou(original.bounds, current.bounds) < .65f) return null
        return original.bounds?.let { frame.estimate(it, now) }
    }

    fun reset() {
        observations.clear()
    }

    private fun iou(a: NormalizedBox?, b: NormalizedBox?): Float {
        if (a == null || b == null || !a.valid() || !b.valid()) return 0f
        val intersection =
            max(0f, min(a.right, b.right) - max(a.left, b.left)) *
                max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        return intersection /
            ((a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) -
                    intersection)
                .coerceAtLeast(.000001f)
    }
}
