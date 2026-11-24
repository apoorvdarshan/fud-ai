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
        let settings = app.buttons["nav.settings"]
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
    func testHomeViewMoreChangesDaysOnSwipesAndOpensOnlyOnTap() {
        let app = makeApp()
        app.launch()

        let date = app.staticTexts["home.dateHeading"]
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
        toggle.tap()
        waitForValue("0", in: toggle)
        relaunchPreservingData(app)
        openSettingsCategory("appPreferences", in: app)
        reveal(toggle, in: app)
        waitForValue("0", in: toggle)
    }

    @MainActor
    func testProgressAndWeeklyChallengeHaveSeparateRailDestinations() {
        let app = makeApp()
        app.launch()
        let progress = app.buttons["nav.progress"]
        reveal(progress, in: app)
        progress.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label == %@", "Time Range"))
            .firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Weekly Recap"].exists)

        let weekly = app.buttons["nav.weeklyChallenge"]
        reveal(weekly, in: app)
        weekly.tap()
        XCTAssertTrue(app.staticTexts["Weekly Challenge"].waitForExistence(timeout: 5))
        XCTAssertTrue(weekly.isSelected)
        XCTAssertFalse(progress.isSelected)
        progress.tap()
        XCTAssertTrue(progress.isSelected)
        XCTAssertTrue(app.buttons["nav.voice"].isHittable)
    }

    @MainActor
    func testWorkoutSearchOpensExerciseDetails() throws {
        let app = makeApp()
        app.launch()

        let workouts = app.buttons["nav.workouts"]
        reveal(workouts, in: app)
        workouts.tap()
        XCTAssertTrue(app.buttons["Add workout"].waitForExistence(timeout: 5))
        let library = app.buttons["nav.exerciseLibrary"]
        reveal(library, in: app)
        library.tap()

        let search = app.textFields["workouts.search"]
        XCTAssertTrue(search.waitForExistence(timeout: 5))
        XCTAssertTrue(library.isSelected)
        XCTAssertFalse(workouts.isSelected)
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

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Barbell Full Squat Details"
        screenshot.lifetime = .keepAlways
        add(screenshot)

        app.buttons["nav.home"].tap()
        XCTAssertTrue(app.buttons["home.add"].waitForExistence(timeout: 5))
        reveal(library, in: app)
        library.tap()
        XCTAssertTrue(visual.waitForExistence(timeout: 5), "Switching tabs should preserve the open exercise detail")
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
        let native = app.staticTexts["Native iOS (On-Device)"]
        XCTAssertTrue(native.waitForExistence(timeout: 3))
        native.tap()
        let source = app.descendants(matching: .any)["settings.localModel.whisper.source"].firstMatch
        reveal(source, in: app)
        XCTAssertGreaterThanOrEqual(source.frame.height, 44)
    }

    @MainActor
    func testAboutAndSupportAreSeparateFromSettings() throws {
        let app = makeApp()
        app.launchArguments += ["-AppleLanguages", "(en)"]
        app.launch()

        let categories = [
            ("appUpdates", "App & Updates", "Open Source (MIT)"),
            ("helpFeedback", "Help & Feedback", "Report an Issue on GitHub"),
            ("community", "Community", "Join Discord"),
            ("legal", "Legal", "Privacy Policy"),
        ]

        let settings = app.buttons["nav.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 8))
        settings.tap()
        XCTAssertFalse(app.buttons["settings.category.appUpdates"].exists)
        XCTAssertFalse(app.buttons["settings.category.support"].exists)

        let rail = app.descendants(matching: .any)["nav.rail"].firstMatch
        XCTAssertTrue(rail.exists)
        let about = app.buttons["nav.about"]
        for _ in 0..<3 where !about.isHittable {
            let from = rail.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.72))
            let to = rail.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.26))
            from.press(forDuration: 0.05, thenDragTo: to)
        }
        XCTAssertTrue(about.isHittable)
        about.tap()

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
                    "Creator footer should live on the About hub"
                )
            }

            let backButton = navigationBar.buttons.firstMatch
            XCTAssertTrue(backButton.exists)
            backButton.tap()
            XCTAssertTrue(app.buttons["settings.category.\(identifier)"].waitForExistence(timeout: 3))
        }
        reveal(app.staticTexts["Made by Apoorv Darshan"], in: app)
        let support = app.buttons["nav.support"]
        for _ in 0..<3 where !support.isHittable {
            let from = rail.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.72))
            let to = rail.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.26))
            from.press(forDuration: 0.05, thenDragTo: to)
        }
        XCTAssertTrue(support.isHittable)
        support.tap()
        XCTAssertTrue(app.navigationBars["Support Fud AI"].waitForExistence(timeout: 5))
        reveal(app.staticTexts["Rate the App"], in: app)
        XCTAssertTrue(app.buttons["nav.voice"].isHittable)
    }

    @MainActor
    func testCoachKeyboardKeepsNavigationRailOnTheRight() {
        let app = makeApp()
        app.launch()
        let home = app.buttons["nav.home"]
        let coach = app.buttons["nav.coach"]
        XCTAssertTrue(coach.waitForExistence(timeout: 15))
        coach.tap()
        let railX = home.frame.minX
        let voice = app.buttons["nav.voice"]
        let voiceX = voice.frame.minX
        let input = app.descendants(matching: .any)["coach.input"].firstMatch
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        input.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(home.isHittable)
        XCTAssertEqual(home.frame.minX, railX, accuracy: 1)
        XCTAssertTrue(voice.isHittable)
        XCTAssertEqual(voice.frame.minX, voiceX, accuracy: 1)
        XCTAssertLessThanOrEqual(voice.frame.maxY, app.keyboards.firstMatch.frame.minY)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Coach keyboard with the right rail"
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
        XCTAssertGreaterThanOrEqual(source.frame.height, 44)
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
        waitForValue("deepseek-v4-flash", in: textModel)
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
        XCTAssertLessThan(reuse.frame.midY, describe.frame.midY)
        XCTAssertLessThan(describe.frame.midY, photos.frame.midY)

        describe.tap()
        let manual = app.buttons["Manual Entry"]
        let voice = app.buttons["Voice"]
        let text = app.buttons["Text Input"]
        XCTAssertTrue(manual.waitForExistence(timeout: 5))
        XCTAssertTrue(voice.isHittable)
        XCTAssertTrue(text.isHittable)
        XCTAssertLessThan(manual.frame.midY, voice.frame.midY)
        XCTAssertLessThan(voice.frame.midY, text.frame.midY)
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
        XCTAssertTrue(app.buttons["Barcode"].waitForExistence(timeout: 5))
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.22, dy: 0.15)).tap()
        XCTAssertTrue(app.buttons["Barcode"].waitForNonExistence(timeout: 5))
        XCTAssertTrue(menu.waitForExistence(timeout: 5))
        menu.tap()
        XCTAssertTrue(reuse.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["Barcode"].exists)
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

        XCTAssertFalse(app.buttons["voiceAssistantButton"].exists, "Home should not duplicate the rail shortcut")
        let voice = app.buttons["nav.voice"]
        XCTAssertTrue(voice.isHittable)
        XCTAssertLessThan(addButton.frame.maxX, voice.frame.minX)

        // The last diary row can scroll above the pinned dock. Its meal
        // summary stays aligned beneath the title.
        app.swipeUp()
        let mealCell = app.cells.containing(.staticText, identifier: "CI oat bowl").firstMatch
        XCTAssertTrue(mealCell.exists)
        XCTAssertTrue(meal.isHittable)
        XCTAssertLessThan(mealCell.frame.maxY, addButton.frame.minY)
        let mealTitle = app.descendants(matching: .any).matching(identifier: "home.meal.title").firstMatch
        let mealCalories = app.staticTexts.matching(identifier: "home.meal.calories").firstMatch
        let mealMacros = app.staticTexts.matching(identifier: "home.meal.macros").firstMatch
        XCTAssertTrue(mealTitle.exists)
        XCTAssertTrue(mealCalories.exists)
        XCTAssertTrue(mealMacros.exists)
        XCTAssertGreaterThanOrEqual(mealCalories.frame.minY, mealTitle.frame.maxY)
        XCTAssertLessThanOrEqual(mealCalories.frame.minX, mealTitle.frame.minX)
        XCTAssertEqual(mealCalories.frame.minX, mealMacros.frame.minX, accuracy: 1)
        XCTAssertLessThan(mealCalories.frame.maxY, addButton.frame.minY)
        XCTAssertLessThan(mealMacros.frame.maxY, addButton.frame.minY)

        relaunchPreservingData(app)
        reveal(meal, in: app)
        XCTAssertTrue(app.staticTexts.matching(identifier: "321 kcal").firstMatch.exists)
    }

    @MainActor
    func testPinnedVoiceExplainsAccessWithoutLeavingTheCurrentTab() {
        let app = makeApp()
        app.launch()
        let coach = app.buttons["nav.coach"]
        reveal(coach, in: app)
        coach.tap()

        let voice = app.buttons["nav.voice"]
        XCTAssertTrue(voice.isHittable)
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
        XCTAssertTrue(coach.isSelected)
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

        let settings = app.buttons["nav.settings"]
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
