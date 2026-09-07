//
//  calorietrackerUITests.swift
//  calorietrackerUITests
//
//  Created by Apoorv Darshan on 05/02/26.
//

import XCTest

final class calorietrackerUITests: XCTestCase {

    private func openSettingsCategory(_ identifier: String, in app: XCUIApplication) {
        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 8))
        settings.tap()

        let category = app.buttons["settings.category.\(identifier)"]
        for _ in 0..<8 where !category.isHittable {
            app.swipeUp()
        }
        XCTAssertTrue(category.waitForExistence(timeout: 3), "Missing Settings category \(identifier)")
        category.tap()
    }

    override func setUpWithError() throws {
        // Put setup code here. This method is called before the invocation of each test method in the class.

        // In UI tests it is usually best to stop immediately when a failure occurs.
        continueAfterFailure = false

        // In UI tests it’s important to set the initial state - such as interface orientation - required for your tests before they run. The setUp method is a good place to do this.
    }

    override func tearDownWithError() throws {
        // Put teardown code here. This method is called after the invocation of each test method in the class.
    }

    @MainActor
    func testExample() throws {
        // UI tests must launch the application that they test.
        let app = XCUIApplication()
        app.launch()

        // Use XCTAssert and related functions to verify your tests produce the correct results.
    }

    @MainActor
    func testBarbellFullSquatV2IllustrationRenders() throws {
        let app = XCUIApplication()
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

        let visual = app.buttons["workouts.exercise.visual.Barbell_Full_Squat"]
        XCTAssertTrue(visual.waitForExistence(timeout: 5))

        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Barbell Full Squat v2 Illustration"
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }

    @MainActor
    func testSpeechProviderPickerOpensInFocusedCategory() throws {
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-selectedSpeechProvider", "Native iOS (On-Device)",
        ]
        app.launch()

        openSettingsCategory("speechToText", in: app)

        let speechSection = app.staticTexts["Speech-to-Text"]
        for _ in 0..<12 where !speechSection.exists {
            app.swipeUp()
        }
        XCTAssertTrue(speechSection.waitForExistence(timeout: 3))

        let providerPicker = app.buttons["settings.speech.provider"]
        for _ in 0..<3 where !providerPicker.isHittable {
            app.swipeUp()
        }
        XCTAssertTrue(providerPicker.waitForExistence(timeout: 3))
        providerPicker.tap()
        XCTAssertTrue(app.staticTexts["Native iOS (On-Device)"].waitForExistence(timeout: 3))
    }

    @MainActor
    fun testSettingsHubShowsFiveFocusedAppInfoCategories() throws {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(en)"]
        app.launch()

        let categories = [
            ("appUpdates", "App & Updates", "Open Source (MIT)"),
            ("support", "Support Fud AI", "Rate the App"),
            ("helpFeedback", "Help & Feedback", "Report an Issue"),
            ("community", "Community", "Follow on X"),
            ("legal", "Legal", "Privacy Policy"),
        ]

        let settings = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 8))
        settings.tap()

        let creatorFooter = app.staticTexts["Made by Apoorv Darshan"]
        for _ in 0..<12 where !creatorFooter.isHittable {
            app.swipeUp()
        }
        XCTAssertTrue(creatorFooter.waitForExistence(timeout: 3), "Missing creator footer on Settings")
        XCTAssertTrue(creatorFooter.isHittable, "Creator footer is not visible on Settings")

        for (identifier, title, expectedAction) in categories {
            let category = app.buttons["settings.category.\(identifier)"]
            for _ in 0..<12 where !category.isHittable {
                app.swipeUp()
            }
            XCTAssertTrue(category.waitForExistence(timeout: 3), "Missing Settings category \(identifier)")
            XCTAssertTrue(category.isHittable, "Settings category \(identifier) is not tappable")
            category.tap()

            let navigationBar = app.navigationBars[title]
            XCTAssertTrue(navigationBar.waitForExistence(timeout: 3), "Missing \(title) detail page")

            let action = app.staticTexts[expectedAction]
            for _ in 0..<6 where !action.exists {
                app.swipeUp()
            }
            XCTAssertTrue(action.waitForExistence(timeout: 3), "Missing \(expectedAction) in \(title)")

            if identifier == "community" {
                XCTAssertFalse(app.staticTexts["Follow on Instagram"].exists)
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
    }

    @MainActor
    func testSeparateTextProviderSettingsAreVisibleAndIndependent() throws {
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-separateTextProviderEnabled", "YES",
            "-selectedTextAIProvider", "DeepSeek",
            "-selectedTextAIModel", "deepseek-v4-flash",
        ]
        app.launch()

        openSettingsCategory("aiProviders", in: app)

        let textProviderSection = app.staticTexts["Text AI"]
        for _ in 0..<10 where !textProviderSection.exists {
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
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-separateTextProviderEnabled", "YES",
            "-selectedTextAIProvider", "Apple Intelligence (On-Device)",
            "-selectedTextAIModel", "System Language Model",
        ]
        app.launch()

        openSettingsCategory("aiProviders", in: app)

        let textProviderSection = app.staticTexts["Text AI"]
        for _ in 0..<10 where !textProviderSection.exists {
            app.swipeUp()
        }
        XCTAssertTrue(textProviderSection.waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Apple Intelligence (On-Device)"].waitForExistence(timeout: 3))
        let systemModel = app.staticTexts["System Language Model"]
        for _ in 0..<4 where !systemModel.exists {
            app.swipeUp()
        }
        XCTAssertTrue(systemModel.waitForExistence(timeout: 3))
    }

    @MainActor
    func testWaterTrackingUsesFixedFourthHomePillar() throws {
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-waterTrackingEnabled", "YES",
        ]
        app.launch()

        XCTAssertTrue(app.staticTexts["Water"].waitForExistence(timeout: 8))
        XCTAssertTrue(app.staticTexts["View More"].waitForExistence(timeout: 3))
        app.staticTexts["View More"].tap()

        XCTAssertTrue(app.navigationBars["Nutrition Details"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Protein, Carbs, Fat, Water"].waitForExistence(timeout: 3))
        XCTAssertTrue(
            app.staticTexts["Water stays fixed as the fourth Home pillar while Water Tracking is enabled."]
                .waitForExistence(timeout: 3)
        )

        app.staticTexts["Home Nutrient Cards"].tap()
        XCTAssertTrue(app.navigationBars["Home Nutrients"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Choose 3 Nutrients"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.staticTexts["Water"].waitForExistence(timeout: 3))
    }

    @MainActor
    func testWaterLogAppearsInDiaryAndCanBeDeleted() throws {
        let app = XCUIApplication()
        app.launchArguments += [
            "-AppleLanguages", "(en)",
            "-waterTrackingEnabled", "YES",
        ]
        app.launch()

        let addButton = app.buttons["home.add"]
        XCTAssertTrue(addButton.waitForExistence(timeout: 8))
        addButton.tap()
        XCTAssertTrue(app.buttons["Water"].waitForExistence(timeout: 3))
        app.buttons["Water"].tap()
        XCTAssertTrue(app.buttons["1 Glass (~250 ml)"].waitForExistence(timeout: 3))
        app.buttons["1 Glass (~250 ml)"].tap()

        let waterRows = app.otherElements.matching(identifier: "water.log.row")
        for _ in 0..<8 where waterRows.count == 0 {
            app.swipeUp()
        }
        XCTAssertGreaterThan(waterRows.count, 0)
        let countBeforeDelete = waterRows.count
        waterRows.firstMatch.swipeLeft()
        let deleteButton = app.buttons["Delete"]
        if deleteButton.waitForExistence(timeout: 2) {
            deleteButton.tap()
        }
        XCTAssertEqual(waterRows.count, countBeforeDelete - 1)
    }

    @MainActor
    func testLaunchPerformance() throws {
        // This measures how long it takes to launch your application.
        measure(metrics: [XCTApplicationLaunchMetric()]) {
            XCUIApplication().launch()
        }
    }
}
