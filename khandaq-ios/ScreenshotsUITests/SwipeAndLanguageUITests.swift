// Covers the two things reported from an iPhone 11 Pro on 17 Aug:
//   * swiping left/right between the Chats / Groups / Favorites filter tabs, and
//   * the in-app language reaching the WHOLE interface, not just the chat titles.
//
// Both assertions use hardcoded translations on purpose: reading the expected text from the same
// bundle the app reads would make the test agree with any wiring, correct or not.

import XCTest

final class SwipeAndLanguageUITests: XCTestCase {
    private var app: XCUIApplication!

    private enum Expected {
        static let englishTabs = ["Contacts", "Chats", "Settings"]
        static let arabicTabs = ["القائمة", "المحادثات", "الإعدادات"]
        static let englishFilters = ["Chats", "Groups", "Favorites"]
        static let arabicFilters = ["المحادثات", "المجموعات", "المفضلة"]
        static let englishEdit = "Edit"
        static let arabicEdit = "تحرير"
        static let englishLanguageRow = "Language"
        static let arabicLanguageRow = "اللغة"
    }

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments.append("UI_TESTING")
        // Start from a known system language so the onboarding buttons are predictable; the in-app
        // language (what this test is about) is switched from Settings later on.
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        dismissSystemAlerts()
        signUpIfNeeded()
    }

    // MARK: - Swipe

    func testSwipeMovesBetweenFilterTabsAndStopsAtBothEdges() {
        openChats()
        selectFilterTab(.direct)

        let list = chatList
        XCTAssertTrue(list.waitForExistence(timeout: 10), "chat list not visible")

        assertSelectedTab("direct", after: "opening Chats")

        list.swipeLeft()
        assertSelectedTab("groups", after: "one swipe left")

        list.swipeLeft()
        assertSelectedTab("favorites", after: "two swipes left")

        list.swipeLeft()
        assertSelectedTab("favorites", after: "a third swipe left (right edge)")

        list.swipeRight()
        assertSelectedTab("groups", after: "one swipe right")

        list.swipeRight()
        assertSelectedTab("direct", after: "two swipes right")

        list.swipeRight()
        assertSelectedTab("direct", after: "a third swipe right (left edge)")
    }

    // MARK: - Language

    func testInAppLanguageReachesTabBarFiltersAndEditButton() {
        switchLanguage(toNativeName: "English", rowTitles: [Expected.englishLanguageRow, Expected.arabicLanguageRow])
        assertInterface(tabs: Expected.englishTabs, filters: Expected.englishFilters,
                        edit: Expected.englishEdit, language: "English")

        switchLanguage(toNativeName: "العربية", rowTitles: [Expected.englishLanguageRow, Expected.arabicLanguageRow])
        assertInterface(tabs: Expected.arabicTabs, filters: Expected.arabicFilters,
                        edit: Expected.arabicEdit, language: "Arabic")
    }
}

// MARK: - Helpers

private extension SwipeAndLanguageUITests {
    var chatList: XCUIElement {
        app.tables.element(boundBy: 0)
    }

    func filterButton(_ name: String) -> XCUIElement {
        app.buttons["chatFilterTab.\(name)"]
    }

    func assertSelectedTab(_ expected: String, after action: String) {
        for name in ["direct", "groups", "favorites"] {
            let button = filterButton(name)
            XCTAssertTrue(button.waitForExistence(timeout: 5), "filter tab \(name) missing")
            let selected = button.isSelected
            if name == expected {
                XCTAssertTrue(selected, "after \(action): expected \(name) to be the active tab")
            }
            else {
                XCTAssertFalse(selected, "after \(action): \(name) should not be active")
            }
        }
    }

    func openChats() {
        let chats = app.tabBars.buttons.element(boundBy: 1)
        XCTAssertTrue(chats.waitForExistence(timeout: 30),
                      "tab bar not visible. Screen was:\n\(app.debugDescription)")
        chats.tap()
    }

    func selectFilterTab(_ tab: ChatFilter) {
        let button = filterButton(tab.rawValue)
        XCTAssertTrue(button.waitForExistence(timeout: 10), "filter bar not visible")
        button.tap()
    }

    enum ChatFilter: String {
        case direct, groups, favorites
    }

    func assertInterface(tabs: [String], filters: [String], edit: String, language: String) {
        openChats()

        for title in tabs {
            XCTAssertTrue(app.tabBars.buttons[title].waitForExistence(timeout: 15),
                          "\(language): bottom tab \"\(title)\" is not in the tab bar — the language did not reach it")
        }

        for (name, title) in zip(["direct", "groups", "favorites"], filters) {
            let button = filterButton(name)
            XCTAssertTrue(button.waitForExistence(timeout: 10), "filter tab \(name) missing")
            XCTAssertEqual(button.label, title, "\(language): filter tab \(name) reads \"\(button.label)\"")
        }

        XCTAssertTrue(app.navigationBars.buttons[edit].waitForExistence(timeout: 10),
                      "\(language): the Edit button does not read \"\(edit)\"")
    }

    func switchLanguage(toNativeName name: String, rowTitles: [String]) {
        let settings = app.tabBars.buttons.element(boundBy: 2)
        XCTAssertTrue(settings.waitForExistence(timeout: 30), "tab bar not visible")
        settings.tap()

        var row: XCUIElement?
        for title in rowTitles where app.staticTexts[title].waitForExistence(timeout: 5) {
            row = app.staticTexts[title]
            break
        }
        guard let languageRow = row else {
            XCTFail("the Language row was not found in Settings under any known translation")
            return
        }
        languageRow.tap()

        let option = app.buttons[name]
        XCTAssertTrue(option.waitForExistence(timeout: 5), "language \(name) is missing from the sheet")
        option.tap()

        // Switching the language rebuilds the session, so wait for the interface to come back.
        XCTAssertTrue(app.tabBars.buttons.element(boundBy: 1).waitForExistence(timeout: 60),
                      "the interface did not come back after switching to \(name)")
    }

    func dismissSystemAlerts() {
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

    /// Walks onboarding to the main interface from wherever it happens to be: intro pages, the
    /// account choice, the name form, or the password form (a previous run may have left it there).
    func signUpIfNeeded() {
        let tabBar = app.tabBars.buttons.element(boundBy: 1)
        if tabBar.waitForExistence(timeout: 6) {
            return
        }

        // A previous run can leave onboarding half way through (UIKit restores the navigation
        // stack), and the password screen has no name behind it. Walk back to the start first.
        for _ in 0..<6 {
            let back = app.navigationBars.buttons["BackButton"]
            guard back.exists, back.isHittable else {
                break
            }
            back.tap()
        }

        let createTitles = ["Create account", "إنشاء حساب", "Создать аккаунт"]
        let advanceTitles = ["Go", "Next", "Continue", "Get started", "التالي", "انتقال", "Далее"]
        // Digits only: the simulator's keyboard may be Arabic, and XCUITest silently skips
        // latin keys it cannot find — that produced a 3-character password and an alert.
        let profileName = "9001_\(Int(Date().timeIntervalSince1970))"

        for _ in 0..<14 {
            if tabBar.exists {
                return
            }

            // The app validates as you go ("Password must be at least 8 characters."); clear the
            // alert first or every later tap lands on it.
            if app.alerts.count > 0 {
                let alert = app.alerts.element(boundBy: 0)
                let button = alert.buttons.element(boundBy: alert.buttons.count - 1)
                if button.exists {
                    button.tap()
                }
                continue
            }

            // Password form: fill both fields, then commit.
            let secure = app.secureTextFields
            if secure.count > 0, secure.element(boundBy: 0).exists {
                fill(secure.element(boundBy: 0), with: "12345678")
                if secure.count > 1 {
                    fill(secure.element(boundBy: 1), with: "12345678")
                }
                tapFirstButton(titled: advanceTitles)
                _ = tabBar.waitForExistence(timeout: 45)
                continue
            }

            // Name form.
            let fields = app.textFields
            if fields.count > 0, fields.element(boundBy: 0).exists {
                fill(fields.element(boundBy: 0), with: "9001")
                if fields.count > 1 {
                    fill(fields.element(boundBy: 1), with: profileName)
                }
                tapFirstButton(titled: advanceTitles)
                continue
            }

            if tapFirstButton(titled: createTitles) {
                continue
            }
            if tapFirstButton(titled: advanceTitles) {
                continue
            }

            // Unknown page: press the bottom-most tappable button, which is the primary action.
            let candidates = (0..<app.buttons.count).map { app.buttons.element(boundBy: $0) }
                    .filter { $0.exists && $0.isHittable }
            guard let bottom = candidates.max(by: { $0.frame.midY < $1.frame.midY }) else {
                break
            }
            bottom.tap()
        }

        XCTAssertTrue(tabBar.waitForExistence(timeout: 60),
                      "could not get past onboarding. Screen was:\n\(app.debugDescription)")
    }

    /// Secure fields silently drop characters when a whole string is typed at once, which produced
    /// a 3-character password and an "at least 8 characters" alert. Clear, then type per character.
    func fill(_ field: XCUIElement, with text: String) {
        guard field.exists else {
            return
        }
        field.tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 30))
        for character in text {
            field.typeText(String(character))
        }
    }

    /// Taps a button of the app, never the keyboard's own key of the same name — the keyboard's
    /// Return key is also called "Go", and tapping that leaves the form untouched.
    @discardableResult
    func tapFirstButton(titled titles: [String]) -> Bool {
        for title in titles {
            let matches = app.buttons.matching(NSPredicate(format: "label == %@", title))
            for index in 0..<matches.count {
                let button = matches.element(boundBy: index)
                guard button.exists, button.isHittable else {
                    continue
                }
                if button.frame.width >= 100 {   // a form button, not a key
                    button.tap()
                    return true
                }
            }
        }
        return false
    }

}
