package com.librevision

import com.librevision.core.audio.FeedbackCues
import com.librevision.data.scene.*
import com.librevision.domain.alerts.FeedbackCue
import com.librevision.domain.assistant.*
import com.librevision.domain.commands.*
import com.librevision.domain.scene.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SurroundingsTest {
    private var now = 100000L

    private fun frames(count: Int = 12): VideoFrames {
        val duration = 12000L
        return VideoFrames(
            VideoFramePlan.offsets(duration, count).map {
                SurroundingsView(byteArrayOf(1, 2, 3), 512, 384, it, now - duration + it)
            },
            duration,
            10,
            200000,
        )
    }

    @Test
    fun framesSpanAllChunksWithoutAssumingAnglesOrCoverage() {
        val offsets = VideoFramePlan.offsets(VideoFramePlan.AUTO_CAPTURE_MS)
        assertEquals(12, offsets.size)
        assertEquals(208L, offsets.first())
        assertEquals(4791L, offsets.last())
        assertTrue(offsets.zipWithNext().all { (a, b) -> b - a in 416L..417L })
        assertEquals(10, VideoFramePlan.offsets(9000, 10).distinct().size)
        assertEquals(12, VideoFramePlan.offsets(750).distinct().size)
    }

    @Test
    fun invalidVideoDurationAndCountsAreRejected() {
        listOf(0L, 749L, 32001L, Long.MAX_VALUE).forEach { duration ->
            assertThrows(IllegalArgumentException::class.java) { VideoFramePlan.offsets(duration) }
        }
        listOf(0, 9, 13).forEach { count ->
            assertThrows(IllegalArgumentException::class.java) {
                VideoFramePlan.offsets(12000, count)
            }
        }
    }

    @Test
    fun conversationKeepsFramesAndCapsHistory() = runBlocking {
        val requests = mutableListOf<AssistantRequest>()
        val session =
            SurroundingsSession(
                MuseAssistant {
                    requests += it
                    Result.success(AssistantReply("A pharmacy is visible beside a white building."))
                },
                { now },
            )
        val selected = frames()
        session.useVideo(selected)
        assertTrue(session.ask("Describe useful surroundings", true).isSuccess)
        repeat(5) { assertTrue(session.ask("Where is the pharmacy?").isSuccess) }
        assertTrue(requests.first().surroundings!!.initial)
        assertFalse(requests.last().surroundings!!.initial)
        assertEquals(12000L, requests.last().surroundings!!.videoDurationMs)
        assertEquals(6, requests.last().history.size)
        assertTrue(
            requests.all { it.surroundings!!.views.first().jpeg === selected.views.first().jpeg }
        )
    }

    @Test
    fun expiryAndLeavingEraseSelectedPictures() = runBlocking {
        val session =
            SurroundingsSession(
                MuseAssistant { Result.success(AssistantReply("A tree is visible.")) },
                { now },
            )
        val selected = frames()
        session.useVideo(selected)
        now += 300001
        assertTrue(session.tick())
        assertFalse(session.hasViews)
        assertEquals(SurroundingsPhase.ERROR, session.state.value.phase)
        assertTrue(selected.views.all { it.jpeg.all { byte -> byte == 0.toByte() } })
        assertTrue(session.ask("What is there?").isFailure)
        val next = frames()
        session.useVideo(next)
        session.clear()
        assertTrue(next.views.all { it.jpeg.all { byte -> byte == 0.toByte() } })
    }

    @Test
    fun offlineFailureRetainsFramesForRetry() = runBlocking {
        var online = false
        val session =
            SurroundingsSession(
                MuseAssistant {
                    if (online) Result.success(AssistantReply("A tree is visible."))
                    else Result.failure(IllegalStateException("Offline"))
                },
                { now },
            )
        session.useVideo(frames())
        assertTrue(session.ask("Describe", true).isFailure)
        assertTrue(session.hasViews)
        online = true
        assertTrue(session.ask("Describe", true).isSuccess)
        assertEquals(SurroundingsPhase.READY, session.state.value.phase)
    }

    @Test
    fun cancelledConversationCannotRestoreExpiredContext() = runBlocking {
        val pending = CompletableDeferred<Result<AssistantReply>>()
        val session = SurroundingsSession(MuseAssistant { pending.await() }, { now })
        val selected = frames()
        session.useVideo(selected)
        val request = async { session.ask("Describe", true) }
        yield()
        session.clear()
        pending.complete(Result.success(AssistantReply("Stale answer")))
        try {
            request.await()
            fail("Must discard late reply")
        } catch (expected: CancellationException) {}
        assertEquals(SurroundingsPhase.IDLE, session.state.value.phase)
        assertTrue(selected.views.all { it.jpeg.all { byte -> byte == 0.toByte() } })
    }

    @Test
    fun replacementErasesOldFramesAndClearsConversationHistory() = runBlocking {
        val requests = mutableListOf<AssistantRequest>()
        val session =
            SurroundingsSession(
                MuseAssistant {
                    requests += it
                    Result.success(AssistantReply("Test description"))
                },
                { now },
            )
        val old = frames()
        session.useVideo(old)
        session.ask("First video", true)
        session.useVideo(frames())
        session.ask("New video", true)
        assertTrue(old.views.all { it.jpeg.all { byte -> byte == 0.toByte() } })
        assertTrue(requests.last().history.isEmpty())
    }

    @Test
    fun recordingCommandsWorkWithoutGenerativeRouting() {
        val interpreter = CommandInterpreter()
        listOf("start recording", "record video").forEach {
            assertEquals(Command.START_SCENE_RECORDING, interpreter.interpret(it))
        }
        listOf("finish recording", "stop recording").forEach {
            assertEquals(Command.FINISH_SCENE_RECORDING, interpreter.interpret(it))
        }
        assertEquals(Command.DESCRIBE_SCENE, interpreter.interpret("describe surroundings"))
        assertEquals(Command.DESCRIBE_SCENE, interpreter.interpret("Tell me what I'm looking at."))
    }

    @Test
    fun cueDurationsAndHapticsStayBrief() {
        FeedbackCue.entries.forEach { cue ->
            val pcm = FeedbackCues.synthesize(cue)
            assertTrue(pcm.size in 1..3200)
            assertTrue(cue.haptics.sum() < 200)
            assertTrue(pcm.all { kotlin.math.abs(it.toInt()) < 10000 })
        }
    }

    @Test
    fun voiceProcessingCueIsQuieterThanTheListeningCueAndDoesNotVibrate() {
        fun peak(cue: FeedbackCue) = FeedbackCues.synthesize(cue).maxOf { kotlin.math.abs(it.toInt()) }
        val processing = FeedbackCue.PROCESSING
        assertTrue(FeedbackCues.synthesize(processing).size < FeedbackCues.synthesize(FeedbackCue.LISTENING).size)
        assertTrue(peak(processing) < peak(FeedbackCue.LISTENING) / 2)
        assertTrue(processing.haptics.isEmpty())
    }
}
