package com.librevision

import com.librevision.domain.commands.*
import com.librevision.domain.vision.*
import org.junit.Assert.*
import org.junit.Test

class HandsFreeTest {
    private val router = VoiceCommandRouter()

    @Test
    fun matchesBoundedSpellingAndPronunciationErrors() {
        listOf(
                "star scanning",
                "stark scanning",
                "start skaning",
                "start scamming",
                "start skinning",
                "please stat scaning please",
            )
            .forEach {
                assertEquals(it, Command.START_SCANNING, router.interpret(it))
            }
        listOf("stpo scanning", "stap scanning", "stop skaning", "stop scannin").forEach {
            assertEquals(it, Command.STOP_SCANNING, router.interpret(it))
        }
    }

    @Test
    fun fuzzyMatchingDoesNotAcceptNegationMixedCommandsOrUnrelatedSpeech() {
        listOf(
                "do not star scanning",
                "dont start scanning",
                "start stop scanning",
                "stop cat ahead",
                "start scanning tomorrow",
                "restart scanning",
                "start singing",
                "stop walking",
                "play music",
                "st scanning",
            )
            .forEach {
                assertEquals(it, Command.UNKNOWN, router.interpret(it))
            }
        // Too much action-word uncertainty must not trigger scanning.
        assertEquals(Command.UNKNOWN, router.interpret("stock scanning"))
    }

    @Test
    fun stripsAreFullHeightAndSideCentresDoNotWarn() {
        fun isMiddle(center: Float) =
            NormalizedBox(center - .05f, 0f, center + .05f, .1f).overlapsCautionRegion()
        assertFalse(isMiddle(.249f))
        assertTrue(isMiddle(.25f))
        assertTrue(isMiddle(.5f))
        assertTrue(isMiddle(.749f))
        assertFalse(isMiddle(.75f))
        assertFalse(isMiddle(.9f))
        assertTrue(NormalizedBox(.4f, .9f, .6f, 1f).overlapsCautionRegion())
        assertFalse(NormalizedBox(.4f, -1f, .6f, -.1f).overlapsCautionRegion())
        // A side-centred object can overlap the middle without being reported as ahead.
        assertFalse(NormalizedBox(.1f, .2f, .3f, .8f).overlapsCautionRegion())
    }

    @Test
    fun silenceAndSingleFrameNoiseDoNotCreateUtterances() {
        val segmenter = SpeechSegmenter()
        repeat(200) { assertNull(segmenter.accept(ShortArray(512), .01f)) }
        assertNull(segmenter.accept(ShortArray(512), .9f))
        repeat(25) { assertNull(segmenter.accept(ShortArray(512), .01f)) }
    }

    @Test
    fun speechIncludesPreRollAndWaitsForSilenceBeforeEmitting() {
        val segmenter = SpeechSegmenter()
        repeat(5) { segmenter.accept(ShortArray(512) { 7 }, .01f) }
        repeat(5) { assertNull(segmenter.accept(ShortArray(512) { 9 }, .9f)) }
        repeat(18) { assertNull(segmenter.accept(ShortArray(512), .01f)) }
        val utterance = segmenter.accept(ShortArray(512), .01f)!!
        assertTrue(utterance.take(512).all { it == 7.toShort() })
        assertTrue(utterance.any { it == 9.toShort() })
        repeat(30) { assertNull(segmenter.accept(ShortArray(512), .01f)) }
    }

    @Test
    fun continuousSpeechIsBoundedAndResetDropsAnInterruptedUtterance() {
        val segmenter = SpeechSegmenter()
        var result: ShortArray? = null
        repeat(250) { segmenter.accept(ShortArray(512), .95f)?.let { out -> result = out } }
        assertNotNull(result)
        assertTrue(result!!.size <= 250 * 512)
        repeat(8) { segmenter.accept(ShortArray(512), .95f) }
        segmenter.reset()
        repeat(30) { assertNull(segmenter.accept(ShortArray(512), .01f)) }
    }

    @Test
    fun briefPausesStayInOneUtteranceAndTinyBlipsAreDiscarded() {
        val segmenter = SpeechSegmenter()
        repeat(4) { segmenter.accept(ShortArray(512), .9f) }
        repeat(10) { assertNull(segmenter.accept(ShortArray(512), .1f)) }
        repeat(4) { assertNull(segmenter.accept(ShortArray(512), .9f)) }
        repeat(18) { assertNull(segmenter.accept(ShortArray(512), .1f)) }
        assertNotNull(segmenter.accept(ShortArray(512), .1f))
        repeat(2) { segmenter.accept(ShortArray(512), .9f) }
        repeat(19) { assertNull(segmenter.accept(ShortArray(512), .1f)) }
    }
}
