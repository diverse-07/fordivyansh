package com.librevision.domain.commands

/** Bounded in-memory VAD segmentation with 192 ms pre-roll and 608 ms silence tail. */
class SpeechSegmenter(private val silentTailFrames: Int = 19) {
    private val preRoll = ArrayDeque<ShortArray>()
    private val utterance = ArrayList<ShortArray>()
    private var onset = 0
    private var voiced = 0
    private var silent = 0
    private var active = false
    val capturing: Boolean
        get() = active

    fun accept(samples: ShortArray, probability: Float): ShortArray? {
        require(samples.size == 512)
        require(probability.isFinite() && probability in 0f..1f)
        if (!active) {
            preRoll.addLast(samples.copyOf())
            if (preRoll.size > 6) preRoll.removeFirst()
            onset = if (probability >= .6f) onset + 1 else 0
            if (onset < 2) return null
            active = true
            voiced = onset
            silent = 0
            utterance.addAll(preRoll)
            preRoll.clear()
            return null
        }
        utterance.add(samples.copyOf())
        if (probability >= .6f) voiced++
        silent = if (probability < .35f) silent + 1 else 0
        if (silent < silentTailFrames && utterance.size < 250) return null
        val result =
            if (voiced >= 3)
                ShortArray(utterance.size * 512).also { out ->
                    utterance.forEachIndexed { index, frame -> frame.copyInto(out, index * 512) }
                }
            else null
        reset()
        return result
    }

    fun reset() {
        preRoll.clear()
        utterance.clear()
        onset = 0
        voiced = 0
        silent = 0
        active = false
    }
}
