import Testing
@testable import calorietracker

struct VoiceMascotAndWorkoutDockTests {
    @Test func mascotSpeaksOnlyWithAssistantPlaybackAndRespectsReducedMotion() {
        for mode in [LiveOrbMode.idle, .listening, .thinking] {
            #expect(VoiceMascotPose.sample(mode: mode, time: 4.3, level: 1, animated: true).mouth == 0)
        }
        let quiet = VoiceMascotPose.sample(mode: .speaking, time: 1, level: 0.4, animated: true)
        let loud = VoiceMascotPose.sample(mode: .speaking, time: 1, level: 0.9, animated: true)
        #expect(quiet.mouth > 0)
        #expect(loud.mouth > quiet.mouth)
        #expect(VoiceMascotPose.sample(mode: .speaking, time: 1, level: 0, animated: true).mouth == 0)
        let still = VoiceMascotPose.sample(mode: .speaking, time: 4.3, level: 1, animated: false)
        #expect(still.mouth == 0 && still.blink == 0 && still.bob == 0)
        #expect(VoiceMascotPose.sample(mode: .listening, time: 4.3, level: 0, animated: true).blink > 0.9)
    }

    @Test func workoutDockPreservesFourUniqueSlots() {
        var dock = WorkoutDockShortcuts(stored: "")
        #expect(dock.actions == [.voice, .saved, .walking, .running])
        dock.assign(.running, to: 0)
        dock.assign(.routine, to: 1)
        #expect(WorkoutDockShortcuts(stored: dock.stored).actions == [.running, .routine, .walking, .voice])
        #expect(WorkoutDockShortcuts(stored: "voice,saved,unknown,running").actions == WorkoutDockShortcuts.defaults)
    }
}
