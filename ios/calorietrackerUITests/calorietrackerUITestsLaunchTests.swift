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
        for tab in ["Home", "Progress", "Coach", "Settings", "Workouts"] {
            XCTAssertTrue(app.tabBars.buttons[tab].exists, "Missing bottom tab: \(tab)")
        }
        XCTAssertTrue(app.tabBars.buttons["Home"].isSelected)
        XCTAssertTrue(app.buttons["nav.voice"].isHittable)
        XCTAssertFalse(app.buttons["nav.camera"].exists)
        XCTAssertTrue(app.buttons["home.calendar"].exists)
        XCTAssertGreaterThanOrEqual(app.tabBars.buttons["Home"].frame.minY, app.buttons["home.add"].frame.maxY)
        XCTAssertTrue(app.tabBars.firstMatch.exists)
        XCTAssertFalse(app.buttons["Get Started"].exists)

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Home navigation"
        attachment.lifetime = .keepAlways
        add(attachment)

        for tab in ["Progress", "Coach", "Settings", "Workouts", "Home"] {
            let button = app.tabBars.buttons[tab]
            button.tap()
            XCTAssertTrue(button.isSelected, "Did not select \(tab) from the bottom bar")
        }
        XCTAssertTrue(app.buttons["home.add"].exists)
    }
}
