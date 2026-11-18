package com.librevision.domain.commands

/** Shared by all screens. Full-utterance matching rejects negation and mixed instructions. */
class VoiceCommandRouter(private val fallback: CommandInterpreter = CommandInterpreter()) {
    private val patterns =
        mapOf(
                Command.START_SCANNING to
                    listOf(
                        "(?:please )?(?:start|begin) (?:the )?(?:scanning|scan)(?: (?:the )?foot ?path)?(?: please)?",
                        "(?:please )?scan (?:the )?foot ?path(?: please)?",
                        "স্ক্যান শুরু করো",
                    ),
                Command.STOP_SCANNING to
                    listOf(
                        "(?:please )?(?:stop|end|cancel) (?:the )?(?:scanning|scan)(?: (?:the )?foot ?path)?(?: please)?",
                        "স্ক্যান বন্ধ করো",
                    ),
            )
            .mapValues { (_, values) -> values.map { Regex(it) } }

    fun interpret(transcript: String): Command {
        if (NavigationVoiceRequest.parse(transcript) != null) return Command.NAVIGATION_DESTINATION
        val text = fallback.normalize(transcript)
        val matches = patterns.filterValues { it.any { pattern -> pattern.matches(text) } }.keys
        if (matches.isNotEmpty()) return matches.singleOrNull() ?: Command.UNKNOWN
        val exact = fallback.interpret(transcript)
        if (exact != Command.UNKNOWN) return exact
        val polite = fallback.interpret(text.removePrefix("please ").removeSuffix(" please"))
        if (polite != Command.UNKNOWN) return polite
        return fuzzyScanCommand(text)
    }

    /** Fuzz only a complete two-word scan command; never fuzzy-match arbitrary sentences. */
    private fun fuzzyScanCommand(text: String): Command {
        val words = text.split(" ").toMutableList()
        if (words.firstOrNull() == "please") words.removeAt(0)
        if (words.lastOrNull() == "please") words.removeAt(words.lastIndex)
        words.removeAll { it == "the" }
        if (words.lastOrNull() == "footpath") words.removeAt(words.lastIndex)
        if (words.size != 2) return Command.UNKNOWN
        val action = words[0]
        val objectWord = words[1]
        if (!action.matches(Regex("[a-z]{3,6}")) || !objectWord.matches(Regex("[a-z]{4,9}")))
            return Command.UNKNOWN
        val objectDistance = minOf(distance(objectWord, "scan"), distance(objectWord, "scanning"))
        // Known pronunciation confusions remain explicit, editable aliases.
        if (objectDistance > 2 && objectWord !in setOf("skinning", "scamming"))
            return Command.UNKNOWN
        val start = listOf("start", "begin").minOf { distance(action, it) }
        val stop = listOf("stop", "end", "cancel").minOf { distance(action, it) }
        if (minOf(start, stop) > 1 || start == stop) return Command.UNKNOWN
        return if (start < stop) Command.START_SCANNING else Command.STOP_SCANNING
    }

    /** Damerau-Levenshtein: insertion, deletion, substitution and adjacent STT transposition. */
    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] =
                minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
