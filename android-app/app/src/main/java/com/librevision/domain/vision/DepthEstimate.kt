package com.librevision.domain.vision

import kotlin.math.*

data class DepthDistance(val meters: Float, val timestampMs: Long, val relativeSpread: Float)

data class DepthFrame(
    val size: Int,
    val values: FloatArray,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val timestampMs: Long,
    val inferenceMs: Double,
) {
    /**
     * Sample the inner object box; map preview coordinates into the depth model's centre square.
     */
    fun estimate(box: NormalizedBox, now: Long): DepthDistance? {
        if (
            !box.valid() ||
                now - timestampMs !in 0..4000 ||
                values.size != size * size ||
                sourceWidth <= 0 ||
                sourceHeight <= 0
        )
            return null
        val side = min(sourceWidth, sourceHeight).toFloat()
        val dx = (sourceWidth - side) / 2
        val dy = (sourceHeight - side) / 2
        val l = box.left + (box.right - box.left) * .25f
        val r = box.right - (box.right - box.left) * .25f
        val t = box.top + (box.bottom - box.top) * .25f
        val b = box.bottom - (box.bottom - box.top) * .25f
        fun x(v: Float) = ((v * sourceWidth - dx) / side * size)
        fun y(v: Float) = ((v * sourceHeight - dy) / side * size)
        if (x(l) < 0 || x(r) > size || y(t) < 0 || y(b) > size) return null
        val samples = ArrayList<Float>()
        val left = x(l).toInt().coerceIn(0, size - 1)
        val right = x(r).toInt().coerceIn(0, size - 1)
        val top = y(t).toInt().coerceIn(0, size - 1)
        val bottom = y(b).toInt().coerceIn(0, size - 1)
        for (py in top..bottom step max(1, (bottom - top) / 12)) for (px in
            left..right step max(1, (right - left) / 12)) {
            val value = values[py * size + px]
            if (value.isFinite() && value in .2f..10f) samples += value
        }
        if (samples.size < 9) return null
        samples.sort()
        val median = samples[samples.size / 2]
        val spread = (samples[samples.size * 3 / 4] - samples[samples.size / 4]) / median
        if (spread > .5f) return null
        return DepthDistance(median, timestampMs, spread)
    }
}

object ObstacleSpeech {
    fun message(label: String, distance: DepthDistance?, stepLength: Float, now: Long): String {
        // The bundled metric-depth model is an unvalidated outdoor range estimator.
        // Vehicle warnings never wait for it or present its output as a stopping distance.
        if (TrafficObjects.contains(label)) return "Stop, $label ahead."
        val valid = distance?.takeIf {
            now - it.timestampMs in 0..4000 &&
                it.meters.isFinite() &&
                it.meters in .2f..8f &&
                it.relativeSpread <= .5f &&
                stepLength in .4f..1f
        }
        return if (valid == null) "Stop, $label ahead."
        else
            "Stop, $label about ${ceil(valid.meters/stepLength).toInt().coerceIn(1,30)} steps away. Estimated."
    }
}
