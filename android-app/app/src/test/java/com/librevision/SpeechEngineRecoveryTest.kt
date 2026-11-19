package com.librevision

import com.librevision.domain.alerts.SpeechEngineConnection
import com.librevision.domain.alerts.SpeechSetupGate
import org.junit.Assert.*
import org.junit.Test

class SpeechEngineRecoveryTest {
    private class Engine(val name: String?, val initialized: (Boolean) -> Unit) {
        var disposed = false
    }
    private class Timer(val expired: () -> Unit) { var cancelled = false }
    private class Harness {
        var installed = listOf("installed.engine")
        var synchronous = false
        var preparation: (Engine) -> Boolean = { true }
        val created = mutableListOf<Engine>()
        val timers = mutableListOf<Timer>()
        val ready = mutableListOf<Engine>()
        var failures = 0
        val connection = SpeechEngineConnection(
            installedEngines = { installed },
            create = { name, initialized ->
                Engine(name, initialized).also {
                    created.add(it)
                    if (synchronous) initialized(true)
                }
            },
            prepare = { preparation(it) },
            dispose = { it.disposed = true },
            scheduleTimeout = { expired ->
                val timer = Timer(expired)
                timers.add(timer)
                val cancel: () -> Unit = { timer.cancelled = true }
                cancel
            },
            connected = { ready.add(it) },
            unavailable = { failures++ },
        )
    }

    @Test fun workingPreferredEngineDoesNotSwitchOrOpenStore() {
        val h = Harness()
        h.connection.connect()
        assertNull(h.created.single().name)
        h.created.single().initialized(true)
        h.connection.connect()
        assertSame(h.created.single(), h.connection.readyEngine())
        assertTrue(h.timers.single().cancelled)
        assertEquals(0, h.failures)
        assertFalse(SpeechSetupGate().shouldOpenStore(false))
    }

    @Test fun failedDefaultTriesInstalledEngineAndIgnoresItsLateSuccess() {
        val h = Harness()
        h.connection.connect()
        val original = h.created.single()
        original.initialized(false)
        assertTrue(original.disposed)
        val replacement = h.created.last()
        assertEquals("installed.engine", replacement.name)
        original.initialized(true)
        assertNull(h.connection.readyEngine())
        replacement.initialized(true)
        assertSame(replacement, h.connection.readyEngine())
        assertEquals(listOf(replacement), h.ready)
    }

    @Test fun installingEngineAfterFailureRecoversWithoutRestart() {
        val h = Harness()
        h.installed = emptyList()
        h.connection.connect()
        h.created.single().initialized(false)
        assertEquals(SpeechEngineConnection.State.FAILED, h.connection.state)
        assertEquals(1, h.failures)
        h.installed = listOf("newly.installed.engine")
        h.connection.connect()
        h.created.last().initialized(false)
        assertEquals("newly.installed.engine", h.created.last().name)
        h.created.last().initialized(true)
        assertNotNull(h.connection.readyEngine())
        assertEquals(1, h.failures)
    }

    @Test fun hangingBindTimesOutAndClosedEngineCannotResumeSpeech() {
        val h = Harness()
        h.connection.connect()
        val original = h.created.single()
        h.timers.single().expired()
        assertTrue(original.disposed)
        val replacement = h.created.last()
        h.connection.close()
        assertTrue(replacement.disposed)
        replacement.initialized(true)
        original.initialized(true)
        h.connection.connect(force = true)
        assertNull(h.connection.readyEngine())
        assertEquals(SpeechEngineConnection.State.CLOSED, h.connection.state)
        assertTrue(h.ready.isEmpty())
        assertTrue(h.timers.all { it.cancelled })
    }

    @Test fun immediateInitializationCallbackWaitsForConstructedEngine() {
        val h = Harness()
        h.synchronous = true
        h.connection.connect()
        assertSame(h.created.single(), h.connection.readyEngine())
        assertEquals(1, h.ready.size)
        assertTrue(h.timers.isEmpty())
    }

    @Test fun testSpeechReconnectReplacesOldConnectionAndIgnoresOldCallback() {
        val h = Harness()
        h.connection.connect()
        val original = h.created.single()
        h.connection.connect(force = true)
        assertTrue(original.disposed)
        original.initialized(true)
        assertTrue(h.ready.isEmpty())
        h.created.last().initialized(true)
        assertSame(h.created.last(), h.connection.readyEngine())
    }

    @Test fun brokenPreparationFallsBackAndRepeatedCallbacksDoNotRepeatFailure() {
        val h = Harness()
        h.preparation = { if (it.name == null) error("Disconnected service") else true }
        h.connection.connect()
        h.created.first().initialized(true)
        h.created.last().initialized(true)
        assertEquals("installed.engine", h.connection.readyEngine()?.name)
        h.created.first().initialized(false)
        assertEquals(0, h.failures)
    }

    @Test fun allFailedCandidatesAreBoundedAndStoreVisitDoesNotLoop() {
        val h = Harness()
        h.installed = listOf("a", "a", "b", "c", "d")
        h.connection.connect()
        repeat(4) { h.created.last().initialized(false) }
        assertEquals(4, h.created.size)
        assertEquals(1, h.failures)
        h.created.last().initialized(false)
        assertEquals(1, h.failures)
        val gate = SpeechSetupGate()
        assertTrue(gate.shouldOpenStore(true))
        assertFalse(gate.shouldOpenStore(true))
        assertFalse(gate.shouldOpenStore(false))
        val recreated = SpeechSetupGate(gate.storeOpened)
        assertFalse(recreated.shouldOpenStore(true))
    }
}
