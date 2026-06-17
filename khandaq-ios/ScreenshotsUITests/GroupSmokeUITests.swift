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

        app.tabBars.buttons.element(boundBy: 1).tap()
        dismissSystemAlerts(in: app)

        let addButton = app.navigationBars[localizedString("chats_title")].buttons[localizedString("group_add_button")]
        XCTAssertTrue(addButton.waitForExistence(timeout: 15), "Add group button missing")
        addButton.tap()

        app.buttons[localizedString("group_create_public")].tap()

        let createNav = app.navigationBars[localizedString("group_create_public")]
        XCTAssertTrue(createNav.waitForExistence(timeout: 5), "Group create screen missing")

        let nameField = app.textFields[localizedString("group_name_placeholder")]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        nameField.tap()
        nameField.typeText("QA NGC Group")

        createNav.buttons[localizedString("alert_create")].tap()

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
        app.tap()
    }

    func createAccountIfNeeded(in app: XCUIApplication) {
        let create = app.buttons[localizedString("create_account")]
        guard create.waitForExistence(timeout: 8) else {
            return
        }

        create.tap()

        let usernameField = app.textFields[localizedString("create_account_username_placeholder")]
        XCTAssertTrue(usernameField.waitForExistence(timeout: 5))
        usernameField.tap()
        usernameField.typeText("qa_user")

        let profileField = app.textFields[localizedString("create_account_profile_placeholder")]
        profileField.tap()
        profileField.typeText(profileName)

        app.keyboards.buttons["Next"].tap()

        let passwordField = app.secureTextFields[localizedString("password")]
        XCTAssertTrue(passwordField.waitForExistence(timeout: 5))
        passwordField.tap()
        passwordField.typeText("qa_pass_1")

        let repeatField = app.secureTextFields[localizedString("repeat_password")]
        repeatField.tap()
        repeatField.typeText("qa_pass_1")

        app.keyboards.buttons["Go"].tap()

        let chatsTab = app.tabBars.buttons.element(boundBy: 1)
        XCTAssertTrue(chatsTab.waitForExistence(timeout: 60), "Main UI did not appear after signup")
    }
}
