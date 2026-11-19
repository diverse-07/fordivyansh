package com.librevision

import com.librevision.domain.alerts.FeedbackCue
import com.librevision.domain.alerts.practiceAudioCues
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioCueLessonsTest {
    @Test
    fun eachRegularCueIsExplainedBeforeThreePlays() = runBlocking {
        val segments = mutableListOf<MutableList<FeedbackCue>>()
        practiceAudioCues(
            narrate = { explanation ->
                assertTrue(explanation.isNotBlank())
                segments.add(mutableListOf())
            },
            play = { segments.last().add(it) },
        )
        assertEquals(FeedbackCue.entries.toSet() - FeedbackCue.EMERGENCY, segments.map { it.first() }.toSet())
        assertEquals(FeedbackCue.entries.size - 1, segments.size)
        assertTrue(segments.all { it.size == 3 && it.distinct().size == 1 })
    }
}
