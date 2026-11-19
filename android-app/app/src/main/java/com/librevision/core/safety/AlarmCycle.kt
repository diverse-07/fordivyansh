package com.librevision.core.safety
/** Streaming AudioTrack does not transition to STOPPED merely because its buffer drains. */
object AlarmCycle {
    fun complete(framesPlayed: Long, framesWritten: Int, elapsedMs: Long): Boolean =
        framesPlayed >= framesWritten || elapsedMs >= 3500
}
