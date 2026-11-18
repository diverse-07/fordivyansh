package com.librevision.domain.commands

/**
 * Small idle grammar. [unk] is essential: unrelated speech must not be forced to an app command.
 */
object AppSpeechPhrases {
    val wakePhrases = listOf("hey vision")
    val wakePattern = Regex("^(?:hey|hey there) vision(?:\\s+|$)")
    private val normalizer = CommandInterpreter()
    private val local =
        CommandInterpreter.defaults.values
            .flatten()
            .map { normalizer.normalize(it).replace("footpath", "foot path") }
            .filter { it.matches(Regex("[a-z ]+")) }
    private val scans =
        listOf(
            "start scanning",
            "stop scanning",
            "begin scanning",
            "end scanning",
            "start scan",
            "stop scan",
            "check the foot path",
            "check foot path",
            "scan the foot path",
            "scan foot path",
        )
    private val places =
        (BangladeshPlaceNames.englishPhrases + listOf("park", "market", "bus stop", "pharmacy"))
            .flatMap { place ->
                listOf("go to $place", "navigate to $place", "take me to $place")
            }
    private val natural =
        listOf("hospital", "clinic", "pharmacy", "park", "market", "bus stop", "restaurant")
            .flatMap {
                listOf(
                    "i want to go to the nearest $it",
                    "i want to go to nearest $it",
                    "find the nearest $it",
                    "find a nearby $it",
                )
            }
    val phrases: List<String> =
        (wakePhrases +
                VoiceControls.grammar +
                local +
                scans +
                places +
                natural +
                scans.map { "please $it" } +
                listOf(
                    "do not start scanning",
                    "do not stop scanning",
                    "don't start scanning",
                    "don't stop scanning",
                ) +
                listOf(
                    "first destination",
                    "second destination",
                    "third destination",
                    "option one",
                    "option two",
                    "option three",
                ))
            .distinct()
    private val known = phrases.map(normalizer::normalize).toSet()

    fun hasWake(text: String) = wakePattern.containsMatchIn(normalizer.normalize(text))

    fun known(text: String) = normalizer.normalize(text) in known

    fun actionable(text: String): Boolean =
        known(text) &&
            !Regex("\\b(?:not|dont|don t)\\b").containsMatchIn(normalizer.normalize(text))
}
