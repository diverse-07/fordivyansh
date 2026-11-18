package com.librevision

import com.librevision.data.remote.MuseReplyParser
import com.librevision.data.routing.PhotonNearbyProvider
import com.librevision.domain.assistant.*
import com.librevision.domain.commands.*
import com.librevision.domain.navigation.*
import com.librevision.domain.vision.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GlobalMuseTest {
    @Test
    fun wakeBypassesLocalMatcherAndPreservesQuestion() {
        for (wake in listOf("Hey Vision", "Hey there Vision")) {
            val result =
                MuseVoiceSession().decide("$wake, I want to go to the nearest hospital!", 100)
                    as MuseVoiceDecision.Ask
            assertEquals("I want to go to the nearest hospital!", result.question)
        }
    }

    @Test
    fun standaloneWakeAcceptsNextUtteranceThenExpires() {
        val session = MuseVoiceSession()
        assertEquals(MuseVoiceDecision.Wake, session.decide("Hey Vision", 100))
        assertEquals(MuseVoiceDecision.Ask("What's nearby?"), session.decide("What's nearby?", 200))
        assertEquals(MuseVoiceDecision.Local, session.decide("ordinary conversation", 300))
        session.arm(1000)
        assertEquals(MuseVoiceDecision.Local, session.decide("hello", 19001))
    }

    @Test
    fun closingConversationDropsFollowupButWakeStillWorks() {
        val session = MuseVoiceSession()
        session.arm(100)
        session.clear()
        assertEquals(MuseVoiceDecision.Local, session.decide("ambient conversation", 200))
        assertFalse(session.awaiting(200))
        assertTrue(session.decide("hey vision how can you help", 200) is MuseVoiceDecision.Ask)
    }

    @Test
    fun playbackAllowsOnlyWakeAndRejectsOwnVoice() {
        val session = MuseVoiceSession()
        session.rememberSpeech("Stop scanning. Say Hey Vision for help.", 100)
        assertEquals(MuseVoiceDecision.Ignore, session.decide("stop scanning", 200, true))
        assertEquals(MuseVoiceDecision.Ignore, session.decide("hey vision", 200, true))
        assertTrue(
            session.decide("Hey Vision where is the hospital", 200, true) is MuseVoiceDecision.Ask
        )
        session.arm(200)
        assertEquals(MuseVoiceDecision.Ignore, session.decide("some speaker audio", 300, true))
    }

    @Test
    fun naturalAppFallbackDoesNotUploadAmbientUnknownConversation() {
        val session = MuseVoiceSession()
        listOf(
                "I want to go to nearest hospital",
                "could you make the text size larger",
                "find nearby clinic",
                "where is the nearest pharmacy",
            )
            .forEach { assertTrue(it, session.isDirectedRequest(it)) }
        listOf(
                "hello again",
                "what a lovely day",
                "what is your favorite song",
                "start scanning tomorrow",
            )
            .forEach { assertFalse(it, session.isDirectedRequest(it)) }
    }

    @Test
    fun toolArgumentsAreClosedAndTyped() {
        assertTrue(
            MuseAction(MuseActionKind.NAVIGATE_NEARBY, category = NearbyCategory.HOSPITAL).valid()
        )
        assertFalse(
            MuseAction(
                    MuseActionKind.NAVIGATE_NEARBY,
                    query = "23,90",
                    category = NearbyCategory.HOSPITAL,
                )
                .valid()
        )
        assertFalse(MuseAction(MuseActionKind.OPEN_SCREEN, value = "EXECUTE_SHELL").valid())
        assertFalse(MuseAction(MuseActionKind.SET_STEP_LENGTH, value = "NaN").valid())
        assertFalse(MuseAction(MuseActionKind.SET_STEP_LENGTH, value = "2.0").valid())
        assertTrue(MuseAction(MuseActionKind.SET_STEP_LENGTH, value = "0.65").valid())
    }

    @Test
    fun replyAcceptsUsefulNaturalAnswerAndValidatedAction() {
        val answer =
            "A blue pharmacy sign is on your left. The doorway is partly obscured, so its entrance is uncertain."
        assertEquals(
            answer,
            MuseReplyParser.parse("""{"model":"muse-spark-1.3","text":"$answer","action":null}""")
                .text,
        )
        assertEquals(
            NearbyCategory.HOSPITAL,
            MuseReplyParser.parse(
                    """{"model":"muse-spark-1.3","text":null,"action":{"action":"NAVIGATE_NEARBY","query":null,"category":"HOSPITAL","value":null}}"""
                )
                .action!!
                .category,
        )
    }

    @Test
    fun unknownToolsCoordinatesAndUnsafeAnswersFailClosed() {
        for (action in
            listOf(
                """{"action":"OPEN_SCREEN","query":null,"category":null,"value":"SHELL"}""",
                """{"action":"NAVIGATE_NEARBY","query":null,"category":"HOSPITAL","value":null,"lat":23}""",
            )) {
            assertThrows(IllegalArgumentException::class.java) {
                MuseReplyParser.parse("""{"model":"muse-spark-1.3","text":null,"action":$action}""")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { MuseReplyParser.text("Cross now.") }
    }

    @Test
    fun nearestHospitalUsesRealCategoryFilterAndKeepsFourQuickOptions() {
        val url =
            PhotonNearbyProvider.url(
                "https://photon.komoot.io/api",
                NearbyCategory.HOSPITAL,
                GeoPoint(23.7, 90.4),
            )
        assertTrue(url.contains("osm_tag=amenity%3Ahospital"))
        assertEquals(4, QuickDestination.entries.size)
    }

    @Test
    fun tiltCooldownSurvivesPausingScanner() {
        val policy = TiltPromptPolicy()
        assertNull(policy.update(PhoneTilt.ADJUST, 1000))
        assertNull(policy.update(PhoneTilt.ADJUST, 4999))
        assertEquals(
            "Hold the phone upright at chest height.",
            policy.update(PhoneTilt.ADJUST, 5000),
        )
        policy.pause()
        assertNull(policy.update(PhoneTilt.ADJUST, 20000))
        assertNull(policy.update(PhoneTilt.ADJUST, 64999))
        assertEquals("Keep the phone upright.", policy.update(PhoneTilt.ADJUST, 65000))
    }

    @Test
    fun wakeOnlyDecoderDeliveryKeepsCaptureAlive() {
        val done = CountDownLatch(1)
        val normal = mutableListOf<String>()
        val wake = mutableListOf<String>()
        SpeechDecodeQueue(
                factory = {
                    object : UtteranceRecognizer {
                        override fun decode(samples: ShortArray) = "hey vision help"

                        override fun close() = Unit
                    }
                },
                onText = { normal.add(it) },
                onUtteranceError = { fail("decode failed") },
                onWakeOnlyText = {
                    wake.add(it)
                    done.countDown()
                },
            )
            .use {
                assertTrue(it.awaitReady { false })
                assertTrue(it.offerWakeAware(shortArrayOf(1), { false }, { true }))
                assertTrue(done.await(5, TimeUnit.SECONDS))
                assertTrue(normal.isEmpty())
                assertEquals(listOf("hey vision help"), wake)
            }
    }
}
