package com.librevision.domain.commands

/** Semantic app controls, shared by local speech and the validated assistant tool. */
data class VoiceControl(val id: String, val label: String, val aliases: Set<String> = emptySet())

object VoiceControls {
    val spokenLabels =
        listOf(
            "back",
            "go back",
            "home",
            "settings",
            "navigate",
            "check foot path",
            "describe surroundings",
            "learn audio cues",
            "search",
            "center map",
            "repeat",
            "go",
            "stop",
            "park",
            "market",
            "bus stop",
            "pharmacy",
            "next result",
            "start scanning",
            "stop scanning",
            "record video",
            "finish recording",
            "start recording",
            "send image",
            "cancel",
            "replay",
            "listening",
            "turn left",
            "turn right",
            "obstruction",
            "view captured",
            "complete",
            "unavailable",
            "vibration",
            "replay tour",
            "app permissions",
            "standard",
            "large",
            "night",
            "field",
            "brief",
            "detailed",
            "english",
            "bangla",
            "test speech",
            "hands free voice",
            "online speech fallback",
            "short sound cues",
            "haptic cues",
            "estimated depth distances",
            "retry model setup",
            "ask saathi",
            "ask saathi ai",
            "hey saathi",
            "ask muse",
            "more settings",
            "less settings",
            "preview audio detail",
            "speak",
            "try again",
            "next",
            "pause",
            "resume",
            "skip tour",
            "take a tour",
        )
    val grammar = spokenLabels.flatMap { label ->
        listOf(label, "press $label", "tap $label", "click $label")
    }
    private val normalizer = CommandInterpreter()

    fun match(text: String, controls: List<VoiceControl>): VoiceControl? {
        val normalized =
            normalizer
                .normalize(text)
                .replace(
                    Regex("^(?:please )?(?:click|tap|press|activate|choose|select|open) (?:the )?"),
                    "",
                )
                .replace(Regex(" button$"), "")
        return controls
            .filter { control ->
                (control.aliases + control.label).any { normalizer.normalize(it) == normalized }
            }
            .singleOrNull()
    }
}

object HandsFreePreference {
    // Earlier debug checks persisted temporary pauses. Only a deliberate preference is durable.
    fun enabled(saved: Boolean, explicitlyChosen: Boolean) = !explicitlyChosen || saved
}
