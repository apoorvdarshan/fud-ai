import Foundation

/// The system instructions for one voice session: a short persona, the rules
/// from the contract, what this session can do, today's totals, and the same
/// profile/goal/forecast summary Coach gets.
enum VoiceAssistantInstructions {
    struct Session {
        let cameraFrames: LiveCameraFrames
        let autoCapture: Bool
        let confirmSaves: Bool
        let workoutDiaryEnabled: Bool
        let language: VoiceAssistantLanguage
        let appLanguageCode: String
        let weightMetric: Bool
        let heightMetric: Bool
        let waterInOunces: Bool
    }

    /// The hosted relay accepts up to 60,000 characters of instructions.
    static let maximumLength = 60_000

    static func build(
        session: Session,
        stores: VoiceAssistantStores,
        bodyMeasurements: [BodyMeasurement],
        waterTracking: Bool,
        waterGoalMl: Int,
        fastingTracking: Bool,
        mascot: VoiceMascotProfile = VoiceMascotProfile(),
        now: Date = .now
    ) -> String {
        let profile = stores.profile()
        var lines: [String] = []
        lines.append("You are Fud AI's voice assistant and coach, inside the Fud AI calorie tracking app. You talk with the user out loud. Usually reply in one or two short sentences unless asked for detail. \(languageRule(session))")
        lines.append(mascot.instruction)
        lines.append("")
        lines.append("## How to work")
        lines += [
            "- Say what you are about to do in a few words, then do it.",
            "- Use the tools for anything in the app (logging, editing, deleting, opening screens, changing settings) instead of telling the user to tap.",
            "- Never invent numbers. Use get_app_state and the data tools when you need facts about the user's day or history.",
            "- Ask one question at a time.",
            "- After a draft (capture_photo, pick_photos, scan_barcode, describe_food or describe_workout), read back the main items and the total calories, then ask whether to log it.",
            "- Never call a tool with user_confirmed: true unless the user agreed to that exact change.",
            "- When a tool returns confirmation_required, read its summary and ask the user to confirm. The user may also tap Confirm or Cancel on the card.",
            "- After a change, the user can tap Undo on its card for 8 seconds. Deletes always need the user's yes first.",
            "- Messages that start with [App] tell you what the user tapped on screen; they are not speech. Acknowledge them briefly.",
            "- Food logging is paused while a fast is running; offer to end the fast first.",
            "- When the user says goodbye or asks you to stop, say a short goodbye, then call end_conversation.",
            "- Avoid medical advice; suggest a doctor when it matters.",
        ]
        lines.append("")
        lines.append("## This session")
        lines.append("- Camera: \(cameraRule(session))")
        lines.append("- capture_photo closes the camera after taking the image. Reopen it only for another camera task. When a camera task is finished or the user moves on, call close_camera and continue talking. Finishing a tool is not a request to end_conversation.")
        lines.append(session.autoCapture && session.cameraFrames != .none
            ? "- Auto-capture is on: call capture_photo as soon as the food is clearly in frame."
            : "- Auto-capture is off: call capture_photo only when the user says to take the photo.")
        lines.append(session.confirmSaves
            ? "- Confirm before saving is on: logging and other changes return confirmation_required until the user agrees."
            : "- Confirm before saving is off: logging and changes happen right away; deletes still need the user's yes.")
        lines.append(session.workoutDiaryEnabled
            ? "- The workout diary is on: describe_workout, log_workout, log_cardio and the workout data tools are available."
            : "- The workout diary is off: there are no workout tools.")
        lines.append("- Units: weight in \(session.weightMetric ? "kg" : "lb"), height in \(session.heightMetric ? "cm" : "feet and inches"), water in \(session.waterInOunces ? "fluid ounces" : "ml"). Tools take the units their parameters name.")
        lines.append("- Dates are the user's local calendar (yyyy-MM-dd). The time now is \(VoiceAssistantParsing.clockTime(now)).")
        lines.append("")
        lines += ChatService.currentDateLines(now: now)
        lines.append("")
        lines += todayLines(profile: profile, stores: stores, waterTracking: waterTracking, waterGoalMl: waterGoalMl, fastingTracking: fastingTracking, workoutDiary: session.workoutDiaryEnabled, now: now)
        lines.append("")
        lines += ChatService.profileSummaryLines(
            profile: profile,
            weights: stores.weight.entries,
            bodyFats: stores.bodyFat.entries,
            measurements: bodyMeasurements,
            foods: stores.food.entries,
            fastingSessions: stores.fasting.sessions,
            heightMetric: session.heightMetric,
            weightMetric: session.weightMetric,
            workoutSessions: session.workoutDiaryEnabled ? stores.workouts.completedSessions : [],
            workoutPlans: session.workoutDiaryEnabled ? Array(stores.workouts.dayPlans.values) : [],
            workoutAccessEnabled: session.workoutDiaryEnabled
        )
        lines += ChatService.userContextLines()
        return limitedToRelayLength(lines.joined(separator: "\n"))
    }

    /// The Worker measures JavaScript string length (UTF-16 code units), while
    /// Swift's prefix counts grapheme clusters. Keep complete characters and
    /// respect the relay's limit for emoji and non-Latin profile/context text.
    static func limitedToRelayLength(_ text: String) -> String {
        guard text.utf16.count > maximumLength else { return text }
        var end = text.startIndex
        var remaining = maximumLength
        while end < text.endIndex {
            let next = text.index(after: end)
            let length = text[end..<next].utf16.count
            guard length <= remaining else { break }
            remaining -= length
            end = next
        }
        return String(text[..<end])
    }

    static func languageRule(_ session: Session) -> String {
        let appLanguage = VoiceAssistantLanguage.englishName(for: session.appLanguageCode)
        switch session.language {
        case .automatic:
            return "Speak the language the user speaks to you; when unsure, use \(appLanguage)."
        case .app:
            return "Always reply in \(appLanguage), the app's language, unless the user asks you to switch."
        case .specific(let code):
            return "Always reply in \(VoiceAssistantLanguage.englishName(for: code)), unless the user asks you to switch."
        }
    }

    static func cameraRule(_ session: Session) -> String {
        switch session.cameraFrames {
        case .continuous:
            "while the assistant's camera is open you see one frame a second (camera_frames: continuous). Ask the user to show the food, then take the photo with capture_photo."
        case .perTurn:
            "while the assistant's camera is open you see the latest frame each time the user speaks (camera_frames: per_turn). Ask the user to show the food and tell you when it's in frame."
        case .none:
            "you can't see the camera in this session (camera_frames: none). When the camera is open, ask the user to say when the food is in frame, then call capture_photo."
        }
    }

    static func todayLines(
        profile: UserProfile,
        stores: VoiceAssistantStores,
        waterTracking: Bool,
        waterGoalMl: Int,
        fastingTracking: Bool,
        workoutDiary: Bool,
        now: Date
    ) -> [String] {
        let food = stores.food
        var lines = ["## Today so far"]
        lines.append("- Eaten: \(food.calories(for: now)) of \(profile.effectiveCalories) kcal; protein \(Int(food.protein(for: now).rounded())) of \(profile.effectiveProtein) g, carbs \(Int(food.carbs(for: now).rounded())) of \(profile.effectiveCarbs) g, fat \(Int(food.fat(for: now).rounded())) of \(profile.effectiveFat) g. \(food.entries(for: now).count) foods logged.")
        if waterTracking {
            lines.append("- Water: \(stores.water.total(on: now)) of \(waterGoalMl) ml.")
        }
        if let fast = stores.fasting.activeSession {
            lines.append("- A fast is running: started \(VoiceAssistantParsing.isoDay(fast.startedAt)) \(VoiceAssistantParsing.clockTime(fast.startedAt)), goal \(fast.goalMinutes / 60) hours.")
        } else if fastingTracking {
            lines.append("- No fast is running.")
        }
        if workoutDiary, let burned = stores.workouts.caloriesBurned(on: now) {
            lines.append("- Workout burn today: \(burned) kcal.")
        }
        return lines
    }
}
