package com.librevision.domain.alerts

/** Main-thread connection lifecycle. Late engine callbacks cannot revive a replaced connection. */
class SpeechEngineConnection<E>(
    private val installedEngines: () -> List<String>,
    private val create: (String?, (Boolean) -> Unit) -> E,
    private val prepare: (E) -> Boolean,
    private val dispose: (E) -> Unit,
    private val scheduleTimeout: (() -> Unit) -> (() -> Unit),
    private val connected: (E) -> Unit,
    private val unavailable: () -> Unit,
) {
    enum class State { IDLE, CONNECTING, READY, FAILED, CLOSED }

    var state = State.IDLE
        private set
    private var engine: E? = null
    private var generation = 0L
    private var cancelTimeout: (() -> Unit)? = null
    private val candidates = ArrayDeque<String?>()

    fun readyEngine(): E? = engine.takeIf { state == State.READY }

    fun connect(force: Boolean = false) {
        if (state == State.CLOSED) return
        if (!force && state in setOf(State.READY, State.CONNECTING)) return
        candidates.clear()
        // The null engine asks Android to honor the user's preferred engine first.
        candidates.addLast(null)
        runCatching { installedEngines() }.getOrDefault(emptyList())
            .filter { it.isNotBlank() }.distinct().take(3).forEach(candidates::addLast)
        next()
    }

    private fun release() {
        generation++
        cancelTimeout?.invoke()
        cancelTimeout = null
        val previous = engine
        engine = null
        previous?.let { runCatching { dispose(it) } }
    }

    private fun next() {
        release()
        if (candidates.isEmpty()) {
            state = State.FAILED
            unavailable()
            return
        }
        state = State.CONNECTING
        val ticket = generation
        val name = candidates.removeFirst()
        var constructing = true
        var deferred: Boolean? = null
        val created = try {
            create(name) { success ->
                if (constructing) deferred = success else initialized(ticket, success)
            }
        } catch (_: Exception) {
            constructing = false
            next()
            return
        }
        engine = created
        constructing = false
        val immediate = deferred
        if (immediate != null) initialized(ticket, immediate)
        if (ticket == generation && state == State.CONNECTING) {
            cancelTimeout = scheduleTimeout { initialized(ticket, false) }
        }
    }

    private fun initialized(ticket: Long, success: Boolean) {
        if (ticket != generation || state != State.CONNECTING) return
        cancelTimeout?.invoke()
        cancelTimeout = null
        val current = engine
        if (!success || current == null || !runCatching { prepare(current) }.getOrDefault(false)) {
            next()
            return
        }
        state = State.READY
        connected(current)
    }

    fun close() {
        release()
        candidates.clear()
        state = State.CLOSED
    }
}
