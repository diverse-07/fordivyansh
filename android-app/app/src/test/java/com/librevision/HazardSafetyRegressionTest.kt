package com.librevision
import com.librevision.domain.hazards.*
import com.librevision.domain.commands.AppSpeechPhrases
import com.librevision.domain.vision.ObstacleSpeech
import org.junit.Assert.*
import org.junit.Test

class HazardSafetyRegressionTest {
    @Test fun lowBoxesDoNotTurnPeopleOrCarsIntoHoles() {
        val classifier=ThreeZoneHazardClassifier()
        for (name in listOf("person","car","bicycle")) {
            val h=classifier.classifyHazard(name,.1f,.95f,.5f,null)
            assertEquals(HazardZone.TORSO_LEVEL,h.zone)
            assertFalse(h.spokenWarning.contains("drop"))
            assertFalse(h.spokenWarning.contains("approaching"))
            assertFalse(h.spokenWarning.contains("flashing"))
            assertNull(h.distanceMeters)
        }
    }
    @Test fun semanticsAndUnknownRangeArePreserved() {
        val c=ThreeZoneHazardClassifier()
        assertEquals(HazardZone.GROUND_LEVEL,c.classifyHazard("pothole",.1f,.3f,.5f,null).zone)
        assertEquals(HazardZone.HEAD_HEIGHT,c.classifyHazard("tree branch",.1f,.3f,.5f,null).zone)
        assertNull(c.classifyHazard("chair",.1f,.9f,.5f,Float.NaN).distanceMeters)
        assertEquals("Stop, chair ahead.",ObstacleSpeech.message("chair",null,.7f,1000))
    }
    @Test fun advertisedWakeIsActuallyRecognized() {
        assertTrue(AppSpeechPhrases.hasWake("Hey Vision"))
        assertFalse(AppSpeechPhrases.hasWake("unrelated conversation"))
    }
    @Test fun bengaliWarningIsLocalizedWithoutInventedRange() {
        val warning=ObstacleSpeech.message("person",null,.7f,1000,true)
        assertTrue(warning.contains("থামুন"));assertFalse(warning.contains("2.0"))
    }
}
