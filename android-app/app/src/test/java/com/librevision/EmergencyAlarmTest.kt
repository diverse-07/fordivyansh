package com.librevision
import com.librevision.domain.commands.VolumeUpGesture
import com.librevision.domain.commands.VolumeUpGesture.Action
import com.librevision.core.safety.AlarmCycle
import org.junit.Assert.*
import org.junit.Test
class EmergencyAlarmTest {
    private fun click(g: VolumeUpGesture, at: Long): List<Action> { g.down(at); return g.up(at+20) }
    @Test fun threeClicksAcrossTheFullDocumentedWindowTriggerAlarm() {
        val g=VolumeUpGesture()
        assertTrue(click(g,1000).isEmpty());assertTrue(click(g,1590).isEmpty())
        assertEquals(listOf(Action.ALARM),click(g,2180))
        assertTrue(g.tick(4000).isEmpty())
    }
    @Test fun singleAndDoubleClicksRetainNormalVolume() {
        val g=VolumeUpGesture();click(g,1000)
        assertEquals(listOf(Action.VOLUME_UP),g.tick(2221))
        click(g,3000);click(g,3100)
        assertEquals(listOf(Action.VOLUME_UP,Action.VOLUME_UP),g.tick(4221))
    }
    @Test fun holdRunsOnceAndNeverCountsAsClick() {
        val g=VolumeUpGesture();g.down(1000)
        assertTrue(g.tick(2999).isEmpty());assertEquals(listOf(Action.VOICE),g.tick(3000))
        assertTrue(g.tick(4000).isEmpty());assertTrue(g.up(4500).isEmpty())
        assertTrue(g.tick(10000).isEmpty())
    }
    @Test fun cancellationClearsBothGestureTimers() {
        val g=VolumeUpGesture();click(g,1000);g.down(1100);g.cancel()
        assertTrue(g.tick(5000).isEmpty());assertNull(g.nextDeadline())
        g.down(6000);g.up(6100,true);assertTrue(g.tick(9000).isEmpty())
    }
    @Test fun finiteSirenCompletesOnFramesOrDeadlineEvenWhileTrackIsPlaying() {
        assertFalse(AlarmCycle.complete(100,110250,3000))
        assertTrue(AlarmCycle.complete(110250,110250,2500))
        assertTrue(AlarmCycle.complete(100,110250,3500))
    }
}
