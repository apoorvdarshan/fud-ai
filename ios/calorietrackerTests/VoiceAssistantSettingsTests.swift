import Foundation
import Testing
@testable import calorietracker

/// Provider resolution, stored preferences, argument parsing and launch requests.
@MainActor
struct VoiceAssistantSettingsTests {
    private static func defaults() throws -> (UserDefaults, String) {
        let suite = "VoiceAssistantSettingsTests.\(UUID().uuidString)"
        return (try #require(UserDefaults(suiteName: suite)), suite)
    }

    // MARK: Provider resolution

    @Test func automaticPrefersTheSelectedOwnKeyProviderThenHostingThenExplains() {
        func route(_ selected: LiveProvider?, keys: Set<LiveProvider>, hosted: Bool) -> VoiceAssistantRoute {
            VoiceAssistantRouting.resolve(choice: .automatic, selectedOwnKeyProvider: selected, providersWithKeys: keys, hasHostedEntitlement: hosted)
        }
        #expect(route(.openai, keys: [.openai, .gemini], hosted: true) == .ready(.openai))
        #expect(route(.gemini, keys: [.gemini], hosted: false) == .ready(.gemini))
        // A key for another provider isn't the selected one.
        #expect(route(.xai, keys: [.gemini], hosted: true) == .ready(.hosted))
        #expect(route(nil, keys: [.gemini, .openai], hosted: true) == .ready(.hosted))
        #expect(route(.gemini, keys: [], hosted: true) == .ready(.hosted))
        #expect(route(.gemini, keys: [], hosted: false) == .needsAccess(.noProvider))
        #expect(route(nil, keys: [], hosted: false) == .needsAccess(.noProvider))
    }

    @Test func fixedChoicesNeedTheirKeyOrPlan() {
        func route(_ choice: VoiceAssistantProviderChoice, keys: Set<LiveProvider>, hosted: Bool) -> VoiceAssistantRoute {
            VoiceAssistantRouting.resolve(choice: choice, selectedOwnKeyProvider: .gemini, providersWithKeys: keys, hasHostedEntitlement: hosted, qwenWorkspaceConfigured: true)
        }
        #expect(route(.hosted, keys: [.gemini], hosted: true) == .ready(.hosted))
        #expect(route(.hosted, keys: [.gemini], hosted: false) == .needsAccess(.needsSubscription))
        #expect(route(.openai, keys: [.openai], hosted: false) == .ready(.openai))
        #expect(route(.openai, keys: [.gemini], hosted: true) == .needsAccess(.missingKey(.openai)))
        #expect(route(.xai, keys: [.xai], hosted: true) == .ready(.xai))
        #expect(route(.qwen, keys: [.qwen], hosted: false) == .ready(.qwen))
        #expect(route(.qwen, keys: [.gemini], hosted: true) == .needsAccess(.needsQwenSetup))
        #expect(VoiceAssistantRouting.resolve(choice: .qwen, selectedOwnKeyProvider: nil,
            providersWithKeys: [.qwen], hasHostedEntitlement: false) == .needsAccess(.needsQwenSetup))
        #expect(LiveProvider(aiProvider: .qwen) == .qwen)
        #expect(LiveProvider.qwen.keyProvider == .qwen)
        // GPT-Live uses the OpenAI key; Automatic keeps OpenAI on its realtime models.
        #expect(route(.gptLive, keys: [.gptLive], hosted: false) == .ready(.gptLive))
        #expect(LiveProvider(aiProvider: .openai) == .openai)
        #expect(route(.gemini, keys: [], hosted: true) == .needsAccess(.missingKey(.gemini)))
    }

    // MARK: Stored preferences

    @Test func replyLanguageStorageKeepsAnExplicitAutomatic() {
        #expect(VoiceAssistantLanguage(storageValue: nil) == .app)
        #expect(VoiceAssistantLanguage(storageValue: "") == .app)
        #expect(VoiceAssistantLanguage(storageValue: "app") == .app)
        #expect(VoiceAssistantLanguage(storageValue: "automatic") == .automatic)
        #expect(VoiceAssistantLanguage(storageValue: "es") == .specific("es"))
        for language in [VoiceAssistantLanguage.app, .automatic, .specific("pt-BR")] {
            #expect(VoiceAssistantLanguage(storageValue: language.storageValue) == language)
        }
    }

    @Test func preferencesDefaultAsTheContractSays() throws {
        let (defaults, suite) = try Self.defaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = VoiceAssistantSettings(defaults: defaults)
        #expect(settings.providerChoice == .automatic)
        #expect(settings.confirmSaves)
        #expect(settings.saveToCoach)
        #expect(settings.autoCapture)
        #expect(settings.geminiBargeIn)
        #expect(settings.language == .app)
        #expect(settings.model(for: .gemini) == "gemini-3.8-live")
        #expect(settings.model(for: .openai) == "gpt-realtime-2.1-mini")
        #expect(settings.model(for: .xai) == "grok-voice-latest")
        #expect(settings.voice(for: .gemini) == "Kore")
        #expect(settings.voice(for: .openai) == "marin")
        #expect(settings.voice(for: .xai) == "ara")
    }

    @Test func cleanupRetiresOnlyOfflineVoiceChoicesAndItsFallback() throws {
        let (defaults, suite) = try Self.defaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = VoiceAssistantSettings(defaults: defaults)
        settings.language = .specific("hi")
        for old in ["lfm3_offline", "lfm3_connected", "qwen"] {
            defaults.set(old, forKey: VoiceAssistantSettings.providerKey)
            defaults.set(true, forKey: "voiceAssistant.localCloudFallback")
            settings.removeRetiredOfflineVoicePreferences()
            #expect(settings.providerChoice == (old == "qwen" ? .qwen : .automatic))
            #expect(defaults.string(forKey: VoiceAssistantSettings.providerKey) == (old == "qwen" ? old : nil))
            #expect(defaults.object(forKey: "voiceAssistant.localCloudFallback") == nil)
            #expect(settings.language == .specific("hi"))
        }
    }

    @Test func preferencesUseTheirKeysAndRejectUnknownValues() throws {
        let (defaults, suite) = try Self.defaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = VoiceAssistantSettings(defaults: defaults)

        settings.providerChoice = .xai
        #expect(defaults.string(forKey: "voiceAssistant.provider") == "xai")
        settings.setModel("gpt-realtime-2.1", for: .openai)
        #expect(defaults.string(forKey: "voiceAssistant.model.openai") == "gpt-realtime-2.1")
        settings.setModel("made-up-model", for: .openai)
        #expect(settings.model(for: .openai) == "gpt-realtime-2.1")
        // Hosted sessions always use the Worker's model.
        settings.setModel("gemini-3.1-flash-live-preview", for: .hosted)
        #expect(settings.model(for: .hosted) == "gemini-3.8-live")

        // Hosted is Gemini Live, so it shares Gemini's voice.
        settings.setVoice("Puck", for: .hosted)
        #expect(settings.voice(for: .gemini) == "Puck")
        #expect(defaults.string(forKey: "voiceAssistant.voice.gemini") == "Puck")
        defaults.set("Nobody", forKey: "voiceAssistant.voice.openai")
        #expect(settings.voice(for: .openai) == "marin")

        settings.language = .specific("pt-BR")
        #expect(defaults.string(forKey: "voiceAssistant.language") == "pt-BR")
        #expect(settings.language == .specific("pt-BR"))
        settings.language = .app
        #expect(VoiceAssistantSettings(defaults: defaults).language == .app)

        settings.confirmSaves = false
        settings.saveToCoach = false
        settings.autoCapture = false
        settings.geminiBargeIn = false
        #expect(defaults.bool(forKey: "voiceAssistant.confirmSaves") == false)
        #expect(defaults.bool(forKey: "voiceAssistant.saveToCoach") == false)
        #expect(defaults.object(forKey: "voiceAssistant.autoCapture") as? Bool == false)
        #expect(!VoiceAssistantSettings(defaults: defaults).autoCapture)
        #expect(!VoiceAssistantSettings(defaults: defaults).geminiBargeIn)
    }

    @Test func builtInModelsAndVoicesMatchTheContract() {
        #expect(LiveProvider.gemini.models == ["gemini-3.8-live", "gemini-3.8-live-extended-thinking", "gemini-3.1-flash-live-preview"])
        #expect(LiveProvider.openai.models == ["gpt-realtime-2.1-mini", "gpt-realtime-2.1"])
        #expect(LiveProvider.xai.models == ["grok-voice-latest", "grok-voice-3.0"])
        #expect(LiveProvider.gemini.voices.count == 12)
        #expect(LiveProvider.openai.voices.count == 10)
        #expect(LiveProvider.xai.voices == ["ara", "eve", "rex", "sal", "leo"])
        #expect(LiveProvider.qwen.models == ["qwen3.8-omni-flash-realtime"])
        #expect(LiveProvider.qwen.defaultVoice == "Tina")
        #expect(LiveProvider.qwen.voices.count == 10)
        #expect(LiveProvider.gptLive.models == ["gpt-live-1", "gpt-live-1.1"])
        #expect(LiveProvider.gptLive.defaultVoice == "quartz")
        #expect(LiveProvider.hosted.voices == LiveProvider.gemini.voices)
    }

    // MARK: Parsing

    @Test func instructionsRespectTheRelaysUTF16LimitWithoutSplittingCharacters() {
        let text = String(repeating: "👩🏽‍💻", count: 20_000)
        let limited = VoiceAssistantInstructions.limitedToRelayLength(text)
        #expect(limited.utf16.count <= VoiceAssistantInstructions.maximumLength)
        #expect(limited.allSatisfy { $0 == "👩🏽‍💻" })
        #expect(text.hasPrefix(limited))
        #expect(limited.utf16.count + "👩🏽‍💻".utf16.count > VoiceAssistantInstructions.maximumLength)
        #expect(VoiceAssistantInstructions.limitedToRelayLength("Hello नमस्ते") == "Hello नमस्ते")
    }

    @Test func waterConversionsRejectValuesOutsideTheIntegerRange() {
        #expect(VoiceAssistantParsing.milliliters(amount: 1e300, unit: "l") == nil)
        #expect(VoiceAssistantParsing.milliliters(amount: .greatestFiniteMagnitude, unit: "cup") == nil)
        #expect(VoiceAssistantParsing.milliliters(amount: 0.5, unit: "l") == 500)
    }

    @Test func datesAcceptWordsAndISODays() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try #require(TimeZone(identifier: "America/New_York"))
        let now = try #require(calendar.date(from: DateComponents(year: 2026, month: 3, day: 9, hour: 14, minute: 30)))

        #expect(try VoiceAssistantParsing.day(nil, now: now, calendar: calendar) == nil)
        #expect(try VoiceAssistantParsing.day("", now: now, calendar: calendar) == nil)
        #expect(try VoiceAssistantParsing.day("today", now: now, calendar: calendar) == calendar.startOfDay(for: now))
        let yesterday = try #require(try VoiceAssistantParsing.day("Yesterday", now: now, calendar: calendar))
        #expect(calendar.component(.day, from: yesterday) == 8)
        let iso = try #require(try VoiceAssistantParsing.day("2026-02-28", now: now, calendar: calendar))
        #expect(VoiceAssistantParsing.isoDay(iso, calendar: calendar) == "2026-02-28")
        for bad in ["2026-02-30", "tomorrow", "09/03/2026", "2026-13-01", "26-03-09"] {
            #expect(throws: VoiceAssistantParsing.DateError.invalid) {
                try VoiceAssistantParsing.day(bad, now: now, calendar: calendar)
            }
        }
    }

    @Test func timesAreTwentyFourHour() throws {
        let morning = try #require(try VoiceAssistantParsing.time("07:30"))
        #expect(morning.hour == 7 && morning.minute == 30)
        let evening = try #require(try VoiceAssistantParsing.time("19:05"))
        #expect(evening.hour == 19 && evening.minute == 5)
        #expect(try VoiceAssistantParsing.time(nil) == nil)
        for bad in ["24:00", "7pm", "12:60", "noon"] {
            #expect(throws: VoiceAssistantParsing.DateError.invalid) {
                try VoiceAssistantParsing.time(bad)
            }
        }
    }

    @Test func logDatesFollowHomesRules() throws {
        let calendar = Calendar(identifier: .gregorian)
        let now = try #require(calendar.date(from: DateComponents(year: 2026, month: 5, day: 20, hour: 13, minute: 45, second: 10)))
        let earlier = try #require(calendar.date(from: DateComponents(year: 2026, month: 5, day: 18)))

        // Today with no time: now.
        #expect(VoiceAssistantParsing.logDate(day: nil, time: nil, defaultDay: now, now: now, calendar: calendar) == now)
        // Another day with no time: that day at the current time of day.
        let carried = VoiceAssistantParsing.logDate(day: earlier, time: nil, defaultDay: now, now: now, calendar: calendar)
        #expect(calendar.component(.day, from: carried) == 18)
        #expect(calendar.component(.hour, from: carried) == 13)
        #expect(calendar.component(.minute, from: carried) == 45)
        // A given time wins.
        let timed = VoiceAssistantParsing.logDate(day: nil, time: (8, 5), defaultDay: earlier, now: now, calendar: calendar)
        #expect(calendar.component(.day, from: timed) == 18)
        #expect(calendar.component(.hour, from: timed) == 8)
        #expect(calendar.component(.minute, from: timed) == 5)
    }

    @Test func unitsConvertAsDocumented() {
        #expect(VoiceAssistantParsing.milliliters(amount: 2, unit: "cup") == 480)
        #expect(VoiceAssistantParsing.milliliters(amount: 1, unit: "glass") == 250)
        #expect(VoiceAssistantParsing.milliliters(amount: 1.5, unit: "l") == 1500)
        #expect(VoiceAssistantParsing.milliliters(amount: 16, unit: "oz") == 473)
        #expect(VoiceAssistantParsing.milliliters(amount: 330, unit: "ML") == 330)
        #expect(VoiceAssistantParsing.milliliters(amount: 0, unit: "ml") == nil)
        #expect(VoiceAssistantParsing.milliliters(amount: 1, unit: "bucket") == nil)

        #expect(VoiceAssistantParsing.kilograms(value: 80, unit: "kg") == 80)
        let pounds = VoiceAssistantParsing.kilograms(value: 165, unit: "lb") ?? 0
        #expect(abs(pounds - 74.84) < 0.01)
        #expect(VoiceAssistantParsing.kilograms(value: 165, unit: "stone") == nil)

        #expect(VoiceAssistantParsing.grams(inServing: "1 bowl (250 g)") == 250)
        #expect(VoiceAssistantParsing.grams(inServing: "30g") == 30)
        #expect(VoiceAssistantParsing.grams(inServing: "2 slices") == nil)
    }

    @Test func looseValuesAreRead() {
        #expect(VoiceAssistantParsing.number("2200") == 2200)
        #expect(VoiceAssistantParsing.number("1,5") == 1.5)
        #expect(VoiceAssistantParsing.number(NSNumber(value: 42)) == 42)
        #expect(VoiceAssistantParsing.number(true) == nil)
        #expect(VoiceAssistantParsing.number("lots") == nil)
        #expect(VoiceAssistantParsing.bool("true") == true)
        #expect(VoiceAssistantParsing.bool("No") == false)
        #expect(VoiceAssistantParsing.bool(NSNumber(value: 1)) == true)
        #expect(VoiceAssistantParsing.bool("maybe") == nil)
        #expect(VoiceAssistantParsing.string("  ") == nil)
        #expect(VoiceAssistantParsing.mealType("Dinner") == .dinner)
        #expect(VoiceAssistantParsing.mealType("brunch") == nil)
    }

    // MARK: Launch request

    // The request is written directly: posting it would also reach the host app's UI.
    @Test func launchRequestsAreConsumedOnce() {
        UserDefaults.standard.set(Date.now.timeIntervalSince1970, forKey: VoiceAssistantLaunchRequest.pendingKey)
        #expect(VoiceAssistantLaunchRequest.consumePending())
        #expect(!VoiceAssistantLaunchRequest.consumePending())
    }

    @Test func staleLaunchRequestsAreIgnored() {
        let longAgo = Date.now.addingTimeInterval(-VoiceAssistantLaunchRequest.lifetime - 5)
        UserDefaults.standard.set(longAgo.timeIntervalSince1970, forKey: VoiceAssistantLaunchRequest.pendingKey)
        #expect(!VoiceAssistantLaunchRequest.consumePending())
        #expect(UserDefaults.standard.object(forKey: VoiceAssistantLaunchRequest.pendingKey) == nil)
    }
}

/// The session instructions and the cards that come with tools.
@MainActor
struct VoiceAssistantInstructionsTests {
    private func session(_ frames: LiveCameraFrames, language: VoiceAssistantLanguage = .automatic, confirmSaves: Bool = true) -> VoiceAssistantInstructions.Session {
        VoiceAssistantInstructions.Session(
            cameraFrames: frames,
            autoCapture: true,
            confirmSaves: confirmSaves,
            workoutDiaryEnabled: true,
            language: language,
            appLanguageCode: "de",
            weightMetric: true,
            heightMetric: true,
            waterInOunces: false
        )
    }

    @Test func instructionsCarryTheRulesTheCapabilitiesAndCoachsSummary() {
        let harness = VoiceAssistantHarness(confirmSaves: true)
        harness.addFood("Granola", calories: 410)
        let text = VoiceAssistantInstructions.build(
            session: session(.continuous),
            stores: harness.executor.stores,
            bodyMeasurements: [],
            waterTracking: true,
            waterGoalMl: 2500,
            fastingTracking: true
        )
        #expect(text.count <= VoiceAssistantInstructions.maximumLength)
        #expect(text.contains("Your name for this session is \"Brulee\""))
        #expect(text.contains("user_confirmed: true unless the user agreed"))
        #expect(text.contains("read back the main items and the total calories"))
        #expect(text.contains("camera_frames: continuous"))
        #expect(text.contains("Auto-capture is on"))
        #expect(text.contains("Eaten: 410 of"))
        #expect(text.contains("of 2500 ml"))
        #expect(text.contains("## User profile"))
        #expect(text.contains("## Current date"))
        #expect(text.contains("when unsure, use German"))
    }

    @Test func selectedNameAndPersonalityReachTheSessionInstructions() {
        let harness = VoiceAssistantHarness(confirmSaves: true)
        var preferences = VoiceMascotPreferences()
        preferences.selected = .jam
        preferences.edit { $0.name = "Toastie"; $0.personality = .calm }
        let text = VoiceAssistantInstructions.build(
            session: session(.continuous),
            stores: harness.executor.stores,
            bodyMeasurements: [],
            waterTracking: false,
            waterGoalMl: 2000,
            fastingTracking: false,
            mascot: preferences.profile
        )
        #expect(text.contains("Your name for this session is \"Toastie\""))
        #expect(text.contains("patient, gentle and reassuring"))
        #expect(text.contains("“How am I doing?”"))
        #expect(text.contains("call get_app_state and wait for its result before answering"))
        #expect(text.contains("do not treat an empty diary as proof the user consumed nothing"))
        #expect(text.contains("Say it only when the user explicitly asks your name or who you are"))
        #expect(text.contains("Never call a tool with user_confirmed: true unless the user agreed"))
    }

    @Test func cameraAndLanguageRulesFollowTheSession() {
        #expect(VoiceAssistantInstructions.cameraRule(session(.perTurn)).contains("each time the user speaks"))
        #expect(VoiceAssistantInstructions.cameraRule(session(.none)).contains("can't see the camera"))
        #expect(VoiceAssistantInstructions.languageRule(session(.none, language: .app)).contains("Always reply in German"))
        #expect(VoiceAssistantInstructions.languageRule(session(.none, language: .specific("ja"))).contains("Always reply in Japanese"))
    }

    @Test func undoCardsExpireAndDraftsReplaceTheirCard() async throws {
        let store = VoiceAssistantCardStore()
        var expired: [UUID] = []
        store.onExpire = { expired.append($0) }
        let undo = VoiceAssistantCard(content: .undo(.init(text: "Logged water", expiresAt: .now.addingTimeInterval(0.05))))
        store.show(undo)
        #expect(store.cards.count == 1)
        for _ in 0..<50 where !store.cards.isEmpty {
            try await Task.sleep(for: .milliseconds(20))
        }
        #expect(store.cards.isEmpty)
        #expect(expired == [undo.id])

        let first = VoiceAssistantCard(content: .draft(.init(draftID: "d1", isWorkout: false, title: "Toast", lines: [], calories: 100, macros: nil, thumbnail: nil)))
        let redo = VoiceAssistantCard(content: .draft(.init(draftID: "d1", isWorkout: false, title: "Toast x2", lines: [], calories: 200, macros: nil, thumbnail: nil)))
        store.show(first)
        store.show(redo)
        #expect(store.cards.map(\.id) == [redo.id])
        store.markDraftLogged("d1")
        #expect(store.cards.first?.isLoggedDraft("d1") == true)

        // Pending confirmations survive when more cards arrive.
        let confirmation = VoiceAssistantCard(content: .confirmation(.init(toolName: "delete_food_entry", text: "Delete toast")))
        store.show(confirmation)
        for index in 0..<VoiceAssistantCardStore.maximumCards + 2 {
            store.show(VoiceAssistantCard(content: .notice(.init(text: "Notice \(index)", action: .none))))
        }
        #expect(store.cards.count == VoiceAssistantCardStore.maximumCards)
        #expect(store.cards.contains { $0.id == confirmation.id })
    }
}
