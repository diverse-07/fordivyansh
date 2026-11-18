package com.librevision

import com.librevision.data.routing.PhotonNearbyProvider
import com.librevision.domain.alerts.*
import com.librevision.domain.commands.*
import com.librevision.domain.navigation.*
import org.junit.Assert.*
import org.junit.Test

class NavigationInteractionTest {
    @Test
    fun originalQuickDestinationsAreAvailable() {
        assertEquals(
            listOf("Park", "Market", "Bus stop", "Pharmacy"),
            QuickDestination.entries.map { it.label },
        )
    }

    @Test
    fun voiceSelectsQuickAndNamedDestinationsAndResultOptions() {
        val router = VoiceCommandRouter()
        listOf(
                "Go to the park!",
                "Market",
                "Take me to nearest bus stop",
                "Please directions to pharmacy please",
            )
            .zip(QuickDestination.entries)
            .forEach { (text, kind) ->
                assertEquals(NavigationVoiceRequest.Quick(kind), NavigationVoiceRequest.parse(text))
                assertEquals(Command.NAVIGATION_DESTINATION, router.interpret(text))
            }
        assertEquals(
            NavigationVoiceRequest.Place("dhanmondi lake"),
            NavigationVoiceRequest.parse("Navigate to Dhanmondi Lake"),
        )
        assertEquals(
            NavigationVoiceRequest.Result(1),
            NavigationVoiceRequest.parse("Second destination"),
        )
        assertEquals(Command.START_NAVIGATION, router.interpret("Go"))
        assertEquals(Command.NEXT_DESTINATION, router.interpret("next destination"))
        assertEquals(Command.RECENTER_MAP, router.interpret("center map"))
        assertEquals(Command.STOP_LISTENING, router.interpret("stop listening"))
    }

    @Test
    fun voiceRejectsNegativeAndMixedDestinationCommands() {
        listOf(
                "don't go to park",
                "do not navigate to pharmacy",
                "go to market and stop scanning",
                "go to park and navigate home",
                "the park is lovely",
                "this is a pharmacy",
            )
            .forEach {
                assertNull(it, NavigationVoiceRequest.parse(it))
                assertEquals(it, Command.UNKNOWN, VoiceCommandRouter().interpret(it))
            }
    }

    @Test
    fun routingAutomaticallyUsesRecentApproximateLocation() {
        val now = 200000L
        assertTrue(
            NavigationLocationPolicy.usableForPlanning(
                LocationFix(23.8, 90.4, now - 60000, 65f),
                now,
            )
        )
        listOf(
                LocationFix(23.8, 90.4, now - 120001, 5f),
                LocationFix(23.8, 90.4, now + 1, 5f),
                LocationFix(999.0, 90.4, now, 5f),
                LocationFix(23.8, 90.4, now, Float.NaN),
            )
            .forEach {
                assertFalse(NavigationLocationPolicy.usableForPlanning(it, now))
            }
        assertFalse(NavigationLocationPolicy.usableForPlanning(null, now))
    }

    @Test
    fun quickSearchIsBoundedAndHasOnlyStaticCategoryFilters() {
        val url =
            PhotonNearbyProvider.url(
                "https://photon.komoot.io/api/",
                QuickDestination.PHARMACY,
                GeoPoint(23.8, 90.4),
            )
        assertEquals(
            "https://photon.komoot.io/reverse?lat=23.8&lon=90.4&radius=3&limit=6&lang=en&osm_tag=amenity%3Apharmacy",
            url,
        )
        assertTrue(
            PhotonNearbyProvider.url(
                    "https://example.test/geo/api/",
                    QuickDestination.MARKET,
                    GeoPoint(23.8, 90.4),
                )
                .startsWith("https://example.test/geo/reverse?")
        )
        assertThrows(IllegalArgumentException::class.java) {
            PhotonNearbyProvider.url(
                "http://example.test",
                QuickDestination.PARK,
                GeoPoint(23.8, 90.4),
            )
        }
    }

    @Test
    fun nearbyResultsMustHaveTheRequestedCategoryAndRealNearbyCoordinates() {
        val json =
            """{"features":[{"geometry":{"type":"Point","coordinates":[90.4,23.802]},"properties":{"name":"Farther","osm_key":"amenity","osm_value":"pharmacy"}},{"geometry":{"type":"Point","coordinates":[90.4,23.8001]},"properties":{"name":"Near pharmacy","osm_key":"amenity","osm_value":"pharmacy"}},{"geometry":{"type":"Point","coordinates":[90.4,23.80001]},"properties":{"name":"Not a pharmacy","osm_key":"shop","osm_value":"clothes"}},{"geometry":{"type":"Point","coordinates":[90.4,24.5]},"properties":{"name":"Too far","osm_key":"amenity","osm_value":"pharmacy"}}]}"""
        val places =
            PhotonNearbyProvider.parse(json, QuickDestination.PHARMACY, GeoPoint(23.8, 90.4))
        assertEquals(listOf("Near pharmacy", "Farther"), places.map { it.name })
        assertEquals(GeoPoint(23.8001, 90.4), places.first().point)
    }

    @Test
    fun mapFeedbackCannotInterruptUrgentObstacleSpeech() {
        val spoken = mutableListOf<String>()
        var stops = 0
        val output =
            object : SpeechOutput {
                override fun speak(text: String, interrupt: Boolean) {
                    spoken += text
                }

                override fun stop() {
                    stops++
                }

                override fun close() {}
            }
        val alerts = AlertCoordinator(output)
        alerts.offer(Alert("Finding park", Priority.ROUTINE))
        alerts.cancelRoutine()
        assertEquals(1, stops)
        alerts.offer(Alert("Stop, cat ahead", Priority.URGENT))
        alerts.cancelRoutine()
        assertEquals(1, stops)
        assertFalse(alerts.offer(Alert("Map centered", Priority.ROUTINE)))
        alerts.finished()
        assertTrue(alerts.offer(Alert("Map centered", Priority.ROUTINE)))
    }
}
