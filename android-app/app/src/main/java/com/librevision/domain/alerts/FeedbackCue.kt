package com.librevision.domain.alerts

/** Pleasant confirmations are brief and distinct from caution; none means that a path is safe. */
enum class FeedbackCue(
    val label: String,
    val frequencies: List<Int>,
    val noteMs: Int,
    val haptics: List<Long>,
    val amplitude: Int = 100,
) {
    LISTENING("Listening", listOf(660, 880), 45, listOf(0, 18)),
    CONFIRMED("Ready", listOf(523, 659), 55, listOf(0, 22)),
    CAPTURED("View captured", listOf(988), 45, listOf(0, 15)),
    COMPLETE("Capture complete", listOf(523, 659, 784), 55, listOf(0, 18, 28, 18)),
    TURN_LEFT("Turn left", listOf(784, 659), 50, listOf(0, 28)),
    TURN_RIGHT("Turn right", listOf(659, 784), 50, listOf(0, 14, 25, 14)),
    OBSTACLE("Obstruction", listOf(330, 330), 65, listOf(0, 40, 35, 40), 220),
    UNAVAILABLE("Unavailable", listOf(523, 392), 60, listOf(0, 12, 20, 12, 20, 12)),
    EMERGENCY("SOS Alarm", listOf(988, 1318), 70, listOf(0, 45, 30, 45), 255),
    VOICE_TRIGGER("Voice Activated", listOf(784, 1046), 40, listOf(0, 50), 180),
}
