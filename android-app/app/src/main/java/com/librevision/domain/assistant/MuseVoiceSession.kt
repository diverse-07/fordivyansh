package com.librevision.domain.assistant

import com.librevision.domain.commands.AppSpeechPhrases
import com.librevision.domain.commands.CommandInterpreter

sealed interface MuseVoiceDecision {
    data class Ask(val question: String) : MuseVoiceDecision

    data object Wake : MuseVoiceDecision

    data object Local : MuseVoiceDecision

    data object Ignore : MuseVoiceDecision
}

/** Routes speech already authorized by the microphone wake/tap gate, or explicit text input. */
class MuseVoiceSession {
    private var waitingUntil = 0L
    @Volatile private var spoken = ""
    @Volatile private var spokenUntil = 0L
    private val normalizer = CommandInterpreter()
    private val wake = AppSpeechPhrases.wakePattern

    fun rememberSpeech(text: String, now: Long) {
        spoken = normalizer.normalize(text)
        spokenUntil = now + 30000
    }

    fun playbackEnded(now: Long) {
        // Suppress the speaker tail, not a user's real wake phrase for the next 30 seconds.
        spokenUntil = minOf(spokenUntil, now + 1200)
    }

    fun echoesOwnSpeech(text: String, now: Long): Boolean {
        val normalized = normalizer.normalize(text)
        return now < spokenUntil &&
            spoken.isNotBlank() &&
            normalized.length >= 6 &&
            spoken.contains(normalized)
    }

    fun arm(now: Long) {
        waitingUntil = now + 18000
    }

    fun extend(now: Long) {
        if (waitingUntil > 0) waitingUntil = now + 18000
    }

    fun clear() {
        waitingUntil = 0
    }

    fun awaiting(now: Long) = now < waitingUntil

    fun decide(text: String, now: Long, wakeOnly: Boolean = false): MuseVoiceDecision {
        val normalized = normalizer.normalize(text)
        if (normalized.isBlank()) return MuseVoiceDecision.Ignore
        val match = wake.find(normalized)
        if (
            (wakeOnly || match != null) &&
                now < spokenUntil &&
                spoken.isNotBlank() &&
                normalized.length >= 6 &&
                spoken.contains(normalized)
        )
            return MuseVoiceDecision.Ignore
        if (match != null) {
            val question = textAfterWake(text, normalized.substring(match.range.last + 1).trim())
            arm(now)
            return if (question.isBlank()) MuseVoiceDecision.Wake
            else MuseVoiceDecision.Ask(question)
        }
        if (wakeOnly) return MuseVoiceDecision.Ignore
        if (awaiting(now)) {
            waitingUntil = 0
            return MuseVoiceDecision.Ask(text.trim().take(500))
        }
        return MuseVoiceDecision.Local
    }

    // Keep the user's punctuation/casing in the actual question where possible.
    private fun textAfterWake(original: String, fallback: String): String =
        Regex("(?i)^\\s*hey(?:\\s+there)?[ ,]*\\s+vision[,.:!?\\s]*(.*)$")
            .matchEntire(original)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.take(500) ?: fallback.take(500)

    fun isDirectedRequest(text: String): Boolean {
        val value = normalizer.normalize(text)
        val domain =
            Regex(
                "\\b(?:hospital|clinic|pharmacy|park|market|bus stop|atm|toilet|restaurant|police|navigate|navigation|route|directions|destination|footpath|scan|scanning|camera|surroundings|audio|volume|settings|text size|step length|depth|muse)\\b"
            )
        val request =
            Regex(
                "^(?:i (?:want|need|would like)|can you|could you|would you|please|help me|take me|find|where|what|how|why|when|is there|tell me|show me|navigate|go to|nearest|nearby|closest)\\b"
            )
        return value.split(' ').size >= 2 &&
            request.containsMatchIn(value) &&
            domain.containsMatchIn(value)
    }
}
