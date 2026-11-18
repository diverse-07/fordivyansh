package com.librevision

import com.librevision.data.routing.*
import com.librevision.domain.alerts.*
import com.librevision.domain.navigation.*
import com.librevision.domain.vision.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class NavigationDepthTest {
    private val a = GeoPoint(23.8, 90.4)
    private val b = GeoPoint(23.801, 90.4)
    private val c = GeoPoint(23.802, 90.4)

    private fun plan() =
        WalkingRoute(
            Destination("real", "Place", c),
            listOf("Walk north.", "Turn left.", "You have arrived."),
            false,
            listOf(a, b, c),
            listOf(
                RouteManeuver(0, "Walk north.", 1),
                RouteManeuver(1, "Turn left.", 15),
                RouteManeuver(2, "You have arrived.", 4),
            ),
            a,
            "Valhalla",
        )

    @Test
    fun realRouteNeedsGeometryAndDoesNotAcceptSimulatedRoute() {
        val engine = WalkingGuidanceEngine { 10000 }
        assertThrows(IllegalArgumentException::class.java) {
            engine.start(plan().copy(simulated = true))
        }
        engine.start(plan())
        assertEquals(NavigationStatus.WAITING_FOR_GPS, engine.currentGuidance().value.status)
    }

    @Test
    fun gpsUpdatesProgressWhileOldFutureBadAndJumpingUpdatesDoNot() {
        var now = 10000L
        val engine = WalkingGuidanceEngine { now }
        engine.start(plan())
        engine.updateLocation(LocationFix(a.latitude, a.longitude, now, 5f))
        val initial = engine.currentGuidance().value
        assertEquals(NavigationStatus.ACTIVE, initial.status)
        assertTrue(initial.message.contains("north"))
        listOf(
                LocationFix(b.latitude, b.longitude, now - 1, 5f),
                LocationFix(b.latitude, b.longitude, now + 1, 5f),
                LocationFix(b.latitude, b.longitude, now, 800f),
            )
            .forEach(engine::updateLocation)
        assertEquals(initial, engine.currentGuidance().value)
        now += 1000
        engine.updateLocation(LocationFix(c.latitude, c.longitude, now, 5f))
        assertEquals(initial, engine.currentGuidance().value)
        now += 20000
        engine.updateLocation(LocationFix(23.8004, 90.4, now, 5f))
        assertTrue(engine.currentGuidance().value.remainingMeters!! < initial.remainingMeters!!)
        now += 16000
        engine.tick()
        assertEquals(NavigationStatus.WAITING_FOR_GPS, engine.currentGuidance().value.status)
    }

    @Test
    fun coarseGpsDoesNotAnnounceAPreciselyTimedTurn() {
        val engine = WalkingGuidanceEngine { 10000 }
        engine.start(plan())
        engine.updateLocation(LocationFix(a.latitude, a.longitude, 10000, 25f))
        assertEquals(NavigationStatus.ACTIVE, engine.currentGuidance().value.status)
        assertNotNull(engine.currentGuidance().value.remainingMeters)
        // Approximate fixes can start a route, without announcing a precisely timed nearby turn.
        val nearTurn = WalkingGuidanceEngine { 10000 }
        nearTurn.start(plan())
        nearTurn.updateLocation(LocationFix(23.8009, 90.4, 10000, 25f))
        assertEquals(NavigationStatus.ACTIVE, nearTurn.currentGuidance().value.status)
        assertTrue(nearTurn.currentGuidance().value.message.startsWith("Upcoming:"))
        assertFalse(nearTurn.currentGuidance().value.message.contains("metres"))
    }

    @Test
    fun routeDeviationStopsManeuverGuidanceAndCanRecover() {
        var now = 10000L
        val engine = WalkingGuidanceEngine { now }
        engine.start(plan())
        engine.updateLocation(LocationFix(23.8, 90.401, now, 5f))
        assertEquals(NavigationStatus.OFF_ROUTE, engine.currentGuidance().value.status)
        now += 60000
        engine.updateLocation(LocationFix(a.latitude, a.longitude, now, 5f))
        assertEquals(NavigationStatus.ACTIVE, engine.currentGuidance().value.status)
    }

    @Test
    fun arrivalNeedsTwoAccurateFixesAndANearbyDestinationSnap() {
        var now = 10000L
        val engine = WalkingGuidanceEngine { now }
        engine.start(plan())
        listOf(a, b, c, c).forEach { point ->
            now += 60000
            engine.updateLocation(LocationFix(point.latitude, point.longitude, now, 5f))
        }
        assertEquals(NavigationStatus.ARRIVED, engine.currentGuidance().value.status)
        assertTrue(engine.currentGuidance().value.message.contains("not verified"))
        engine.stop()
        assertEquals(NavigationStatus.STOPPED, engine.currentGuidance().value.status)
    }

    @Test
    fun crossingInstructionsNeverClaimSafeCrossing() {
        assertTrue(WalkingGuidanceEngine.safeInstruction("Cross the road.").contains("assess"))
        assertTrue(WalkingGuidanceEngine.safeInstruction("Take the stairs.").contains("assess"))
        assertEquals("Turn left.", WalkingGuidanceEngine.safeInstruction("Turn left."))
    }

    @Test
    fun polylineSixDecoderUsesRealCoordinatesAndRejectsBrokenInput() {
        val encoded = encode(listOf(a, b, c))
        assertEquals(listOf(a, b, c), RouteGeometry.decodePolyline6(encoded))
        assertThrows(IllegalArgumentException::class.java) { RouteGeometry.decodePolyline6("_") }
        val lengths = RouteGeometry.cumulative(listOf(a, b, c))
        assertEquals(222.4, lengths.last(), 1.0)
    }

    @Test
    fun parserRejectsUnusableOrUnrelatedRoutes() {
        val leg =
            JSONObject()
                .put("shape", encode(listOf(a, b, c)))
                .put(
                    "maneuvers",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("begin_shape_index", 0)
                                .put("instruction", "Walk north.")
                                .put("type", 1)
                        ),
                )
        val json =
            JSONObject()
                .put("trip", JSONObject().put("status", 0).put("legs", JSONArray().put(leg)))
                .toString()
        val route = WalkingResponseParser.route(json, Destination("p", "Place", c), a)
        assertFalse(route.simulated)
        assertEquals(3, route.geometry.size)
        assertThrows(IllegalArgumentException::class.java) {
            WalkingResponseParser.route(json, Destination("p", "Place", c), GeoPoint(0.0, 0.0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalkingResponseParser.route(
                "{\"trip\":{\"status\":1}}",
                Destination("p", "Place", c),
                a,
            )
        }
    }

    @Test
    fun photonCoordinatesAreLongitudeLatitudeAndInvalidResultsAreDropped() {
        val raw =
            """{"features":[{"geometry":{"type":"Point","coordinates":[90.4,23.8]},"properties":{"name":"Park","city":"Dhaka","osm_id":1,"osm_type":"N"}},{"geometry":{"type":"Point","coordinates":[999,999]},"properties":{"name":"bad"}}]}"""
        val places = WalkingResponseParser.places(raw)
        assertEquals(1, places.size)
        assertEquals(a, places.single().point)
    }

    @Test
    fun depthSamplesAreMappedToTheSameCentreSquareAndDistanceIsQualified() {
        val depth = DepthFrame(280, FloatArray(280 * 280) { 1.3f }, 640, 480, 1000, 10.0)
        val distance = depth.estimate(NormalizedBox(.4f, .4f, .6f, .6f), 1200)!!
        assertEquals(1.3f, distance.meters, .001f)
        assertEquals(
            "Stop, cat about 2 steps away. Estimated.",
            ObstacleSpeech.message("cat", distance, .65f, 1200),
        )
        assertEquals("Stop, cat ahead.", ObstacleSpeech.message("cat", distance, .65f, 6000))
        assertNull(depth.estimate(NormalizedBox(0f, .4f, .1f, .6f), 1200))
    }

    @Test
    fun invalidOrMixedDepthDoesNotInventDistance() {
        val invalid = DepthFrame(280, FloatArray(280 * 280) { Float.NaN }, 640, 480, 100, 1.0)
        assertNull(invalid.estimate(NormalizedBox(.4f, .4f, .6f, .6f), 200))
        val mixed = invalid.copy(values = FloatArray(280 * 280) { if (it % 2 == 0) 1f else 5f })
        assertNull(mixed.estimate(NormalizedBox(.4f, .4f, .6f, .6f), 200))
        assertEquals("Stop, dog ahead.", ObstacleSpeech.message("dog", null, .65f, 200))
    }

    @Test
    fun routineGpsUpdatesWaitForSpeechCompletionButHazardsInterrupt() {
        val spoken = mutableListOf<String>()
        val output =
            object : SpeechOutput {
                override fun speak(text: String, interrupt: Boolean) {
                    spoken += text
                }

                override fun stop() {}

                override fun close() {}
            }
        val alerts = AlertCoordinator(output)
        assertTrue(alerts.offer(Alert("Hold phone upright", Priority.ROUTINE)))
        assertFalse(alerts.offer(Alert("Turn left", Priority.ROUTINE)))
        assertTrue(alerts.offer(Alert("Stop, cat ahead.", Priority.URGENT)))
        assertFalse(alerts.offer(Alert("Turn left", Priority.ROUTINE)))
        alerts.finished()
        assertTrue(alerts.offer(Alert("Turn left", Priority.ROUTINE)))
    }

    @Test
    fun navigationVoiceCommandsUseTheSharedDeterministicRouter() {
        val router = com.librevision.domain.commands.VoiceCommandRouter()
        assertEquals(
            com.librevision.domain.commands.Command.START_NAVIGATION,
            router.interpret("Start navigation!"),
        )
        assertEquals(
            com.librevision.domain.commands.Command.STOP_NAVIGATION,
            router.interpret("stop route"),
        )
        assertEquals(
            com.librevision.domain.commands.Command.REPEAT_NAVIGATION,
            router.interpret("repeat route guidance"),
        )
        assertEquals(
            com.librevision.domain.commands.Command.UNKNOWN,
            router.interpret("don't start navigation"),
        )
    }

    private fun encode(points: List<GeoPoint>): String {
        var lat = 0L
        var lon = 0L
        val out = StringBuilder()
        fun emit(delta: Long) {
            var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (value >= 32) {
                out.append(((32 or (value and 31).toInt()) + 63).toChar())
                value = value shr 5
            }
            out.append((value.toInt() + 63).toChar())
        }
        points.forEach {
            val x = (it.latitude * 1e6).toLong()
            val y = (it.longitude * 1e6).toLong()
            emit(x - lat)
            emit(y - lon)
            lat = x
            lon = y
        }
        return out.toString()
    }
}
