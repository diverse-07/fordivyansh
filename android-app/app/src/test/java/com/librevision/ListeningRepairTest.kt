package com.librevision

import com.librevision.domain.alerts.*
import com.librevision.domain.commands.*
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ListeningRepairTest {
    private fun CountDownLatch.waitFor() {
        assertTrue("Timed out waiting for decoder", await(5, TimeUnit.SECONDS))
    }

    @Test
    fun unknownSpeechBeforeAnyStartCommandDoesNotTerminateListening() {
        val handler = CommandTranscriptHandler()
        val results = Collections.synchronizedList(mutableListOf<TranscriptOutcome>())
        val completed = Array(4) { CountDownLatch(1) }
        val index = AtomicInteger()
        val errors = AtomicInteger()
        SpeechDecodeQueue(
                factory = {
                    object : UtteranceRecognizer {
                        override fun decode(samples: ShortArray) =
                            listOf(
                                "what is the weather",
                                "start scanning",
                                "hello again",
                                "stop scanning",
                            )[samples[0].toInt()]

                        override fun close() = Unit
                    }
                },
                onText = {
                    results.add(handler.handle(it))
                    completed[index.getAndIncrement()].countDown()
                },
                onUtteranceError = { errors.incrementAndGet() },
            )
            .use { decoder ->
                assertTrue(decoder.awaitReady { false })
                repeat(4) { i ->
                    assertTrue(decoder.offer(shortArrayOf(i.toShort())))
                    completed[i].waitFor()
                }
                assertEquals(
                    listOf(null, Command.START_SCANNING, null, Command.STOP_SCANNING),
                    results.map { it.command },
                )
                assertEquals(0, errors.get())
                assertTrue(results[0].message.contains("Still listening"))
            }
    }

    @Test
    fun slowFirstDecodeCannotBlockTheAudioProducerOrOverflowItsPcmQueue() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(3)
        val errors = AtomicInteger()
        SpeechDecodeQueue(
                factory = {
                    object : UtteranceRecognizer {
                        override fun decode(samples: ShortArray): String {
                            started.countDown()
                            release.await()
                            return "unknown speech"
                        }

                        override fun close() = Unit
                    }
                },
                onText = { done.countDown() },
                onUtteranceError = { errors.incrementAndGet() },
            )
            .use { decoder ->
                assertTrue(decoder.awaitReady { false })
                assertTrue(decoder.offer(ShortArray(512)))
                started.waitFor()
                var accepted = 0
                repeat(1000) { if (decoder.offer(ShortArray(512))) accepted++ }
                // STT backpressure rejects extra whole utterances; it does not fail microphone/VAD
                // capture.
                assertEquals(2, accepted)
                release.countDown()
                done.waitFor()
                assertEquals(0, errors.get())
            }
    }

    @Test
    fun oneBadUtteranceDoesNotKillDecoderOrPreventLaterStart() {
        val failed = CountDownLatch(1)
        val decoded = CountDownLatch(1)
        SpeechDecodeQueue(
                factory = {
                    object : UtteranceRecognizer {
                        override fun decode(samples: ShortArray): String {
                            if (samples[0] == 0.toShort())
                                throw IllegalArgumentException("bad utterance")
                            return "start scanning"
                        }

                        override fun close() = Unit
                    }
                },
                onText = {
                    assertEquals(Command.START_SCANNING, VoiceCommandRouter().interpret(it))
                    decoded.countDown()
                },
                onUtteranceError = { failed.countDown() },
            )
            .use { decoder ->
                assertTrue(decoder.awaitReady { false })
                assertTrue(decoder.offer(shortArrayOf(0)))
                failed.waitFor()
                assertTrue(decoder.offer(shortArrayOf(1)))
                decoded.waitFor()
            }
    }

    @Test
    fun interruptedSpeechResultsAreDiscardedBeforeDispatch() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val valid = AtomicBoolean(true)
        val outputs = Collections.synchronizedList(mutableListOf<String>())
        SpeechDecodeQueue(
                factory = {
                    object : UtteranceRecognizer {
                        override fun decode(samples: ShortArray): String {
                            if (samples[0] == 0.toShort()) {
                                started.countDown()
                                release.await()
                            }
                            return if (samples[0] == 0.toShort()) "start scanning"
                            else "stop scanning"
                        }

                        override fun close() = Unit
                    }
                },
                onText = {
                    outputs.add(it)
                    done.countDown()
                },
                onUtteranceError = {},
            )
            .use { decoder ->
                assertTrue(decoder.awaitReady { false })
                decoder.offer(shortArrayOf(0)) { valid.get() }
                started.waitFor()
                valid.set(false)
                decoder.offer(shortArrayOf(1))
                release.countDown()
                done.waitFor()
                assertEquals(listOf("stop scanning"), outputs)
            }
    }

    @Test
    fun nativeVoiceSelectionKeepsDeviceVoiceAndDoesNotChooseAnArbitraryMaleVoice() {
        val voices =
            listOf(
                NativeVoice("first-male", "en", true, true),
                NativeVoice("device-selected", "en", true, true),
                NativeVoice("network", "en", false, true),
                NativeVoice("not-installed", "en", true, false),
            )
        assertEquals(
            "device-selected",
            NativeVoicePolicy.select(voices, "en", "device-selected", "first-male"),
        )
        assertEquals(
            "device-selected",
            NativeVoicePolicy.select(voices, "en", null, "device-selected"),
        )
        assertNull(NativeVoicePolicy.select(voices, "bn", null, null))
        assertNull(NativeVoicePolicy.select(voices.takeLast(2), "en", null, null))
    }

    @Test
    fun playbackInvalidatesAnInFlightTranscriptWithoutStoppingMicrophone() {
        var time = 0L
        val gate = RecognitionPlaybackGate { time }
        val version = gate.version()
        gate.playback(true)
        assertNotEquals(version, gate.version())
        assertTrue(gate.suppressRecognition())
        gate.playback(false)
        time = 601
        assertFalse(gate.suppressRecognition())
        assertNotEquals(version, gate.version())
    }

    @Test
    fun aReadyCueDoesNotCreateOrExtendTheSpeechSuppressionWindow() {
        var time = 0L
        val gate = RecognitionPlaybackGate { time }
        gate.playback(false)
        assertFalse(gate.suppressRecognition())
        gate.playback(true)
        gate.playback(false)
        time = 500
        gate.playback(false)
        assertTrue(gate.suppressRecognition())
        time = 601
        assertFalse(gate.suppressRecognition())
    }
}
