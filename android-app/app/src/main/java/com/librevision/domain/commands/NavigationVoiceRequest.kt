package com.librevision.domain.commands

import com.librevision.domain.navigation.QuickDestination

sealed interface NavigationVoiceRequest {
    data class Quick(val destination: QuickDestination) : NavigationVoiceRequest

    data class Place(val query: String) : NavigationVoiceRequest

    data class Result(val index: Int) : NavigationVoiceRequest

    companion object {
        fun parse(text: String): NavigationVoiceRequest? {
            val value =
                CommandInterpreter().normalize(text).removePrefix("please ").removeSuffix(" please")
            if (
                Regex("""\b(not|dont|don t)\b""").containsMatchIn(value) ||
                    Regex("""\band (stop|start|cancel|navigate|scan)\b""").containsMatchIn(value)
            )
                return null
            val query =
                Regex("^(?:navigate to|go to|take me to|directions to) (.+)$")
                    .matchEntire(value)
                    ?.groupValues
                    ?.get(1) ?: value
            val name = query.removePrefix("the ").removePrefix("nearest ").removePrefix("nearby ")
            val quick =
                when (name) {
                    "park",
                    "local park" -> QuickDestination.PARK
                    "market",
                    "local market",
                    "supermarket",
                    "grocery store" -> QuickDestination.MARKET
                    "bus stop",
                    "bus station" -> QuickDestination.BUS_STOP
                    "pharmacy",
                    "chemist",
                    "drugstore" -> QuickDestination.PHARMACY
                    else -> null
                }
            if (quick != null) return Quick(quick)
            val index =
                when (value) {
                    "first destination",
                    "option one",
                    "first result" -> 0
                    "second destination",
                    "option two",
                    "second result" -> 1
                    "third destination",
                    "option three",
                    "third result" -> 2
                    else -> null
                }
            if (index != null) return Result(index)
            val canonical = BangladeshPlaceNames.canonical(query)
            if (canonical in BangladeshPlaceNames.names) return Place(canonical)
            if (query != value && query.length in 2..100)
                return Place(BangladeshPlaceNames.canonical(query))
            return null
        }
    }
}
