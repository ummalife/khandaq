// Smoke test: real OCTManager, create profile, public group, send message.

import XCTest

class GroupSmokeUITests: XCTestCase {
    private var profileName: String!

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        profileName = "qa_group_\(Int(Date().timeIntervalSince1970))"
    }

    func testCreateGroupAndSendMessage() {
        let app = XCUIApplication()
        app.launch()

        dismissSystemAlerts(in: app)
        createAccountIfNeeded(in: app)

        XCTAssertTrue(app.returnToAir(timeout: 30), "Air screen missing")
        dismissSystemAlerts(in: app)

        let createButton = app.buttons["air.create"]
        XCTAssertTrue(createButton.waitForExistence(timeout: 15), "Create button missing")
        createButton.tap()

        let newGroup = app.buttons["air.newGroup"]
        XCTAssertTrue(newGroup.waitForExistence(timeout: 5), "New group action missing")
        newGroup.tap()

        // One sheet: the name, who can join (open by the group key here), nobody to invite.
        let nameField = app.textViews["air.newGroup.name"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5), "New group sheet missing")
        nameField.tap()
        nameField.typeText("QA NGC Group")

        let open = app.descendants(matching: .any)["air.newGroup.privacy.open"]
        XCTAssertTrue(open.waitForExistence(timeout: 5), "Privacy choice missing")
        open.tap()

        let create = app.buttons["air.newGroup.create"]
        XCTAssertTrue(create.waitForExistence(timeout: 5))
        XCTAssertTrue(create.isEnabled, "Create group waits for nothing once the group is named")
        create.tap()

        let input = app.textViews.element(boundBy: 0)
        XCTAssertTrue(input.waitForExistence(timeout: 30), "Group chat input not visible")

        let message = "ios_ui_smoke_\(Int(Date().timeIntervalSince1970))"
        input.tap()
        input.typeText(message)

        let send = app.buttons[localizedString("chat_send_button")]
        XCTAssertTrue(send.waitForExistence(timeout: 5))
        send.tap()

        XCTAssertTrue(app.staticTexts[message].waitForExistence(timeout: 20), "Sent message not shown")
    }
}

private extension GroupSmokeUITests {
    func dismissSystemAlerts(in app: XCUIApplication) {
        addUIInterruptionMonitor(withDescription: "System alerts") { alert -> Bool in
            for title in ["OK", "Allow", "Allow While Using App", "Don't Allow", "Not Now"] {
                let button = alert.buttons[title]
                if button.exists {
                    button.tap()
                    return true
                }
            }
            return false
        }
        app.tapNeutralPoint()
    }

    func createAccountIfNeeded(in app: XCUIApplication) {
        guard app.textFields["air.first.name"].waitForExistence(timeout: 8) else {
            return
        }
        XCTAssertTrue(app.createFirstProfile(name: profileName, password: "qa_pass_1"),
                      "Main UI did not appear after signup")
    }
}
