package com.librevision

import com.librevision.data.remote.*
import com.librevision.domain.navigation.*
import org.junit.Assert.*
import org.junit.Test

class ConciseDirectionsTest {
    private val a = GeoPoint(23.8, 90.4)
    private val b = GeoPoint(23.801, 90.4)
    private val c = GeoPoint(23.801, 90.399)
    private val d = GeoPoint(23.802, 90.399)

    private fun route() =
        WalkingRoute(
            Destination("fixture", "Fixture", d),
            listOf("Walk north.", "Turn left.", "Turn right.", "Destination."),
            false,
            listOf(a, b, c, d),
            listOf(
                RouteManeuver(0, "Walk north.", 1),
                RouteManeuver(1, "Turn left.", 15),
                RouteManeuver(2, "Turn right.", 10),
                RouteManeuver(3, "Destination.", 4),
            ),
            a,
        )

    @Test
    fun metresKilometresAndEstimatedStepsUseTheSameStride() {
        assertEquals("30 m", NavigationMeasures.display(30.0))
        assertEquals("1.2 km", NavigationMeasures.display(1199.0))
        assertEquals("30 metres, about 46 steps", NavigationMeasures.toTurnSpeech(30.0, .65f))
        assertEquals("1.2 kilometres", NavigationMeasures.spoken(1199.0))
        assertEquals(2000, NavigationMeasures.steps(1000.0, .5f))
        assertEquals(.65f, NavigationMeasures.validStride(Float.NaN), 0f)
        assertThrows(IllegalArgumentException::class.java) { NavigationMeasures.steps(-1.0, .65f) }
    }

    @Test
    fun tripOverviewIncludesTotalDistanceAndTotalEstimatedStepsOnceNotEveryTurn() {
        val engine = WalkingGuidanceEngine { 10000 }
        engine.start(route())
        val total = RouteGeometry.cumulative(route().geometry).last()
        assertEquals(
            NavigationMeasures.steps(total, .65f),
            engine.currentGuidance().value.totalEstimatedSteps,
        )
        assertTrue(engine.routeOverview()!!.endsWith("steps total."))
        engine.updateLocation(LocationFix(a.latitude, a.longitude, 10000, 5f))
        val guide = engine.currentGuidance().value
        assertTrue(guide.displayText!!.contains("Total "))
        assertTrue(guide.displayText.contains(" m"))
        assertFalse(guide.message.contains("steps total"))
        assertTrue(guide.message.contains("steps"))
    }

    @Test
    fun turnIsNotSkippedEightMetresBeforeTheIntersection() {
        var now = 10000L
        val engine = WalkingGuidanceEngine { now }
        engine.start(route())
        engine.updateLocation(LocationFix(a.latitude, a.longitude, now, 5f))
        now += 60000
        engine.updateLocation(LocationFix(23.80096, 90.4, now, 5f))
        assertEquals(1, engine.currentGuidance().value.maneuverIndex)
        assertTrue(engine.currentGuidance().value.message.contains("Turn left"))
        now += 10000
        engine.updateLocation(LocationFix(23.801, 90.39994, now, 5f))
        assertEquals(2, engine.currentGuidance().value.maneuverIndex)
        assertTrue(engine.currentGuidance().value.message.contains("Turn right"))
    }

    @Test
    fun ordinaryGpsTicksDoNotCreateDuplicateSpeechKeys() {
        var now = 10000L
        val engine = WalkingGuidanceEngine { now }
        engine.start(route())
        engine.updateLocation(LocationFix(a.latitude, a.longitude, now, 5f))
        val key = engine.currentGuidance().value.speechKey
        now += 1000
        engine.updateLocation(LocationFix(23.80001, 90.4, now, 5f))
        assertEquals(key, engine.currentGuidance().value.speechKey)
    }

    @Test
    fun strideChangeUpdatesEstimatesWithoutInventingRouteProgress() {
        val engine = WalkingGuidanceEngine { 10000 }
        engine.start(route())
        engine.updateLocation(LocationFix(a.latitude, a.longitude, 10000, 5f))
        val before = engine.currentGuidance().value
        engine.setStepLength(.5f)
        val after = engine.currentGuidance().value
        assertTrue(after.totalEstimatedSteps!! > before.totalEstimatedSteps!!)
        assertEquals(before.remainingMeters, after.remainingMeters)
        assertEquals(before.lastLocationTimestampMs, after.lastLocationTimestampMs)
        assertEquals(before.speechKey, after.speechKey)
    }

    @Test
    fun uncertainNearbyGpsDoesNotAnnounceAnExactTurnOrStepCount() {
        val engine = WalkingGuidanceEngine { 10000 }
        engine.start(route())
        engine.updateLocation(LocationFix(23.8009, 90.4, 10000, 25f))
        val guide = engine.currentGuidance().value
        assertTrue(guide.message.startsWith("Upcoming:"))
        assertFalse(guide.message.contains("steps"))
        assertNull(guide.maneuverEstimatedSteps)
        assertNotNull(guide.totalEstimatedSteps)
    }

    @Test
    fun succinctProviderInstructionsDoNotHideCrossingCautionsOrAddASecondTurn() {
        assertTrue(
            WalkingGuidanceEngine.shortInstruction(
                    RouteManeuver(1, "Cross the road.", 15, "Turn left.")
                )
                .contains("assess")
        )
        assertEquals(
            "Walk north.",
            WalkingGuidanceEngine.shortInstruction(
                RouteManeuver(0, "Walk north on a long road.", 1, "Walk north. Then turn left.")
            ),
        )
        assertEquals(
            "Walk north.",
            WalkingGuidanceEngine.shortInstruction(RouteManeuver(0, "Walk north for 200 feet.", 1)),
        )
        assertEquals(
            com.librevision.domain.commands.Command.ROUTE_SUMMARY,
            com.librevision.domain.commands.VoiceCommandRouter().interpret("route summary"),
        )
        assertEquals(
            "Turn left.",
            WalkingGuidanceEngine.shortInstruction(
                RouteManeuver(1, "Turn left onto a long road.", 15)
            ),
        )
        assertEquals(
            "Turn left.",
            WalkingGuidanceEngine.shortInstruction(
                RouteManeuver(1, "Turn left onto " + "Long road name ".repeat(20), 15)
            ),
        )
    }

    @Test
    fun museFlatContractSpeaksOnlyOneShortLandmarkAndPreservesUncertainty() {
        val raw =
            """{"model":"muse-spark-1.3","context":{"f":"SHOP_SIGN","c":"BLUE","p":"LEFT","q":"UNCERTAIN","n":"NONE"},"usage":{"outputTokens":39,"reasoningTokens":0}}"""
        val parsed = GatewayResponseParser.parse(raw)
        assertEquals("View: Possible blue shop sign, left.", parsed.text)
        assertEquals(39, parsed.usage!!.output)
        assertEquals(0, parsed.usage.reasoning)
        assertEquals(
            "View: No landmark identified.",
            GatewayResponseParser.parse(raw.replace("SHOP_SIGN", "NONE")).text,
        )
        assertThrows(IllegalArgumentException::class.java) {
            GatewayResponseParser.parse(
                raw.replace("\"n\":\"NONE\"", "\"n\":\"NONE\",\"turn\":\"right\"")
            )
        }
        for (feature in VisualFeature.entries) {
            val text = GatewayResponseParser.parse(raw.replace("SHOP_SIGN", feature.name)).text
            assertTrue(text.split(' ').size <= 12)
        }
    }
}
