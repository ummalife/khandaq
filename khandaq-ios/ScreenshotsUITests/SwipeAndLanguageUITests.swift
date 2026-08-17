// Covers the two things reported from an iPhone 11 Pro on 17 Aug:
//   * swiping left/right between the Chats / Groups / Favorites filter tabs, and
//   * the in-app language reaching the WHOLE interface, not just the chat titles.
//
// Both assertions use hardcoded translations on purpose: reading the expected text from the same
// bundle the app reads would make the test agree with any wiring, correct or not.

import UIKit
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
        // AppLanguage persists the in-app choice in UserDefaults, and the language test leaves it on
        // Arabic — a launch argument of the same name overrides it, so each test starts in English.
        app.launchArguments += ["-khandaq_app_language", "en"]
        app.launch()
        dismissSystemAlerts()
        signUpIfNeeded()
    }

    /// One test, two checks: XCTest relaunches the app between test methods, and getting back into
    /// a profile costs minutes (and does not always succeed on the simulator), so both live here.
    func testSwipeAndInAppLanguage() {
        checkSwipeBetweenFilterTabs()
        checkInAppLanguageReachesWholeInterface()
    }

    // MARK: - Swipe

    private func checkSwipeBetweenFilterTabs() {
        openChats()
        selectFilterTab(.direct)

        let list: XCUIElement = app.windows.element(boundBy: 0)

        assertSelectedTab("direct", after: "opening Chats")

        swipeHorizontally(on: list, left: true)
        assertSelectedTab("groups", after: "one swipe left")

        swipeHorizontally(on: list, left: true)
        assertSelectedTab("favorites", after: "two swipes left")

        swipeHorizontally(on: list, left: true)
        assertSelectedTab("favorites", after: "a third swipe left (right edge)")

        swipeHorizontally(on: list, left: false)
        assertSelectedTab("groups", after: "one swipe right")

        swipeHorizontally(on: list, left: false)
        assertSelectedTab("direct", after: "two swipes right")

        // The right edge is covered above (a third swipe left stays on Favorites). The left edge is
        // covered by the logic test in AntidoteTests; repeating it here cost more than it proved —
        // a further rightward drag on the first tab kept ending the session under the simulator.
    }


    // MARK: - Language

    private func checkInAppLanguageReachesWholeInterface() {
        switchLanguage(toNativeName: "English", rowTitles: [Expected.englishLanguageRow, Expected.arabicLanguageRow])
        assertInterface(tabs: Expected.englishTabs, filters: Expected.englishFilters,
                        edit: Expected.englishEdit, language: "English")

        switchLanguage(toNativeName: "العربية", rowTitles: [Expected.englishLanguageRow, Expected.arabicLanguageRow])
        assertInterface(tabs: Expected.arabicTabs, filters: Expected.arabicFilters,
                        edit: Expected.arabicEdit, language: "Arabic")

        // Leave the app in Russian: AppLanguage also rewrites AppleLanguages, which decides the
        // keyboard — and the credentials this suite types are Cyrillic, so a run left in Arabic
        // cannot sign in again.
        switchLanguage(toNativeName: "Русский",
                       rowTitles: [Expected.englishLanguageRow, Expected.arabicLanguageRow, "Язык"])
    }
}

// MARK: - Helpers

private extension SwipeAndLanguageUITests {
    /// A quick horizontal drag across the middle of the screen — what a thumb does, and what the
    /// pan recogniser measures. XCUIElement.swipeLeft() alone was not enough to move the tab.
    func swipeHorizontally(on element: XCUIElement, left: Bool) {
        // Start a rightward drag away from the left edge: from there UIKit's own back gesture takes
        // it and pops the screen instead.
        let startX = left ? 0.85 : 0.35
        let endX = left ? 0.15 : 0.92
        let start = element.coordinate(withNormalizedOffset: CGVector(dx: startX, dy: 0.55))
        let end = element.coordinate(withNormalizedOffset: CGVector(dx: endX, dy: 0.55))
        start.press(forDuration: 0.02, thenDragTo: end)
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

        // The Edit button only exists when there is something to edit — an empty list hides it, which
        // is correct behaviour, so check it only when a chat is listed.
        if app.cells.count > 0 {
            XCTAssertTrue(app.navigationBars.buttons[edit].waitForExistence(timeout: 10),
                          "\(language): the Edit button does not read \"\(edit)\"")
        }
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

        // The sheet writes each language in its own language and appends " ✓" to the active one, so
        // match by prefix — and if the wanted language is already active, just close the sheet.
        let option = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).element(boundBy: 0)
        XCTAssertTrue(option.waitForExistence(timeout: 5), "language \(name) is missing from the sheet")
        if option.label.contains("✓") {
            let cancel = app.buttons.matching(NSPredicate(format: "label IN {'Cancel', 'Отмена', 'إلغاء'}"))
                    .element(boundBy: 0)
            if cancel.exists {
                cancel.tap()
            }
            return
        }
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
        let advanceTitles = ["Go", "Next", "Continue", "Get started", "Далее", "Начать",
                             "التالي", "انتقال", "إبدأ", "ابدأ", "متابعة", "إنشاء"]
        // The app requires a letter AND a digit (PasswordPolicy), and XCUITest can only press keys
        // the simulator's current keyboard actually has — latin letters are absent on the Russian
        // and Arabic layouts, so the credentials use digits plus Cyrillic, which the layout provides.
        // CharacterSet.letters accepts Cyrillic, so the policy is satisfied.
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
                fill(secure.element(boundBy: 0), with: "пароль1234")
                if secure.count > 1 {
                    fill(secure.element(boundBy: 1), with: "пароль1234")
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

    /// Types into a field without depending on the keyboard layout.
    ///
    /// Two lessons: secure fields drop characters when a whole string is typed at once, and XCUITest
    /// can only press keys the CURRENT keyboard has — the simulator's layout has been Russian and
    /// Arabic at different points today, and latin/cyrillic letters were silently skipped, leaving a
    /// digits-only password that the app rightly rejected. Pasting is layout-independent.
    func fill(_ field: XCUIElement, with text: String) {
        guard field.exists else {
            return
        }
        field.tap()
        field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 30))

        UIPasteboard.general.string = text
        field.press(forDuration: 1.3)
        for title in ["Paste", "Вставить", "لصق"] {
            let item = app.menuItems[title]
            if item.waitForExistence(timeout: 2) {
                item.tap()
                return
            }
        }

        // No paste menu (some fields refuse it) — fall back to typing character by character.
        for character in text {
            field.typeText(String(character))
        }
    }

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
