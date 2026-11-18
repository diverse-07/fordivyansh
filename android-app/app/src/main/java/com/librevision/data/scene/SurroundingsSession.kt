package com.librevision.data.scene

import com.librevision.domain.assistant.*
import com.librevision.domain.scene.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*

/** Ephemeral visual conversation: each question sends only the bounded selected still set. */
class SurroundingsSession(
    private val muse: MuseAssistant,
    private val now: () -> Long,
) {
    private val mutable = MutableStateFlow(SurroundingsState())
    val state = mutable.asStateFlow()
    private var views: List<SurroundingsView> = emptyList()
    private val history = ArrayDeque<AssistantTurn>()
    private var videoDurationMs = 0L
    private var generation = 0L
    private var request = 0L
    private var finishedAt = 0L
    val hasViews
        get() = views.isNotEmpty() && now() - finishedAt in 0..300000

    fun requestConsent() {
        mutable.update { it.copy(phase = SurroundingsPhase.CONSENT) }
    }

    fun cancelConsent() {
        mutable.update {
            it.copy(phase = if (hasViews) SurroundingsPhase.READY else SurroundingsPhase.IDLE)
        }
    }

    fun preparing() {
        mutable.value =
            SurroundingsState(SurroundingsPhase.PREPARING, instruction = "Preparing camera…")
    }

    fun recording(elapsedMs: Long) {
        mutable.value =
            SurroundingsState(
                SurroundingsPhase.CAPTURING,
                instruction = "Recording…",
                elapsedMs = elapsedMs,
            )
    }

    fun extracting() {
        mutable.value =
            SurroundingsState(SurroundingsPhase.EXTRACTING, instruction = "Preparing pictures…")
    }

    fun useVideo(frames: VideoFrames) {
        require(frames.views.size in 10..12)
        views.forEach { it.jpeg.fill(0) }
        views = frames.views
        videoDurationMs = frames.durationMs
        finishedAt = now()
        history.clear()
        generation++
        mutable.value =
            SurroundingsState(
                SurroundingsPhase.ANALYZING,
                views.size,
                views.size,
                instruction = "Describing…",
                capturedAtMs = finishedAt,
            )
    }

    suspend fun ask(question: String, initial: Boolean = false): Result<String> {
        if (!hasViews)
            return Result.failure(IllegalStateException("Capture your surroundings first."))
        val session = generation
        val ticket = ++request
        mutable.update { it.copy(phase = SurroundingsPhase.ANALYZING, instruction = "Thinking…") }
        val context =
            SurroundingsContext(views.toList(), now() - finishedAt, initial, videoDurationMs)
        return try {
            val reply =
                muse
                    .ask(
                        AssistantRequest(
                            question.take(500),
                            "{\"screen\":\"SCENE\",\"savedSurroundings\":true}",
                            history.toList(),
                            surroundings = context,
                        )
                    )
                    .getOrThrow()
            if (session != generation || ticket != request)
                throw CancellationException("Surroundings changed")
            val text = checkNotNull(reply.text) { "Muse did not return a description." }
            check(reply.action == null)
            history.addLast(AssistantTurn("user", question.take(500)))
            history.addLast(AssistantTurn("assistant", text.take(500)))
            while (history.size > 6) history.removeFirst()
            mutable.update {
                it.copy(
                    phase = SurroundingsPhase.READY,
                    summary = if (initial) text else it.summary,
                    answer = if (initial) "" else text,
                    instruction = "Ask about these saved views.",
                )
            }
            Result.success(text)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (session == generation && ticket == request)
                fail(error.message ?: "Muse is unavailable. Try again.")
            Result.failure(error)
        }
    }

    fun fail(message: String) {
        mutable.update { it.copy(phase = SurroundingsPhase.ERROR, instruction = message) }
    }

    fun tick(): Boolean {
        if (views.isNotEmpty() && !hasViews) {
            clear()
            fail("Saved views expired. Capture again for current surroundings.")
            return true
        }
        return false
    }

    fun clear() {
        generation++
        request++
        views.forEach { it.jpeg.fill(0) }
        views = emptyList()
        history.clear()
        finishedAt = 0
        videoDurationMs = 0
        mutable.value = SurroundingsState()
    }
}
