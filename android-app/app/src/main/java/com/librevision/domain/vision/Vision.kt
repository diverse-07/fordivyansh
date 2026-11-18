package com.librevision.domain.vision

data class Detection(val label: String, val confidence: Float, val bounds: NormalizedBox? = null)

data class Observation(
    val timestampMs: Long,
    val detections: List<Detection>,
    val simulated: Boolean,
)

/** Create, infer and close on the same inference executor. Input: normalized RGB NCHW 384x384. */
interface VisionDetector : AutoCloseable {
    fun detect(input: FloatArray): List<Detection>
}

enum class PathState {
    CLEAR,
    POSSIBLE_BLOCKAGE,
    UNCERTAIN,
}

data class TemporalState(
    val state: PathState = PathState.UNCERTAIN,
    val message: String = "Path conditions unknown.",
    val alert: Boolean = false,
    val alertLabel: String? = null,
)

class TemporalStateEngine(private val expiryMs: Long = 4000, private val repeatMs: Long = 8000) {
    private var latest = Long.MIN_VALUE
    private var relevantAt: Long? = null
    private val announced = mutableMapOf<String, Long>()
    private val relevant = ObstructionCatalog.supported + ObstructionCatalog.specializedHazards

    fun consume(observation: Observation, nowMs: Long): TemporalState {
        if (
            observation.timestampMs <= latest ||
                observation.timestampMs > nowMs ||
                nowMs - observation.timestampMs > expiryMs
        )
            return current(nowMs)
        latest = observation.timestampMs
        val objects =
            observation.detections.filter { it.confidence >= .45f && it.label in relevant }
        if (objects.isEmpty()) return current(nowMs)
        relevantAt = observation.timestampMs
        val labels = objects.map { it.label }.distinct()
        val fresh = labels.filter {
            announced[it]?.let { last -> nowMs - last >= repeatMs } ?: true
        }
        fresh.forEach { announced[it] = nowMs }
        return TemporalState(
            PathState.POSSIBLE_BLOCKAGE,
            "Possible obstruction: ${labels.joinToString()}. Path safety unknown.",
            fresh.isNotEmpty(),
            fresh.firstOrNull(),
        )
    }

    fun reset() {
        latest = Long.MIN_VALUE
        relevantAt = null
        announced.clear()
    }

    fun current(nowMs: Long): TemporalState {
        val active = relevantAt?.let { nowMs - it in 0..expiryMs } == true
        return if (active)
            TemporalState(
                PathState.POSSIBLE_BLOCKAGE,
                "Recent possible obstruction. Path safety unknown.",
            )
        else TemporalState(message = "No current evidence. Path safety unknown.")
    }
}
