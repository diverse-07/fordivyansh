package com.librevision

import com.librevision.data.speech.CompressedUtterance
import com.librevision.domain.alerts.*
import com.librevision.domain.assistant.*
import com.librevision.domain.commands.*
import com.librevision.domain.vision.*
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.junit.Assert.*
import org.junit.Test

class EarlyTrafficSpeechTest {
    @Test
    fun smallDistantVehiclesWarnAtTheDetectorThresholdOnTheFirstObservation() {
        val box = NormalizedBox(.49f, .25f, .51f, .28f)
        for (label in TrafficObjects.labels) {
            val detection = Detection(label, .45f, box)
            val relevant = ObstructionPolicy().select(listOf(detection))
            assertEquals(listOf(detection), relevant)
            assertTrue(TemporalStateEngine().consume(Observation(100, relevant, false), 100).alert)
        }
    }

    @Test
    fun distantVehiclesAreNotHiddenByLargeStaticObjects() {
        val car = Detection("car", .5f, NormalizedBox(.49f, .2f, .51f, .24f))
        val chair = Detection("chair", .99f, NormalizedBox(.3f, .2f, .7f, .99f))
        assertEquals(car, ObstructionPolicy().select(listOf(chair, car)).first())
    }

    @Test
    fun vehiclePartEnteringTheMiddleStripWarnsButPeripheralOrInvalidBoxesDoNot() {
        val policy = ObstructionPolicy()
        assertEquals(
            1,
            policy
                .select(listOf(Detection("motorcycle", .6f, NormalizedBox(.1f, .2f, .3f, .3f))))
                .size,
        )
        for (box in
            listOf(
                NormalizedBox(0f, .1f, .24f, .4f),
                NormalizedBox(.76f, .1f, 1f, .4f),
                NormalizedBox(.4f, 0f, .6f, 0f),
            )) {
            assertTrue(policy.select(listOf(Detection("car", .9f, box))).isEmpty())
        }
        assertTrue(
            policy
                .select(listOf(Detection("car", .449f, NormalizedBox(.3f, .2f, .7f, .8f))))
                .isEmpty()
        )
    }

    @Test
    fun indoorDepthNeverBecomesAVehicleStoppingDistance() {
        for (label in TrafficObjects.labels) assertEquals(
            "Stop, $label ahead.",
            ObstacleSpeech.message(label, DepthDistance(1f, 100, .1f), .65f, 100),
        )
    }

    @Test
    fun trafficEscalationBypassesARecentOrdinaryWarningAndPreservesSpeechPriority() {
        val policy = ObstacleAnnouncementPolicy()
        policy.accepted("chair", 100)
        assertTrue(policy.canAnnounce("car", 200, 200, traffic = true))
        policy.accepted("car", 200, traffic = true)
        assertFalse(policy.canAnnounce("motorcycle", 201, 201, traffic = true))
        assertTrue(policy.canAnnounce("motorcycle", 2200, 2200, traffic = true))
        val messages = mutableListOf<String>()
        var stops = 0
        val alerts =
            AlertCoordinator(
                object : SpeechOutput {
                    override fun speak(text: String, interrupt: Boolean) {
                        messages += text
                    }

                    override fun stop() {
                        stops++
                    }

                    override fun close() = Unit
                }
            )
        assertTrue(alerts.offer(Alert("chair", Priority.URGENT)))
        assertTrue(alerts.offer(Alert("car", Priority.TRAFFIC)))
        alerts.cancelRoutine()
        assertEquals(0, stops)
        assertFalse(alerts.offer(Alert("bus", Priority.TRAFFIC)))
        assertFalse(alerts.offerRefinement(Alert("chair two steps", Priority.URGENT)))
        assertEquals(listOf("chair", "car"), messages)
    }

    @Test
    fun compressedUtteranceIsLosslessMono16kWaveWithNoMetadata() {
        val pcm = ShortArray(16000) { (it % 20).toShort() }
        val compressed = CompressedUtterance.encode(pcm)
        assertTrue(compressed.size < pcm.size * 2)
        val wave = GZIPInputStream(ByteArrayInputStream(compressed)).readBytes()
        val b = ByteBuffer.wrap(wave).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wave, 0, 4))
        assertEquals("WAVEfmt ", String(wave, 8, 8))
        assertEquals(16000, b.getInt(24))
        assertEquals(1, b.getShort(22).toInt())
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(32000, b.getInt(40))
        assertEquals(32044, wave.size)
        for (i in pcm.indices) assertEquals(pcm[i], b.getShort(44 + i * 2))
        assertThrows(IllegalArgumentException::class.java) {
            CompressedUtterance.encode(ShortArray(128001))
        }
    }

    @Test
    fun uncertainNamesAndUnknownSpeechHaveAudioFallbackButStopRemainsLocal() {
        assertTrue(
            SpeechFallbackPolicy.needsAudio(
                DecodedSpeech("go to bird em", .95f),
                Command.NAVIGATION_DESTINATION,
            )
        )
        assertTrue(SpeechFallbackPolicy.needsAudio(DecodedSpeech("", 0f), Command.UNKNOWN))
        assertTrue(
            SpeechFallbackPolicy.needsAudio(DecodedSpeech("unrelated words", .9f), Command.UNKNOWN)
        )
        assertTrue(
            SpeechFallbackPolicy.needsAudio(
                DecodedSpeech("start scanning", .5f),
                Command.START_SCANNING,
            )
        )
        assertFalse(
            SpeechFallbackPolicy.needsAudio(
                DecodedSpeech("start scanning", .95f),
                Command.START_SCANNING,
            )
        )
        assertFalse(
            SpeechFallbackPolicy.needsAudio(
                DecodedSpeech("stop scanning", .1f),
                Command.STOP_SCANNING,
            )
        )
        assertEquals(
            NavigationVoiceRequest.Place("Square Hospital"),
            NavigationVoiceRequest.parse("Square hospital"),
        )
        assertEquals(
            Command.CHECK_FOOTPATH,
            VoiceCommandRouter().interpret("Please check footpath."),
        )
    }

    @Test
    fun decoderFailureRetainsOneBoundedUtteranceForFallbackAndKeepsListening() {
        val done = CountDownLatch(2)
        val failures = mutableListOf<ShortArray>()
        SpeechDecodeQueue(
                factory = {
                    object : UtteranceRecognizer {
                        override fun decode(samples: ShortArray): String {
                            if (samples[0] == 1.toShort()) throw IllegalArgumentException()
                            return "start scanning"
                        }

                        override fun close() = Unit
                    }
                },
                onText = {
                    assertEquals("start scanning", it)
                    done.countDown()
                },
                onUtteranceError = { fail("fallback should receive the failed utterance") },
                onDecodeFailure = {
                    failures += it.copyOf()
                    done.countDown()
                },
            )
            .use {
                assertTrue(it.awaitReady { false })
                assertTrue(it.offer(shortArrayOf(1)))
                assertTrue(it.offer(shortArrayOf(2)))
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertArrayEquals(shortArrayOf(1), failures.single())
            }
    }

    @Test
    fun speakerPlaybackFailureNeverPassesAudioToTheCloudFallbackCallback() {
        val done = CountDownLatch(1)
        SpeechDecodeQueue(
                factory = {
                    object : UtteranceRecognizer {
                        override fun decode(samples: ShortArray): String =
                            throw IllegalArgumentException("speaker audio")

                        override fun close() = Unit
                    }
                },
                onText = { fail("Playback text must not dispatch") },
                onUtteranceError = { done.countDown() },
                onDecodeFailure = { fail("Playback audio must not become an upload") },
            )
            .use {
                assertTrue(it.awaitReady { false })
                assertTrue(
                    it.offerWakeAware(shortArrayOf(1), normalAllowed = { false }, valid = { true })
                )
                assertTrue(done.await(5, TimeUnit.SECONDS))
            }
    }

    @Test
    fun playbackModeAcceptsOnlyWakeAndNeverAmbientCommands() {
        val session = MuseVoiceSession()
        session.rememberSpeech("Stop, car ahead.", 100)
        assertEquals(MuseVoiceDecision.Ignore, session.decide("stop scanning", 200, true))
        assertEquals(MuseVoiceDecision.Ignore, session.decide("stop navigation", 200, true))
        assertEquals(MuseVoiceDecision.Ignore, session.decide("stop", 200, true))
        assertEquals(MuseVoiceDecision.Ignore, session.decide("start scanning", 200, true))
        session.rememberSpeech("Say stop listening to turn the microphone off.", 300)
        assertEquals(MuseVoiceDecision.Ignore, session.decide("stop listening", 400, true))
        assertEquals(MuseVoiceDecision.Wake, session.decide("hey vision", 500, true))
    }
}
