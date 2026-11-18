package com.librevision.domain.vision

enum class FootpathIndicator {
    UNKNOWN,
    NO_OBSTRUCTION,
    OBSTRUCTION,
}

/** A green UI state means no qualifying obstruction was reported, never verified safe passage. */
class FootpathEvidence(private val expiryMs: Long = 4000) {
    private var frameAt: Long? = null
    private var obstructionAt: Long? = null

    fun update(observation: Observation, important: List<Detection>, nowMs: Long) {
        if (
            observation.simulated ||
                nowMs - observation.timestampMs !in 0..expiryMs ||
                frameAt?.let { observation.timestampMs <= it } == true
        )
            return
        frameAt = observation.timestampMs
        if (important.isNotEmpty()) obstructionAt = observation.timestampMs
    }

    fun indicator(
        scanning: Boolean,
        running: Boolean,
        tilt: PhoneTilt,
        nowMs: Long,
    ): FootpathIndicator {
        if (!scanning || !running || frameAt?.let { nowMs - it in 0..expiryMs } != true)
            return FootpathIndicator.UNKNOWN
        if (obstructionAt?.let { nowMs - it in 0..expiryMs } == true)
            return FootpathIndicator.OBSTRUCTION
        return if (tilt == PhoneTilt.UPRIGHT) FootpathIndicator.NO_OBSTRUCTION
        else FootpathIndicator.UNKNOWN
    }

    fun reset() {
        frameAt = null
        obstructionAt = null
    }
}
