package com.librevision.domain.commands

import java.util.concurrent.atomic.AtomicLong

/**
 * Marks app playback so decoded speech can be restricted to explicit wake requests. Never controls
 * speaker output.
 */
class RecognitionPlaybackGate(private val nowMs: () -> Long) {
    private val epoch = AtomicLong()

    fun version() = epoch.get()

    fun isPlaying() = playing

    @Volatile private var playing = false
    @Volatile private var until = 0L

    fun playback(active: Boolean) {
        if (active == playing) return
        if (active) epoch.incrementAndGet()
        playing = active
        until = nowMs() + 600
    }

    fun suppressRecognition() = playing || nowMs() < until
}
