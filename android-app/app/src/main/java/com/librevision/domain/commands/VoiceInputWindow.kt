package com.librevision.domain.commands

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Wake/tap starts an 18-second conversation window. Idle speech is never a command or upload. */
class VoiceInputWindow(private val now: () -> Long) {
    private data class Window(val id: Long, val until: Long, val listening: Boolean)

    private val sequence = AtomicLong()
    private val window = AtomicReference<Window?>(null)

    fun open(): Long {
        val id = sequence.incrementAndGet()
        window.set(Window(id, now() + 18000, true))
        return id
    }

    fun close() {
        window.set(null)
    }

    fun token(): Long? = window.get()?.takeIf { now() < it.until }?.id

    fun authorized(id: Long?) = id != null && token() == id

    fun allowsDictation() = window.get()?.let { it.listening && now() < it.until } == true

    fun hasSession() = token() != null

    /**
     * Wake metadata comes only from the local wake recognizer, never a transcript or cloud result.
     */
    fun authorize(result: DecodedSpeech): Long? {
        val wake = result.wakeDetected && AppSpeechPhrases.hasWake(result.text)
        if (wake) open()
        val bareWake =
            AppSpeechPhrases.wakePhrases.any { CommandInterpreter().normalize(result.text) == it }
        return if (wake && bareWake) token() else claim()
    }

    fun claim(): Long? {
        while (true) {
            val current = window.get() ?: return null
            if (!current.listening || now() >= current.until) return null
            val request = Window(sequence.incrementAndGet(), now() + 20000, false)
            if (window.compareAndSet(current, request)) return request.id
        }
    }

    /**
     * Hand an activated conversation to asynchronous search/Muse work, without activating idle
     * audio.
     */
    fun hold(): Long? {
        while (true) {
            val current = window.get() ?: return null
            if (now() >= current.until) return null
            val request = Window(sequence.incrementAndGet(), now() + 45000, false)
            if (window.compareAndSet(current, request)) return request.id
        }
    }

    fun finish(id: Long?) {
        while (true) {
            val current = window.get() ?: return
            if (current.id != id || current.listening || now() >= current.until) return
            if (
                window.compareAndSet(current, current.copy(until = now() + 18000, listening = true))
            )
                return
        }
    }
}
