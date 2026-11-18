package com.librevision

import com.librevision.domain.alerts.FeedbackCue
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class EmergencyAlarmTest {

    @Test
    fun emergencyAndVoiceTriggerCuesAreBriefAndValid() {
        val emergency = FeedbackCue.EMERGENCY
        assertEquals("SOS Alarm", emergency.label)
        assertTrue(emergency.frequencies.isNotEmpty())
        assertTrue(emergency.haptics.sum() < 200)
        assertEquals(255, emergency.amplitude)

        val voice = FeedbackCue.VOICE_TRIGGER
        assertEquals("Voice Activated", voice.label)
        assertTrue(voice.frequencies.isNotEmpty())
        assertTrue(voice.haptics.sum() < 200)
    }

    @Test
    fun tripleClickWindowLogicRequiresThreeClicksWithinThreshold() {
        val clickThresholdMs = 500L
        var clickCount = 0
        var lastClickTime = 0L
        var alarmTriggered = false

        fun handleClick(now: Long) {
            if (now - lastClickTime < clickThresholdMs) {
                clickCount++
            } else {
                clickCount = 1
            }
            lastClickTime = now

            if (clickCount >= 3) {
                clickCount = 0
                alarmTriggered = true
            }
        }

        // 1st click at t=1000
        handleClick(1000L)
        assertEquals(1, clickCount)
        assertFalse(alarmTriggered)

        // 2nd click at t=1300 (<500ms later)
        handleClick(1300L)
        assertEquals(2, clickCount)
        assertFalse(alarmTriggered)

        // 3rd click at t=1600 (<500ms later) -> Fires!
        handleClick(1600L)
        assertEquals(0, clickCount)
        assertTrue(alarmTriggered)

        // Delayed click at t=3000 (>500ms later) -> Resets to 1 click, does not fire
        alarmTriggered = false
        handleClick(3000L)
        assertEquals(1, clickCount)
        assertFalse(alarmTriggered)
    }

    @Test
    fun alarmToggleStateFlipsCorrectly() {
        val isAlarmActive = AtomicBoolean(false)

        fun toggle(): Boolean {
            return if (isAlarmActive.get()) {
                isAlarmActive.set(false)
                false
            } else {
                isAlarmActive.set(true)
                true
            }
        }

        assertFalse(isAlarmActive.get())
        assertTrue(toggle()) // Activated
        assertTrue(isAlarmActive.get())
        assertFalse(toggle()) // Silenced
        assertFalse(isAlarmActive.get())
    }
}
