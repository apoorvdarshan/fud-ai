import Testing
@testable import calorietracker

struct VoiceMascotAndWorkoutDockTests {
    @Test func savesNamesColorsAndPersonalitiesSeparatelyPerCharacter() {
        var preferences = VoiceMascotPreferences()
        #expect(preferences.selected == .brulee)
        #expect(preferences.profile.name == "Brulee")
        #expect(preferences.profile.options.color == .original)
        #expect(VoiceMascot.allCases.count == 9)
        #expect(VoiceMascotPreferences.decode(#"{"selected":"brulee"}"#).selected == .brulee)
        #expect(VoiceMascotPreferences.decode("{}").selected == .brulee)
        preferences.selected = .orb
        #expect(VoiceMascotPreferences.decode(preferences.encoded).selected == .orb)
        preferences.selected = .alien
        preferences.edit {
            $0.name = "  Nova\n\t星  "
            $0.color = .purple
            $0.personality = .playful
        }
        preferences.selected = .teddy
        #expect(preferences.profile.name == "Teddy")
        #expect(preferences.profile.options.color == .original)
        preferences.edit { $0.name = "Waffles" }

        var reloaded = VoiceMascotPreferences.decode(preferences.encoded)
        #expect(reloaded.profile.name == "Waffles")
        reloaded.selected = .alien
        #expect(reloaded.profile.name == "Nova 星")
        #expect(reloaded.profile.options.color == .purple)
        #expect(reloaded.profile.options.personality == .playful)
        #expect(!reloaded.profile.instruction.contains("\n"))
        #expect(VoiceMascotPreferences.decode("invalid").selected == .brulee)
    }

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
        #expect(still.mouth == 0 && still.blink == 0 && still.bob == 0 && still.stretch == 0 && still.tilt == 0)
        #expect(VoiceMascotPose.sample(mode: .listening, time: 4.3, level: 0, animated: true).blink > 0.9)
    }

    @Test func mascotSquashesBeforeHoppingAndCanDoubleBlink() {
        let crouch = VoiceMascotPose.sample(mode: .idle, time: 0.32, level: 0, animated: true)
        let hop = VoiceMascotPose.sample(mode: .idle, time: 0.78, level: 0, animated: true)
        #expect(crouch.stretch < -0.03)
        #expect(hop.stretch > 0.04 && hop.bob < -0.025)
        #expect(VoiceMascotPose.sample(mode: .idle, time: 4.445, level: 0, animated: true).blink == 0)
        #expect(VoiceMascotPose.sample(mode: .idle, time: 4.55, level: 0, animated: true).blink > 0.8)
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
