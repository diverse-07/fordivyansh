package com.librevision

import com.librevision.domain.assistant.*
import com.librevision.domain.commands.*
import org.junit.Assert.*
import org.junit.Test

class GlobalVoiceControlsTest {
    @Test
    fun temporaryMicrophonePausesDoNotSurviveAsUserChoices() {
        assertTrue(HandsFreePreference.enabled(false, false))
        assertTrue(HandsFreePreference.enabled(true, false))
        assertFalse(HandsFreePreference.enabled(false, true))
        assertTrue(HandsFreePreference.enabled(true, true))
    }

    @Test
    fun buttonsMatchLabelsWithCommonActivationPhrases() {
        val controls =
            listOf(
                VoiceControl("scan", "Start Scanning"),
                VoiceControl("back", "Back", setOf("go back")),
            )
        for (text in
            listOf(
                "Start scanning",
                "Press Start Scanning",
                "please click the Start Scanning button",
                "tap start scanning!",
            )) assertEquals(text, "scan", VoiceControls.match(text, controls)?.id)
        assertEquals("back", VoiceControls.match("go back", controls)?.id)
        for (text in
            listOf(
                "don't press start scanning",
                "start scanning tomorrow",
                "press stop scanning",
                "click start scanning and go back",
            )) assertNull(text, VoiceControls.match(text, controls))
    }

    @Test
    fun unavailableAndAmbiguousButtonsNeverActivate() {
        assertNull(VoiceControls.match("Replay", emptyList()))
        assertNull(
            VoiceControls.match(
                "Replay",
                listOf(VoiceControl("a", "Replay"), VoiceControl("b", "Replay")),
            )
        )
    }

    @Test
    fun backHomeAndSettingsAreLocalCommands() {
        val router = VoiceCommandRouter()
        assertEquals(Command.BACK, router.interpret("go back"))
        assertEquals(Command.BACK, router.interpret("close dialog"))
        assertEquals(Command.HOME, router.interpret("main menu"))
        assertEquals(Command.SETTINGS, router.interpret("please open settings"))
    }

    @Test
    fun authorizedWakeAcceptsAnyQuestionAndEchoTailExpiresQuickly() {
        val session = MuseVoiceSession()
        assertEquals(
            MuseVoiceDecision.Ask("Why is the sky blue?"),
            session.decide("Hey Vision, Why is the sky blue?", 1000),
        )
        assertTrue(
            session.decide("What should I do if I feel lost?", 2000) is MuseVoiceDecision.Ask
        )
        session.rememberSpeech("Say Hey Vision for help.", 3000)
        session.playbackEnded(4000)
        assertEquals(MuseVoiceDecision.Ignore, session.decide("Hey Vision", 4500, true))
        assertEquals(MuseVoiceDecision.Wake, session.decide("Hey Vision", 5201, true))
    }

    @Test
    fun controlToolsAcceptOnlyBoundedSemanticIds() {
        assertTrue(MuseAction(MuseActionKind.ACTIVATE_CONTROL, value = "more_settings").valid())
        assertTrue(MuseAction(MuseActionKind.BACK).valid())
        for (id in
            listOf(
                "",
                "../settings",
                "https://example.com",
                "tap 100 200",
                "a".repeat(41),
            )) assertFalse(id, MuseAction(MuseActionKind.ACTIVATE_CONTROL, value = id).valid())
        assertFalse(
            MuseAction(MuseActionKind.ACTIVATE_CONTROL, value = "go", query = "execute").valid()
        )
    }

    @Test
    fun assistantPositionRequiresFreshFiniteLocationAndAccuracy() {
        val fix = com.librevision.domain.navigation.LocationFix(23.7, 90.4, 1000, 12f)
        assertTrue(AssistantLocationPolicy.fresh(fix, 31000))
        assertFalse(AssistantLocationPolicy.fresh(fix, 31001))
        assertFalse(AssistantLocationPolicy.fresh(fix, 999))
        assertFalse(AssistantLocationPolicy.fresh(null, 1000))
        assertFalse(AssistantLocationPolicy.fresh(fix.copy(accuracyMeters = Float.NaN), 1000))
        assertFalse(AssistantLocationPolicy.fresh(fix.copy(accuracyMeters = 201f), 1000))
        assertFalse(AssistantLocationPolicy.fresh(fix.copy(latitude = Double.NaN), 1000))
    }

    @Test
    fun grammarIncludesSpokenButtonNamesWithoutRemovingUnknownTokenSupport() {
        assertTrue("press start scanning" in AppSpeechPhrases.phrases)
        assertTrue("click replay" in AppSpeechPhrases.phrases)
        assertTrue("tap more settings" in AppSpeechPhrases.phrases)
        assertFalse(AppSpeechPhrases.actionable("do not start scanning"))
    }
}
