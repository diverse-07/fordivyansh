package com.librevision

import com.librevision.data.vision.rfdetr.CocoLabels
import com.librevision.domain.commands.*
import com.librevision.domain.vision.*
import org.junit.Assert.*
import org.junit.Test

class FootpathTest {
    private val router = VoiceCommandRouter()
    private val central = NormalizedBox(.35f, .4f, .65f, .9f)

    @Test
    fun startCommandsUseFullNormalizedUtterance() {
        listOf(
                "Start Scanning",
                " PLEASE start scanning! ",
                "begin the scan",
                "scan the foot path",
                "start scanning footpath please",
            )
            .forEach {
                assertEquals(it, Command.START_SCANNING, router.interpret(it))
            }
    }

    @Test
    fun stopCommandsAreSeparateFromGenericStop() {
        listOf(
                "Stop Scanning.",
                "end the scan",
                "please cancel scanning",
                "stop scan footpath please",
            )
            .forEach {
                assertEquals(it, Command.STOP_SCANNING, router.interpret(it))
            }
        assertEquals(Command.STOP, router.interpret("stop"))
    }

    @Test
    fun rejectsNegatedAmbiguousAndSelfSpokenWarnings() {
        listOf(
                "do not start scanning",
                "don't stop scanning",
                "start scanning and stop scanning",
                "stop cat ahead",
                "restart scanning",
                "start scanning tomorrow",
            )
            .forEach {
                assertEquals(it, Command.UNKNOWN, router.interpret(it))
            }
    }

    @Test
    fun scanningRouteWorksFromAnyFeatureIncludingHome() {
        assertEquals(
            ScanCommandAction.OPEN_FOOTPATH_AND_START,
            router.interpret("start scanning").scanAction(),
        )
        assertEquals(ScanCommandAction.STOP, router.interpret("stop scanning").scanAction())
        assertEquals(ScanCommandAction.NONE, router.interpret("navigate").scanAction())
    }

    @Test
    fun includesCatsAndLargeSupportedListWithoutInventingCoverage() {
        assertTrue(ObstructionCatalog.supported.size > 50)
        assertTrue(ObstructionCatalog.supported.all { it in CocoLabels.names })
        assertTrue(ObstructionCatalog.unsupported.intersect(ObstructionCatalog.supported).isEmpty())
        val policy = ObstructionPolicy()
        assertEquals("Stop, cat ahead.", policy.stopMessage(listOf(Detection("cat", .8f, central))))
        assertNull(policy.stopMessage(listOf(Detection("stairs", 1f, central))))
    }

    @Test
    fun ignoresLowConfidenceInvalidAndPeripheralBoxes() {
        val policy = ObstructionPolicy()
        assertTrue(
            policy
                .select(
                    listOf(
                        Detection("cat", .44f, central),
                        Detection("cat", Float.NaN, central),
                        Detection("cat", .99f, NormalizedBox(0f, .5f, .1f, .7f)),
                        Detection("cat", .99f, NormalizedBox(.6f, .5f, .4f, .7f)),
                        Detection("cat", .99f, NormalizedBox(Float.NaN, .5f, .8f, .7f)),
                        Detection("cat", .99f),
                    )
                )
                .isEmpty()
        )
        assertNull(policy.stopMessage(emptyList()))
    }

    @Test
    fun highestConfidenceRelevantObjectIsSpokenAndLabelsDeduplicated() {
        val selected =
            ObstructionPolicy()
                .select(
                    listOf(
                        Detection("cat", .6f, central),
                        Detection("dog", .95f, central),
                        Detection("cat", .7f, central),
                    )
                )
        assertEquals(listOf("dog", "cat"), selected.map { it.label })
        assertEquals("Stop, dog ahead.", ObstructionPolicy().stopMessage(selected))
    }

    @Test
    fun catsAlertImmediatelySuppressRepeatedFramesAndExpire() {
        val temporal = TemporalStateEngine()
        val cat = Detection("cat", .8f, central)
        assertTrue(temporal.consume(Observation(1000, listOf(cat), false), 1100).alert)
        assertFalse(temporal.consume(Observation(2000, listOf(cat), false), 2100).alert)
        assertEquals(PathState.UNCERTAIN, temporal.current(6001).state)
        assertTrue(temporal.consume(Observation(9100, listOf(cat), false), 9200).alert)
        temporal.reset()
        assertTrue(temporal.consume(Observation(9200, listOf(cat), false), 9300).alert)
    }

    @Test
    fun tiltRejectsFlatUpsideDownSidewaysAndInvalidReadings() {
        assertEquals(PhoneTilt.UPRIGHT, TiltPolicy.classify(0f, 9.8f, 0f))
        assertEquals(PhoneTilt.UPRIGHT, TiltPolicy.classify(0f, 9.3f, 2f))
        assertEquals(PhoneTilt.ADJUST, TiltPolicy.classify(0f, 0f, 9.8f))
        assertEquals(PhoneTilt.ADJUST, TiltPolicy.classify(0f, -9.8f, 0f))
        assertEquals(PhoneTilt.ADJUST, TiltPolicy.classify(9.8f, 0f, 0f))
        assertEquals(PhoneTilt.UNKNOWN, TiltPolicy.classify(Float.NaN, 9.8f, 0f))
        assertEquals(PhoneTilt.UNKNOWN, TiltPolicy.classify(0f, 0f, 0f))
    }

    @Test
    fun displayRotationUsesScreenUprightRatherThanNaturalDeviceAxes() {
        assertEquals(PhoneTilt.UPRIGHT, TiltPolicy.classify(-9.8f, 0f, 0f, 1))
        assertEquals(PhoneTilt.UPRIGHT, TiltPolicy.classify(0f, -9.8f, 0f, 2))
        assertEquals(PhoneTilt.UPRIGHT, TiltPolicy.classify(9.8f, 0f, 0f, 3))
        assertEquals(PhoneTilt.ADJUST, TiltPolicy.classify(9.8f, 0f, 0f, 1))
    }

    @Test
    fun newlyAppearingLabelCanInterruptWithoutRepeatingTheOldLabel() {
        val temporal = TemporalStateEngine()
        temporal.consume(Observation(100, listOf(Detection("cat", .9f, central)), false), 200)
        val next =
            temporal.consume(
                Observation(
                    300,
                    listOf(Detection("cat", .9f, central), Detection("dog", .8f, central)),
                    false,
                ),
                400,
            )
        assertTrue(next.alert)
        assertEquals("dog", next.alertLabel)
    }

    @Test
    fun startInstructionAlsoCountsTowardsTiltReminderCooldown() {
        val policy = TiltPromptPolicy()
        policy.reset(initialPromptAt = 1000)
        assertNull(policy.update(PhoneTilt.ADJUST, 1100))
        assertNull(policy.update(PhoneTilt.ADJUST, 2000))
        assertNull(policy.update(PhoneTilt.ADJUST, 60999))
        assertNotNull(policy.update(PhoneTilt.ADJUST, 61000))
    }

    @Test
    fun tiltPromptDebouncesAndDoesNotSpeakOnEverySensorSample() {
        val policy = TiltPromptPolicy()
        assertNull(policy.update(PhoneTilt.ADJUST, 1000))
        assertNull(policy.update(PhoneTilt.ADJUST, 1500))
        assertNotNull(policy.update(PhoneTilt.ADJUST, 5000))
        assertNull(policy.update(PhoneTilt.ADJUST, 3000))
        assertNull(policy.update(PhoneTilt.UPRIGHT, 4000))
        assertNull(policy.update(PhoneTilt.ADJUST, 6000))
        assertNull(policy.update(PhoneTilt.ADJUST, 8000))
        assertNotNull(policy.update(PhoneTilt.ADJUST, 65000))
        policy.reset()
        assertNull(policy.update(PhoneTilt.UNKNOWN, 15000))
    }
}
