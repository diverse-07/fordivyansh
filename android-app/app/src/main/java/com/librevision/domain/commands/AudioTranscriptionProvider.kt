package com.librevision.domain.commands

interface AudioTranscriptionProvider {
    /**
     * A single bounded VAD utterance. Ownership stays with the caller; no recording is persisted.
     */
    suspend fun transcribe(samples: ShortArray): Result<String>
}

data class DecodedSpeech(
    val text: String,
    val confidence: Float? = null,
    val wakeDetected: Boolean = false,
)

object SpeechFallbackPolicy {
    fun needsAudio(result: DecodedSpeech, command: Command): Boolean {
        if (
            command in
                setOf(
                    Command.STOP,
                    Command.STOP_SCANNING,
                    Command.STOP_NAVIGATION,
                    Command.STOP_LISTENING,
                )
        )
            return false
        if (result.text.isBlank() || result.confidence?.let { !it.isFinite() || it < .8f } == true)
            return true
        if (command == Command.UNKNOWN) return true
        // Place names are precisely where a plausible, confidently wrong English transcript is
        // dangerous.
        return NavigationVoiceRequest.parse(result.text) is NavigationVoiceRequest.Place
    }
}
