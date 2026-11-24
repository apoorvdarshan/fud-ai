import XCTest

final class calorietrackerUITestsLaunchTests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testLaunchShowsMainNavigationAndHome() throws {
        let app = XCUIApplication()
        app.launchArguments = [
            "--ui-testing", "--ui-testing-reset", "--ui-testing-onboarded",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-WorkoutVectorsBaseURL", "none",
        ]
        app.launch()

        XCTAssertTrue(app.buttons["home.add"].waitForExistence(timeout: 15))
        for tab in ["home", "progress", "coach", "settings", "workouts"] {
            XCTAssertTrue(app.buttons["nav.\(tab)"].exists, "Missing rail item: \(tab)")
        }
        XCTAssertTrue(app.buttons["nav.home"].isSelected)
        XCTAssertTrue(app.buttons["nav.camera"].exists)
        XCTAssertTrue(app.buttons["home.calendar"].exists)
        XCTAssertGreaterThan(app.buttons["nav.home"].frame.minX, app.buttons["home.add"].frame.maxX)
        XCTAssertFalse(app.tabBars.firstMatch.exists)
        XCTAssertFalse(app.buttons["Get Started"].exists)

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Home navigation"
        attachment.lifetime = .keepAlways
        add(attachment)

        for tab in ["progress", "coach", "settings", "workouts", "home"] {
            let button = app.buttons["nav.\(tab)"]
            button.tap()
            XCTAssertTrue(button.isSelected, "Did not select \(tab) from the right rail")
        }
        XCTAssertTrue(app.buttons["home.add"].exists)
    }
}
