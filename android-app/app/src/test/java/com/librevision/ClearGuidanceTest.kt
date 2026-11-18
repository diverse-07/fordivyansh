package com.librevision

import com.librevision.domain.alerts.*
import com.librevision.domain.navigation.*
import org.junit.Assert.*
import org.junit.Test

class ClearGuidanceTest {
    @Test
    fun obstacleWarningFinishesBeforeAnotherWarningCanSpeak() {
        val spoken = mutableListOf<String>()
        val output =
            object : SpeechOutput {
                override fun speak(text: String, interrupt: Boolean) {
                    spoken += text
                }

                override fun stop() = Unit

                override fun close() = Unit
            }
        val alerts = AlertCoordinator(output)
        assertTrue(alerts.offer(Alert("Turn left.", Priority.ROUTINE)))
        assertTrue(alerts.offer(Alert("Stop, person ahead.", Priority.URGENT)))
        assertFalse(alerts.offer(Alert("Stop, chair ahead.", Priority.URGENT)))
        assertFalse(
            alerts.offerRefinement(
                Alert("Stop, person about 2 steps away. Estimated.", Priority.URGENT)
            )
        )
        assertFalse(alerts.offer(Alert("Long scene.", Priority.SCENE)))
        assertEquals(listOf("Turn left.", "Stop, person ahead."), spoken)
        alerts.finished()
        assertTrue(alerts.offer(Alert("Stop, chair ahead.", Priority.URGENT)))
    }

    @Test
    fun changingLabelsCannotRestartWarningsOnEveryFrame() {
        val policy = ObstacleAnnouncementPolicy()
        assertTrue(policy.canAnnounce("person", 100, 100))
        policy.accepted("person", 100)
        for (now in listOf(200L, 1000L, 4000L, 5099L)) {
            assertFalse(policy.canAnnounce("chair", now, now))
        }
        assertTrue(policy.canAnnounce("chair", 5100, 5100))
        assertFalse(policy.canAnnounce("person", 5100, 5100))
        assertTrue(policy.canAnnounce("person", 8100, 8100))
    }

    @Test
    fun rejectedOrExpiredWarningsDoNotBecomeAStaleSpeechQueue() {
        val policy = ObstacleAnnouncementPolicy()
        assertFalse(policy.canAnnounce("cat", 100, 4101))
        assertFalse(policy.canAnnounce("cat", 101, 100))
        assertFalse(policy.canAnnounce("", 100, 100))
        // A rejected offer never advances the accepted-speech clock.
        assertTrue(policy.canAnnounce("cat", 200, 200))
        assertTrue(policy.canAnnounce("chair", 201, 201))
        policy.accepted("cat", 300)
        policy.reset()
        assertTrue(policy.canAnnounce("cat", 301, 301))
    }

    @Test
    fun locationMaintenanceIsSilentButRealManeuversStillHaveSpeech() {
        var now = 100000L
        val engine = WalkingGuidanceEngine { now }
        val a = GeoPoint(23.7, 90.4)
        val b = GeoPoint(23.701, 90.4)
        engine.start(
            WalkingRoute(
                Destination("test", "Fixture", b),
                listOf("Continue.", "Left."),
                false,
                listOf(a, b),
                listOf(RouteManeuver(0, "Continue.", 1), RouteManeuver(1, "Turn left.", 15)),
            )
        )
        assertTrue(engine.currentGuidance().value.speechKey.isBlank())
        engine.updateLocation(LocationFix(a.latitude, a.longitude, now, 5f))
        assertEquals(NavigationStatus.ACTIVE, engine.currentGuidance().value.status)
        assertTrue(engine.currentGuidance().value.speechKey.isNotBlank())
        assertTrue(engine.currentGuidance().value.message.contains("left", true))
        now += 16000
        engine.tick()
        assertEquals(NavigationStatus.WAITING_FOR_GPS, engine.currentGuidance().value.status)
        assertTrue(engine.currentGuidance().value.speechKey.isBlank())
        assertNull(engine.currentGuidance().value.maneuverDistanceMeters)
        now += 1000
        engine.updateLocation(LocationFix(a.latitude, a.longitude, now, 100f))
        assertTrue(engine.currentGuidance().value.speechKey.isBlank())
        assertTrue(engine.currentGuidance().value.displayText!!.contains("route preview"))
        now += 1000
        engine.updateLocation(LocationFix(a.latitude, a.longitude, now, 5f))
        assertTrue(engine.currentGuidance().value.speechKey.isNotBlank())
    }
}
