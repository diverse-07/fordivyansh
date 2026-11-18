package com.librevision.domain.commands

/** Unknown/background speech is an ordinary listening outcome, not a microphone error. */
data class TranscriptOutcome(val command: Command?, val message: String)

class CommandTranscriptHandler(private val router: VoiceCommandRouter = VoiceCommandRouter()) {
    fun handle(text: String): TranscriptOutcome {
        val command = router.interpret(text)
        return if (command == Command.UNKNOWN)
            TranscriptOutcome(null, "Still listening. Say a command or place name.")
        else TranscriptOutcome(command, "Heard: $text. Listening hands-free.")
    }
}
