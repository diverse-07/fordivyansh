package com.librevision

import com.librevision.core.design.ReadoutText
import com.librevision.domain.assistant.*
import com.librevision.domain.commands.*
import com.librevision.domain.navigation.*
import org.junit.Assert.*
import org.junit.Test

class NavigationUxTest {
    @Test
    fun destinationSelectionNeverStartsCameraAndActiveRouteDoes() {
        for (status in
            listOf(NavigationStatus.IDLE, NavigationStatus.STOPPED, NavigationStatus.ARRIVED)) {
            assertFalse(NavigationCameraPolicy.shouldScan(status, true, false, true))
        }
        for (status in
            listOf(
                NavigationStatus.ACTIVE,
                NavigationStatus.WAITING_FOR_GPS,
                NavigationStatus.OFF_ROUTE,
            )) {
            assertTrue(NavigationCameraPolicy.shouldScan(status, true, false, true))
            assertFalse(NavigationCameraPolicy.shouldScan(status, true, true, true))
            assertFalse(NavigationCameraPolicy.shouldScan(status, false, false, true))
            assertFalse(NavigationCameraPolicy.shouldScan(status, true, false, false))
        }
    }

    @Test
    fun stepWarningUsesTwoShortLinesWithoutDiscardingEstimate() {
        assertEquals(
            ReadoutText("Stop · cat ahead", "≈2 steps · estimated"),
            ReadoutText.from("Stop, cat about 2 steps away. Estimated."),
        )
        assertEquals(
            ReadoutText("Stop · fire hydrant ahead", "≈3 steps · estimated"),
            ReadoutText.from("Stop, fire hydrant about 3 steps away. Estimated."),
        )
        assertEquals(
            ReadoutText("Left · 20 m", "Total: 500 m · ≈770 steps"),
            ReadoutText.from("Left · 20 m\nTotal: 500 m · ≈770 steps"),
        )
    }

    @Test
    fun appPhrasesAndReportedFootpathErrorAreRecognized() {
        val router = VoiceCommandRouter()
        for (text in
            listOf("Check the but", "Check the foot path", "Check the footpath")) assertEquals(
            Command.CHECK_FOOTPATH,
            router.interpret(text),
        )
        assertTrue("check foot path" in AppSpeechPhrases.phrases)
        assertTrue("start scanning" in AppSpeechPhrases.phrases)
        assertTrue("stop scanning" in AppSpeechPhrases.phrases)
        assertFalse(AppSpeechPhrases.actionable("do not start scanning"))
        assertFalse(AppSpeechPhrases.actionable("don't stop scanning"))
        assertFalse(AppSpeechPhrases.actionable("unrelated conversation"))
    }

    @Test
    fun reviewedBangladeshNamesCanonicalizeOnlyAnExactDestinationAlias() {
        assertEquals(40, BangladeshPlaceNames.names.size)
        assertEquals(
            NavigationVoiceRequest.Place("Labaid"),
            NavigationVoiceRequest.parse("navigate to lab aid"),
        )
        assertEquals(
            NavigationVoiceRequest.Place("Evercare Hospital"),
            NavigationVoiceRequest.parse("go to ever care hospital"),
        )
        assertEquals(
            NavigationVoiceRequest.Place("Square Hospital"),
            NavigationVoiceRequest.parse("take me to square hospital"),
        )
        assertNull(NavigationVoiceRequest.parse("don't go to square hospital"))
        assertEquals("square hospital road", BangladeshPlaceNames.canonical("square hospital road"))
    }

    @Test
    fun heyVisionOpensTheSameGlobalMuseConversation() {
        val session = MuseVoiceSession()
        assertEquals(MuseVoiceDecision.Wake, session.decide("Hey Vision", 100))
        assertEquals(
            MuseVoiceDecision.Ask("Where is the nearest hospital?"),
            session.decide("Where is the nearest hospital?", 200),
        )
        assertEquals(
            MuseVoiceDecision.Ask("Help me navigate"),
            session.decide("Hey Vision, Help me navigate", 300),
        )
    }

    @Test
    fun fullDictationExpiresAndCanBeClosedWithoutKeepingTheMicrophoneAlive() {
        var time = 100L
        val window = VoiceInputWindow { time }
        assertFalse(window.allowsDictation())
        window.open()
        assertTrue(window.allowsDictation())
        time = 18099
        assertTrue(window.allowsDictation())
        time = 18100
        assertFalse(window.allowsDictation())
        window.open()
        window.close()
        assertFalse(window.allowsDictation())
    }
}
