package com.librevision.domain.alerts

enum class Priority {
    SCENE,
    ROUTINE,
    URGENT,
    TRAFFIC,
}

interface SpeechOutput {
    fun speak(text: String, interrupt: Boolean = true)

    fun stop()

    fun close()
}

data class Alert(val text: String, val priority: Priority)

/** Only higher priority speech interrupts. Callers may retry fresh messages after completion. */
class AlertCoordinator(private val output: SpeechOutput) {
    private var active: Priority? = null
    private var last: String? = null

    @Synchronized
    fun offer(alert: Alert): Boolean {
        // An urgent warning may interrupt a direction or scene, but never another warning.
        if (active?.let { it >= alert.priority } == true) return false
        active = alert.priority
        last = alert.text
        output.speak(alert.text, interrupt = true)
        return true
    }

    /** Distance detail may interrupt a direction, but must not cut off the initial stop warning. */
    @Synchronized
    fun offerRefinement(alert: Alert): Boolean =
        if (active?.let { it >= Priority.URGENT } == true) false else offer(alert)

    @Synchronized fun isSpeaking() = active != null

    @Synchronized
    fun currentAlert(): Alert? = active?.let { priority -> last?.let { Alert(it, priority) } }

    @Synchronized
    fun finished() {
        active = null
    }

    @Synchronized
    fun cancelRoutine() {
        if (active?.let { it < Priority.URGENT } == true) {
            active = null
            output.stop()
        }
    }

    @Synchronized
    fun stop() {
        active = null
        output.stop()
    }

    @Synchronized
    fun replay() {
        last?.let {
            val text = it
            stop()
            offer(Alert(text, Priority.ROUTINE))
        }
    }
}
