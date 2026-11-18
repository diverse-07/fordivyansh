package com.librevision.domain.commands

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface UtteranceRecognizer : AutoCloseable {
    fun decode(samples: ShortArray): String

    fun decodeDetailed(samples: ShortArray) = DecodedSpeech(decode(samples))
}

/** Slow STT cannot block VAD/capture. At most two pending utterances, then ask for a repeat. */
class SpeechDecodeQueue(
    private val factory: () -> UtteranceRecognizer,
    private val onText: (String) -> Unit,
    private val onUtteranceError: () -> Unit,
    private val onWakeOnlyText: (String) -> Unit = {},
    private val onDecoded: ((DecodedSpeech, ShortArray) -> Unit)? = null,
    private val onDecodeFailure: ((ShortArray) -> Unit)? = null,
) : AutoCloseable {
    private data class Work(
        val samples: ShortArray,
        val valid: () -> Boolean,
        val normalAllowed: () -> Boolean = { true },
    )

    private val pending = ArrayBlockingQueue<Work>(2)
    private val running = AtomicBoolean(true)
    private val ready = CountDownLatch(1)
    @Volatile private var startupError: Throwable? = null
    private val thread =
        Thread(
                {
                    try {
                        factory().use { recognizer ->
                            ready.countDown()
                            while (running.get()) {
                                val work = pending.poll(100, TimeUnit.MILLISECONDS) ?: continue
                                if (!work.valid()) {
                                    work.samples.fill(0)
                                    continue
                                }
                                try {
                                    val result = recognizer.decodeDetailed(work.samples)
                                    if (running.get() && work.valid()) {
                                        if (work.normalAllowed()) {
                                            if (onDecoded != null)
                                                onDecoded.invoke(result, work.samples)
                                            else onText(result.text)
                                        } else onWakeOnlyText(result.text)
                                    }
                                } catch (_: Exception) {
                                    if (running.get() && work.valid()) {
                                        if (work.normalAllowed() && onDecodeFailure != null)
                                            onDecodeFailure.invoke(work.samples)
                                        else onUtteranceError()
                                    }
                                } finally {
                                    work.samples.fill(0)
                                }
                            }
                        }
                    } catch (_: InterruptedException) {
                        // Closing a session interrupts only queue waits, never the microphone.
                    } catch (error: Throwable) {
                        startupError = error
                        if (ready.count == 0L && running.get()) onUtteranceError()
                    } finally {
                        running.set(false)
                        clearPending()
                        ready.countDown()
                    }
                },
                "netrasaathi-stt",
            )
            .also { it.start() }

    fun awaitReady(cancelled: () -> Boolean): Boolean {
        while (!ready.await(100, TimeUnit.MILLISECONDS)) if (cancelled()) return false
        if (cancelled()) return false
        startupError?.let { throw IllegalStateException("Speech model initialization failed", it) }
        return running.get()
    }

    private fun enqueue(
        samples: ShortArray,
        valid: () -> Boolean,
        normalAllowed: () -> Boolean,
    ): Boolean {
        if (!running.get()) return false
        val owned = samples.copyOf()
        if (pending.offer(Work(owned, valid, normalAllowed))) return true
        owned.fill(0)
        return false
    }

    private fun clearPending() {
        while (true) {
            val work = pending.poll() ?: break
            work.samples.fill(0)
        }
    }

    fun offer(samples: ShortArray, valid: () -> Boolean = { true }): Boolean =
        enqueue(samples, valid, { true })

    fun offerWakeAware(
        samples: ShortArray,
        normalAllowed: () -> Boolean,
        valid: () -> Boolean,
    ): Boolean = enqueue(samples, valid, normalAllowed)

    override fun close() {
        running.set(false)
        clearPending()
        thread.interrupt()
        if (Thread.currentThread() !== thread) thread.join()
    }
}
