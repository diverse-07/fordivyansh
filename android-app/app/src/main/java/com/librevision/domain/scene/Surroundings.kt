package com.librevision.domain.scene

enum class SurroundingsPhase {
    IDLE,
    CONSENT,
    PREPARING,
    CAPTURING,
    EXTRACTING,
    ANALYZING,
    READY,
    ERROR,
}

data class SurroundingsState(
    val phase: SurroundingsPhase = SurroundingsPhase.IDLE,
    val captured: Int = 0,
    val total: Int = 0,
    val instruction: String = "Record what's around you, then ask questions.",
    val summary: String = "",
    val answer: String = "",
    val capturedAtMs: Long = 0,
    val elapsedMs: Long = 0,
)

data class SurroundingsView(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    val offsetMs: Long,
    val capturedAtMs: Long,
)

data class SurroundingsContext(
    val views: List<SurroundingsView>,
    val ageMs: Long,
    val initial: Boolean,
    val videoDurationMs: Long,
)

/**
 * One representative timestamp in each equal-duration chunk; no rotation or coverage assumptions.
 */
object VideoFramePlan {
    const val AUTO_CAPTURE_MS = 5000L
    const val MAX_DURATION_MS = 30000L

    fun offsets(durationMs: Long, count: Int = 12): List<Long> {
        require(durationMs in 750..MAX_DURATION_MS + 2000) {
            "Video too short. Record for at least one second."
        }
        require(count in 10..12)
        return (0 until count).map { ((it + .5) * durationMs / count).toLong() }
    }
}
