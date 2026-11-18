package com.librevision

import com.librevision.data.remote.*
import com.librevision.domain.alerts.*
import com.librevision.domain.commands.*
import com.librevision.domain.navigation.*
import com.librevision.domain.vision.*
import org.junit.Assert.*
import org.junit.Test

class FasterGuidanceTest {
    private val cat = Detection("cat", .9f, NormalizedBox(.35f, .35f, .65f, .85f))

    private fun observation(time: Long, d: Detection = cat) = Observation(time, listOf(d), false)

    private fun depth(time: Long) =
        DepthFrame(280, FloatArray(280 * 280) { 1.3f }, 640, 480, time, 800.0)

    @Test
    fun slowerDepthPairsWithItsOriginalFrameDespiteANewerDetectionCycle() {
        val matcher = DepthObservationMatcher()
        matcher.record(observation(1000))
        matcher.record(observation(2000))
        assertEquals(1.3f, matcher.estimate(depth(1000), cat, 2100)!!.meters, .001f)
    }

    @Test
    fun changedObjectsOrMissingExactFrameNeverReceiveOldDepth() {
        val matcher = DepthObservationMatcher()
        matcher.record(observation(1000))
        assertNull(matcher.estimate(depth(999), cat, 1500))
        assertNull(matcher.estimate(depth(1000), cat.copy(label = "dog"), 1500))
        assertNull(
            matcher.estimate(
                depth(1000),
                cat.copy(bounds = NormalizedBox(.1f, .1f, .2f, .2f)),
                1500,
            )
        )
        assertNull(matcher.estimate(depth(1000), cat, 3500))
        matcher.reset()
        assertNull(matcher.estimate(depth(1000), cat, 1500))
    }

    @Test
    fun oldObservationCacheIsBoundedAndRespectsFutureTimestamps() {
        val matcher = DepthObservationMatcher()
        for (time in 1000L..6000L step 1000) matcher.record(observation(time))
        assertNull(matcher.estimate(depth(1000), cat, 6500))
        assertNull(matcher.estimate(depth(6000), cat, 5900))
        assertNotNull(matcher.estimate(depth(6000), cat, 6500))
    }

    @Test
    fun distanceRefinesAsSoonAsInitialStopSpeechFinishesWithoutAFixedDelay() {
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
        assertTrue(alerts.offer(Alert("Stop, cat ahead.", Priority.URGENT)))
        assertFalse(
            alerts.offerRefinement(
                Alert("Stop, cat about 2 steps away. Estimated.", Priority.URGENT)
            )
        )
        assertEquals(1, spoken.size)
        alerts.finished()
        assertTrue(
            alerts.offerRefinement(
                Alert("Stop, cat about 2 steps away. Estimated.", Priority.URGENT)
            )
        )
        assertFalse(alerts.offer(Alert("Selected view: tree on the left.", Priority.SCENE)))
    }

    @Test
    fun routeOrManeuverChangesAndLateCloudResponsesAreRejected() {
        val ticket = VisualContextTicket(4, 2, 1000)
        assertTrue(ticket.current(4, 2, 2000))
        assertFalse(ticket.current(5, 2, 2000))
        assertFalse(ticket.current(4, 3, 2000))
        assertFalse(ticket.current(4, 2, 17000))
        assertFalse(ticket.current(4, null, 2000))
        assertFalse(ticket.current(4, 2, 999))
    }

    @Test
    fun museCommandsUseGlobalDeterministicRouter() {
        val router = VoiceCommandRouter()
        assertEquals(Command.EXPLAIN_NAVIGATION, router.interpret("Explain this view!"))
        assertEquals(Command.EXPLAIN_NAVIGATION, router.interpret("ask muse"))
        assertEquals(Command.CONFIRM_IMAGE_UPLOAD, router.interpret("send image"))
        assertEquals(Command.CANCEL_IMAGE_UPLOAD, router.interpret("cancel image"))
        assertEquals(Command.UNKNOWN, router.interpret("don't ask muse"))
    }

    @Test
    fun gatewayOutputIsDescriptiveAndNeverBecomesARouteInstruction() {
        val raw =
            """{"model":"muse-spark-1.3","context":{"f":"SHOP_SIGN","c":"BLUE","p":"LEFT","q":"UNCERTAIN","n":"BUSY"},"latencyMs":1100}"""
        val response = GatewayResponseParser.parse(raw)
        assertTrue(response.text.contains("Possible blue shop sign, left"))
        assertTrue(response.text.startsWith("View:"))
        for (label in listOf("WALK_FORWARD", "SAFE_PATH", "CROSS_NOW", "TREE_2_METRES_AWAY")) {
            assertThrows(IllegalArgumentException::class.java) {
                GatewayResponseParser.parse(raw.replace("SHOP_SIGN", label))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GatewayResponseParser.parse(raw.replace("muse-spark-1.3", "other-model"))
        }
    }

    @Test
    fun insecureRemoteGatewayUrlsAreRejectedButDebugLoopbackIsSupported() {
        assertTrue(GatewayPreferences.validGatewayUrl("https://gateway.example", false))
        assertTrue(GatewayPreferences.validGatewayUrl("http://127.0.0.1:8790", true))
        assertFalse(GatewayPreferences.validGatewayUrl("http://127.0.0.1:8790", false))
        assertFalse(GatewayPreferences.validGatewayUrl("http://gateway.example", true))
        assertFalse(
            GatewayPreferences.validGatewayUrl("https://user:secret@gateway.example", false)
        )
    }
}
