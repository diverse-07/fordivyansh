package com.librevision

import com.librevision.domain.commands.DecodedSpeech
import com.librevision.domain.commands.VoiceInputWindow
import org.junit.Assert.*
import org.junit.Test

class VoiceInputPrivacyTest {
    @Test
    fun idleCannotAuthorizeAnyCommandOrCloudFallback() {
        val gate = VoiceInputWindow { 100L }
        repeat(10) {
            assertNull(gate.claim())
            assertNull(gate.token())
            assertFalse(gate.allowsDictation())
            assertFalse(gate.authorized(null))
        }
        for (text in
            listOf(
                "start scanning",
                "go",
                "go to Square Hospital",
                "hey vision",
                "random conversation",
                "",
            )) {
            assertNull(gate.authorize(DecodedSpeech(text, 1f)))
        }
        assertNotNull(gate.authorize(DecodedSpeech("hey vision", 1f, wakeDetected = true)))
        assertTrue(gate.allowsDictation())
    }

    @Test
    fun completedRequestsAllowFollowupsFor18SecondsThenReturnToWakeDetection() {
        var now = 100L
        val gate = VoiceInputWindow { now }
        gate.open()
        assertTrue(gate.allowsDictation())
        val activation = checkNotNull(gate.claim())
        assertFalse(gate.allowsDictation())
        assertNull(gate.claim()) // A second conversation cannot join an in-flight request.
        assertTrue(gate.authorized(activation))
        gate.finish(activation)
        assertTrue(gate.allowsDictation())
        now += 17999
        val go = checkNotNull(gate.authorize(DecodedSpeech("go", 1f)))
        assertNotEquals(activation, go)
        assertFalse(gate.authorized(activation))
        gate.finish(go)
        now += 18000
        assertFalse(gate.allowsDictation())
        assertNull(gate.authorize(DecodedSpeech("go", 1f)))
    }

    @Test
    fun staleRepliesCannotRunAndFinishingOldRequestCannotCloseNewWake() {
        val gate = VoiceInputWindow { 100L }
        val old = gate.open()
        gate.claim()
        val current = gate.open()
        assertFalse(gate.authorized(old))
        gate.finish(old)
        assertTrue(gate.authorized(current))
        assertTrue(gate.allowsDictation())
        gate.close() // Backgrounding, dismissing or leaving the screen revokes permission.
        assertFalse(gate.authorized(current))
        assertNull(gate.claim())
    }

    @Test
    fun listeningAndProcessingBothExpireWithoutReopening() {
        var clock = 100L
        val gate = VoiceInputWindow { clock }
        val wake = gate.open()
        clock += 18000
        assertFalse(gate.authorized(wake))
        assertNull(gate.claim())
        gate.open()
        val request = checkNotNull(gate.claim())
        clock += 20000
        assertFalse(gate.authorized(request))
        assertFalse(gate.allowsDictation())
        assertNull(gate.claim())
    }

    @Test
    fun asynchronousSearchHandsOffAuthorizationAndStartsFollowupTimeWhenResultsAreReady() {
        var now = 100L
        val gate = VoiceInputWindow { now }
        gate.open()
        val speech = checkNotNull(gate.claim())
        val muse = checkNotNull(gate.hold())
        gate.finish(speech)
        assertFalse(gate.allowsDictation())
        now += 10000
        val search = checkNotNull(gate.hold())
        gate.finish(muse)
        assertFalse(gate.allowsDictation())
        now += 15000
        gate.finish(search)
        assertTrue(gate.allowsDictation())
        now += 17999
        assertNotNull(gate.authorize(DecodedSpeech("go", 1f)))
    }

    @Test
    fun cancelledOrExpiredWorkCannotReopenListening() {
        var now = 100L
        val gate = VoiceInputWindow { now }
        assertNull(gate.hold())
        gate.open()
        val cancelled = checkNotNull(gate.hold())
        gate.close()
        gate.finish(cancelled)
        assertFalse(gate.allowsDictation())
        gate.open()
        val expired = checkNotNull(gate.hold())
        now += 45000
        gate.finish(expired)
        assertFalse(gate.allowsDictation())
        assertNull(gate.hold())
    }
}
