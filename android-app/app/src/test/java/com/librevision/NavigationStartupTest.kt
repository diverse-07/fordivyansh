package com.librevision

import com.librevision.domain.commands.AppSpeechPhrases
import com.librevision.domain.commands.DecodedSpeech
import com.librevision.domain.commands.VoiceInputWindow
import com.librevision.domain.navigation.*
import org.junit.Assert.*
import org.junit.Test

class NavigationStartupTest {
    private val start = GeoPoint(23.75, 90.38)
    private val turn = GeoPoint(23.751, 90.38)
    private val end = GeoPoint(23.751, 90.381)

    private fun route() =
        WalkingRoute(
            Destination("fixture", "Hospital", end),
            listOf("Walk north.", "Turn right.", "End."),
            false,
            listOf(start, turn, end),
            listOf(
                RouteManeuver(0, "Walk north.", 1),
                RouteManeuver(1, "Turn right.", 10),
                RouteManeuver(2, "End.", 4),
            ),
        )

    @Test
    fun routeShowsThePlannedNextTurnImmediatelyWithoutPretendingToHaveLivePosition() {
        val engine = WalkingGuidanceEngine { 100000L }
        engine.start(route())
        val state = engine.currentGuidance().value
        assertEquals(NavigationStatus.WAITING_FOR_GPS, state.status)
        assertTrue(state.displayText!!.contains("Turn right"))
        assertTrue(state.displayText.contains("route preview"))
        assertNull(state.maneuverDistanceMeters)
        assertEquals("", state.speechKey)
        assertNotNull(state.totalEstimatedSteps)
    }

    @Test
    fun stalePlanningOrCoarseFixesKeepTheUpcomingTurnVisibleAndFreshFixActivatesDistances() {
        var now = 100000L
        val engine = WalkingGuidanceEngine { now }
        engine.start(route())
        engine.updateLocation(LocationFix(start.latitude, start.longitude, now - 60000, 5f))
        engine.tick()
        assertTrue(engine.currentGuidance().value.displayText!!.contains("Turn right"))
        engine.updateLocation(LocationFix(start.latitude, start.longitude, now, 100f))
        assertTrue(engine.currentGuidance().value.displayText!!.contains("Turn right"))
        assertNull(engine.currentGuidance().value.maneuverDistanceMeters)
        now += 1000
        engine.updateLocation(LocationFix(start.latitude, start.longitude, now, 5f))
        assertEquals(NavigationStatus.ACTIVE, engine.currentGuidance().value.status)
        assertNotNull(engine.currentGuidance().value.maneuverDistanceMeters)
        assertTrue(engine.currentGuidance().value.speechKey.isNotBlank())
        now += 16000
        engine.tick()
        assertTrue(engine.currentGuidance().value.displayText!!.contains("Turn right"))
        assertNull(engine.currentGuidance().value.maneuverDistanceMeters)
        assertNull(engine.currentGuidance().value.remainingMeters)
    }

    @Test
    fun freshNetworkFixIsNotSuppressedByStaleAccurateGpsCache() {
        val old = LocationFix(start.latitude, start.longitude, 75000, 5f)
        val fresh = old.copy(timestampMs = 80000, accuracyMeters = 100f)
        assertTrue(NavigationLocationPolicy.acceptsUpdate(old, fresh, 100000))
        assertFalse(
            NavigationLocationPolicy.acceptsUpdate(
                old.copy(timestampMs = 99000),
                fresh.copy(timestampMs = 100000),
                100000,
            )
        )
        assertFalse(
            NavigationLocationPolicy.acceptsUpdate(old, old.copy(timestampMs = 74000), 100000)
        )
        assertFalse(
            NavigationLocationPolicy.acceptsUpdate(null, fresh.copy(timestampMs = -50000), 100000)
        )
    }

    @Test
    fun onlyHeyVisionActivatesWakeRecognition() {
        val gate = VoiceInputWindow { 100L }
        for (removed in listOf("hey muse", "hey news", "hey mews", "hey moose")) {
            assertFalse(AppSpeechPhrases.hasWake(removed))
            assertNull(gate.authorize(DecodedSpeech(removed, 1f, true)))
        }
        assertNotNull(gate.authorize(DecodedSpeech("hey vision", 1f, true)))
    }
}
