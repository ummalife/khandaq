// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import XCTest

private struct Constants {
    static let SnapshotConversation = "01_Conversation"
    static let SnapshotContactList = "02_ContactList"
    static let SnapshotPin = "03_Pin"
}

class ScreenshotsUITests: XCTestCase {
    override func setUp() {
        super.setUp()
        
        let app = XCUIApplication()
        app.launchArguments.append("UI_TESTING")
        setupSnapshot(app)
        app.launch()
    }
    
    override func tearDown() {
        super.tearDown()
    }
    
    func testExample() {
        // I have no idea why it doesn't work without delay here.
        sleep(1)

        createAccount()

        // Asked of the device under test, not of the app: a UI test has no business compiling the
        // app's own sources (that is what made this target rot in the first place).
        if UIDevice.current.userInterfaceIdiom == .pad {
            iPadPart()
        }
        else {
            iPhonePart()
        }
    }

    func createAccount() {
        let app = XCUIApplication()

        XCUIDevice.shared.orientation = .portrait

        app.createFirstProfile(name: "user", password: "password1")

        addUIInterruptionMonitor(withDescription: "Notifications alert") { (alert) -> Bool in
            let button = alert.buttons["OK"]
            if button.exists {
                button.tap()
                return true
            }
            return false
        }

    }

    func iPhonePart() {
        let app = XCUIApplication()

        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 30), "the Air screen did not open")
        // need to interact with the app for the handler to fire
        app.tapNeutralPoint()

        snapshot(Constants.SnapshotContactList)

        // A row is one accessibility element whose label carries the last message, so its texts are
        // not reachable one by one.
        app.cells.matching(NSPredicate(format: "label CONTAINS %@",
                                       localizedString("app_store_screenshot_conversation_7")))
                .element(boundBy: 0).tap()

        snapshot(Constants.SnapshotConversation)

        app.navigationBars.buttons["BackButton"].tap()

        app.buttons["air.you"].tap()

        pinPart()
    }

    func iPadPart() {
        let app = XCUIApplication()

        app.tables.staticTexts[localizedString("app_store_screenshot_conversation_7")].tap()

        app.children(matching: .window).element(boundBy: 0).children(matching: .other).element.children(matching: .other).element(boundBy: 2).children(matching: .other).element.children(matching: .other).element.children(matching: .other).element.children(matching: .other).element(boundBy: 0).children(matching: .textView).element.tap()
        
        XCUIDevice.shared.orientation = .landscapeRight

        snapshot(Constants.SnapshotConversation)

        app.navigationBars["Antidote.PrimaryIpad"].children(matching: .other).element.children(matching: .button).element.tap()
        
        pinPart()
    }

    func pinPart() {
        let app = XCUIApplication()

        app.tables.staticTexts[localizedString("profile_details")].tap()
        app.tables.switches[localizedString("pin_enabled")].tap()
        
        let button = app.buttons["1"]
        button.tap()
        button.tap()
        button.tap()

        let delayTime = DispatchTime.now() + Double(Int64(1.0 * Double(NSEC_PER_SEC))) / Double(NSEC_PER_SEC)
        DispatchQueue.main.asyncAfter(deadline: delayTime) {
            snapshot("03_Pin")
        }

        app.buttons["7"].press(forDuration: 5.0)
    }
}

/// The phone has no tab bar: the Air screen is the root, and settings and the profile open from its
/// header. These helpers are shared by every UI test in this target.
extension XCUIApplication {
    /// The command line at the top of the Air screen. Only that screen has it.
    var airCommand: XCUIElement {
        return textFields["air.command"]
    }

    /// A filter pill on the Air screen: "requests", "transit" or "favorites". With none selected the
    /// screen shows everything.
    func airPill(_ name: String) -> XCUIElement {
        return buttons["air.filter.\(name)"]
    }

    /// The first row of a kind on the Air screen: "direct", "group", "saved" or "contact".
    func airRow(_ kind: String) -> XCUIElement {
        return cells.matching(identifier: "air.row.\(kind)").element(boundBy: 0)
    }

    /// True when the Air screen is on top and takes touches: nothing pushed over it (its own
    /// navigation bar is hidden), nothing presented, no alert.
    var isOnAir: Bool {
        guard airCommand.exists, airCommand.isHittable else {
            return false
        }
        return navigationBars.count == 0 && alerts.count == 0 && sheets.count == 0
            && !buttons["air.newContact"].exists
    }

    /// A tap that interacts with the app without opening anything (the status bar), so interruption
    /// monitors get their turn. The middle of the screen is a chat row on the Air screen.
    func tapNeutralPoint() {
        coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.01)).tap()
    }

    /// The system's offer to save a password that was typed on a page now gone ("Save Password?"),
    /// in the language of the simulator: "Not Now". Returns whether it was there.
    @discardableResult
    func dismissSavePasswordOffer(timeout: TimeInterval = 0) -> Bool {
        let notNow = buttons.matching(NSPredicate(format: "label IN {'Not Now', 'Не сейчас'}")).firstMatch
        guard notNow.waitForExistence(timeout: timeout) else {
            return false
        }
        notNow.tap()
        return true
    }

    /// First launch (redesign stage 4, E19) by the identifiers of its pages: the name, a password typed
    /// twice, then "Add the first person", which opens the Air screen. Returns false when first launch
    /// is not there (a profile is on the device already) or one of its pages did not come.
    @discardableResult
    func createFirstProfile(name: String, password: String) -> Bool {
        let nameField = textFields["air.first.name"]
        guard nameField.waitForExistence(timeout: 8) else {
            return false
        }
        let main = buttons["air.step.main"]
        nameField.tap()
        nameField.typeText(name)
        main.tap()

        let passwordField = secureTextFields["air.first.password"]
        guard passwordField.waitForExistence(timeout: 10) else {
            return false
        }
        // A secure field drops characters of a string typed at once.
        for field in [passwordField, secureTextFields["air.first.password.repeat"]] {
            field.tap()
            for character in password {
                field.typeText(String(character))
            }
        }
        main.tap()

        guard buttons["air.first.share"].waitForExistence(timeout: 60) else {
            return false
        }
        dismissSavePasswordOffer(timeout: 2)
        main.tap()
        guard airCommand.waitForExistence(timeout: 30) else {
            return false
        }
        leaveCommandLine()
        return true
    }

    /// "Add the first person" ends first launch with the command line in focus, which hides the "you"
    /// block and the header buttons; a walk goes on from the Air screen at rest.
    func leaveCommandLine() {
        let cancel = buttons["air.command.cancel"]
        if cancel.waitForExistence(timeout: 2), cancel.isHittable {
            cancel.tap()
        }
    }

    /// Leaves whatever is on top (alerts, action sheets, modal sheets, pushed screens) and comes back
    /// to the Air screen. Returns whether it got there.
    @discardableResult
    func returnToAir(timeout: TimeInterval = 15) -> Bool {
        let cancelTitles = "{'Cancel', 'Отмена', 'إلغاء', 'إلغاء الامر', '取消'}"
        let softTitles = "{'Cancel', 'Отмена', 'إلغاء', 'إلغاء الامر', '取消', 'OK', 'Not Now', 'Не сейчас', 'Позже'}"
        let notNowTitles = "{'Not Now', 'Не сейчас', 'ليس الآن', '以后', '现在不'}"
        let closeTitles = "{'Cancel', 'Close', 'Done', 'Отмена', 'Закрыть', 'Готово', 'إلغاء', 'إلغاء الامر', "
            + "'إغلاق', 'تم', '取消', '关闭', '完成'}"
        let deadline = Date().addingTimeInterval(timeout)
        while !isOnAir, Date() < deadline {
            if alerts.count > 0 {
                let alert = alerts.element(boundBy: 0)
                let soft = alert.buttons.matching(NSPredicate(format: "label IN \(softTitles)")).element(boundBy: 0)
                if soft.exists {
                    soft.tap()
                }
                else if alert.buttons.count > 0 {
                    alert.buttons.element(boundBy: alert.buttons.count - 1).tap()
                }
                continue
            }
            if sheets.count > 0 {
                let sheet = sheets.element(boundBy: 0)
                let cancel = sheet.buttons.matching(NSPredicate(format: "label IN \(cancelTitles)")).element(boundBy: 0)
                // The system's "Save Password?" after signing in again has no Cancel, only "Not Now"
                // (stage 5, on the SE): its upper part is the sheet, so a tap there does not close it.
                let notNow = sheet.buttons.matching(NSPredicate(format: "label IN \(notNowTitles)")).element(boundBy: 0)
                if cancel.exists {
                    cancel.tap()
                }
                else if notNow.exists {
                    notNow.tap()
                }
                else {
                    coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12)).tap()
                }
                continue
            }

            let back = navigationBars.buttons["BackButton"]
            let closer = navigationBars.buttons.matching(NSPredicate(format: "label IN \(closeTitles)"))
                    .element(boundBy: 0)
            if back.exists, back.isHittable {
                back.tap()
            }
            else if closer.exists, closer.isHittable {
                closer.tap()
            }
            else if buttons["air.newContact"].exists {
                // The "Create" sheet is short; the dimmed area above it closes it.
                coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12)).tap()
            }
            else {
                swipeDown()
            }
            // Let the pop or the dismissal finish before looking again.
            _ = airCommand.waitForExistence(timeout: 1)
        }
        return isOnAir
    }
}

func localizedString(_ key: String) -> String {
    class LocalizableDummy {}

    let bundle = Bundle(for: type(of: LocalizableDummy()))

    var language = deviceLanguage

    switch language {
        case "en-US":
            language = "en"
        default:
            break
    }

    let path = bundle.path(forResource: language, ofType: "lproj")!

    let localizationBundle = Bundle(path: path)!
    return NSLocalizedString(key, bundle:localizationBundle, comment: "")
}

