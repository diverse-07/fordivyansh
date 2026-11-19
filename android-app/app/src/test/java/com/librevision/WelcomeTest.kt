package com.librevision

import com.librevision.data.models.PreparationProgress
import com.librevision.feature.welcome.*
import org.junit.Assert.*
import org.junit.Test

class WelcomeTest {
    @Test
    fun firstLaunchRequestsPermissionsOnceBeforeDownload() {
        var saved = WelcomeSnapshot()
        val flow = WelcomeFlow(saved) { saved = it }
        assertEquals(WelcomeStage.WELCOME, flow.state.value.stage)
        flow.begin()
        flow.begin()
        assertFalse(saved.permissionsAsked)
        assertTrue(flow.claimPermissionRequest())
        assertTrue(saved.permissionsAsked)
        assertFalse(flow.claimPermissionRequest())
        flow.permissionsFinished(listOf("Camera"))
        assertEquals(WelcomeStage.DOWNLOAD, flow.state.value.stage)
        assertEquals(listOf("Camera"), flow.state.value.denied)
        val restored = WelcomeFlow(saved) {}
        assertEquals(WelcomeStage.DOWNLOAD, restored.state.value.stage)
        assertFalse(restored.claimPermissionRequest())
    }

    @Test
    fun interruptedPermissionRequestDoesNotPromptAgainAfterProcessDeath() {
        var saved = WelcomeSnapshot()
        val flow = WelcomeFlow(saved) { saved = it }
        flow.begin()
        flow.claimPermissionRequest()
        val restored = WelcomeFlow(saved) {}
        restored.begin()
        assertFalse(restored.claimPermissionRequest())
        assertEquals(WelcomeStage.DOWNLOAD, restored.state.value.stage)
    }

    @Test
    fun readyModelsOfferTourAndFailureCanRetryWithoutPermissions() {
        val flow = WelcomeFlow(WelcomeSnapshot(permissionsAsked = true)) {}
        flow.preparation(40, false, true)
        assertTrue(flow.state.value.failed)
        flow.preparation(65, false, false)
        assertFalse(flow.state.value.failed)
        assertEquals(65, flow.state.value.percentage)
        flow.preparation(100, true, false)
        assertEquals(WelcomeStage.TOUR_OFFER, flow.state.value.stage)
        assertEquals(100, flow.state.value.percentage)
        assertFalse(flow.claimPermissionRequest())
    }

    @Test
    fun skipIsPersistentAndFutureLaunchGoesToHome() {
        var saved = WelcomeSnapshot(permissionsAsked = true)
        val flow = WelcomeFlow(saved) { saved = it }
        flow.preparation(100, true, false)
        flow.finish()
        assertTrue(saved.completed)
        assertEquals(WelcomeStage.COMPLETE, WelcomeFlow(saved) {}.state.value.stage)
    }

    @Test
    fun tourAdvancesAutomaticallyOnlyThroughLessonsAndFinishes() {
        var saved = WelcomeSnapshot(permissionsAsked = true)
        val flow = WelcomeFlow(saved) { saved = it }
        flow.preparation(100, true, false)
        flow.startTour()
        assertEquals(WelcomeStage.TOUR, flow.state.value.stage)
        repeat(WelcomeFlow.lessons.size - 1) { index ->
            flow.next()
            assertEquals(index + 1, flow.state.value.step)
        }
        flow.next()
        flow.next()
        assertEquals(WelcomeStage.COMPLETE, flow.state.value.stage)
        assertTrue(saved.completed)
        assertNull(saved.tourStep)
    }

    @Test
    fun interruptedTourResumesAtSameLessonPaused() {
        var saved = WelcomeSnapshot(permissionsAsked = true)
        val flow = WelcomeFlow(saved) { saved = it }
        flow.preparation(100, true, false)
        flow.startTour()
        flow.next()
        flow.pause()
        flow.next()
        assertTrue(flow.state.value.paused)
        val restored = WelcomeFlow(saved) {}
        assertEquals(2, restored.state.value.step)
        assertTrue(restored.state.value.paused)
        restored.resume()
        assertFalse(restored.state.value.paused)
    }

    @Test
    fun tourCanReplayAfterCompletionWithoutPermissionOrDownloads() {
        val flow = WelcomeFlow(WelcomeSnapshot(permissionsAsked = true, completed = true)) {}
        flow.startTour()
        assertEquals(WelcomeStage.TOUR, flow.state.value.stage)
        assertEquals(0, flow.state.value.step)
        assertFalse(flow.claimPermissionRequest())
        flow.preparation(0, false, false)
        assertEquals(WelcomeStage.TOUR, flow.state.value.stage)
    }

    @Test
    fun tutorialIsNotAllowedToBypassFirstSetup() {
        val flow = WelcomeFlow(WelcomeSnapshot()) {}
        flow.startTour()
        flow.finish()
        assertEquals(WelcomeStage.WELCOME, flow.state.value.stage)
    }

    @Test
    fun overallProgressIsByteWeightedAndMonotonic() {
        val progress = PreparationProgress(mapOf("a" to 80L, "b" to 20L))
        assertEquals(0, progress.percentage)
        assertEquals(40, progress.update("a", 40))
        assertEquals(40, progress.update("a", 20))
        assertEquals(60, progress.update("b", 20))
        assertEquals(99, progress.update("a", 80))
        assertEquals(99, progress.update("a", 100))
    }

    @Test
    fun progressRejectsInvalidSpecificationsAndUnknownFiles() {
        assertThrows(IllegalArgumentException::class.java) { PreparationProgress(emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) {
            PreparationProgress(mapOf("bad" to 0))
        }
        assertThrows(IllegalStateException::class.java) {
            PreparationProgress(mapOf("a" to 20)).update("unknown", 4)
        }
    }

    @Test
    fun tourContainsPracticalControlsAndCueExamples() {
        val text = WelcomeFlow.lessons.joinToString { it.text }
        listOf("Hey Vision", "18 seconds", "Start Scanning", "stops automatically", "Settings")
            .forEach { assertTrue(it, text.contains(it)) }
        assertEquals(2, WelcomeFlow.lessons.count { it.cue != null })
        assertFalse(WelcomeFlow.lessons.last().text.contains("Hey Vision"))
    }
}
