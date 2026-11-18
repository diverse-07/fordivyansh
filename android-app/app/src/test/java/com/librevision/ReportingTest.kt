package com.librevision

import com.librevision.domain.alerts.*
import com.librevision.domain.commands.RecognitionPlaybackGate
import com.librevision.domain.vision.*
import org.junit.Assert.*
import org.junit.Test

class ReportingTest {
    private val policy = ObstructionPolicy()
    private val large = NormalizedBox(.25f, .25f, .75f, .95f)

    @Test
    fun bottleOnTableNeverWinsOverTheTableRegardlessOfConfidence() {
        val bottle = Detection("bottle", .99f, NormalizedBox(.45f, .4f, .55f, .65f))
        val table = Detection("dining table", .76f, large)
        assertEquals(listOf(table), policy.select(listOf(bottle, table)))
        assertEquals("Stop, dining table ahead.", policy.stopMessage(listOf(bottle, table)))
        assertNull(policy.stopMessage(listOf(bottle)))
        assertNull(policy.stopMessage(listOf(bottle.copy(bounds = large))))
    }

    @Test
    fun smallOrDistantObjectsAndHighFurnitureStayVisibleButDoNotWarn() {
        assertTrue(
            policy
                .select(
                    listOf(
                        Detection("person", .99f, NormalizedBox(.45f, .4f, .55f, .65f)),
                        Detection("chair", .99f, NormalizedBox(.3f, .05f, .7f, .5f)),
                        Detection("backpack", .99f, NormalizedBox(.4f, .1f, .7f, .5f)),
                        Detection("bird", .99f, large),
                        Detection("cup", .99f, large),
                        Detection("car", .44f, large),
                    )
                )
                .isEmpty()
        )
    }

    @Test
    fun sizeAndClassWeightTakePrecedenceOverConfidence() {
        val smallCat = Detection("cat", .99f, NormalizedBox(.4f, .6f, .6f, .8f))
        val truck = Detection("truck", .70f, large)
        val result = policy.select(listOf(smallCat, truck))
        assertEquals(listOf("truck", "cat"), result.map { it.label })
        assertEquals("Stop, truck ahead.", policy.stopMessage(result))
        val temporal = TemporalStateEngine()
        assertEquals(
            "truck",
            temporal.consume(Observation(100, result.take(1), false), 200).alertLabel,
        )
    }

    @Test
    fun sufficientlyLargeAnimalsAndPeopleStillAlertImmediately() {
        listOf("cat", "dog", "person", "bicycle").forEach {
            val selected = policy.select(listOf(Detection(it, .8f, large)))
            assertEquals(it, selected.single().label)
            assertTrue(TemporalStateEngine().consume(Observation(100, selected, false), 200).alert)
        }
    }

    @Test
    fun middleStripAndFiniteValidBoxesRemainRequired() {
        assertTrue(
            policy
                .select(
                    listOf(
                        Detection("person", .99f, NormalizedBox(0f, .1f, .4f, .95f)),
                        Detection("chair", .99f, NormalizedBox(.4f, .1f, Float.NaN, .95f)),
                        Detection("cat", Float.NaN, large),
                    )
                )
                .isEmpty()
        )
    }

    @Test
    fun bannerNeverTurnsGreenWithoutFreshUprightLiveEvidence() {
        val evidence = FootpathEvidence()
        assertEquals(
            FootpathIndicator.UNKNOWN,
            evidence.indicator(true, true, PhoneTilt.UPRIGHT, 100),
        )
        evidence.update(Observation(100, emptyList(), false), emptyList(), 200)
        assertEquals(
            FootpathIndicator.NO_OBSTRUCTION,
            evidence.indicator(true, true, PhoneTilt.UPRIGHT, 200),
        )
        assertEquals(
            FootpathIndicator.UNKNOWN,
            evidence.indicator(true, false, PhoneTilt.UPRIGHT, 200),
        )
        assertEquals(
            FootpathIndicator.UNKNOWN,
            evidence.indicator(false, true, PhoneTilt.UPRIGHT, 200),
        )
        assertEquals(
            FootpathIndicator.UNKNOWN,
            evidence.indicator(true, true, PhoneTilt.ADJUST, 200),
        )
        assertEquals(
            FootpathIndicator.UNKNOWN,
            evidence.indicator(true, true, PhoneTilt.UNKNOWN, 200),
        )
        assertEquals(
            FootpathIndicator.UNKNOWN,
            evidence.indicator(true, true, PhoneTilt.UPRIGHT, 4200),
        )
    }

    @Test
    fun obstructionBannerHoldsBrieflyThenExpiresOnlyWithFreshFrames() {
        val evidence = FootpathEvidence()
        val cat = Detection("cat", .9f, large)
        evidence.update(Observation(100, listOf(cat), false), listOf(cat), 200)
        assertEquals(
            FootpathIndicator.OBSTRUCTION,
            evidence.indicator(true, true, PhoneTilt.ADJUST, 200),
        )
        evidence.update(Observation(3000, emptyList(), false), emptyList(), 3100)
        assertEquals(
            FootpathIndicator.OBSTRUCTION,
            evidence.indicator(true, true, PhoneTilt.UPRIGHT, 3100),
        )
        assertEquals(
            FootpathIndicator.NO_OBSTRUCTION,
            evidence.indicator(true, true, PhoneTilt.UPRIGHT, 4200),
        )
        assertEquals(
            FootpathIndicator.UNKNOWN,
            evidence.indicator(true, true, PhoneTilt.UPRIGHT, 8000),
        )
        evidence.reset()
        assertEquals(
            FootpathIndicator.UNKNOWN,
            evidence.indicator(true, true, PhoneTilt.UPRIGHT, 8000),
        )
    }

    @Test
    fun simulatedFutureAndOutOfOrderFramesDoNotCreateGreen() {
        val evidence = FootpathEvidence()
        evidence.update(Observation(100, emptyList(), true), emptyList(), 200)
        evidence.update(Observation(300, emptyList(), false), emptyList(), 200)
        assertEquals(
            FootpathIndicator.UNKNOWN,
            evidence.indicator(true, true, PhoneTilt.UPRIGHT, 200),
        )
        evidence.update(Observation(100, emptyList(), false), emptyList(), 5000)
        assertEquals(
            FootpathIndicator.UNKNOWN,
            evidence.indicator(true, true, PhoneTilt.UPRIGHT, 5000),
        )
    }

    @Test
    fun recognitionSuppressionNeverPreventsUrgentSpeakerRequests() {
        var now = 100L
        val gate = RecognitionPlaybackGate { now }
        var requests = 0
        val output =
            object : SpeechOutput {
                override fun speak(text: String, interrupt: Boolean) {
                    requests++
                    gate.playback(true)
                }

                override fun stop() {
                    gate.playback(false)
                }

                override fun close() = Unit
            }
        val coordinator = AlertCoordinator(output)
        assertFalse(gate.suppressRecognition())
        coordinator.offer(Alert("long scene", Priority.SCENE))
        assertTrue(gate.suppressRecognition())
        assertTrue(coordinator.offer(Alert("Stop, cat ahead.", Priority.URGENT)))
        assertEquals(2, requests)
        coordinator.stop()
        assertTrue(gate.suppressRecognition())
        now += 601
        assertFalse(gate.suppressRecognition())
    }
}
