package com.librevision.domain.commands

/** Shared production gesture state machine. Clock values are monotonic milliseconds. */
class VolumeUpGesture {
    enum class Action { VOLUME_UP, VOICE, ALARM }
    private var downAt: Long? = null
    private var firstClick: Long? = null
    private var clicks = 0
    private var held = false
    fun down(now: Long): List<Action> {
        val actions = tick(now)
        if (downAt == null) { downAt = now; held = false }
        return actions
    }
    fun up(now: Long, cancelled: Boolean = false): List<Action> {
        if (cancelled) { cancel(); return emptyList() }
        if (downAt == null) return emptyList()
        val actions = tick(now).toMutableList()
        downAt = null
        if (held) { held = false; return actions }
        if (firstClick?.let { now - it > 1200 } == true) {
            repeat(clicks) { actions += Action.VOLUME_UP }
            clicks = 0; firstClick = null
        }
        if (firstClick == null) firstClick = now
        clicks++
        if (clicks == 3) {
            clicks = 0; firstClick = null
            actions += Action.ALARM
        }
        return actions
    }
    fun tick(now: Long): List<Action> {
        val down = downAt
        if (down != null && !held && now - down >= 2000) {
            held = true; clicks = 0; firstClick = null
            return listOf(Action.VOICE)
        }
        if (down == null && firstClick?.let { now - it > 1200 } == true) {
            val result = List(clicks) { Action.VOLUME_UP }
            clicks = 0; firstClick = null
            return result
        }
        return emptyList()
    }
    fun nextDeadline(): Long? = downAt?.takeIf { !held }?.plus(2000)
        ?: firstClick?.takeIf { downAt == null }?.plus(1201)
    fun cancel() { downAt = null; firstClick = null; clicks = 0; held = false }
}
