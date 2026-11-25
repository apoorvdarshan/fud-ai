package com.apoorvdarshan.calorietracker.ui.voice

import com.apoorvdarshan.calorietracker.models.WorkoutDockAction
import com.apoorvdarshan.calorietracker.models.WorkoutDockShortcuts
import com.apoorvdarshan.calorietracker.services.voice.OrbMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMascotAndWorkoutDockTest {
    @Test
    fun opensMouthOnlyForAssistantSpeechAndStaysStillWithReducedMotion() {
        for (mode in listOf(OrbMode.IDLE, OrbMode.LISTENING, OrbMode.THINKING)) {
            assertEquals(0f, VoiceMascotPose.sample(mode, 4.3, 1f, animated = true).mouth, 0f)
        }
        val quiet = VoiceMascotPose.sample(OrbMode.SPEAKING, 1.0, 0.4f, animated = true)
        val loud = VoiceMascotPose.sample(OrbMode.SPEAKING, 1.0, 0.9f, animated = true)
        assertTrue(quiet.mouth > 0f && loud.mouth > quiet.mouth)
        assertEquals(0f, VoiceMascotPose.sample(OrbMode.SPEAKING, 1.0, 0f, animated = true).mouth, 0f)
        assertEquals(VoiceMascotPose.Still, VoiceMascotPose.sample(OrbMode.SPEAKING, 4.3, 1f, animated = false))
        assertTrue(VoiceMascotPose.sample(OrbMode.LISTENING, 4.3, 0f, animated = true).blink > 0.9f)
    }

    @Test
    fun mascotSquashesBeforeHoppingAndCanDoubleBlink() {
        val crouch = VoiceMascotPose.sample(OrbMode.IDLE, 0.32, 0f, animated = true)
        val hop = VoiceMascotPose.sample(OrbMode.IDLE, 0.78, 0f, animated = true)
        assertTrue(crouch.stretch < -0.03f)
        assertTrue(hop.stretch > 0.04f && hop.bob < -0.025f)
        assertEquals(0f, VoiceMascotPose.sample(OrbMode.IDLE, 4.445, 0f, animated = true).blink, 0f)
        assertTrue(VoiceMascotPose.sample(OrbMode.IDLE, 4.55, 0f, animated = true).blink > 0.8f)
    }

    @Test
    fun workoutDockPreservesFourUniqueSlots() {
        val dock = WorkoutDockShortcuts.decode(null)
        assertEquals(listOf(WorkoutDockAction.VOICE, WorkoutDockAction.SAVED, WorkoutDockAction.WALKING, WorkoutDockAction.RUNNING),
            dock.actions)
        val updated = dock.assigning(0, WorkoutDockAction.RUNNING).assigning(1, WorkoutDockAction.ROUTINE)
        assertEquals(listOf(WorkoutDockAction.RUNNING, WorkoutDockAction.ROUTINE, WorkoutDockAction.WALKING, WorkoutDockAction.VOICE),
            WorkoutDockShortcuts.decode(updated.encode()).actions)
        assertEquals(dock, WorkoutDockShortcuts.decode("voice,saved,unknown,running"))
    }
}
