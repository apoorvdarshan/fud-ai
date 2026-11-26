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
    private func reveal(
        _ element: XCUIElement, in app: XCUIApplication,
        file: StaticString = #filePath, line: UInt = #line
    ) {
        // Each accessibility lookup can take seconds on a busy CI simulator.
        // Return as soon as the element is already tappable; only diagnose an
        // absent element after checking both scroll directions.
        for attempt in 0...20 {
            if element.isHittable { return }
            if attempt < 10 {
                app.swipeUp()
            } else if attempt < 20 {
                app.swipeDown()
            }
        }
        let reason = element.exists ? "Element is not tappable" : "Missing element"
        XCTFail("\(reason): \(element.identifier)", file: file, line: line)
    }

    /// Typing races the sheet's focus animation on busy runners: a tap can land
    /// before the field accepts the keyboard, and `typeText` then fails with
    /// "Neither element nor any descendant has keyboard focus". Tap first so the
    /// field takes focus even when a keyboard is already up, then confirm the
    /// keyboard before typing.
    @MainActor
    private func type(_ text: String, into field: XCUIElement, app: XCUIApplication) {
        for _ in 0..<3 {
            field.tap()
            if app.keyboards.firstMatch.waitForExistence(timeout: 2) { break }
        }
        XCTAssertTrue(
            app.keyboards.firstMatch.waitForExistence(timeout: 5),
            "The keyboard never appeared for \(field.identifier)"
        )
        field.typeText(text)
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
    private func waitForValue(
        _ value: String, in element: XCUIElement,
        file: StaticString = #filePath, line: UInt = #line
    ) {
        if element.value as? String == value { return }
        let expectation = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "value == %@", value), object: element
        )
        if XCTWaiter.wait(for: [expectation], timeout: 5) == .completed { return }
        XCTAssertEqual(element.value as? String, value, "\(element.identifier) value", file: file, line: line)
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
    func testHomeViewMoreChangesDaysOnSwipesAndOpensOnlyOnTap() {
        let app = makeApp()
        app.launch()

        let date = app.buttons["home.calendar"]
        XCTAssertTrue(date.waitForExistence(timeout: 8))
        let today = date.label
        XCTAssertEqual(today, "Today")
        let viewMore = app.buttons["home.viewMore"]
        let details = app.navigationBars["Nutrition Details"]
        reveal(viewMore, in: app)
        let start = viewMore.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
        let shortDragEnd = start.withOffset(CGVector(dx: 30, dy: 0))
        start.press(forDuration: 0.05, thenDragTo: shortDragEnd)
        XCTAssertFalse(details.exists)
        reveal(app.buttons["home.calendar"], in: app)
        XCTAssertEqual(date.label, today)
        reveal(viewMore, in: app)
        viewMore.swipeRight()
        XCTAssertFalse(details.exists)
        reveal(app.buttons["home.calendar"], in: app)
        XCTAssertNotEqual(date.label, today)
        reveal(viewMore, in: app)
        viewMore.swipeLeft()
        XCTAssertFalse(details.exists)
        reveal(viewMore, in: app)
        viewMore.swipeLeft()
        XCTAssertFalse(details.exists)
        reveal(app.buttons["home.calendar"], in: app)
        XCTAssertEqual(date.label, today)
        reveal(viewMore, in: app)
        viewMore.tap()
        XCTAssertTrue(details.waitForExistence(timeout: 5))
    }

    @MainActor
    func testHomeCalendarOpensAsAPopup() {
        let app = makeApp()
        app.launch()
        let calendar = app.buttons["home.calendar"]
        reveal(calendar, in: app)
        calendar.tap()
        let popup = app.descendants(matching: .any)["home.calendarPopup"].firstMatch
        XCTAssertTrue(popup.waitForExistence(timeout: 5))
        XCTAssertLessThan(popup.frame.height, app.frame.height * 0.8)
    }

    @MainActor
    func testHapticsAreOnByDefaultAndCanBeTurnedOff() {
        let app = makeApp()
        app.launch()
        openSettingsCategory("appPreferences", in: app)
        let toggle = app.switches["settings.haptics"]
        reveal(toggle, in: app)
        waitForValue("1", in: toggle)
        // XCTest gives the native switch the whole row as its frame, but on
        // iOS 27.2 tapping the label area doesn't activate the thumb.
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        waitForValue("0", in: toggle)
        relaunchPreservingData(app)
        openSettingsCategory("appPreferences", in: app)
        reveal(toggle, in: app)
        waitForValue("0", in: toggle)
    }

    @MainActor
    func testChallengesShareTheProgressTab() {
        let app = makeApp()
        app.launch()
        let progress = app.tabBars.buttons["Progress"]
        reveal(progress, in: app)
        progress.tap()
        XCTAssertTrue(progress.isSelected)
        XCTAssertFalse(app.staticTexts["Weekly Recap"].exists)

        let weekly = app.buttons["Weekly Challenge"]
        reveal(weekly, in: app)
        weekly.tap()
        XCTAssertTrue(app.staticTexts["Challenges"].waitForExistence(timeout: 5))
        let daily = app.buttons["Daily"].firstMatch
        XCTAssertTrue(daily.waitForExistence(timeout: 5))
        daily.tap()
        XCTAssertTrue(app.staticTexts["Join Weekly to create your challenge profile first."].waitForExistence(timeout: 5))
        app.buttons["Monthly"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "longest run")).firstMatch.exists)
        XCTAssertTrue(weekly.isSelected)
        XCTAssertTrue(progress.isSelected)
        app.buttons["My Progress"].tap()
        XCTAssertTrue(progress.isSelected)
        XCTAssertFalse(app.buttons["nav.voice"].exists)
    }

    @MainActor
    func testWorkoutSearchOpensExerciseDetails() throws {
        let app = makeApp()
        app.launch()

        let workouts = app.tabBars.buttons["Workouts"]
        reveal(workouts, in: app)
        workouts.tap()
        XCTAssertTrue(app.buttons["Add workout"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["voiceAssistantButton"].isHittable)
        let library = app.buttons["Exercise library"]
        reveal(library, in: app)
        library.tap()

        let search = app.textFields["workouts.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        XCTAssertTrue(workouts.isSelected)
        let clearSearch = app.buttons["workouts.search.clear"]
        if clearSearch.exists {
            clearSearch.tap()
        }
        type("Barbell Full Squat", into: search, app: app)

        let result = app.buttons["workouts.exercise.Barbell_Full_Squat"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        result.tap()

        let visual = app.descendants(matching: .any)["workouts.exercise.visual.Barbell_Full_Squat"]
        XCTAssertTrue(visual.waitForExistence(timeout: 5))
        let reportImage = app.buttons["workouts.exercise.reportImage"]
        XCTAssertTrue(reportImage.waitForExistence(timeout: 5))
        reportImage.tap()
        XCTAssertTrue(app.buttons["Report an Issue on GitHub"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Report an Issue on Discord"].exists)
        XCTAssertFalse(app.buttons["Report an Issue on GitHub"].isEnabled)
        app.buttons["Cancel"].tap()

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Barbell Full Squat Details"
        screenshot.lifetime = .keepAlways
        add(screenshot)

        app.tabBars.buttons["Home"].tap()
        XCTAssertTrue(app.buttons["home.add"].waitForExistence(timeout: 5))
        workouts.tap()
        XCTAssertTrue(visual.waitForExistence(timeout: 5), "Switching tabs should preserve the open exercise detail")
    }

    @MainActor
    func testCanEditWeightRepsAndEffortInAWorkoutSet() {
        let app = makeApp(defaults: ["--ui-testing-workout-set", "-weightUnit", "kg"])
        app.launch()
        let workouts = app.tabBars.buttons["Workouts"]
        XCTAssertTrue(workouts.waitForExistence(timeout: 15))
        reveal(workouts, in: app)
        workouts.tap()

        let values = [("kg", "25"), ("Reps", "8"), ("RPE", "6")]
        let dock = app.buttons["workouts.shortcut.0"]
        let done = app.buttons["workouts.keyboard.done"]
        for (placeholder, value) in values {
            let field = app.textFields.matching(NSPredicate(format: "placeholderValue == %@", placeholder)).firstMatch
            XCTAssertTrue(field.waitForExistence(timeout: 8))
            // In the compact rail layout XCTest can report a row as hittable
            // even when the pinned workout dock or keyboard is in front of it.
            func viewportBottom() -> CGFloat {
                if done.exists, !done.frame.isEmpty { return done.frame.minY }
                let keyboard = app.keyboards.firstMatch
                if keyboard.exists, !keyboard.frame.isEmpty { return keyboard.frame.minY }
                return dock.exists ? dock.frame.minY - 8 : app.frame.maxY - 100
            }
            for _ in 0..<3 {
                if field.isHittable && field.frame.maxY < viewportBottom() { break }
                let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: min(0.70, (viewportBottom() - 8) / app.frame.height)))
                let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.12, dy: 0.32))
                start.press(forDuration: 0.05, thenDragTo: end)
            }
            XCTAssertLessThanOrEqual(field.frame.maxY, viewportBottom(), "\(placeholder) must be above the dock or keyboard")
            XCTAssertTrue(field.isHittable)
            field.tap()
            field.typeText(value)
            XCTAssertEqual(field.value as? String, value)
            if placeholder == "Reps" {
                XCTAssertTrue(app.buttons["Skip rest"].waitForExistence(timeout: 5))
            }
        }
        XCTAssertEqual(app.state, .runningForeground)

        relaunchPreservingData(app)
        reveal(workouts, in: app)
        workouts.tap()
        for (placeholder, value) in values {
            let field = app.textFields.matching(NSPredicate(format: "placeholderValue == %@", placeholder)).firstMatch
            XCTAssertTrue(field.waitForExistence(timeout: 8))
            XCTAssertEqual(field.value as? String, value)
        }
    }

    @MainActor
    func testWorkoutLogReorderButtonStaysCompactAndTappable() {
        let app = makeApp(defaults: [
            "--screenshot-demo", "-screenshotScene", "workouts",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryL",
        ])
        app.launch()

        let reorder = app.buttons["workout.log.reorder"]
        XCTAssertTrue(reorder.waitForExistence(timeout: 15))
        reveal(reorder, in: app)
        XCTAssertEqual(reorder.label, "Reorder")
        XCTAssertLessThan(reorder.frame.height, 64, "The reorder action must not stretch the section header")
        reorder.tap()
        let done = app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@",
                                                    "workout.log.reorder", "Done")).firstMatch
        XCTAssertTrue(done.waitForExistence(timeout: 5))
        done.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "identifier == %@ AND label == %@",
                                                       "workout.log.reorder", "Reorder")).firstMatch
            .waitForExistence(timeout: 5))
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
        let native = app.staticTexts["System Speech Recognition"]
        XCTAssertTrue(native.waitForExistence(timeout: 3))
        native.tap()
        let source = app.descendants(matching: .any)["settings.localModel.whisper.source"].firstMatch
        reveal(source, in: app)
        XCTAssertGreaterThanOrEqual(source.frame.height, 44)
    }

    @MainActor
    func testAvailableUpdateIsVisibleInSettings() {
        let app = makeApp()
        app.launchArguments += ["-AppleLanguages", "(en)", "--ui-testing-app-update-available"]
        app.launch()

        app.tabBars.buttons["Settings"].tap()
        let updates = app.buttons["settings.category.appUpdates"]
        reveal(updates, in: app)
        XCTAssertEqual(updates.value as? String, "Update Available")
        updates.tap()

        let store = app.buttons["about.update.store"]
        XCTAssertTrue(store.waitForExistence(timeout: 5))
        XCTAssertTrue(store.label.contains("Update Available"))
        XCTAssertTrue(store.label.contains("99.0"))
    }

    @MainActor
    func testAboutAndSupportOpenFromSettings() throws {
        let app = makeApp()
        app.launchArguments += ["-AppleLanguages", "(en)"]
        app.launch()

        let categories = [
            ("appUpdates", "App & Updates", "Open Source (MIT)"),
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
                reveal(app.staticTexts["311099925"], in: app)
                XCTAssertTrue(app.staticTexts["D-U-N-S Number"].exists)
                XCTAssertTrue(app.staticTexts["Dun & Bradstreet"].exists)
                XCTAssertFalse(
                    app.staticTexts["Made by Apoorv Darshan"].isHittable,
                    "Creator footer should live on the About hub"
                )
            }

            let backButton = navigationBar.buttons.firstMatch
            XCTAssertTrue(backButton.exists)
            backButton.tap()
            XCTAssertTrue(app.buttons["settings.category.\(identifier)"].waitForExistence(timeout: 3))
        }
        reveal(app.staticTexts["Made by Apoorv Darshan"], in: app)
        let support = app.buttons["settings.category.support"]
        reveal(support, in: app)
        support.tap()
        XCTAssertTrue(app.navigationBars["Support Fud AI"].waitForExistence(timeout: 5))
        reveal(app.staticTexts["Rate the App"], in: app)
        XCTAssertFalse(app.buttons["nav.voice"].exists)
    }

    @MainActor
    func testCoachComposerRemainsReachableWithKeyboard() {
        let app = makeApp()
        app.launch()
        let home = app.tabBars.buttons["Home"]
        let coach = app.tabBars.buttons["Coach"]
        XCTAssertTrue(coach.waitForExistence(timeout: 15))
        coach.tap()
        let input = app.descendants(matching: .any)["coach.input"].firstMatch
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        input.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["nav.voice"].exists)
        XCTAssertTrue(input.isHittable)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Coach keyboard"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        home.tap()
        XCTAssertTrue(app.buttons["home.calendar"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testSeparateTextProviderSettingsAreVisibleAndIndependent() throws {
        let app = makeApp()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-selectedAIProvider", "Google Gemini",
            "-separateTextProviderEnabled", "YES",
            "-selectedTextAIProvider", "DeepSeek",
            "-selectedTextAIModel", "deepseek-v4-flash",
        ]
        app.launch()

        openSettingsCategory("aiProviders", in: app)

        XCTAssertTrue(app.navigationBars["AI Providers & Fallbacks"].exists)
        let primary = app.buttons["settings.ai.primaryProvider"]
        reveal(primary, in: app)
        waitForValue("Google Gemini", in: primary)

        let source = app.descendants(matching: .any)["settings.localModel.gemma.source"].firstMatch
        reveal(source, in: app)
        // AX frame subtraction can report exactly 44 pt as 43.999999999999986.
        XCTAssertGreaterThanOrEqual(source.frame.height + 1e-9, 44)
        let modelScreenshot = XCTAttachment(screenshot: app.screenshot())
        modelScreenshot.name = "AI Providers model source"
        modelScreenshot.lifetime = .keepAlways
        add(modelScreenshot)

        // Scroll to the actual controls: the lazy section header may appear
        // before its rows, and a native picker combines its label and value.
        let separate = app.switches["settings.ai.useSeparateText"]
        reveal(separate, in: app)
        waitForValue("1", in: separate)
        let textProvider = app.buttons["settings.ai.textProvider"]
        reveal(textProvider, in: app)
        waitForValue("DeepSeek", in: textProvider)
        let textModel = app.buttons["settings.ai.textModel"]
        reveal(textModel, in: app)
        // The saved legacy ID above must migrate to the current DeepSeek preset.
        waitForValue("deepseek-flash", in: textModel)
    }

    @MainActor
    func testAppleIntelligenceAppearsAsTextProvider() {
        let app = makeApp()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-separateTextProviderEnabled", "YES",
            // A long native menu can open scrolled to its selection. Start
            // near Apple Intelligence so this test does not depend on the
            // OS's popup hierarchy or scroll position.
            "-selectedTextAIProvider", "Google Gemini",
        ]
        app.launch()

        openSettingsCategory("aiProviders", in: app)

        let provider = app.buttons["settings.ai.textProvider"]
        reveal(provider, in: app)
        waitForValue("Google Gemini", in: provider)
        provider.tap()
        let appleIntelligence = app.buttons["Apple Intelligence (On-Device)"]
        XCTAssertTrue(appleIntelligence.waitForExistence(timeout: 5))
        appleIntelligence.tap()
        waitForValue("Apple Intelligence (On-Device)", in: provider)
        let systemModel = app.staticTexts["settings.ai.systemTextModel"]
        reveal(systemModel, in: app)
        XCTAssertEqual(systemModel.label, "System Language Model")
    }

    @MainActor
    func testWaterTrackingAppearsAfterSelectedHomeNutrients() throws {
        let app = makeApp()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-waterTrackingEnabled", "YES",
            "-appearanceMode", "dark",
        ]
        app.launch()

        XCTAssertTrue(app.staticTexts["Water"].waitForExistence(timeout: 8))
        let viewMore = app.buttons["home.viewMore"]
        reveal(viewMore, in: app)
        viewMore.tap()

        XCTAssertTrue(app.navigationBars["Nutrition Details"].waitForExistence(timeout: 5))
        let cardPicker = app.buttons["nutrition.homeCards"]
        XCTAssertTrue(cardPicker.waitForExistence(timeout: 3))
        let selectedNutrients = cardPicker.value as? String ?? ""
        XCTAssertTrue(selectedNutrients.contains("Fiber") && selectedNutrients.contains("Water"))
        XCTAssertTrue(
            app.staticTexts["Water is shown after your selected nutrients while Water Tracking is enabled."]
                .waitForExistence(timeout: 3)
        )

        let details = XCTAttachment(screenshot: app.screenshot())
        details.name = "Flash Nutrition Details - dark"
        details.lifetime = .keepAlways
        add(details)

        cardPicker.tap()
        let pickerToolbar = app.navigationBars["Home Nutrients"]
        XCTAssertTrue(pickerToolbar.waitForExistence(timeout: 5))
        let cancel = pickerToolbar.buttons["Cancel"]
        let done = pickerToolbar.buttons["Done"]
        XCTAssertTrue(cancel.isHittable && done.isHittable)
        XCTAssertFalse(cancel.frame.intersects(done.frame))
        XCTAssertTrue(app.descendants(matching: .any)["Water, shown after selected nutrients"].waitForExistence(timeout: 3))
        let fiber = app.buttons["nutrition.picker.fiber"]
        reveal(fiber, in: app)
        XCTAssertTrue(fiber.isSelected)
        fiber.tap()
        XCTAssertFalse(fiber.isSelected)
        done.tap()
        XCTAssertTrue(pickerToolbar.waitForNonExistence(timeout: 5))
        let updatedSelection = cardPicker.value as? String ?? ""
        XCTAssertFalse(updatedSelection.contains("Fiber"))
        XCTAssertTrue(updatedSelection.contains("Water"))
    }

    @MainActor
    func testHomeAddMenuFollowsTheSettingsOrder() {
        let app = makeApp(defaults: ["-appearanceMode", "light"])
        app.launch()

        func menuButton(_ label: String) -> XCUIElement {
            app.buttons.matching(NSPredicate(
                format: "label == %@ AND NOT (identifier BEGINSWITH %@)", label, "home.foodShortcut."
            )).firstMatch
        }

        let menu = app.buttons["home.add"]
        XCTAssertTrue(menu.waitForExistence(timeout: 15))
        menu.tap()

        let reuse = app.buttons["Reuse Meal"]
        let describe = app.buttons["Describe Meal"]
        let photos = app.buttons["Photo & Scan"]
        XCTAssertTrue(reuse.waitForExistence(timeout: 5))
        XCTAssertTrue(reuse.isHittable)
        XCTAssertTrue(describe.isHittable)
        XCTAssertTrue(photos.isHittable)
        XCTAssertLessThan(photos.frame.midY, describe.frame.midY)
        XCTAssertLessThan(describe.frame.midY, reuse.frame.midY)

        describe.tap()
        let manual = menuButton("Manual Entry")
        let voice = menuButton("Voice")
        let text = menuButton("Text Input")
        XCTAssertTrue(manual.waitForExistence(timeout: 5))
        XCTAssertTrue(voice.isHittable)
        XCTAssertTrue(text.isHittable)
        XCTAssertLessThan(text.frame.midY, voice.frame.midY)
        XCTAssertLessThan(voice.frame.midY, manual.frame.midY)
        let menuScreenshot = XCTAttachment(screenshot: app.screenshot())
        menuScreenshot.name = "Native Log Food submenu - light"
        menuScreenshot.lifetime = .keepAlways
        add(menuScreenshot)
        // Tap the diary behind the anchored menu to dismiss it.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.22, dy: 0.15)).tap()
        XCTAssertTrue(app.buttons["Manual Entry"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(menu.isHittable)
        menu.tap()
        XCTAssertTrue(photos.waitForExistence(timeout: 5))
        photos.tap()
        let camera = menuButton("Camera")
        let library = menuButton("Photos")
        let barcode = menuButton("Barcode")
        XCTAssertTrue(barcode.waitForExistence(timeout: 5))
        XCTAssertTrue(camera.isHittable)
        XCTAssertTrue(library.isHittable)
        XCTAssertLessThan(camera.frame.midY, library.frame.midY)
        XCTAssertLessThan(library.frame.midY, barcode.frame.midY)
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.22, dy: 0.15)).tap()
        XCTAssertTrue(barcode.waitForNonExistence(timeout: 5))
        XCTAssertTrue(menu.waitForExistence(timeout: 5))
        menu.tap()
        XCTAssertTrue(reuse.waitForExistence(timeout: 5))
        XCTAssertFalse(barcode.exists)
    }

    @MainActor
    func testFlashEnergyUnitPickerAppliesSelection() {
        let app = makeApp(defaults: ["-appearanceMode", "dark"])
        app.launch()
        openSettingsCategory("appPreferences", in: app)

        let picker = app.buttons["settings.energyUnit"]
        reveal(picker, in: app)
        picker.tap()
        let kilojoules = app.buttons["Kilojoules (kJ)"]
        XCTAssertTrue(kilojoules.waitForExistence(timeout: 5))
        let pickerScreenshot = XCTAttachment(screenshot: app.screenshot())
        pickerScreenshot.name = "Flash energy unit picker - dark"
        pickerScreenshot.lifetime = .keepAlways
        add(pickerScreenshot)
        kilojoules.tap()
        waitForValue("Kilojoules (kJ)", in: picker)

        // Also cover the picker that regressed to a bottom sheet, without
        // relaunching the entire app for a second settings test.
        app.navigationBars["App Settings"].buttons.firstMatch.tap()
        let workout = app.buttons["settings.category.workout"]
        reveal(workout, in: app)
        workout.tap()
        let timer = app.buttons["Rest Timer"]
        reveal(timer, in: app)
        timer.tap()
        let off = app.buttons["Off"]
        XCTAssertTrue(off.waitForExistence(timeout: 5))
        XCTAssertTrue(app.navigationBars["Workout"].exists)
        let timerScreenshot = XCTAttachment(screenshot: app.screenshot())
        timerScreenshot.name = "Native Rest Timer dropdown - dark"
        timerScreenshot.lifetime = .keepAlways
        add(timerScreenshot)
        off.tap()
        waitForValue("Off", in: timer)
    }

    @MainActor
    func testEditFoodNativeToolbarKeepsSaveAndCancelSeparateAtLargeTextSize() {
        let app = makeApp(defaults: [
            "--screenshot-demo", "-screenshotScene", "food", "-appearanceMode", "dark",
            "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"
        ])
        app.launch()

        let toolbar = app.navigationBars["Edit Food"]
        XCTAssertTrue(toolbar.waitForExistence(timeout: 15))
        let cancel = toolbar.buttons["Cancel"]
        let save = toolbar.buttons["Save"]
        XCTAssertTrue(cancel.isHittable)
        XCTAssertTrue(save.isHittable)
        XCTAssertFalse(cancel.frame.intersects(save.frame))
        XCTAssertTrue(app.textFields["food.editor.name"].exists || app.textViews["food.editor.name"].exists)

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Edit Food header at large text size"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        cancel.tap()
        XCTAssertTrue(app.buttons["home.add"].waitForExistence(timeout: 5))
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
    func testFoodDescriptionShowsTheFactDuringTheActualAnalysisSheet() {
        let app = makeApp(defaults: ["--ui-testing-hold-food-analysis"])
        app.launch()

        let add = app.buttons["home.add"]
        XCTAssertTrue(add.waitForExistence(timeout: 15))
        add.tap()
        let describe = app.buttons["Describe Meal"]
        XCTAssertTrue(describe.waitForExistence(timeout: 5))
        describe.tap()
        let text = app.buttons["Text Input"].firstMatch
        XCTAssertTrue(text.waitForExistence(timeout: 5))
        text.tap()

        let description = app.descendants(matching: .any)["food.description"].firstMatch
        XCTAssertTrue(description.waitForExistence(timeout: 5))
        description.tap()
        description.typeText("Oatmeal with blueberries")
        let analyze = app.buttons["Analyze"]
        XCTAssertTrue(analyze.isEnabled)
        analyze.tap()

        let fact = app.descendants(matching: .any)["analysis.waitingFact"].firstMatch
        XCTAssertTrue(fact.waitForExistence(timeout: 5))
        XCTAssertTrue(fact.isHittable, "A fact must be visible without scrolling")
        XCTAssertTrue(fact.label.contains("Did you know?"))
        let cancel = app.buttons["Cancel"].firstMatch
        XCTAssertTrue(cancel.isHittable)
        XCTAssertLessThan(fact.frame.maxY, cancel.frame.minY)
        cancel.tap()
        XCTAssertTrue(add.waitForExistence(timeout: 5))
        XCTAssertFalse(fact.exists)
    }

    @MainActor
    func testManualFoodEntryValidatesRequiredFieldsAndPersists() throws {
        let app = makeApp(defaults: ["-appearanceMode", "dark"])
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
        type("CI oat bowl", into: name, app: app)
        XCTAssertFalse(save.isEnabled, "Calories are required before saving")

        let calories = app.textFields["manual.calories"]
        type("321", into: calories, app: app)
        let done = app.buttons["Done"]
        if done.exists { done.tap() }
        reveal(save, in: app)
        XCTAssertTrue(save.isEnabled)
        save.tap()

        let meal = app.staticTexts["CI oat bowl"]
        reveal(meal, in: app)
        XCTAssertTrue(app.staticTexts.matching(identifier: "321 kcal").firstMatch.exists)

        let voice = app.buttons["voiceAssistantButton"]
        XCTAssertTrue(voice.isHittable)
        XCTAssertLessThanOrEqual(voice.frame.maxX, addButton.frame.minX)
        XCTAssertTrue(meal.isHittable)

        relaunchPreservingData(app)
        reveal(meal, in: app)
        XCTAssertTrue(app.staticTexts.matching(identifier: "321 kcal").firstMatch.exists)
    }

    @MainActor
    func testHomeKeepsFoodMethodsInClassicAddMenu() {
        let app = makeApp()
        app.launch()
        XCTAssertTrue(app.buttons["home.add"].waitForExistence(timeout: 10))
        for index in 0...2 { XCTAssertFalse(app.buttons["home.foodShortcut.\(index)"].exists) }
        XCTAssertFalse(app.buttons["home.favorites"].exists)
        app.buttons["home.add"].tap()
        app.buttons["Describe Meal"].tap()
        XCTAssertTrue(app.buttons["Manual Entry"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testHomeVoiceExplainsAccessWithoutLeavingHome() {
        let app = makeApp()
        app.launch()
        let voice = app.buttons["voiceAssistantButton"]
        XCTAssertTrue(voice.waitForExistence(timeout: 10))
        voice.tap()

        // UI tests run without a subscription or a Gemini, OpenAI or xAI key, so the
        // assistant explains both ways to get voice instead of starting a session.
        let explanation = app.descendants(matching: .any)["voiceAssistantAccessExplanation"]
        XCTAssertTrue(explanation.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["voiceAssistant.access.title"].exists)
        XCTAssertTrue(app.buttons["voiceAssistant.access.subscribe"].exists)
        XCTAssertTrue(app.buttons["voiceAssistant.access.addKey"].exists)

        app.buttons["voiceAssistant.access.notNow"].tap()
        XCTAssertTrue(explanation.waitForNonExistence(timeout: 5))
        XCTAssertTrue(voice.isHittable)
        XCTAssertTrue(app.tabBars.buttons["Home"].isSelected)
    }

    @MainActor
    func testVoiceMascotGallerySelectsThePinnedCharacter() {
        let app = makeApp()
        app.launch()
        openSettingsCategory("voiceAssistant", in: app)

        let gallery = "settings.voiceMascot.gallery."
        let brulee = app.buttons[gallery + "brulee"]
        let jam = app.buttons[gallery + "jam"]
        XCTAssertTrue(brulee.waitForExistence(timeout: 5))
        for id in ["brulee", "alien", "star", "croissantCat", "bomboloni", "cherry", "teddy", "jam"] {
            XCTAssertTrue(app.buttons[gallery + id].isHittable, "Missing gallery tile: \(id)")
        }
        XCTAssertTrue(app.buttons[gallery + "orb"].isSelected)
        jam.tap()
        XCTAssertTrue(jam.isSelected)
        XCTAssertFalse(brulee.isSelected)

        let customize = app.buttons["settings.voiceAssistant.mascot"]
        reveal(customize, in: app)
        customize.tap()
        XCTAssertTrue(app.textFields["settings.voiceMascot.name"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["settings.voiceMascot.color"].exists)
        XCTAssertTrue(app.buttons["settings.voiceMascot.personality"].exists)
        relaunchPreservingData(app)
        openSettingsCategory("voiceAssistant", in: app)
        XCTAssertTrue(app.buttons[gallery + "jam"].isSelected)
    }

    @MainActor
    func testVoicePreviewRequiresConfiguredKeyAndOffersSavedVoicePicker() throws {
        let app = makeApp(defaults: ["-voiceAssistant.provider", "gemini"])
        app.launch()
        openSettingsCategory("voiceAssistant", in: app)

        let addKey = app.buttons["settings.voiceAssistant.addKey"]
        reveal(addKey, in: app)
        XCTAssertTrue(addKey.exists)
        let preview = app.buttons["settings.voiceAssistant.preview"]
        reveal(preview, in: app)
        XCTAssertTrue(preview.waitForExistence(timeout: 5))
        XCTAssertEqual(preview.label, "Preview Voice")
        XCTAssertFalse(preview.isEnabled, "Preview must not connect without the selected provider's key")
        XCTAssertFalse(app.staticTexts["settings.voiceAssistant.preview.preparing"].exists)

        let voice = app.buttons["settings.voiceAssistant.voice"]
        reveal(voice, in: app)
        voice.tap()
        let option = app.buttons["Puck"]
        XCTAssertTrue(option.waitForExistence(timeout: 5))
        option.tap()
        reveal(preview, in: app)
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
