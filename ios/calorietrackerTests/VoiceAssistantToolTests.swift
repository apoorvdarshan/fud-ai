import Foundation
import Testing
import UIKit
@testable import calorietracker

/// Catalog parity, the confirmation protocol, Undo, drafts, settings and
/// navigation, run against real stores in a throwaway defaults suite.
@MainActor
struct VoiceAssistantToolTests {
    private static let allowedErrors: Set<String> = [
        "invalid_arguments", "not_found", "unsupported", "cancelled", "timeout", "camera_unavailable",
        "permission_denied", "analysis_failed", "quota_exceeded", "confirmation_required", "workout_diary_off",
    ]

    // MARK: Catalog

    @Test func everyBundledToolHasAHandler() async throws {
        let specs = try VoiceAssistantToolCatalog.load()
        #expect(specs.count == 25)
        #expect(Set(specs.map(\.name)) == VoiceAssistantToolExecutor.handledToolNames)

        let harness = VoiceAssistantHarness(confirmSaves: false)
        for spec in specs {
            let result = await harness.executor.execute(name: spec.name, arguments: [:])
            #expect(result["ok"] is Bool, "\(spec.name) result has no ok")
            #expect((result["message"] as? String)?.hasPrefix("Unknown tool") != true, "\(spec.name) has no handler")
            if result["ok"] as? Bool == false {
                let code = result["error"] as? String ?? ""
                #expect(Self.allowedErrors.contains(code), "\(spec.name) returned \(code)")
                #expect(result["message"] is String, "\(spec.name) failure has no message")
            }
        }
    }

    @Test func declarationsAddCoachToolsAndHideWorkoutToolsWhenTheDiaryIsOff() throws {
        let specs = try VoiceAssistantToolCatalog.load()
        let on = VoiceAssistantToolCatalog.declarations(specs: specs, workoutDiaryEnabled: true)
        let off = VoiceAssistantToolCatalog.declarations(specs: specs, workoutDiaryEnabled: false)
        let onNames = on.map(\.name)
        let offNames = off.map(\.name)

        #expect(onNames.count == specs.count + CoachTools.nutritionToolNames.count + CoachTools.workoutToolNames.count)
        #expect(Set(onNames).count == onNames.count)
        for name in CoachTools.nutritionToolNames { #expect(offNames.contains(name)) }
        for name in CoachTools.workoutToolNames { #expect(!offNames.contains(name)) }
        for spec in specs where spec.requiresWorkoutDiary { #expect(!offNames.contains(spec.name)) }
        #expect(Set(specs.filter(\.requiresWorkoutDiary).map(\.name)) == ["describe_workout", "log_workout", "log_cardio"])
        // Every schema is a JSON object the wires can read.
        for declaration in on { #expect(declaration.parameters?["type"] as? String == "object") }
        #expect(on.reduce(0) { $0 + ($1.parametersJSON?.utf8.count ?? 0) + $1.description.utf8.count } < 64 * 1024)
    }

    @Test func catalogRulesMatchTheContract() throws {
        let specs = try VoiceAssistantToolCatalog.load()
        func spec(_ name: String) throws -> VoiceAssistantToolSpec { try #require(specs.first { $0.name == name }) }
        #expect(try spec("delete_food_entry").confirm == .always)
        for name in ["log_food", "update_food_entry", "log_water", "start_fast", "end_fast", "log_weight", "log_body_fat", "log_workout", "log_cardio", "update_setting"] {
            #expect(try spec(name).confirm == .setting, "\(name)")
        }
    }

    // MARK: Confirmation and Undo

    @Test func foodSaveClosesCameraOnlyWhenConfirmedAndKeepsConversation() async {
        let harness = VoiceAssistantHarness(confirmSaves: true)
        harness.host.cameraMode = .food
        let item: [String: Any] = ["name": "Banana", "calories": 105]
        let pending = await harness.executor.execute(name: "log_food", arguments: ["item": item])
        #expect(pending["error"] as? String == "confirmation_required")
        #expect(harness.host.cameraMode == .food)
        let saved = await harness.executor.execute(name: "log_food", arguments: ["item": item, "user_confirmed": true])
        #expect(saved["ok"] as? Bool == true)
        #expect(harness.host.cameraMode == nil)
        #expect(harness.food.entries.count == 1)
        #expect(!harness.host.ended)
    }

    @Test func unconfirmedSaveChangesNothingAndConfirmedSaveCanBeUndone() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: true)
        let executor = harness.executor

        let pending = await executor.execute(name: "log_water", arguments: ["amount": 2, "unit": "cup"])
        #expect(pending["ok"] as? Bool == false)
        #expect(pending["error"] as? String == "confirmation_required")
        #expect((pending["summary"] as? String)?.contains("480 ml") == true)
        #expect(harness.water.entries.isEmpty)
        #expect(harness.cards.cards.contains { $0.isConfirmation })

        let done = await executor.execute(name: "log_water", arguments: ["amount": 2, "unit": "cup", "user_confirmed": true])
        #expect(done["ok"] as? Bool == true)
        #expect(done["undo_seconds"] as? Int == 8)
        #expect(harness.water.entries.map(\.milliliters) == [480])
        #expect(!harness.cards.cards.contains { $0.isConfirmation })

        let undo = try #require(harness.cards.cards.first { $0.isUndo })
        if case .undo(let card) = undo.content {
            #expect(abs(card.expiresAt.timeIntervalSince(harness.now) - 8) < 0.5)
        }
        let line = try #require(executor.undo(cardID: undo.id))
        #expect(line.hasPrefix("[App]"))
        #expect(harness.water.entries.isEmpty)
        #expect(executor.undo(cardID: undo.id) == nil)
    }

    @Test func tappingConfirmOnTheCardMakesTheChangeAndTellsTheModel() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: true)
        harness.defaults.set(true, forKey: FastingSettings.enabledKey)

        let pending = await harness.executor.execute(name: "start_fast", arguments: ["goal_hours": 16])
        #expect(pending["error"] as? String == "confirmation_required")
        #expect(harness.fasting.activeSession == nil)

        let card = try #require(harness.cards.cards.first { $0.isConfirmation })
        let line = try #require(await harness.executor.confirm(cardID: card.id))
        #expect(line.hasPrefix("[App] The user tapped Confirm"))
        #expect(line.contains(#""ok":true"#))
        #expect(harness.fasting.activeSession?.goalMinutes == 16 * 60)
    }

    @Test func cancellingTheCardChangesNothing() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: true)
        _ = await harness.executor.execute(name: "log_water", arguments: ["amount": 300, "unit": "ml"])
        let card = try #require(harness.cards.cards.first { $0.isConfirmation })
        let line = try #require(harness.executor.cancel(cardID: card.id))
        #expect(line.contains("Nothing changed"))
        #expect(harness.water.entries.isEmpty)
        #expect(harness.cards.cards.isEmpty)
    }

    @Test func deletesAlwaysNeedConfirmationAndHaveNoUndo() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let entry = harness.addFood("Toast", calories: 120)

        let first = await harness.executor.execute(name: "delete_food_entry", arguments: ["entry_id": entry.id.uuidString])
        #expect(first["error"] as? String == "confirmation_required")
        #expect(harness.food.entries.count == 1)

        let second = await harness.executor.execute(name: "delete_food_entry", arguments: ["entry_id": entry.id.uuidString, "user_confirmed": true])
        #expect(second["ok"] as? Bool == true)
        #expect(harness.food.entries.isEmpty)
        #expect(!harness.cards.cards.contains { $0.isUndo })
    }

    @Test func savesRunStraightAwayWhenConfirmationIsOff() async {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let result = await harness.executor.execute(name: "log_water", arguments: ["amount": 0.5, "unit": "l"])
        #expect(result["ok"] as? Bool == true)
        #expect(harness.water.total(on: harness.now) == 500)
        #expect(harness.cards.cards.contains { $0.isUndo })
    }

    @Test func endingAFastCanBeUndone() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        harness.defaults.set(true, forKey: FastingSettings.enabledKey)
        _ = harness.fasting.start(goalMinutes: 14 * 60, at: harness.now.addingTimeInterval(-15 * 3600))

        let result = await harness.executor.execute(name: "end_fast", arguments: [:])
        #expect(result["ok"] as? Bool == true)
        #expect(harness.fasting.activeSession == nil)
        let undo = try #require(harness.cards.cards.first { $0.isUndo })
        _ = harness.executor.undo(cardID: undo.id)
        #expect(harness.fasting.activeSession != nil)
    }

    @Test func undoingTheFirstWeightRestoresItsProfileValueWithoutOverwritingLaterEdits() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let originalWeight = harness.profile.weightKg
        let loggedWeight = originalWeight + 2
        #expect(await harness.executor.execute(name: "log_weight", arguments: ["value": loggedWeight, "unit": "kg"])["ok"] as? Bool == true)
        // WeightStore synchronizes the production profile through UserDefaults;
        // this harness keeps its profile in memory, so reproduce that callback.
        var synced = harness.profile
        synced.weightKg = loggedWeight
        harness.executor.stores.saveProfile(synced)
        let undo = try #require(harness.cards.cards.last { $0.isUndo })
        _ = harness.executor.undo(cardID: undo.id)
        #expect(harness.weight.entries.isEmpty)
        #expect(harness.profile.weightKg == originalWeight)

        _ = await harness.executor.execute(name: "log_weight", arguments: ["value": loggedWeight, "unit": "kg"])
        var laterEdit = harness.profile
        laterEdit.weightKg = originalWeight + 5
        harness.executor.stores.saveProfile(laterEdit)
        let secondUndo = try #require(harness.cards.cards.last { $0.isUndo })
        _ = harness.executor.undo(cardID: secondUndo.id)
        #expect(harness.profile.weightKg == originalWeight + 5)
    }

    @Test func bodyFatRejectsFutureDates() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let tomorrow = try #require(Calendar.current.date(byAdding: .day, value: 1, to: harness.now))
        let result = await harness.executor.execute(name: "log_body_fat", arguments: ["percent": 20, "date": VoiceAssistantParsing.isoDay(tomorrow)])
        #expect(result["error"] as? String == "invalid_arguments")
    }

    // MARK: Food

    @Test func describedFoodBecomesADraftThatLogsScaled() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: true)
        let draft = await harness.executor.execute(name: "describe_food", arguments: ["description": "a bowl of oatmeal"])
        let draftID = try #require(draft["draft_id"] as? String)
        #expect((draft["totals"] as? [String: Any])?["calories"] as? Int == 300)
        #expect((draft["items"] as? [[String: Any]])?.first?["name"] as? String == "Oatmeal")
        #expect(harness.food.entries.isEmpty)
        #expect(harness.cards.cards.contains { $0.draftID == draftID })

        let logged = await harness.executor.execute(name: "log_food", arguments: [
            "draft_id": draftID, "servings": 2, "meal": "breakfast", "time": "08:15", "user_confirmed": true,
        ])
        #expect(logged["ok"] as? Bool == true)
        let ids = try #require(logged["entry_ids"] as? [String])
        let entry = try #require(harness.food.entries.first)
        #expect(ids == [entry.id.uuidString])
        #expect(entry.calories == 600)
        #expect(entry.servingSizeGrams == 500)
        #expect(entry.mealType == .breakfast)
        #expect(entry.source == .textInput)
        #expect(Calendar.current.component(.hour, from: entry.timestamp) == 8)
        #expect(Calendar.current.component(.minute, from: entry.timestamp) == 15)
        #expect(harness.cards.cards.contains { $0.isLoggedDraft(draftID) })
    }

    @Test func tappingLogOnADraftCardLogsIt() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: true)
        let draft = await harness.executor.execute(name: "describe_food", arguments: ["description": "oatmeal"])
        let draftID = try #require(draft["draft_id"] as? String)
        let line = try #require(await harness.executor.logDraft(draftID))
        #expect(line.hasPrefix("[App] The user tapped Log"))
        #expect(harness.food.entries.count == 1)
    }

    @Test func retryingADraftDoesNotLogItTwiceAndUndoAllowsLoggingAgain() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let draft = await harness.executor.execute(name: "describe_food", arguments: ["description": "oatmeal"])
        let draftID = try #require(draft["draft_id"] as? String)
        let first = await harness.executor.execute(name: "log_food", arguments: ["draft_id": draftID])
        let retry = await harness.executor.execute(name: "log_food", arguments: ["draft_id": draftID])
        #expect(first["entry_ids"] as? [String] == retry["entry_ids"] as? [String])
        #expect(harness.food.entries.count == 1)
        #expect(harness.cards.cards.filter(\.isUndo).count == 1)

        let undo = try #require(harness.cards.cards.first { $0.isUndo })
        _ = harness.executor.undo(cardID: undo.id)
        #expect(harness.food.entries.isEmpty)
        #expect(!harness.cards.cards.contains { $0.isLoggedDraft(draftID) })
        #expect(await harness.executor.execute(name: "log_food", arguments: ["draft_id": draftID])["ok"] as? Bool == true)
        #expect(harness.food.entries.count == 1)
    }

    @Test(arguments: [false, true])
    func lateAnalysisCannotAddCardsAfterCancellation(resetSession: Bool) async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let (started, signal) = AsyncStream<Void>.makeStream()
        var continuation: CheckedContinuation<Void, Never>?
        harness.analyzer.beforeText = {
            await withCheckedContinuation {
                continuation = $0
                signal.yield(())
            }
        }
        let analysis = Task {
            await harness.executor.execute(name: "describe_food", arguments: ["description": "oatmeal"])
        }
        var iterator = started.makeAsyncIterator()
        await iterator.next()
        if resetSession { harness.executor.reset() } else { analysis.cancel() }
        continuation?.resume()
        signal.finish()
        let result = await analysis.value
        #expect(result["error"] as? String == "cancelled")
        #expect(harness.cards.cards.isEmpty)
        #expect(harness.executor.foodDrafts.isEmpty)
        if resetSession {
            #expect(await harness.executor.execute(name: "log_water", arguments: ["amount": 250, "unit": "ml"])["error"] as? String == "cancelled")
            #expect(harness.water.entries.isEmpty)
        }
    }

    @Test func oversizedNumericArgumentsReturnErrorsWithoutTrapping() async {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        #expect(await harness.executor.execute(name: "log_water", arguments: ["amount": 1e300, "unit": "l"])["error"] as? String == "invalid_arguments")
        #expect(await harness.executor.execute(name: "update_setting", arguments: ["key": "calorie_goal", "value": 1e300])["error"] as? String == "invalid_arguments")
        #expect(await harness.executor.execute(name: "log_food", arguments: ["item": ["name": "Rice", "calories": 100, "protein_g": 1e300]])["error"] as? String == "invalid_arguments")
        #expect(await harness.executor.execute(name: "search_foods", arguments: ["query": "rice", "limit": 1e300])["ok"] as? Bool == true)
        #expect(harness.water.entries.isEmpty)
        #expect(harness.food.entries.isEmpty)
    }

    @Test(arguments: [1e300, -1e300, Double(Int.max), Double(Int.min).nextDown])
    func oversizedSettingValuesAreRejectedBeforeFormatting(value: Double) async {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let originalProfile = harness.profile
        let rawValues: [Any] = [value, String(value)]
        for key in ["calorie_goal", "protein_goal_g", "carbs_goal_g", "fat_goal_g", "water_goal_ml",
                    "theme_color", "appearance", "units", "assistant_voice"] {
            for rawValue in rawValues {
                let result = await harness.executor.execute(name: "update_setting", arguments: ["key": key, "value": rawValue])
                #expect(result["error"] as? String == "invalid_arguments", "\(key): \(rawValue)")
            }
        }
        #expect(harness.profile.customCalories == originalProfile.customCalories)
        #expect(harness.profile.customProtein == originalProfile.customProtein)
        #expect(harness.profile.customCarbs == originalProfile.customCarbs)
        #expect(harness.profile.customFat == originalProfile.customFat)
        #expect(!harness.cards.cards.contains { $0.isUndo })
    }

    @Test func logFoodNeedsExactlyOneSource() async {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let none = await harness.executor.execute(name: "log_food", arguments: [:])
        #expect(none["error"] as? String == "invalid_arguments")
        let two = await harness.executor.execute(name: "log_food", arguments: ["draft_id": "d1", "item": ["name": "Apple", "calories": 95]])
        #expect(two["error"] as? String == "invalid_arguments")
        let unknown = await harness.executor.execute(name: "log_food", arguments: ["draft_id": "d404"])
        #expect(unknown["error"] as? String == "not_found")
        let badMeal = await harness.executor.execute(name: "log_food", arguments: ["item": ["name": "Apple", "calories": 95], "meal": "brunch"])
        #expect(badMeal["error"] as? String == "invalid_arguments")
    }

    @Test func manualItemsKeepTheirServingWeight() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let result = await harness.executor.execute(name: "log_food", arguments: [
            "item": ["name": "Rice", "calories": "205", "protein_g": 4.3, "carbs_g": 45, "fat_g": 0.4, "serving": "1 cup (158 g)"],
            "date": "yesterday",
        ])
        #expect(result["ok"] as? Bool == true)
        let entry = try #require(harness.food.entries.first)
        #expect(entry.calories == 205)
        #expect(entry.servingSizeGrams == 158)
        #expect(entry.source == .manual)
        let yesterday = try #require(Calendar.current.date(byAdding: .day, value: -1, to: harness.now))
        #expect(Calendar.current.isDate(entry.timestamp, inSameDayAs: yesterday))
    }

    @Test func foodCannotBeLoggedWhileFasting() async {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        _ = harness.fasting.start(goalMinutes: 16 * 60, at: harness.now.addingTimeInterval(-3600))
        let result = await harness.executor.execute(name: "log_food", arguments: ["item": ["name": "Apple", "calories": 95]])
        #expect(result["error"] as? String == "unsupported")
        #expect(harness.food.entries.isEmpty)
    }

    @Test func updatingAnEntryChangesOnlyTheGivenFieldsAndUndoRestoresIt() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let original = harness.addFood("Pasta", calories: 500, protein: 18)

        let result = await harness.executor.execute(name: "update_food_entry", arguments: [
            "entry_id": original.id.uuidString, "calories": 450, "meal": "dinner",
        ])
        #expect(result["ok"] as? Bool == true)
        let updated = try #require(harness.food.entries.first)
        #expect(updated.id == original.id)
        #expect(updated.calories == 450)
        #expect(updated.protein == 18)
        #expect(updated.name == "Pasta")
        #expect(updated.mealType == .dinner)

        let undo = try #require(harness.cards.cards.first { $0.isUndo })
        _ = harness.executor.undo(cardID: undo.id)
        #expect(harness.food.entries.first?.calories == 500)
        #expect(harness.food.entries.first?.mealType == original.mealType)

        let empty = await harness.executor.execute(name: "update_food_entry", arguments: ["entry_id": original.id.uuidString])
        #expect(empty["error"] as? String == "invalid_arguments")
    }

    @Test func savedFoodsAreFoundAndLoggedAsNewEntries() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let oats = harness.addFood("Overnight Oats", calories: 350)
        harness.food.toggleFavorite(oats)

        let search = await harness.executor.execute(name: "search_foods", arguments: ["query": "oats"])
        let foods = try #require(search["foods"] as? [[String: Any]])
        // A favorite snapshot and its diary row can be edited independently.
        #expect(foods.count == 2)
        let savedID = try #require(foods.first?["saved_food_id"] as? String)
        #expect(foods.first?["source"] as? String == "favorite")

        let logged = await harness.executor.execute(name: "log_food", arguments: ["saved_food_id": savedID, "servings": 0.5])
        #expect(logged["ok"] as? Bool == true)
        #expect(harness.food.entries.count == 2)
        let copy = try #require(harness.food.entries.first { $0.id != oats.id })
        #expect(copy.calories == 175)
        #expect(copy.name == "Overnight Oats")

        let none = await harness.executor.execute(name: "search_foods", arguments: ["query": "pizza"])
        #expect(none["count"] as? Int == 0)
    }

    @Test func coachFoodEntriesIncludeEntryIDs() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let entry = harness.addFood("Yogurt", calories: 150)
        let day = VoiceAssistantParsing.isoDay(harness.now)
        let result = await harness.executor.execute(name: "get_food_entries", arguments: ["from": day, "to": day])
        #expect(result["ok"] as? Bool == true)
        let foods = try #require(result["foods"] as? [[String: Any]])
        #expect(foods.first?["entry_id"] as? String == entry.id.uuidString)
    }

    @Test(arguments: [false, true], ["log_food", "update_food_entry", "delete_food_entry"])
    func blockedFoodWritesReturnFailureWithoutUndoOrCallbacks(reloadBeforeWrite: Bool, tool: String) async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false, corruptBackupDirectory: URL(fileURLWithPath: "/dev/null/cannot-create"))
        let original = harness.addFood("Toast", calories: 120)
        var callbacks = 0
        harness.food.onEntriesChanged = { callbacks += 1 }
        let corrupt = Data("unreadable diary".utf8)
        harness.defaults.set(corrupt, forKey: FoodStore.storageKey)
        if reloadBeforeWrite {
            harness.food.reloadFromDefaults()
            #expect(harness.food.isPersistenceBlocked)
        }
        callbacks = 0

        let arguments: [String: Any] = tool == "log_food"
            ? ["item": ["name": "Banana", "calories": 90]]
            : ["entry_id": original.id.uuidString, "calories": 90, "user_confirmed": true]
        let result = await harness.executor.execute(name: tool, arguments: arguments)
        #expect(result["ok"] as? Bool == false)
        #expect(result["error"] as? String == "unsupported")
        #expect(harness.food.entries.first?.calories == 120)
        #expect(harness.food.entries.count == 1)
        #expect(harness.defaults.data(forKey: FoodStore.storageKey) == corrupt)
        #expect(callbacks == 0)
        #expect(!harness.cards.cards.contains { $0.isUndo })
    }

    @Test(arguments: [false, true])
    func foodUndoPreservesLaterUserEdits(updating: Bool) async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        if updating {
            let original = harness.addFood("Toast", calories: 120)
            _ = await harness.executor.execute(name: "update_food_entry", arguments: ["entry_id": original.id.uuidString, "calories": 90])
        } else {
            _ = await harness.executor.execute(name: "log_food", arguments: ["item": ["name": "Toast", "calories": 90]])
        }
        let undo = try #require(harness.cards.cards.first { $0.isUndo })
        var edited = try #require(harness.food.entries.first)
        edited.customNote = "Keep my later note"
        edited.servingSizeGrams = 75
        #expect(harness.food.updateEntry(edited))
        let result = try #require(harness.executor.undo(cardID: undo.id))
        #expect(result.contains("could not be reverted"))
        #expect(harness.food.entries.first?.customNote == edited.customNote)
        #expect(harness.food.entries.first?.servingSizeGrams == 75)
        #expect(harness.food.entries.first?.calories == 90)
    }

    @Test func searchPreservesDistinctNutritionAndFavoriteSnapshots() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        var first = harness.addFood("Protein bowl", calories: 300, protein: 10)
        harness.food.toggleFavorite(first)
        first.protein = 20
        first.servingSizeGrams = 200
        #expect(harness.food.updateEntry(first))
        let older = FoodEntry(name: "Protein bowl", calories: 300, protein: 30, carbs: 20, fat: 5,
                              timestamp: harness.now.addingTimeInterval(-45 * 86_400), source: .manual,
                              servingSizeGrams: 250)
        #expect(harness.food.addEntry(older))
        let result = await harness.executor.execute(name: "search_foods", arguments: ["query": "protein bowl"])
        let foods = try #require(result["foods"] as? [[String: Any]])
        #expect(foods.count == 3)
        #expect(Set(foods.compactMap { $0["saved_food_id"] as? String }) == [
            "favorite-\(first.id.uuidString)", "food-\(first.id.uuidString)", "food-\(older.id.uuidString)",
        ])
        #expect(Set(foods.compactMap { $0["protein_g"] as? Double }) == [10, 20, 30])
    }

    @Test func searchedFoodUsesCurrentValuesAndCannotBeLoggedAfterDeletion() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        var original = harness.addFood("Toast", calories: 120)
        let result = await harness.executor.execute(name: "search_foods", arguments: ["query": "toast"])
        let foods = try #require(result["foods"] as? [[String: Any]])
        let id = try #require(foods.first?["saved_food_id"] as? String)
        original.calories = 180
        #expect(harness.food.updateEntry(original))
        #expect(await harness.executor.execute(name: "log_food", arguments: ["saved_food_id": id])["ok"] as? Bool == true)
        #expect(harness.food.entries.allSatisfy { $0.calories == 180 })
        #expect(harness.food.deleteEntry(original))
        #expect(await harness.executor.execute(name: "log_food", arguments: ["saved_food_id": id])["error"] as? String == "not_found")
        #expect(harness.food.entries.count == 1)
    }

    @Test(arguments: ["update_food_entry", "delete_food_entry"], [false, true])
    func foodConfirmationRejectsLaterEdits(tool: String, tapCard: Bool) async throws {
        let harness = VoiceAssistantHarness(confirmSaves: true)
        var original = harness.addFood("Toast", calories: 120)
        var arguments: [String: Any] = ["entry_id": original.id.uuidString, "calories": 90]
        _ = await harness.executor.execute(name: tool, arguments: arguments)
        let confirmation = try #require(harness.cards.cards.first { $0.isConfirmation })
        original.customNote = "Changed after the request"
        #expect(harness.food.updateEntry(original))
        if tapCard {
            #expect(await harness.executor.confirm(cardID: confirmation.id)?.contains("could not be applied") == true)
        } else {
            arguments["user_confirmed"] = true
            #expect(await harness.executor.execute(name: tool, arguments: arguments)["ok"] as? Bool == false)
        }
        #expect(harness.food.entries.first?.calories == 120)
        #expect(harness.food.entries.first?.customNote == original.customNote)
        #expect(!harness.cards.cards.contains { $0.isUndo })
    }

    @Test func goalUndoRestoresOnlyItsOwnUnchangedFields() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        harness.defaults.set(false, forKey: AdaptiveGoalSettings.enabledKey)
        let before = harness.profile
        let changed = await harness.executor.execute(name: "update_setting", arguments: ["key": "calorie_goal", "value": 2100])
        try #require(changed["ok"] as? Bool == true)
        let undo = try #require(harness.cards.cards.first { $0.isUndo })
        var edited = harness.profile
        edited.name = "Later name"
        edited.weightKg += 3
        harness.executor.stores.saveProfile(edited)
        #expect(harness.executor.undo(cardID: undo.id)?.contains("was reverted") == true)
        #expect(harness.profile.customCalories == before.customCalories)
        #expect(harness.profile.customProtein == before.customProtein)
        #expect(harness.profile.name == "Later name")
        #expect(harness.profile.weightKg == edited.weightKg)

        let changedAgain = await harness.executor.execute(name: "update_setting", arguments: ["key": "calorie_goal", "value": 2200])
        try #require(changedAgain["ok"] as? Bool == true)
        let secondUndo = try #require(harness.cards.cards.first { $0.isUndo })
        var laterGoal = harness.profile
        laterGoal.applyCaloriesEdit(2300)
        harness.executor.stores.saveProfile(laterGoal)
        #expect(harness.executor.undo(cardID: secondUndo.id)?.contains("could not be reverted") == true)
        #expect(harness.profile == laterGoal)
    }

    @Test func hostedPhotoPickerNeverIncludesAnUnanalyzedFourthPhoto() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        harness.defaults.set(AIMode.hosted.rawValue, forKey: "aiAccessMode")
        harness.host.pickResult = .value([UIImage(), UIImage(), UIImage()])
        #expect(await harness.executor.execute(name: "pick_photos", arguments: [:])["ok"] as? Bool == true)
        #expect(harness.host.lastPickLimit == HostedAIConstants.maxHostedImages)
        #expect(harness.analyzer.lastImageCount == 3)
        harness.host.pickResult = .value([UIImage(), UIImage(), UIImage(), UIImage()])
        #expect(await harness.executor.execute(name: "pick_photos", arguments: [:])["error"] as? String == "invalid_arguments")
        #expect(harness.analyzer.lastImageCount == 3)
    }

    // MARK: Waiting for the user

    @Test func photosAndBarcodesReportCancelTimeoutAndPermission() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        harness.host.pickResult = .cancelled
        #expect(await harness.executor.execute(name: "pick_photos", arguments: [:])["error"] as? String == "cancelled")
        harness.host.pickResult = .timeout
        #expect(await harness.executor.execute(name: "pick_photos", arguments: [:])["error"] as? String == "timeout")
        harness.host.pickResult = .value([UIImage()])
        let picked = await harness.executor.execute(name: "pick_photos", arguments: ["note": "half portion"])
        #expect(picked["draft_id"] is String)
        #expect(harness.analyzer.lastNote == "half portion")

        harness.host.scanResult = .denied
        #expect(await harness.executor.execute(name: "scan_barcode", arguments: [:])["error"] as? String == "permission_denied")
        harness.host.scanResult = .value("5000112637922")
        let scanned = await harness.executor.execute(name: "scan_barcode", arguments: [:])
        #expect(scanned["barcode"] as? String == "5000112637922")
        harness.analyzer.barcodeError = OpenFoodFactsService.LookupError.productNotFound
        #expect(await harness.executor.execute(name: "scan_barcode", arguments: [:])["error"] as? String == "not_found")
    }

    @Test func scannedBarcodeChecksSavedLabelsAndLogsALabelAsALabel() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let label = FoodEntry(
            name: "My label",
            calories: 150,
            protein: 5,
            carbs: 20,
            fat: 4,
            source: .nutritionLabel,
            productMetadata: BarcodeLabelMemory.labelMetadata(nil, barcode: "016000275287")
        )
        #expect(harness.food.addEntry(label))
        harness.host.scanResult = .value("016000275287")
        harness.analyzer.analysis = label.reviewAnalysis
        harness.analyzer.barcodeSource = .nutritionLabel

        let scanned = await harness.executor.execute(name: "scan_barcode", arguments: [:])
        #expect(harness.analyzer.lastSavedLabelEntries.map(\.id) == [label.id])
        let draftID = try #require(scanned["draft_id"] as? String)
        #expect(await harness.executor.execute(name: "log_food", arguments: ["draft_id": draftID])["ok"] as? Bool == true)

        let logged = try #require(harness.food.entries.first { $0.id != label.id })
        #expect(logged.source == .nutritionLabel)
        #expect(logged.productMetadata?.barcode == "016000275287")
    }

    @Test func capturePhotoOpensTheCameraFirst() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        #expect(harness.host.cameraMode == nil)
        let result = await harness.executor.execute(name: "capture_photo", arguments: ["note": "cooked in olive oil"])
        #expect(result["draft_id"] is String)
        #expect(harness.host.cameraMode == nil)
        #expect(result["camera"] as? String == "closed")
        #expect(harness.analyzer.lastNote == "cooked in olive oil")

        harness.host.still = nil
        #expect(await harness.executor.execute(name: "capture_photo", arguments: [:])["error"] as? String == "camera_unavailable")
        #expect(await harness.executor.execute(name: "open_camera", arguments: ["mode": "barcode"])["error"] as? String == "invalid_arguments")
    }

    @Test func workoutQuestionsComeBackAsQuestions() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let result = await harness.executor.execute(name: "describe_workout", arguments: ["description": "some cardio"])
        #expect(result["error"] as? String == "invalid_arguments")
        #expect(result["question"] as? String == "How many minutes of cardio did you do?")
    }

    @Test func workoutAnalysisUsesTheVisibleWorkoutDiaryDateByDefault() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let yesterday = try #require(Calendar.current.date(byAdding: .day, value: -1, to: harness.now))
        harness.host.workoutDiaryDate = yesterday
        _ = await harness.executor.execute(name: "describe_workout", arguments: ["description": "running"])
        let date = try #require(harness.analyzer.lastWorkoutDate)
        #expect(Calendar.current.isDate(date, inSameDayAs: yesterday))
        _ = await harness.executor.execute(name: "describe_workout", arguments: ["description": "running", "date": "today"])
        #expect(Calendar.current.isDate(try #require(harness.analyzer.lastWorkoutDate), inSameDayAs: harness.now))
    }

    @Test func workoutToolsAreOffWithTheDiary() async {
        let harness = VoiceAssistantHarness(confirmSaves: false, workoutDiary: false)
        #expect(await harness.executor.execute(name: "log_cardio", arguments: ["activity": "running", "minutes": 30])["error"] as? String == "workout_diary_off")
        #expect(await harness.executor.execute(name: "get_workout_history", arguments: [:])["error"] as? String == "workout_diary_off")
        #expect(await harness.executor.execute(name: "get_data_summary", arguments: [:])["ok"] as? Bool == true)
    }

    @Test func cardioActivitiesMapToTheLibrary() {
        let library = [
            ExerciseLibraryItem(id: "Running_Outdoor", name: "Running", category: "cardio"),
            ExerciseLibraryItem(id: "Bicycling", name: "Bicycling", category: "cardio"),
            ExerciseLibraryItem(id: "Rowing_Stationary", name: "Rowing, Stationary", category: "cardio"),
        ]
        #expect(VoiceAssistantToolExecutor.cardioItem(for: "jogging", in: library)?.id == "Running_Outdoor")
        #expect(VoiceAssistantToolExecutor.cardioItem(for: "Cycling", in: library)?.id == "Bicycling")
        #expect(VoiceAssistantToolExecutor.cardioItem(for: "rowing", in: library)?.id == "Rowing_Stationary")
        #expect(VoiceAssistantToolExecutor.cardioItem(for: "swimming", in: library) == nil)
    }

    @Test func cardioUndoRestoresThePreviousExplicitBurn() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let first = await harness.executor.execute(name: "log_cardio", arguments: ["activity": "swimming", "minutes": 20, "calories": 123])
        #expect(first["ok"] as? Bool == true)
        #expect(harness.workouts.caloriesBurned(on: harness.now) == 123)
        let originalIDs = harness.workouts.exercises(for: harness.now).map(\.id)
        let second = await harness.executor.execute(name: "log_cardio", arguments: ["activity": "walking", "minutes": 10])
        #expect(second["ok"] as? Bool == true)
        let undo = try #require(harness.cards.cards.last { $0.isUndo })
        _ = harness.executor.undo(cardID: undo.id)
        #expect(harness.workouts.exercises(for: harness.now).map(\.id) == originalIDs)
        #expect(harness.workouts.caloriesBurned(on: harness.now) == 123)
    }

    @Test func workoutDraftLogsOnceAndCanBeUndone() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        harness.analyzer.workout = WorkoutTextDraft(date: VoiceAssistantParsing.isoDay(harness.now), exercises: [
            WorkoutTextExercise(exerciseID: nil, name: "Swimming", minutes: "20"),
        ])
        let draft = await harness.executor.execute(name: "describe_workout", arguments: ["description": "swam for 20 minutes"])
        let draftID = try #require(draft["draft_id"] as? String)
        #expect(await harness.executor.execute(name: "log_workout", arguments: ["draft_id": draftID])["ok"] as? Bool == true)
        #expect(await harness.executor.execute(name: "log_workout", arguments: ["draft_id": draftID])["ok"] as? Bool == true)
        #expect(harness.workouts.exercises(for: harness.now).count == 1)
        #expect(harness.cards.cards.filter(\.isUndo).count == 1)
        let undo = try #require(harness.cards.cards.last { $0.isUndo })
        _ = harness.executor.undo(cardID: undo.id)
        #expect(harness.workouts.exercises(for: harness.now).isEmpty)
        #expect(!harness.cards.cards.contains { $0.isLoggedDraft(draftID) })
    }

    // MARK: Settings

    @Test func settingsChangeTheAppsStoredValuesAndUndoRestoresThem() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        harness.defaults.set(AppThemeColor.fudPink.rawValue, forKey: AppThemeColor.storageKey)
        harness.defaults.set(HeightUnit.cm.rawValue, forKey: HeightUnit.storageKey)
        harness.defaults.set(WeightUnit.kg.rawValue, forKey: WeightUnit.storageKey)

        #expect(await harness.executor.execute(name: "update_setting", arguments: ["key": "theme_color", "value": "blue"])["ok"] as? Bool == true)
        #expect(harness.defaults.string(forKey: AppThemeColor.storageKey) == "blue")

        #expect(await harness.executor.execute(name: "update_setting", arguments: ["key": "appearance", "value": "Dark"])["ok"] as? Bool == true)
        #expect(harness.defaults.string(forKey: "appearanceMode") == "dark")

        #expect(await harness.executor.execute(name: "update_setting", arguments: ["key": "units", "value": "imperial"])["ok"] as? Bool == true)
        #expect(harness.defaults.string(forKey: HeightUnit.storageKey) == HeightUnit.ftin.rawValue)
        #expect(harness.defaults.string(forKey: WeightUnit.storageKey) == WeightUnit.lbs.rawValue)
        #expect(harness.defaults.string(forKey: WaterSettings.unitKey) == WaterUnit.fluidOunces.rawValue)
        let unitsUndo = try #require(harness.cards.cards.last { $0.isUndo })
        _ = harness.executor.undo(cardID: unitsUndo.id)
        #expect(harness.defaults.string(forKey: WeightUnit.storageKey) == WeightUnit.kg.rawValue)
        #expect(harness.defaults.string(forKey: WaterSettings.unitKey) == nil)

        #expect(await harness.executor.execute(name: "update_setting", arguments: ["key": "water_goal_ml", "value": "2500"])["ok"] as? Bool == true)
        #expect(harness.defaults.integer(forKey: WaterSettings.dailyGoalKey) == 2500)

        #expect(await harness.executor.execute(name: "update_setting", arguments: ["key": "assistant_auto_capture", "value": "true"])["ok"] as? Bool == true)
        #expect(harness.settings.autoCapture)
        #expect(await harness.executor.execute(name: "update_setting", arguments: ["key": "assistant_voice", "value": "puck"])["ok"] as? Bool == true)
        #expect(harness.settings.voice(for: .gemini) == "Puck")

        let settings = try #require(await harness.executor.execute(name: "get_settings", arguments: [:])["settings"] as? [String: Any])
        #expect(settings["theme_color"] as? String == "blue")
        #expect(settings["units"] as? String == "metric")
        #expect(settings["assistant_voice"] as? String == "Puck")

        #expect(await harness.executor.execute(name: "update_setting", arguments: ["key": "font_size", "value": "large"])["error"] as? String == "unsupported")
        #expect(await harness.executor.execute(name: "update_setting", arguments: ["key": "assistant_voice", "value": "nobody"])["error"] as? String == "invalid_arguments")
        #expect(await harness.executor.execute(name: "update_setting", arguments: ["key": "theme_color", "value": "plaid"])["error"] as? String == "invalid_arguments")
    }

    @Test func goalChangesFollowAdaptiveGoalsAndLockTheTarget() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        harness.defaults.set(true, forKey: AdaptiveGoalSettings.enabledKey)
        let blocked = await harness.executor.execute(name: "update_setting", arguments: ["key": "calorie_goal", "value": 2100])
        #expect(blocked["error"] as? String == "unsupported")

        harness.defaults.set(false, forKey: AdaptiveGoalSettings.enabledKey)
        let changed = await harness.executor.execute(name: "update_setting", arguments: ["key": "calorie_goal", "value": 2100])
        #expect(changed["ok"] as? Bool == true)
        #expect(harness.profile.effectiveCalories == 2100)
        #expect(harness.profile.isCaloriesLocked)

        let protein = await harness.executor.execute(name: "update_setting", arguments: ["key": "protein_goal_g", "value": "160"])
        #expect(protein["ok"] as? Bool == true)
        #expect(harness.profile.effectiveProtein == 160)
        #expect(harness.profile.effectiveCalories == 2100)

        let undo = try #require(harness.cards.cards.last { $0.isUndo })
        _ = harness.executor.undo(cardID: undo.id)
        #expect(harness.profile.effectiveProtein != 160)
    }

    // MARK: Navigation

    @Test func navigationToolsMoveTheApp() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        let settings = await harness.executor.execute(name: "open_screen", arguments: ["screen": "settings", "settings_section": "ai_providers"])
        #expect(settings["ok"] as? Bool == true)
        #expect(harness.host.opened.last?.screen == .settings)
        #expect(harness.host.opened.last?.section == .aiProviders)

        #expect(await harness.executor.execute(name: "open_screen", arguments: ["screen": "coach", "settings_section": "goals"])["error"] as? String == "invalid_arguments")
        #expect(await harness.executor.execute(name: "open_screen", arguments: ["screen": "kitchen"])["error"] as? String == "invalid_arguments")

        let yesterday = await harness.executor.execute(name: "set_diary_date", arguments: ["date": "yesterday"])
        #expect(yesterday["ok"] as? Bool == true)
        let shown = try #require(harness.host.shownDays.last)
        #expect(Calendar.current.isDate(shown, inSameDayAs: harness.now.addingTimeInterval(-86_400)))
        let tomorrow = VoiceAssistantParsing.isoDay(harness.now.addingTimeInterval(86_400))
        #expect(await harness.executor.execute(name: "set_diary_date", arguments: ["date": tomorrow])["error"] as? String == "invalid_arguments")

        let entry = harness.addFood("Salad", calories: 220)
        #expect(await harness.executor.execute(name: "open_food_entry", arguments: ["entry_id": entry.id.uuidString])["ok"] as? Bool == true)
        #expect(harness.host.openedEntries == [entry.id])
        #expect(await harness.executor.execute(name: "open_food_entry", arguments: ["entry_id": UUID().uuidString])["error"] as? String == "not_found")

        let state = await harness.executor.execute(name: "get_app_state", arguments: [:])
        #expect(state["camera_frames"] as? String == "continuous")
        #expect((state["today_totals"] as? [String: Any])?["calories"] as? Int == 220)

        _ = await harness.executor.execute(name: "end_conversation", arguments: [:])
        #expect(harness.host.ended)
    }

    @Test func hiddenTabsCannotBeOpenedAndAreReported() async throws {
        let harness = VoiceAssistantHarness(confirmSaves: false)
        harness.defaults.set("coach,workouts", forKey: TabBarSettings.hiddenTabsKey)

        let coach = await harness.executor.execute(name: "open_screen", arguments: ["screen": "coach"])
        #expect(coach["ok"] as? Bool == false)
        #expect(coach["error"] as? String == "unsupported")
        #expect(harness.host.opened.isEmpty)

        let progress = await harness.executor.execute(name: "open_screen", arguments: ["screen": "progress"])
        #expect(progress["ok"] as? Bool == true)
        #expect(harness.host.opened.last?.screen == .progress)

        let state = await harness.executor.execute(name: "get_app_state", arguments: [:])
        #expect(state["hidden_screens"] as? [String] == ["coach", "workouts"])
    }

    @Test func everyOpenScreenSectionMapsToASettingsSection() throws {
        let specs = try VoiceAssistantToolCatalog.load()
        let openScreen = try #require(specs.first { $0.name == "open_screen" })
        let schema = try #require(LiveJSON.object(openScreen.parametersJSON))
        let properties = try #require(schema["properties"] as? [String: Any])
        let sections = try #require((properties["settings_section"] as? [String: Any])?["enum"] as? [String])
        let screens = try #require((properties["screen"] as? [String: Any])?["enum"] as? [String])
        for section in sections {
            let category = try #require(ProfileSettingsCategory(voiceAssistantSection: section), "\(section)")
            let reported = try #require(category.voiceAssistantSection, "\(section)")
            // "reminders" is kept for compatibility and opens the page reported as "notifications".
            #expect(reported == (section == "reminders" ? "notifications" : section))
            #expect(ProfileSettingsCategory(voiceAssistantSection: reported) == category)
        }
        #expect(ProfileSettingsCategory(voiceAssistantSection: "reminders") == .trackingNotifications)
        #expect(ProfileSettingsCategory(voiceAssistantSection: "notifications") == .trackingNotifications)
        for screen in screens {
            #expect(VoiceAssistantScreen(rawValue: screen) != nil, "\(screen)")
        }
    }
}

// MARK: - Harness

@MainActor
final class VoiceAssistantHarness {
    let suite = "VoiceAssistantTests.\(UUID().uuidString)"
    let defaults: UserDefaults
    let food: FoodStore
    let water: WaterStore
    let fasting: FastingStore
    let weight: WeightStore
    let bodyFat: BodyFatStore
    let workouts: StrengthWorkoutStore
    let settings: VoiceAssistantSettings
    let cards = VoiceAssistantCardStore()
    let host = StubVoiceAssistantHost()
    let analyzer = StubVoiceAssistantAnalyzer()
    let now: Date
    let executor: VoiceAssistantToolExecutor
    private let profileBox = ProfileBox()

    /// The profile the executor reads and saves (goal edits), kept in memory.
    var profile: UserProfile { profileBox.profile }

    @MainActor
    private final class ProfileBox {
        var profile = UserProfile.default
    }

    init(confirmSaves: Bool, workoutDiary: Bool = true, corruptBackupDirectory: URL? = nil) {
        defaults = UserDefaults(suiteName: suite)!
        food = FoodStore(observesExternalChanges: false, defaults: defaults, corruptBackupDirectory: corruptBackupDirectory)
        water = WaterStore(defaults: defaults)
        fasting = FastingStore(defaults: defaults)
        weight = WeightStore(observesExternalChanges: false, defaults: defaults)
        bodyFat = BodyFatStore()
        workouts = StrengthWorkoutStore(defaults: defaults, storageKey: "voiceAssistantTests.workouts")
        settings = VoiceAssistantSettings(defaults: defaults)
        settings.confirmSaves = confirmSaves
        // The real time: Undo cards expire against the clock.
        now = .now
        host.homeDiaryDate = now
        host.workoutDiaryDate = now
        let now = now
        let profileBox = profileBox
        executor = VoiceAssistantToolExecutor(
            specs: try? VoiceAssistantToolCatalog.load(),
            stores: VoiceAssistantStores(
                food: food,
                water: water,
                fasting: fasting,
                weight: weight,
                bodyFat: bodyFat,
                workouts: workouts,
                profile: { profileBox.profile },
                saveProfile: { profileBox.profile = $0 }
            ),
            settings: settings,
            appDefaults: defaults,
            cards: cards,
            analyzer: analyzer,
            workoutDiaryEnabled: workoutDiary,
            host: host,
            now: { now }
        )
    }

    deinit {
        UserDefaults.standard.removePersistentDomain(forName: suite)
    }

    @discardableResult
    func addFood(_ name: String, calories: Int, protein: Double = 10) -> FoodEntry {
        let entry = FoodEntry(name: name, calories: calories, protein: protein, carbs: 20, fat: 5, timestamp: now, source: .manual, mealType: .lunch)
        precondition(food.addEntry(entry))
        return entry
    }
}

@MainActor
final class StubVoiceAssistantHost: VoiceAssistantToolHost {
    var cameraFrames: LiveCameraFrames = .continuous
    var cameraMode: VoiceAssistantCamera.Mode?
    var sessionProvider: LiveProvider? = .gemini
    var currentScreen: VoiceAssistantScreen = .home
    var currentSettingsSection: ProfileSettingsCategory?
    var homeDiaryDate: Date = .now
    var workoutDiaryDate: Date = .now
    var opened: [(screen: VoiceAssistantScreen, section: ProfileSettingsCategory?)] = []
    var shownDays: [Date] = []
    var openedEntries: [UUID] = []
    var still: UIImage? = UIImage()
    var pickResult: VoiceAssistantWait<[UIImage]> = .cancelled
    var scanResult: VoiceAssistantWait<String> = .timeout
    var ended = false
    var lastPickLimit: Int?

    func open(_ screen: VoiceAssistantScreen, settingsSection: ProfileSettingsCategory?) {
        opened.append((screen, settingsSection))
        currentScreen = screen
    }

    func showDiary(on day: Date) {
        shownDays.append(day)
        homeDiaryDate = day
    }

    func openFoodEntry(_ entry: FoodEntry) {
        openedEntries.append(entry.id)
    }

    func openCamera(_ mode: VoiceAssistantCamera.Mode) async -> VoiceAssistantCamera.StartResult {
        cameraMode = mode
        return .started
    }

    func closeCamera() {
        cameraMode = nil
    }

    func captureStill() async -> UIImage? {
        defer { closeCamera() }
        return still
    }
    func pickPhotos(limit: Int, timeout: TimeInterval) async -> VoiceAssistantWait<[UIImage]> {
        lastPickLimit = limit
        return pickResult
    }
    func scanBarcode(timeout: TimeInterval) async -> VoiceAssistantWait<String> { scanResult }
    func endConversation() { ended = true }
}

@MainActor
final class StubVoiceAssistantAnalyzer: VoiceAssistantAnalyzing {
    var analysis = GeminiService.FoodAnalysis(name: "Oatmeal", calories: 300, protein: 10, carbs: 50, fat: 6, servingSizeGrams: 250)
    var barcodeError: Error?
    var barcodeSource: FoodSource = .barcode
    private(set) var lastSavedLabelEntries: [FoodEntry] = []
    var workout: WorkoutTextDraft?
    var beforeText: (() async -> Void)?
    private(set) var lastWorkoutDate: Date?
    private(set) var lastNote: String?
    private(set) var lastImageCount = 0

    func analyzeImages(_ images: [UIImage], note: String?) async throws -> GeminiService.FoodAnalysis {
        lastImageCount = images.count
        lastNote = note
        return analysis
    }

    func analyzeText(_ description: String) async throws -> GeminiService.FoodAnalysis {
        await beforeText?()
        return analysis
    }

    func lookUpBarcode(
        _ code: String,
        savedLabelEntries: [FoodEntry]
    ) async throws -> (analysis: GeminiService.FoodAnalysis, image: UIImage?, source: FoodSource) {
        lastSavedLabelEntries = savedLabelEntries
        if let barcodeError { throw barcodeError }
        return (analysis, nil, barcodeSource)
    }

    func parseWorkout(_ description: String, date: Date, unit: WeightUnit, library: [ExerciseLibraryItem]) async throws -> WorkoutTextDraft {
        lastWorkoutDate = date
        if let workout { return workout }
        throw WorkoutClarification(question: "How many minutes of cardio did you do?", options: ["20", "30"])
    }
}

extension VoiceAssistantCard {
    var isUndo: Bool {
        if case .undo = content { return true }
        return false
    }

    var draftID: String? {
        if case .draft(let draft) = content { return draft.draftID }
        return nil
    }

    func isLoggedDraft(_ id: String) -> Bool {
        if case .draft(let draft) = content { return draft.draftID == id && draft.isLogged }
        return false
    }
}
