package com.librevision

import com.librevision.domain.alerts.*
import com.librevision.domain.commands.*
import com.librevision.domain.navigation.*
import com.librevision.domain.vision.*
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test
    fun normalizesPunctuationWhitespaceAndCase() {
        val router = CommandInterpreter()
        assertEquals(Command.CHECK_FOOTPATH, router.interpret("  CHECK   foot-path!! "))
        assertEquals(Command.NAVIGATE, router.interpret("পথ দেখাও।"))
        assertEquals(Command.STOP, router.interpret("থামো!"))
        assertEquals(Command.REPEAT, router.interpret("Say again."))
    }

    @Test
    fun unknownAndMultipleCommandsAreRejected() {
        val router = CommandInterpreter()
        listOf("", "   ", "navigate then stop", "go across the road", "don't stop", "!!").forEach {
            assertEquals(it, Command.UNKNOWN, router.interpret(it))
        }
    }

    @Test
    fun ambiguousAliasesAreRejected() {
        val router =
            CommandInterpreter(mapOf(Command.STOP to setOf("go"), Command.NAVIGATE to setOf("GO!")))
        assertEquals(Command.UNKNOWN, router.interpret("go"))
    }

    @Test
    fun aliasesCanBeExtendedWithoutChangingUi() {
        val router =
            CommandInterpreter(CommandInterpreter.defaults + (Command.NAVIGATE to setOf("take me")))
        assertEquals(Command.NAVIGATE, router.interpret("Take me!"))
    }

    @Test
    fun provisionalAlertIsImmediateAndDuplicatesSuppressed() {
        val engine = TemporalStateEngine()
        fun observation(t: Long) = Observation(t, listOf(Detection("bicycle", .8f)), true)
        assertTrue(engine.consume(observation(100), 100).alert)
        assertFalse(engine.consume(observation(200), 200).alert)
        assertEquals(PathState.POSSIBLE_BLOCKAGE, engine.current(300).state)
        assertEquals(PathState.UNCERTAIN, engine.current(4201).state)
        assertFalse(engine.consume(observation(100), 4201).alert)
        assertTrue(engine.consume(observation(8300), 8300).alert)
    }

    @Test
    fun emptyDetectionsNeverMeanSafeAndDoNotImmediatelyEraseObservation() {
        val engine = TemporalStateEngine()
        assertEquals(
            PathState.UNCERTAIN,
            engine.consume(Observation(100, emptyList(), true), 100).state,
        )
        engine.consume(Observation(200, listOf(Detection("car", .9f)), true), 200)
        assertEquals(
            PathState.POSSIBLE_BLOCKAGE,
            engine.consume(Observation(300, emptyList(), true), 300).state,
        )
        assertEquals(PathState.UNCERTAIN, engine.current(4201).state)
    }

    @Test
    fun staleFutureLowConfidenceAndOutOfOrderObservationsAreIgnored() {
        val engine = TemporalStateEngine()
        val car = listOf(Detection("car", 1f))
        assertEquals(PathState.UNCERTAIN, engine.consume(Observation(10000, car, true), 200).state)
        assertEquals(PathState.UNCERTAIN, engine.consume(Observation(100, car, true), 5000).state)
        assertFalse(
            engine.consume(Observation(5000, listOf(Detection("car", .2f)), true), 5000).alert
        )
        assertFalse(engine.consume(Observation(4999, car, true), 5000).alert)
    }

    @Test
    fun newObjectCanAlertDuringSuppressionWindow() {
        val engine = TemporalStateEngine()
        assertTrue(engine.consume(Observation(100, listOf(Detection("car", 1f)), true), 100).alert)
        assertTrue(engine.consume(Observation(200, listOf(Detection("bus", 1f)), true), 200).alert)
    }

    @Test
    fun urgentAlertsInterruptAndBlockRoutineSpeechUntilCompletion() {
        val output = RecordingSpeech()
        val coordinator = AlertCoordinator(output)
        assertTrue(coordinator.offer(Alert("long scene", Priority.SCENE)))
        assertTrue(coordinator.offer(Alert("obstruction", Priority.URGENT)))
        assertFalse(coordinator.offer(Alert("turn", Priority.ROUTINE)))
        assertFalse(coordinator.offer(Alert("scene", Priority.SCENE)))
        assertEquals(listOf("long scene", "obstruction"), output.messages)
        assertTrue(output.interrupted)
        coordinator.finished()
        assertTrue(coordinator.offer(Alert("turn", Priority.ROUTINE)))
    }

    @Test
    fun stopReleasesPriorityAndReplayUsesLastAcceptedMessage() {
        val output = RecordingSpeech()
        val coordinator = AlertCoordinator(output)
        coordinator.offer(Alert("obstruction", Priority.URGENT))
        coordinator.offer(Alert("discarded", Priority.SCENE))
        coordinator.stop()
        assertTrue(output.stopped)
        coordinator.replay()
        assertEquals(listOf("obstruction", "obstruction"), output.messages)
        coordinator.finished()
        assertTrue(coordinator.offer(Alert("scene", Priority.SCENE)))
    }

    @Test
    fun navigationTransitionsAreExplicitAndRequireDemoRoute() {
        val engine = DemoNavigationEngine { 30000 }
        assertEquals(NavigationStatus.IDLE, engine.currentGuidance().value.status)
        engine.start(route())
        assertEquals(NavigationStatus.ACTIVE, engine.currentGuidance().value.status)
        engine.advance()
        assertEquals("Demo: two", engine.currentGuidance().value.message)
        engine.advance()
        assertEquals(NavigationStatus.ARRIVED, engine.currentGuidance().value.status)
        engine.stop()
        engine.advance()
        assertEquals(NavigationStatus.STOPPED, engine.currentGuidance().value.status)
        engine.start(route())
        assertEquals("Demo: one", engine.currentGuidance().value.message)
    }

    @Test(expected = IllegalArgumentException::class)
    fun demoEngineRejectsRealRoutes() {
        DemoNavigationEngine { 0 }.start(route().copy(simulated = false))
    }

    @Test
    fun staleFutureInaccurateAndOutOfOrderGpsNeverReplaceAcceptedFix() {
        val engine = DemoNavigationEngine { 30000 }
        engine.start(route())
        engine.updateLocation(LocationFix(23.8, 90.4, 29000, 5f))
        val accepted = engine.currentGuidance().value
        assertEquals(29000L, accepted.lastLocationTimestampMs)
        listOf(
                LocationFix(23.8, 90.4, 100, 5f),
                LocationFix(23.8, 90.4, 31000, 5f),
                LocationFix(23.8, 90.4, 28999, 5f),
                LocationFix(23.8, 90.4, 29500, 100f),
                LocationFix(Double.NaN, 90.4, 29500, 5f),
            )
            .forEach(engine::updateLocation)
        assertEquals(accepted, engine.currentGuidance().value)
        engine.updateLocation(LocationFix(23.8, 90.4, 29500, 5f))
        assertEquals(29500L, engine.currentGuidance().value.lastLocationTimestampMs)
        assertEquals("Demo: one", engine.currentGuidance().value.message)
        engine.stop()
        engine.updateLocation(LocationFix(23.8, 90.4, 30000, 5f))
        assertNull(engine.currentGuidance().value.lastLocationTimestampMs)
    }

    @Test
    fun uncertainHeadingIsNotPromotedToReliable() {
        val engine = DemoNavigationEngine { 0 }
        listOf(
                HeadingEstimate(null, true),
                HeadingEstimate(Float.NaN, true),
                HeadingEstimate(20f, false),
                HeadingEstimate(900f, true),
            )
            .forEach {
                engine.updateHeading(it)
                assertFalse(engine.currentGuidance().value.headingReliable)
            }
        engine.updateHeading(HeadingEstimate(20f, true))
        assertTrue(engine.currentGuidance().value.headingReliable)
    }

    private fun route() = WalkingRoute(Destination("park", "Park"), listOf("one", "two"), true)

    private class RecordingSpeech : SpeechOutput {
        val messages = mutableListOf<String>()
        var interrupted = false
        var stopped = false

        override fun speak(text: String, interrupt: Boolean) {
            messages.add(text)
            interrupted = interrupt
        }

        override fun stop() {
            stopped = true
        }

        override fun close() = Unit
    }
}
