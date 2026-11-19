//
//  calorietrackerUITests.swift
//  calorietrackerUITests
//
//  Created by Apoorv Darshan on 05/02/26.
//

import XCTest

final class calorietrackerUITests: XCTestCase {

    /// Every test starts in a fresh Debug simulator data domain. UI mode also
    /// isolates startup from live accounts, billing, cloud sync and update prompts.
    @MainActor
    private func makeApp(onboarded: Bool = true, defaults: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "--ui-testing", "--ui-testing-reset",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-WorkoutVectorsBaseURL", "none",
            // The native first-meal review prompt is outside these local-flow tests.
            "-didPromptReviewAfterFirstLog", "YES",
        ]
        if onboarded { app.launchArguments.append("--ui-testing-onboarded") }
        app.launchArguments += defaults
        return app
    }

    @MainActor
    private func relaunchPreservingData(_ app: XCUIApplication) {
        app.terminate()
        app.launchArguments.removeAll {
            $0 == "--ui-testing-reset" || $0 == "--ui-testing-onboarded"
        }
        app.launch()
        XCTAssertTrue(app.buttons["home.add"].waitForExistence(timeout: 15))
    }

    @MainActor
    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<10 {
            if element.isHittable { break }
            app.swipeUp()
        }
        // A previous category may have left the Settings list below the target.
        for _ in 0..<10 {
            if element.isHittable { break }
            app.swipeDown()
        }
        XCTAssertTrue(element.exists, "Missing element: \(element.identifier)")
        XCTAssertTrue(element.isHittable, "Element is not tappable: \(element.identifier)")
    }

    @MainActor
    private func openSettingsCategory(_ identifier: String, in app: XCUIApplication) {
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        settings.tap()
        let category = app.buttons["settings.category.\(identifier)"]
        reveal(category, in: app)
        category.tap()
    }

    /// Fails at the caller's line with the count it found and the matching elements attached,
    /// so an intermittent mismatch shows whether an element was missing or duplicated.
    @MainActor
    private func waitForCount(
        _ count: Int, in query: XCUIElementQuery, file: StaticString = #filePath, line: UInt = #line
    ) {
        // Resolving an accessibility snapshot can itself exceed the polling
        // timeout on a busy runner. Check the current state before starting
        // that timer, so an already-correct hierarchy cannot time out.
        if query.count == count { return }
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate { _, _ in query.count == count },
            object: nil
        )
        guard XCTWaiter.wait(for: [expectation], timeout: 5) != .completed else { return }
        // A snapshot requested by the predicate may complete at the deadline.
        // Assert its actual result, not just the waiter's elapsed-time status.
        let observedCount = query.count
        guard observedCount != count else { return }
        let matches = XCTAttachment(string: query.debugDescription)
        matches.name = "Elements matching the counted query"
        matches.lifetime = .keepAlways
        add(matches)
        XCTFail("Expected \(count) matching element(s), found \(observedCount)", file: file, line: line)
    }

    @MainActor
    private func waitForValue(_ value: String, in element: XCUIElement) {
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", value), object: element
        )
        XCTAssertEqual(XCTWaiter.wait(for: [expectation], timeout: 5), .completed)
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testFreshInstallStartsOnboardingAndAdvancesThroughProfileSteps() throws {
        let app = makeApp(onboarded: false)
        app.launch()

        let start = app.buttons["Get Started"]
        XCTAssertTrue(start.waitForExistence(timeout: 15))
        XCTAssertFalse(app.buttons["home.add"].exists)
        start.tap()

        for title in ["What's your gender?", "When's your birthday?"] {
            XCTAssertTrue(app.staticTexts[title].waitForExistence(timeout: 5))
            app.buttons["Continue"].tap()
        }
        XCTAssertTrue(app.staticTexts["Height & Weight"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.segmentedControls.buttons["Imperial"].exists)
        XCTAssertTrue(app.segmentedControls.buttons["Metric"].exists)
        XCTAssertFalse(app.buttons["home.add"].exists)
    }

    @MainActor
    func testReturningUserWelcomeBackCanStartFresh() throws {
        // Continue reaches iCloud and Health, so this checks picking a source
        // and the way into the normal setup. Picking iCloud also asks iCloud
        // for a backup, so the test picks Apple Health only.
        let app = makeApp(onboarded: false, defaults: ["--ui-testing-returning"])
        app.launch()

        XCTAssertTrue(app.staticTexts["Welcome back"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["Restore from iCloud"].exists)
        XCTAssertFalse(app.buttons["Get Started"].exists)
        XCTAssertFalse(app.buttons["home.add"].exists)

        let health = app.buttons["Import from Apple Health"]
        let continueButton = app.buttons["Continue"]
        XCTAssertTrue(health.exists)
        XCTAssertFalse(continueButton.isEnabled)
        health.tap()
        XCTAssertTrue(health.isSelected)
        XCTAssertTrue(continueButton.isEnabled)
        health.tap()
        XCTAssertFalse(health.isSelected)
        XCTAssertFalse(continueButton.isEnabled)

        app.buttons["Start fresh"].tap()
        XCTAssertTrue(app.staticTexts["What's your gender?"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testWorkoutSearchOpensExerciseDetails() throws {
        let app = makeApp()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-fudai.workouts.tab.mode.v2", "library",
        ]
        app.launch()

        let workouts = app.tabBars.buttons["Workouts"]
        XCTAssertTrue(workouts.waitForExistence(timeout: 8))
        workouts.tap()

        let search = app.textFields["workouts.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        let clearSearch = app.buttons["workouts.search.clear"]
        if clearSearch.exists {
            clearSearch.tap()
        }
        search.tap()
        search.typeText("Barbell Full Squat")

        let result = app.buttons["workouts.exercise.Barbell_Full_Squat"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        result.tap()

        let visual = app.descendants(matching: .any)["workouts.exercise.visual.Barbell_Full_Squat"]
        XCTAssertTrue(visual.waitForExistence(timeout: 5))

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Barbell Full Squat Details"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    @MainActor
    func testSpeechProviderPickerOpensInFocusedCategory() throws {
        let app = makeApp()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-selectedSpeechProvider", "Native iOS (On-Device)",
        ]
        app.launch()

        openSettingsCategory("speechToText", in: app)

        let speechSection = app.staticTexts["Speech-to-Text"]
        for _ in 0..<12 {
            if speechSection.exists { break }
            app.swipeUp()
        }
        XCTAssertTrue(speechSection.waitForExistence(timeout: 3))

        let providerPicker = app.buttons["settings.speech.provider"]
        for _ in 0..<3 {
            if providerPicker.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(providerPicker.waitForExistence(timeout: 3))
        providerPicker.tap()
        XCTAssertTrue(app.staticTexts["Native iOS (On-Device)"].waitForExistence(timeout: 3))
    }

    @MainActor
    func testSettingsHubShowsFiveFocusedAppInfoCategories() throws {
        let app = makeApp()
        app.launchArguments += ["-AppleLanguages", "(en)"]
        app.launch()

        let categories = [
            ("appUpdates", "App & Updates", "Open Source (MIT)"),
            ("support", "Support Fud AI", "Rate the App"),
            ("helpFeedback", "Help & Feedback", "Report an Issue on GitHub"),
            ("community", "Community", "Join Discord"),
            ("legal", "Legal", "Privacy Policy"),
        ]

        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 8))
        settings.tap()

        for (identifier, title, expectedAction) in categories {
            let category = app.buttons["settings.category.\(identifier)"]
            reveal(category, in: app)
            category.tap()

            let navigationBar = app.navigationBars[title]
            XCTAssertTrue(navigationBar.waitForExistence(timeout: 3), "Missing \(title) detail page")

            let action = app.staticTexts[expectedAction]
            for _ in 0..<6 {
                if action.exists { break }
                app.swipeUp()
            }
            XCTAssertTrue(action.waitForExistence(timeout: 3), "Missing \(expectedAction) in \(title)")

            if identifier == "community" {
                XCTAssertTrue(app.staticTexts["Join Discord"].exists)
                XCTAssertTrue(app.staticTexts["Follow on X"].exists)
                XCTAssertTrue(app.staticTexts["Follow on Instagram"].exists)
                XCTAssertTrue(app.staticTexts["Follow on LinkedIn"].exists)
            }

            if identifier == "legal" {
                XCTAssertFalse(
                    app.staticTexts["Made by Apoorv Darshan"].isHittable,
                    "Creator footer should live on the main Settings page"
                )
            }

            let backButton = navigationBar.buttons.firstMatch
            XCTAssertTrue(backButton.exists)
            backButton.tap()
            XCTAssertTrue(app.buttons["settings.category.\(identifier)"].waitForExistence(timeout: 3))
        }
        reveal(app.staticTexts["Made by Apoorv Darshan"], in: app)
    }

    @MainActor
    func testSeparateTextProviderSettingsAreVisibleAndIndependent() throws {
        let app = makeApp()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-separateTextProviderEnabled", "YES",
            "-selectedTextAIProvider", "DeepSeek",
            "-selectedTextAIModel", "deepseek-v4-flash",
        ]
        app.launch()

        openSettingsCategory("aiProviders", in: app)

        let textProviderSection = app.staticTexts["Text AI"]
        for _ in 0..<10 {
            if textProviderSection.exists { break }
            app.swipeUp()
        }
        XCTAssertTrue(textProviderSection.waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["AI Providers & Fallbacks"].exists)
        XCTAssertEqual(app.switches["Use Separate Text Provider"].value as? String, "1")
        XCTAssertTrue(app.staticTexts["DeepSeek"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["deepseek-v4-flash"].waitForExistence(timeout: 3))
    }

    @MainActor
    func testAppleIntelligenceAppearsAsTextProvider() throws {
        let app = makeApp()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-separateTextProviderEnabled", "YES",
            "-selectedTextAIProvider", "Apple Intelligence (On-Device)",
            "-selectedTextAIModel", "System Language Model",
        ]
        app.launch()

        openSettingsCategory("aiProviders", in: app)

        let textProviderSection = app.staticTexts["Text AI"]
        for _ in 0..<10 {
            if textProviderSection.exists { break }
            app.swipeUp()
        }
        XCTAssertTrue(textProviderSection.waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Apple Intelligence (On-Device)"].waitForExistence(timeout: 3))
        let systemModel = app.staticTexts["System Language Model"]
        for _ in 0..<4 {
            if systemModel.exists { break }
            app.swipeUp()
        }
        XCTAssertTrue(systemModel.waitForExistence(timeout: 3))
    }

    @MainActor
    func testWaterTrackingAppearsAfterSelectedHomeNutrients() throws {
        let app = makeApp()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-waterTrackingEnabled", "YES",
        ]
        app.launch()

        XCTAssertTrue(app.staticTexts["Water"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["View More"].waitForExistence(timeout: 3))
        app.staticTexts["View More"].tap()

        XCTAssertTrue(app.navigationBars["Nutrition Details"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Protein, Carbs, Fat, Fiber, Water"].waitForExistence(timeout: 3))
        XCTAssertTrue(
            app.staticTexts["Water is shown after your selected nutrients while Water Tracking is enabled."]
                .waitForExistence(timeout: 3)
        )

        app.staticTexts["Home Nutrient Cards"].tap()
        XCTAssertTrue(app.navigationBars["Home Nutrients"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Choose 1–4 Nutrients"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.descendants(matching: .any)["Water, shown after selected nutrients"].waitForExistence(timeout: 3))
    }

    @MainActor
    func testWaterLogPersistsAfterRelaunchAndDeletionPersists() throws {
        let app = makeApp(defaults: ["-waterTrackingEnabled", "YES"])
        app.launch()

        let addButton = app.buttons["home.add"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 15))
        addButton.tap()
        let waterMenu = app.buttons["Water"]
        XCTAssertTrue(waterMenu.waitForExistence(timeout: 5))
        waterMenu.tap()
        // The smallest default quick-add amount.
        let glass = app.buttons.matching(NSPredicate(format: "label == %@", "250 ml")).firstMatch
        XCTAssertTrue(glass.waitForExistence(timeout: 5))
        glass.tap()

        let waterRows = app.descendants(matching: .any).matching(identifier: "water.log.row")
        reveal(waterRows.firstMatch, in: app)
        waitForCount(1, in: waterRows)

        relaunchPreservingData(app)
        reveal(waterRows.firstMatch, in: app)
        waitForCount(1, in: waterRows)
        waterRows.firstMatch.swipeLeft()
        let confirmation = app.alerts["Delete Water Log?"]
        // A full swipe opens confirmation directly; a shorter swipe reveals
        // the row action first. In either case deletion requires confirmation.
        if !confirmation.waitForExistence(timeout: 1) {
            let deleteButton = app.buttons["Delete"]
            XCTAssertTrue(deleteButton.waitForExistence(timeout: 3))
            deleteButton.tap()
        }
        XCTAssertTrue(confirmation.waitForExistence(timeout: 5))
        confirmation.buttons["Delete"].tap()
        waitForCount(0, in: waterRows)
        reveal(app.staticTexts["No diary entries"], in: app)

        relaunchPreservingData(app)
        reveal(app.staticTexts["No diary entries"], in: app)
        waitForCount(0, in: waterRows)
    }

    @MainActor
    func testManualFoodEntryValidatesRequiredFieldsAndPersists() throws {
        let app = makeApp()
        app.launch()
        let addButton = app.buttons["home.add"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 15))
        addButton.tap()
        let describeMeal = app.buttons["Describe Meal"]
        XCTAssertTrue(describeMeal.waitForExistence(timeout: 5))
        describeMeal.tap()
        let manualEntry = app.buttons["Manual Entry"]
        XCTAssertTrue(manualEntry.waitForExistence(timeout: 5))
        manualEntry.tap()

        let name = app.textFields["manual.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5))
        let save = app.buttons["manual.save"]
        XCTAssertFalse(save.isEnabled, "A blank meal must not be saved")
        name.tap()
        name.typeText("CI oat bowl")
        XCTAssertFalse(save.isEnabled, "Calories are required before saving")

        let calories = app.textFields["manual.calories"]
        calories.tap()
        calories.typeText("321")
        let done = app.buttons["Done"]
        if done.exists { done.tap() }
        reveal(save, in: app)
        XCTAssertTrue(save.isEnabled)
        save.tap()

        let meal = app.staticTexts["CI oat bowl"]
        reveal(meal, in: app)
        XCTAssertTrue(app.staticTexts.matching(identifier: "321 kcal").firstMatch.exists)

        let voiceButton = app.buttons["voiceAssistantButton"]
        XCTAssertTrue(voiceButton.exists)
        XCTAssertEqual(voiceButton.frame.midY, addButton.frame.midY, accuracy: 2)
        XCTAssertEqual(addButton.frame.minX - voiceButton.frame.maxX, 12, accuracy: 2)
        XCTAssertLessThan(addButton.frame.maxY, app.tabBars.firstMatch.frame.minY)

        // The last diary row can scroll above both floating controls. Its meal
        // summary stays beneath the title on the leading side, away from them.
        app.swipeUp()
        let mealCell = app.cells.containing(.staticText, identifier: "CI oat bowl").firstMatch
        XCTAssertTrue(mealCell.exists)
        XCTAssertTrue(meal.isHittable)
        XCTAssertLessThan(mealCell.frame.maxY, voiceButton.frame.minY)
        let mealTitle = app.descendants(matching: .any).matching(identifier: "home.meal.title").firstMatch
        let mealCalories = app.staticTexts.matching(identifier: "home.meal.calories").firstMatch
        let mealMacros = app.staticTexts.matching(identifier: "home.meal.macros").firstMatch
        XCTAssertTrue(mealTitle.exists)
        XCTAssertTrue(mealCalories.exists)
        XCTAssertTrue(mealMacros.exists)
        XCTAssertGreaterThanOrEqual(mealCalories.frame.minY, mealTitle.frame.maxY)
        XCTAssertLessThanOrEqual(mealCalories.frame.minX, mealTitle.frame.minX)
        XCTAssertEqual(mealCalories.frame.minX, mealMacros.frame.minX, accuracy: 1)
        XCTAssertLessThan(mealCalories.frame.maxX, voiceButton.frame.minX)
        XCTAssertLessThan(mealMacros.frame.maxX, voiceButton.frame.minX)

        relaunchPreservingData(app)
        reveal(meal, in: app)
        XCTAssertTrue(app.staticTexts.matching(identifier: "321 kcal").firstMatch.exists)
    }

    @MainActor
    func testHomeOrbExplainsVoiceAccessWithoutSubscriptionOrKey() throws {
        let app = makeApp()
        app.launch()

        let orb = app.buttons["voiceAssistantButton"]
        XCTAssertTrue(orb.waitForExistence(timeout: 15))
        orb.tap()

        // UI tests run without a subscription or a Gemini, OpenAI or xAI key, so the
        // assistant explains both ways to get voice instead of starting a session.
        let explanation = app.descendants(matching: .any)["voiceAssistantAccessExplanation"]
        XCTAssertTrue(explanation.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["voiceAssistant.access.title"].exists)
        XCTAssertTrue(app.buttons["voiceAssistant.access.subscribe"].exists)
        XCTAssertTrue(app.buttons["voiceAssistant.access.addKey"].exists)

        app.buttons["voiceAssistant.access.notNow"].tap()
        XCTAssertTrue(explanation.waitForNonExistence(timeout: 5))
        XCTAssertTrue(orb.waitForExistence(timeout: 5))
    }

    @MainActor
    func testVoicePreviewRequiresConfiguredKeyAndOffersSavedVoicePicker() throws {
        let app = makeApp(defaults: ["-voiceAssistant.provider", "gemini"])
        app.launch()
        openSettingsCategory("voiceAssistant", in: app)

        let preview = app.buttons["settings.voiceAssistant.preview"]
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        XCTAssertEqual(preview.label, "Preview Voice")
        XCTAssertFalse(preview.isEnabled, "Preview must not connect without the selected provider's key")
        XCTAssertTrue(app.buttons["settings.voiceAssistant.addKey"].exists)
        XCTAssertFalse(app.staticTexts["settings.voiceAssistant.preview.preparing"].exists)

        let voice = app.buttons["settings.voiceAssistant.voice"]
        reveal(voice, in: app)
        voice.tap()
        let option = app.buttons["Puck"]
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        option.tap()
        XCTAssertFalse(preview.isEnabled)
        XCTAssertTrue(app.buttons["settings.voiceAssistant.model"].exists)
        XCTAssertFalse(app.alerts.firstMatch.exists, "Choosing a preview voice must not request microphone access")
    }

    @MainActor
    func testWaterTrackingSettingPersistsAfterRelaunch() throws {
        let app = makeApp()
        app.launch()
        openSettingsCategory("trackingNotifications", in: app)

        let toggle = app.switches["settings.water.enabled"]
        let waterGoal = app.staticTexts["Daily Water Goal"]
        reveal(toggle, in: app)
        waitForValue("0", in: toggle)
        XCTAssertFalse(waterGoal.exists)
        toggle.tap()
        // Toggling rebuilds the list. Verify committed state through its dependent
        // rows here, then check the native switch's value again after relaunch.
        XCTAssertTrue(waterGoal.waitForExistence(timeout: 5))

        relaunchPreservingData(app)
        XCTAssertTrue(app.staticTexts["Water"].waitForExistence(timeout: 5))
        openSettingsCategory("trackingNotifications", in: app)
        reveal(toggle, in: app)
        waitForValue("1", in: toggle)
        XCTAssertTrue(waterGoal.exists)
        toggle.tap()
        XCTAssertTrue(waterGoal.waitForNonExistence(timeout: 5))

        relaunchPreservingData(app)
        openSettingsCategory("trackingNotifications", in: app)
        reveal(toggle, in: app)
        waitForValue("0", in: toggle)
        XCTAssertFalse(waterGoal.exists)
    }

    @MainActor
    func testTrackingAndNotificationsShareOneSettingsPage() throws {
        let app = makeApp()
        app.launch()

        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        settings.tap()
        let combined = app.buttons["settings.category.trackingNotifications"]
        reveal(combined, in: app)
        // The former separate rows are gone (#432).
        XCTAssertFalse(app.buttons["settings.category.trackingReminders"].exists)
        XCTAssertFalse(app.buttons["settings.category.notifications"].exists)
        combined.tap()

        XCTAssertTrue(app.navigationBars["Tracking & Notifications"].waitForExistence(timeout: 5))
        let waterTracking = app.switches["settings.water.enabled"]
        XCTAssertTrue(waterTracking.waitForExistence(timeout: 5))
        // Tracking comes first, then the notification master switch.
        let notifications = app.switches["settings.notifications.enabled"]
        XCTAssertTrue(notifications.waitForExistence(timeout: 5))
        XCTAssertLessThan(waterTracking.frame.minY, notifications.frame.minY)
    }
}
