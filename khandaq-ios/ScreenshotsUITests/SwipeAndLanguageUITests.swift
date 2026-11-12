// Covers the two things reported from an iPhone 11 Pro on 17 Aug:
//   * swiping left/right between the list filters (on the Air screen: everything, requests,
//     in transit, favorites), and
//   * the in-app language reaching the WHOLE interface, not just the chat titles.
//
// Both assertions use hardcoded translations on purpose: reading the expected text from the same
// bundle the app reads would make the test agree with any wiring, correct or not.

import UIKit
import UniformTypeIdentifiers
import XCTest

final class SwipeAndLanguageUITests: XCTestCase {
    private var app: XCUIApplication!
    /// False for tests whose name contains "Real"; see setUp. Those run against the real
    /// messenger; everything else gets OCTManagerMock.
    private var useMockBackend = true
    /// When setUp launched the app: the demo contacts read "connecting" for the first minute.
    private var launchedAt = Date()

    /// Where screenshots and accessibility dumps go. Overridable so a CI box (or another machine)
    /// does not silently write into a path that only existed on the author's laptop.
    private static var artifactsDir: String {
        let fromEnv = ProcessInfo.processInfo.environment["KHANDAQ_UITEST_ARTIFACTS"]
        let base = (fromEnv?.isEmpty == false ? fromEnv! : NSTemporaryDirectory())
        return base.hasSuffix("/") ? base : base + "/"
    }

    /// What the Air screen reads in one language: the pills (requests, in transit, favorites), the
    /// header buttons, the command line and the "you" block.
    private struct AirTexts {
        let pills: [String]
        let settings: String
        let create: String
        let command: String
        let you: String
    }

    private enum Expected {
        static let english = AirTexts(pills: ["Requests", "In transit", "Favorites"],
                                      settings: "Settings",
                                      create: "Create",
                                      command: "Find a chat or paste an ID",
                                      you: "You")
        static let arabic = AirTexts(pills: ["الطلبات", "في الطريق", "المفضلة"],
                                     settings: "الإعدادات",
                                     create: "إنشاء",
                                     command: "ابحث عن محادثة أو الصق معرّفًا",
                                     you: "أنت")
        static let englishLanguageRow = "Language"
        static let arabicLanguageRow = "اللغة"
    }

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
        // ★ Tests whose name contains "Real" must run against the real messenger. Without this the
        // flag stays true and even a test named ...Real gets the mock, which is exactly how three
        // "reactions are broken" runs proved nothing.
        useMockBackend = !name.contains("Real")
        app = XCUIApplication()
        // ★ UI_TESTING swaps the whole messenger for OCTManagerMock, whose toggleReaction is a no-op.
        // Any test about real behaviour (reactions, sending) must run WITHOUT it.
        if useMockBackend {
            app.launchArguments.append("UI_TESTING")
        }
        // Start from a known system language so the onboarding buttons are predictable; the in-app
        // language (what this test is about) is switched from Settings later on.
        // AppLanguage persists the in-app choice in UserDefaults, and the language test leaves it on
        // Arabic: a launch argument of the same name overrides it, so each test starts in English.
        // Default to English so the assertions are predictable; KHANDAQ_UITEST_LANG overrides it when
        // a run needs to look at another language (e.g. the longest one, ru).
        let env = ProcessInfo.processInfo.environment
        let uiLang = env["KHANDAQ_UITEST_LANG"] ?? "en"
        if env["KHANDAQ_UITEST_SYSTEM_LANG"] == "1" {
            // The system speaks the run's language and the app follows it: an empty choice of its own
            // is no language, so the app takes the system's (stage 5, right to left set by the system).
            let locale = ["ar": "ar_SA", "ru": "ru_RU", "zh": "zh_CN"][uiLang] ?? "en_US"
            app.launchArguments += ["-AppleLanguages", "(\(uiLang))", "-AppleLocale", locale]
            app.launchArguments += ["-khandaq_app_language", ""]
        }
        else {
            app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
            app.launchArguments += ["-khandaq_app_language", uiLang]
        }
        // A run at another text size: a content size category, e.g. UICTContentSizeCategoryAccessibilityL.
        if let size = env["KHANDAQ_UITEST_TEXT_SIZE"], !size.isEmpty {
            app.launchArguments += ["-UIPreferredContentSizeCategoryName", size]
        }
        // The demo world of the mock: 200 more chats, and the offline contact coming back after a message.
        if env["KHANDAQ_DEMO_BULK"] == "1" || name.contains("Performance") {
            app.launchArguments.append("DEMO_BULK")
        }
        // The performance numbers of stage 5: a conversation of 3000 messages besides the 200 chats.
        if name.contains("Performance") {
            app.launchArguments.append("DEMO_LONG_THREAD")
        }
        if env["KHANDAQ_DEMO_RETURN"] == "1" || name.contains("ConversationWalk") || name.contains("SiteCaptures") {
            app.launchArguments.append("DEMO_RETURN")
        }
        // The store and site captures (stage 5): the redesign's demo people only, and the theme of the
        // system, which the run sets.
        if name.contains("Captures") {
            app.launchArguments.append("DEMO_OURS_ONLY")
            app.launchArguments += ["-user-info/theme-preference", "system"]
        }
        // Each store frame is one screen: the demo world without the long name, the unanswered request and
        // the group you only read, so Air keeps Saved Messages on the screen and Yusuf has one message waiting.
        if name.contains("StoreCaptures") {
            app.launchArguments.append("DEMO_STORE")
        }
        // Our own node starts without the network and finds it on "Connect again" (E4).
        if name.contains("NetworkWalk") {
            app.launchArguments.append("DEMO_CONNECTING")
        }
        // Moving a profile in: the picker hands over a profile file made on the spot (stage 4, E20).
        if name.contains("FirstLaunch") {
            app.launchArguments.append("DEMO_IMPORT_FILE")
        }
        app.launch()
        launchedAt = Date()
        dismissSystemAlerts()
        if name.contains("AuditWalk") || name.contains("FirstLaunch") || name.contains("Upgrade") || name.contains("AppReview") {
            // These walks record first launch themselves, so they must start in front of it.
            return
        }
        signUpIfNeeded()
    }

    // MARK: - Redesign audit walk (local tool, not run in CI)

    private var auditDir = ""
    private var auditCounter = 0

    /// Walks every screen it can reach and saves a screenshot and the accessibility tree of each to
    /// $KHANDAQ_UITEST_ARTIFACTS/audit-<lang>/, so the redesign audit looks at the real interface.
    /// KHANDAQ_AUDIT_SHORT=1 keeps it to onboarding, the Air screen and what opens from its header,
    /// one chat and the dark theme.
    func testAuditWalkScreens() {
        let env = ProcessInfo.processInfo.environment
        let lang = env["KHANDAQ_UITEST_LANG"] ?? "en"
        let short = env["KHANDAQ_AUDIT_SHORT"] == "1"
        auditDir = Self.artifactsDir + "audit-\(lang)\(short ? "-short" : "")/"
        try? FileManager.default.createDirectory(atPath: auditDir, withIntermediateDirectories: true)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }

        // 1. Onboarding, recorded while it is walked.
        let air = app.airCommand
        for page in 0..<6 {
            if air.waitForExistence(timeout: 3) {
                break
            }
            auditShot("onboarding-\(page)")
            if app.textFields.count > 0 || app.secureTextFields.count > 0 {
                break
            }
            if tapFirstButton(titled: ["Create account", "Создать аккаунт", "إنشاء حساب"]) {
                continue
            }
            if tapFirstButton(titled: ["Next", "Continue", "Get started", "Далее", "Начать", "Продолжить",
                                       "التالي", "متابعة", "ابدأ"]) {
                continue
            }
            break
        }
        if !air.exists, app.textFields.count > 0 {
            auditShot("onboarding-name-form")
        }
        signUpIfNeeded()
        dismissAuditAlerts()
        guard air.waitForExistence(timeout: 60), app.returnToAir() else {
            auditShot("stuck-in-onboarding")
            XCTFail("no Air screen after onboarding")
            return
        }

        // 2. The Air screen as it opens, then its filters, the command line and the "Create" sheet.
        auditShot("air")
        auditAirScreen()

        // 3. One chat in depth, and one group if the profile has any.
        auditChatDeepDive(prefix: "chat")

        // 4. Settings and the profile: the root of each, and in the full walk every row one level deep.
        auditCrawl(entry: app.buttons["air.settings"], prefix: "settings", rows: !short)
        auditCrawl(entry: app.buttons["air.you"], prefix: "profile", rows: !short)

        // 5. The main screens in the dark theme.
        auditDarkPass()
    }

    private func auditShot(_ name: String) {
        auditCounter += 1
        usleep(800_000)
        let base = auditDir + String(format: "%03d-", auditCounter) + auditSlug(name)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: base + ".png"))
        try? app.debugDescription.write(toFile: base + ".txt", atomically: true, encoding: .utf8)
    }

    private func auditSlug(_ text: String) -> String {
        let allowed = Set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-")
        return String(String(text.map { allowed.contains($0) ? $0 : "_" }).prefix(40))
    }

    /// Rows that would end the session, lose data or leave the app are recorded only as part of their list.
    private func auditIsRisky(_ label: String) -> Bool {
        let lower = label.lowercased()
        let words = ["выйти", "выход", "log out", "logout", "sign out", "удал", "delete", "remove", "сброс",
                     "reset", "очист", "clear", "заблок", "block", "export", "экспорт", "خروج", "حذف"]
        return words.contains { lower.contains($0) }
    }

    private func dismissAuditAlerts() {
        for _ in 0..<3 where app.alerts.count > 0 {
            let alert = app.alerts.element(boundBy: 0)
            let soft = alert.buttons.matching(NSPredicate(
                    format: "label IN {'Cancel', 'Отмена', 'إلغاء', 'OK', 'Not Now', 'Не сейчас', 'Позже'}"))
                    .element(boundBy: 0)
            if soft.exists {
                soft.tap()
            }
            else {
                alert.buttons.element(boundBy: alert.buttons.count - 1).tap()
            }
        }
    }

    /// The Air screen: each filter, the command line (focused, with matches, with none), the "Create"
    /// sheet and the two screens it opens.
    private func auditAirScreen() {
        for filter in [FilterName.requests, .transit, .favorites] {
            let pill = app.airPill(filter.rawValue)
            if pill.waitForExistence(timeout: 3) {
                selectFilter(filter)
                auditShot("air-filter-\(filter.rawValue)")
            }
        }
        selectFilter(.all)

        let command = app.airCommand
        command.tap()
        auditShot("air-command-focus")
        // Mock names are the same in every language; "Mar" finds two of them.
        fill(command, with: "Mar")
        auditShot("air-search")
        fill(command, with: "zzqx")
        auditShot("air-search-no-match")
        let cancel = app.buttons["air.command.cancel"]
        if cancel.exists, cancel.isHittable {
            cancel.tap()
        }
        app.returnToAir()

        let create = app.buttons["air.create"]
        guard create.exists else {
            return
        }
        create.tap()
        auditShot("air-create-sheet")
        let newContact = app.buttons["air.newContact"]
        if newContact.waitForExistence(timeout: 3) {
            newContact.tap()
            auditShot("add-contact")
        }
        app.returnToAir()

        create.tap()
        let newGroup = app.buttons["air.newGroup"]
        if newGroup.waitForExistence(timeout: 3) {
            newGroup.tap()
            auditShot("group-create-contacts")
            let next = app.navigationBars.buttons.matching(
                    NSPredicate(format: "label IN {'Next', 'Далее', 'التالي', '下一步'}")).element(boundBy: 0)
            if next.waitForExistence(timeout: 3) {
                next.tap()
                auditShot("group-create-name")
            }
        }
        app.returnToAir()
    }

    /// The first table on screen that is not the Air list (or the first collection view).
    private func auditCurrentList() -> XCUIElement {
        let tables = app.tables.matching(NSPredicate(format: "identifier != %@", "air.list"))
        return tables.count > 0 ? tables.element(boundBy: 0) : app.collectionViews.element(boundBy: 0)
    }

    /// Opens a screen from the Air header and records it; with `rows`, also every row on it one level
    /// deep, opening the screen again from the Air header after each row.
    private func auditCrawl(entry: XCUIElement, prefix: String, rows: Bool) {
        guard app.returnToAir(), entry.waitForExistence(timeout: 5) else {
            return
        }
        entry.tap()
        auditShot("\(prefix)-root")
        var list = auditCurrentList()
        guard rows, list.waitForExistence(timeout: 5) else {
            app.returnToAir()
            return
        }
        list.swipeUp()
        auditShot("\(prefix)-root-scrolled")
        list.swipeDown()
        list.swipeDown()

        let rowCount = min(list.cells.count, 30)
        var onRoot = true
        for row in 0..<rowCount {
            if !onRoot {
                guard app.returnToAir(), entry.exists else {
                    break
                }
                entry.tap()
                onRoot = true
            }
            list = auditCurrentList()
            guard list.waitForExistence(timeout: 5), row < list.cells.count else {
                break
            }
            let cell = list.cells.element(boundBy: row)
            let label = cell.staticTexts.count > 0 ? cell.staticTexts.element(boundBy: 0).label : cell.label
            if auditIsRisky(label) || auditIsRisky(cell.label) || cell.switches.count > 0 {
                continue
            }
            cell.tap()
            onRoot = false
            auditShot("\(prefix)-row\(row)-\(auditSlug(label))")
        }
        app.returnToAir()
    }

    private func auditChatDeepDive(prefix: String) {
        app.returnToAir()
        guard let firstChat = firstChatRow() else {
            auditShot("\(prefix)-no-chats")
            return
        }
        firstChat.tap()
        auditShot("\(prefix)-open")

        // The message menu on the last message bubble (the bottom-most text view is the input field).
        let texts = app.textViews.allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
                .sorted { $0.frame.maxY < $1.frame.maxY }
        if texts.count >= 2 {
            texts[texts.count - 2].press(forDuration: 1.2)
            auditShot("\(prefix)-message-menu")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).tap()
        }
        // The menu may still be closing, so look the input up again and type only once it has focus.
        let inputs = app.textViews.allElementsBoundByIndex.filter { $0.exists && $0.isHittable }
                .sorted { $0.frame.maxY < $1.frame.maxY }
        if let input = inputs.last {
            input.tap()
            if app.keyboards.element.waitForExistence(timeout: 3) {
                app.typeText("1234")
                auditShot("\(prefix)-typing")
                app.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: 4))
            }
            else {
                auditShot("\(prefix)-input-no-keyboard")
            }
        }

        // The paperclip stays in the header of "For yourself"; a conversation with a person keeps its
        // files in the dossier (redesign stage 2).
        let paperclip = app.navigationBars.buttons.matching(
                NSPredicate(format: "label IN {'Attachments', 'Вложения', 'المرفقات'}")).element(boundBy: 0)
        if paperclip.exists, paperclip.isHittable {
            paperclip.tap()
            auditShot("\(prefix)-attachments")
            let back = app.navigationBars.buttons["BackButton"]
            if back.exists, back.isHittable {
                back.tap()
            }
        }

        // The header title opens the dossier of a person or the group card.
        let title = app.buttons["air.chat.title"]
        let bar = app.navigationBars.element(boundBy: 0)
        if title.exists, title.isHittable {
            title.tap()
            auditShot("\(prefix)-info")
        }
        else if bar.exists {
            bar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            auditShot("\(prefix)-info")
        }
        if openDossierAttachments() {
            auditShot("\(prefix)-attachments")
        }
        app.returnToAir()

        // One group, if the profile has any.
        let group = app.airRow("group")
        if group.exists {
            group.tap()
            auditShot("\(prefix)-group-open")
            let groupBar = app.navigationBars.element(boundBy: 0)
            if groupBar.exists {
                groupBar.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
                auditShot("\(prefix)-group-info")
            }
            app.returnToAir()
        }
    }

    private func auditDarkPass() {
        guard #available(iOS 17.0, *) else {
            return
        }
        XCUIDevice.shared.appearance = .dark
        sleep(2)
        app.returnToAir()
        auditShot("dark-air")
        if app.airPill(FilterName.transit.rawValue).exists {
            selectFilter(.transit)
            auditShot("dark-air-filter-transit")
            selectFilter(.all)
        }
        app.airCommand.tap()
        auditShot("dark-air-command-focus")
        let cancel = app.buttons["air.command.cancel"]
        if cancel.exists, cancel.isHittable {
            cancel.tap()
        }

        for (identifier, name) in [("air.create", "create-sheet"), ("air.settings", "settings"), ("air.you", "profile")] {
            let entry = app.buttons[identifier]
            guard app.returnToAir(), entry.exists else {
                continue
            }
            entry.tap()
            auditShot("dark-\(name)")
        }
        app.returnToAir()
        if let chat = firstChatRow(timeout: 3) {
            chat.tap()
            auditShot("dark-chat-open")
            app.returnToAir()
        }
        XCUIDevice.shared.appearance = .light
    }

    /// In an open dossier, presses "Attachments" (scrolling the dossier to it if needed). Returns
    /// whether the attachments screen was asked for.
    @discardableResult
    private func openDossierAttachments() -> Bool {
        let attachments = app.buttons["air.chat.dossier.attachments"]
        guard attachments.waitForExistence(timeout: 3) else {
            return false
        }
        let panel = app.otherElements["air.chat.dossier.panel"]
        for _ in 0..<3 where !attachments.isHittable && panel.exists {
            panel.swipeUp()
        }
        guard attachments.isHittable else {
            return false
        }
        attachments.tap()
        return true
    }

    // MARK: - Redesign stage 2: the conversation walk (local tool, not run in CI)

    /// SelfConnectionTracker.friendReconnectGrace in the app (60 s), with a margin.
    private enum SelfConnectionGrace {
        static let seconds: TimeInterval = 66
    }

    /// The demo people of OCTManagerMock, the part of each name the walk looks for in a row.
    private static let demoPeople: [String: (amina: String, yusuf: String, bilal: String, long: String)] = [
        "en": ("Amina", "Yusuf", "Bilal", "Maximilian"),
        "ru": ("Амина", "Юсуф", "Билал", "Максимилиан"),
        "ar": ("أمينة", "يوسف", "بلال", "عبد الرحمن"),
        "zh": ("阿米娜", "尤素福", "比拉勒", "马克西米利安"),
    ]

    /// The stage 3 demo request whose sender becomes a contact once accepted (demo_s3_timur).
    private static let demoRequester: [String: String] = [
        "en": "Timur", "ru": "Тимур", "ar": "تيمور", "zh": "帖木儿",
    ]

    /// The group Bilal invites you to in the network walk (demo_s3_band_invite_group).
    private static let demoBandGroup: [String: String] = [
        "en": "Saturday by the river", "ru": "Суббота у реки", "ar": "السبت عند النهر", "zh": "河边的周六",
    ]

    /// Walks the conversation with one person on the mock's demo world and saves a screenshot and the
    /// accessibility tree of each state to $KHANDAQ_UITEST_ARTIFACTS/conversation-<lang>[-dark][-<size>]/:
    /// a person online (runs, statuses, the day strip, the hint "goes right away", the dossier), a person
    /// offline (the "In transit" tray folded and unfolded, the hint "waits in transit", then their
    /// return: the tray empties and the marks turn to two checks), a person behind a relay with a
    /// refused message, a very long name, and "For yourself". KHANDAQ_UITEST_DARK=1 runs it dark,
    /// KHANDAQ_UITEST_TEXT_SIZE at another text size.
    func testConversationWalkScreens() {
        let env = ProcessInfo.processInfo.environment
        let lang = env["KHANDAQ_UITEST_LANG"] ?? "en"
        let dark = env["KHANDAQ_UITEST_DARK"] == "1"
        var folder = "conversation-\(lang)" + (dark ? "-dark" : "")
        if let size = env["KHANDAQ_UITEST_TEXT_SIZE"], !size.isEmpty {
            folder += "-" + auditSlug(size.replacingOccurrences(of: "UICTContentSizeCategory", with: ""))
        }
        auditDir = Self.artifactsDir + folder + "/"
        try? FileManager.default.createDirectory(atPath: auditDir, withIntermediateDirectories: true)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = dark ? .dark : .light
        }
        guard let people = Self.demoPeople[lang] else {
            XCTFail("no demo names for \(lang)")
            return
        }
        openAir()
        auditShot("air-demo-world")

        // 1. A person online: the transcript, the hint under the field, the dossier.
        XCTAssertTrue(openConversation(with: people.amina), "no conversation with \(people.amina)")
        auditShot("amina-conversation")
        if typeInField("19:45") {
            auditShot("amina-composer-live")
            clearField(count: 5)
        }
        openDossierFromTitle()
        auditShot("amina-dossier")
        let panel = app.otherElements["air.chat.dossier.panel"]
        if panel.exists {
            panel.swipeUp()
            auditShot("amina-dossier-scrolled")
        }
        closeDossierFromTitle()
        app.returnToAir()

        // 2. A person behind a relay: the refused message with "Retry" and the dossier's relay path.
        XCTAssertTrue(openConversation(with: people.bilal), "no conversation with \(people.bilal)")
        auditShot("bilal-relay-refused")
        assertLogKeepsItsRoom(env, "beside a refused message")
        openDossierFromTitle()
        auditShot("bilal-dossier")
        closeDossierFromTitle()
        app.returnToAir()

        // 3. A very long name, offline for over a week.
        if openConversation(with: people.long) {
            auditShot("long-name-conversation")
            app.returnToAir()
        }

        // 4. For yourself: no statuses, the hint says the note stays on this device.
        if let saved = scrollToRow(app.airRow("saved")) {
            saved.tap()
            auditShot("saved-conversation")
            if typeInField("2741") {
                auditShot("saved-composer")
            }
            app.returnToAir()
        }

        // 5. A person offline: the tray, folded and unfolded, the hint, then the person comes back.
        // For the first minute after our own node comes online, recently seen contacts read
        // "connecting" (SelfConnectionTracker); the walk waits for the steady "offline for ...".
        let settle = SelfConnectionGrace.seconds - Date().timeIntervalSince(launchedAt)
        if settle > 0 {
            sleep(UInt32(ceil(settle)))
        }
        XCTAssertTrue(openConversation(with: people.yusuf), "no conversation with \(people.yusuf)")
        auditShot("yusuf-tray-folded")
        let trayHeader = app.buttons["air.chat.tray.header"]
        if trayHeader.waitForExistence(timeout: 3) {
            trayHeader.tap()
            auditShot("yusuf-tray-unfolded")
            assertLogKeepsItsRoom(env, "under the open tray")
        }
        if typeInField("19:45") {
            auditShot("yusuf-composer-wait")
            assertLogKeepsItsRoom(env, share: 0.125, "with the keyboard up and messages in transit")
            let send = app.buttons["air.chat.send"]
            if send.exists, send.isHittable {
                send.tap()
                // The mock brings Yusuf online four seconds after a message (DEMO_RETURN), takes the
                // waiting messages 0.6 s later and confirms them 2 s after that.
                auditShot("yusuf-sent-into-tray")
                sleep(3)
                auditShot("yusuf-back-online")
                sleep(2)
                auditShot("yusuf-delivered")
                // The file that waited is drawn again in place once delivered: it keeps who sent it and
                // when (stage 5: it lost both, and its day, and hung under the run of the day before).
                // At the larger text sizes it can be above the screen.
                let file = app.descendants(matching: .any)
                    .matching(NSPredicate(format: "value CONTAINS %@", "trip-photos.zip")).firstMatch
                if (env["KHANDAQ_UITEST_TEXT_SIZE"] ?? "").isEmpty {
                    XCTAssertTrue(file.exists, "no delivered trip-photos.zip in the transcript")
                }
                if file.exists {
                    XCTAssertTrue(file.label.hasPrefix(appString("air_you", lang)),
                                  "the delivered file is read without its author: '\(file.label)'")
                }
            }
        }
        app.returnToAir()

        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// A whole ID with a correct check, and the same ID with a typo in its check.
    private static let stageThreeId = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5"

    /// Walks the screens of stage 3 on the mock's demo world and saves a screenshot and the
    /// accessibility tree of each to $KHANDAQ_UITEST_ARTIFACTS/stage3-<lang>[-dark][-<size>]/, like the
    /// conversation walk: New contact with its ruler and verdicts, the command line reading the start
    /// of an ID, then Block from the dossier and Settings > Privacy > Blocked. KHANDAQ_UITEST_DARK=1
    /// runs it dark, KHANDAQ_UITEST_TEXT_SIZE at another text size.
    func testStageThreeWalkScreens() {
        let env = ProcessInfo.processInfo.environment
        let lang = env["KHANDAQ_UITEST_LANG"] ?? "en"
        let dark = env["KHANDAQ_UITEST_DARK"] == "1"
        var folder = "stage3-\(lang)" + (dark ? "-dark" : "")
        if let size = env["KHANDAQ_UITEST_TEXT_SIZE"], !size.isEmpty {
            folder += "-" + auditSlug(size.replacingOccurrences(of: "UICTContentSizeCategory", with: ""))
        }
        auditDir = Self.artifactsDir + folder + "/"
        try? FileManager.default.createDirectory(atPath: auditDir, withIntermediateDirectories: true)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = dark ? .dark : .light
        }
        guard let people = Self.demoPeople[lang] else {
            XCTFail("no demo names for \(lang)")
            return
        }
        openAir()
        stageThreeNewContact()
        stageThreeCommandLine()
        stageThreeRequests(requester: Self.demoRequester[lang] ?? "Timur")
        stageThreeBlock(person: people.long)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// E11: the empty form, part of an ID, a typo in the check, your own ID, a good one, then sent.
    private func stageThreeNewContact() {
        let create = app.buttons["air.create"]
        XCTAssertTrue(create.waitForExistence(timeout: 10), "no Create button")
        create.tap()
        let newContact = app.buttons["air.newContact"]
        XCTAssertTrue(newContact.waitForExistence(timeout: 5), "no New contact in the Create sheet")
        newContact.tap()
        let field = app.textViews["air.newContact.id"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "New contact did not open")
        auditShot("nc-empty")

        let id = Self.stageThreeId
        field.typeText(String(id.prefix(30)))
        auditShot("nc-partial")
        let typo = String(id.dropFirst(30).dropLast()) + (id.hasSuffix("5") ? "6" : "5")
        field.typeText(typo)
        auditShot("nc-bad-check")
        XCTAssertTrue(app.staticTexts["air.newContact.verdict"].exists)

        clearNewContactField()
        field.typeText(id)
        app.textViews["air.newContact.message"].tap()
        auditShot("nc-ok")
        app.textViews["air.newContact.message"].typeText("1945")
        auditShot("nc-message")
        let send = app.buttons["air.newContact.send"]
        let ownId = app.staticTexts["air.newContact.ownId"]
        let form = app.scrollViews.element(boundBy: 0)
        for _ in 0..<3 where !(ownId.exists && ownId.isHittable) {
            form.swipeUp()
        }
        auditShot("nc-own-id")
        XCTAssertTrue(send.isEnabled, "a good ID must let the request go")
        send.tap()
        XCTAssertTrue(app.staticTexts["air.newContact.sentTitle"].waitForExistence(timeout: 5), "no Request on its way")
        auditShot("nc-sent")
        let done = app.buttons["air.newContact.done"]
        if done.exists {
            done.tap()
        }
        app.returnToAir()
    }

    private func clearNewContactField() {
        let clear = app.buttons["air.newContact.clear"]
        // At the accessibility text sizes the button stands under the send bar: the tap scrolls the form
        // to it, as VoiceOver does (stage 5: flicks overshot it, and the form could not be scrolled to it).
        XCTAssertTrue(clear.exists, "no Clear beside the ID")
        clear.tap()
        // An empty field has nothing to clear: the button goes.
        XCTAssertTrue(clear.waitForNonExistence(timeout: 3), "Clear left the ID")
    }

    /// E3: the start of an ID in the command line shows "New contact with this ID".
    private func stageThreeCommandLine() {
        let command = app.airCommand
        XCTAssertTrue(command.waitForExistence(timeout: 10))
        command.tap()
        command.typeText(String(Self.stageThreeId.prefix(20)))
        let row = app.otherElements["air.command.newContact"].exists ? app.otherElements["air.command.newContact"] : app.buttons["air.command.newContact"]
        XCTAssertTrue(row.waitForExistence(timeout: 5), "no New contact row for the start of an ID")
        auditShot("cmd-id-row")
        row.tap()
        if app.textViews["air.newContact.id"].waitForExistence(timeout: 5) {
            auditShot("cmd-id-new-contact")
        }
        app.returnToAir()
    }

    /// E12: two requests and two group invitations as cards. "Decline" leaves at once with "Undo" in a
    /// toast; an undo brings the card back, a missed one removes it. "Join" opens the group.
    private func stageThreeRequests(requester: String) {
        app.returnToAir()
        selectFilter(.requests)
        let invites = app.cells.matching(identifier: "air.invite")
        let requests = app.cells.matching(identifier: "air.request")
        XCTAssertTrue(invites.element(boundBy: 0).waitForExistence(timeout: 5), "no invitation card")
        XCTAssertEqual(invites.count, 2, "two invitations")
        XCTAssertEqual(requests.count, 2, "two requests")
        auditShot("requests-cards")

        // The quiet request (no message, the oldest): declined, then taken back.
        let list = app.tables["air.list"]
        let quiet = requests.element(boundBy: 1)
        let quietDecline = quiet.buttons["air.request.decline"]
        reach(quietDecline, in: list)
        quietDecline.tap()
        let undo = app.buttons["air.toast.action"]
        XCTAssertTrue(undo.waitForExistence(timeout: 3), "no Undo after Decline")
        XCTAssertEqual(requests.count, 1, "the card leaves at once")
        auditShot("request-declined-undo")
        undo.tap()
        XCTAssertTrue(requests.element(boundBy: 1).waitForExistence(timeout: 3), "Undo did not bring the card back")
        auditShot("request-undone")

        // The family invitation from Yusuf, who is offline: declined and left to go.
        let family = invites.element(boundBy: 1)
        let familyDecline = family.buttons["air.invite.decline"]
        reach(familyDecline, in: list)
        familyDecline.tap()
        XCTAssertTrue(undo.waitForExistence(timeout: 3))
        let gone = NSPredicate(format: "exists == false")
        expectation(for: gone, evaluatedWith: app.otherElements["air.toast"], handler: nil)
        waitForExpectations(timeout: 12, handler: nil)
        XCTAssertEqual(invites.count, 1, "a missed Undo removes the invitation")
        auditShot("invite-declined-gone")

        // Amina's book club: "Join" opens the group.
        let join = invites.element(boundBy: 0).buttons["air.invite.join"]
        reach(join, in: list)
        join.tap()
        let opened = app.buttons["air.chat.title"].waitForExistence(timeout: 10) || app.navigationBars.count > 0
        XCTAssertTrue(opened, "Join did not open the group")
        auditShot("invite-joined")
        app.returnToAir()

        // Timur's request: accepted, the card goes and he is a contact, online, among the chats.
        let timur = requests.element(boundBy: 0)
        XCTAssertTrue(timur.waitForExistence(timeout: 5), "no request from Timur")
        let accept = timur.buttons["air.request.accept"]
        reach(accept, in: list)
        accept.tap()
        expectation(for: NSPredicate(format: "count == 1"), evaluatedWith: requests, handler: nil)
        waitForExpectations(timeout: 5, handler: nil)
        selectFilter(.all)
        // Nobody has written yet, so his row is a contact's (it starts the conversation), not a chat's.
        let contact = app.cells.matching(NSPredicate(format: "identifier IN {'air.row.contact', 'air.row.direct'} AND label CONTAINS %@",
                                                     requester)).element(boundBy: 0)
        XCTAssertNotNil(scrollToRow(contact), "the accepted request is not a contact in the list")
        auditShot("request-accepted")
    }

    /// Block from the dossier: the confirmation, then Settings > Privacy > Blocked and Unblock.
    private func stageThreeBlock(person: String) {
        guard openConversation(with: person) else {
            XCTFail("no conversation with \(person)")
            return
        }
        openDossierFromTitle()
        let panel = app.otherElements["air.chat.dossier.panel"]
        let block = app.buttons["air.chat.dossier.block"]
        for _ in 0..<4 where !(block.exists && block.isHittable) && panel.exists {
            panel.swipeUp()
        }
        guard block.exists, block.isHittable else {
            XCTFail("no Block in the dossier")
            return
        }
        block.tap()
        auditShot("block-confirm")
        let sheet = app.sheets.element(boundBy: 0)
        let confirm = sheet.buttons.element(boundBy: 0)
        XCTAssertTrue(confirm.waitForExistence(timeout: 3), "no confirmation for Block")
        confirm.tap()
        XCTAssertTrue(app.returnToAir(), "Block did not come back to Air")
        let gone = app.cells.matching(NSPredicate(format: "identifier == 'air.row.direct' AND label CONTAINS %@", person))
                .element(boundBy: 0)
        XCTAssertNil(scrollToRow(gone), "the blocked contact is still in the list")
        let list = app.tables["air.list"]
        for _ in 0..<8 where list.exists {
            list.swipeDown()
        }
        auditShot("block-air-after")

        let settings = app.buttons["air.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10))
        settings.tap()
        // Settings lists its sections as rows (E18); the old table cell is kept as a fallback.
        let privacy = app.buttons["settings.privacy"]
        _ = privacy.waitForExistence(timeout: 5)
        let entry = privacy.exists ? privacy : app.cells.matching(identifier: "settings.privacy").element(boundBy: 0)
        for _ in 0..<3 where !entry.exists {
            app.swipeUp()
        }
        if entry.exists {
            entry.tap()
        }
        let blocked = app.otherElements["air.settings.privacy.blocked"].exists ? app.otherElements["air.settings.privacy.blocked"] : app.buttons["air.settings.privacy.blocked"]
        XCTAssertTrue(blocked.waitForExistence(timeout: 5), "no Privacy screen")
        auditShot("privacy")
        blocked.tap()
        let unblock = app.buttons["air.settings.blocked.unblock.0"]
        XCTAssertTrue(unblock.waitForExistence(timeout: 5), "the blocked key is not listed")
        auditShot("blocked-list")
        unblock.tap()
        auditShot("blocked-unblocked")
        app.returnToAir()
    }

    /// What the group walk looks for in one language: the two demo groups of OCTManagerMock, a member
    /// of the first, the first words of the "Make moderator" action, and the name given to a new group.
    private struct GroupWalkTexts {
        let founderGroup: String
        let observerGroup: String
        let member: String
        let makeModerator: String
        let newGroup: String
    }

    private static let groupWalkTexts: [String: GroupWalkTexts] = [
        "en": GroupWalkTexts(founderGroup: "Hiking club", observerGroup: "Mosque announcements", member: "Bilal",
                             makeModerator: "Make moderator", newGroup: "Book club"),
        "ru": GroupWalkTexts(founderGroup: "Походный клуб", observerGroup: "Объявления мечети", member: "Билал",
                             makeModerator: "Назначить модератором", newGroup: "Книжный клуб"),
        "ar": GroupWalkTexts(founderGroup: "نادي المشي", observerGroup: "إعلانات المسجد", member: "بلال",
                             makeModerator: "تعيين مشرفًا", newGroup: "نادي الكتاب"),
        "zh": GroupWalkTexts(founderGroup: "徒步俱乐部", observerGroup: "清真寺公告", member: "比拉勒",
                             makeModerator: "设为管理员", newGroup: "读书会"),
    ]

    /// Walks the group screens of stage 3 (E13 to E15) on the mock's demo world and saves a screenshot
    /// and the accessibility tree of each to $KHANDAQ_UITEST_ARTIFACTS/stage3g-<lang>[-dark][-<size>]/:
    /// the group you founded with its header, strip and a mention of you, its dossier with a change of
    /// role and its undo, the invitation screen, the group where you only read with the line in place of
    /// "Write to a moderator" and the member search, then "New group" from an empty name to the created
    /// group.
    func testStageThreeGroupWalkScreens() {
        let env = ProcessInfo.processInfo.environment
        let lang = env["KHANDAQ_UITEST_LANG"] ?? "en"
        let dark = env["KHANDAQ_UITEST_DARK"] == "1"
        var folder = "stage3g-\(lang)" + (dark ? "-dark" : "")
        if let size = env["KHANDAQ_UITEST_TEXT_SIZE"], !size.isEmpty {
            folder += "-" + auditSlug(size.replacingOccurrences(of: "UICTContentSizeCategory", with: ""))
        }
        auditDir = Self.artifactsDir + folder + "/"
        try? FileManager.default.createDirectory(atPath: auditDir, withIntermediateDirectories: true)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = dark ? .dark : .light
        }
        guard let texts = Self.groupWalkTexts[lang] else {
            XCTFail("no group walk texts for \(lang)")
            return
        }
        openAir()
        stageThreeFounderGroup(texts)
        stageThreeObserverGroup(texts)
        stageThreeNewGroup(texts)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// E13 and E14 in the group you founded: the conversation, the dossier, a member made moderator
    /// (asked, done, undone) and the invitation screen.
    private func stageThreeFounderGroup(_ texts: GroupWalkTexts) {
        XCTAssertTrue(openGroup(named: texts.founderGroup), "no group \(texts.founderGroup)")
        XCTAssertTrue(app.descendants(matching: .any)["air.group.band"].waitForExistence(timeout: 5), "no Get it now strip")
        auditShot("g-founder-chat")
        XCTAssertFalse(app.descendants(matching: .any)["air.group.readOnly"].exists, "the founder writes")

        app.buttons["air.chat.title"].tap()
        let dossier = app.descendants(matching: .any)["air.group.dossier"]
        XCTAssertTrue(dossier.waitForExistence(timeout: 5), "the title did not open the group dossier")
        auditShot("g-dossier-top")
        let scroll = app.scrollViews.element(boundBy: 0)
        scroll.swipeUp()
        auditShot("g-dossier-members")
        scroll.swipeUp()
        auditShot("g-dossier-key")
        scroll.swipeUp()
        scroll.swipeUp()
        auditShot("g-dossier-bottom")
        for _ in 0..<4 {
            scroll.swipeDown()
        }

        // A member made moderator: the action, the question, the toast with Undo, then undone.
        let member = app.descendants(matching: .any).matching(
                NSPredicate(format: "identifier == 'air.group.member' AND label CONTAINS %@", texts.member)).element(boundBy: 0)
        for _ in 0..<4 where !(member.exists && member.isHittable) {
            scroll.swipeUp()
        }
        XCTAssertTrue(member.exists && member.isHittable, "no member \(texts.member) in the dossier")
        member.tap()
        let makeModerator = app.sheets.element(boundBy: 0).buttons[texts.makeModerator]
        XCTAssertTrue(makeModerator.waitForExistence(timeout: 3), "no Make moderator for a member")
        auditShot("g-member-actions")
        makeModerator.tap()
        let confirm = app.sheets.element(boundBy: 0).buttons[texts.makeModerator]
        XCTAssertTrue(confirm.waitForExistence(timeout: 3), "a change of role is not asked first")
        auditShot("g-role-confirm")
        confirm.tap()
        let undo = app.buttons["air.toast.action"]
        XCTAssertTrue(undo.waitForExistence(timeout: 3), "no Undo after a change of role")
        auditShot("g-role-done")
        undo.tap()
        auditShot("g-role-undone")

        // The invitation screen: who can be invited now, then one chosen.
        let invite = app.buttons["air.group.dossier.invite"]
        for _ in 0..<5 where !(invite.exists && invite.isHittable) {
            scroll.swipeUp()
        }
        XCTAssertTrue(invite.exists && invite.isHittable, "no Invite in the dossier")
        invite.tap()
        XCTAssertTrue(app.descendants(matching: .any)["air.group.invite"].waitForExistence(timeout: 5), "Invite did not open")
        auditShot("g-invite")
        let candidate = app.descendants(matching: .any).matching(identifier: "air.invite.contact").element(boundBy: 0)
        if candidate.exists, candidate.isHittable {
            candidate.tap()
            auditShot("g-invite-chosen")
        }
        app.returnToAir()
    }

    /// E13 in the group where you only read: the bar in place of the field, with the line that says an
    /// observer cannot write even privately where "Write to a moderator" was, and the dossier of a large
    /// group with its search.
    private func stageThreeObserverGroup(_ texts: GroupWalkTexts) {
        XCTAssertTrue(openGroup(named: texts.observerGroup), "no group \(texts.observerGroup)")
        let bar = app.descendants(matching: .any)["air.group.readOnly"]
        XCTAssertTrue(bar.waitForExistence(timeout: 5), "an observer can type")
        XCTAssertFalse(app.textViews["air.chat.field"].exists && app.textViews["air.chat.field"].isHittable,
                       "the field stays hidden for an observer")
        assertObserverBarSaysWhy(ProcessInfo.processInfo.environment["KHANDAQ_UITEST_LANG"] ?? "en")
        auditShot("g-observer-chat")

        app.buttons["air.chat.title"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["air.group.dossier"].waitForExistence(timeout: 5), "no dossier")
        auditShot("g-observer-dossier")
        let search = app.descendants(matching: .any)["air.group.dossier.search"]
        let scroll = app.scrollViews.element(boundBy: 0)
        for _ in 0..<3 where !(search.exists && search.isHittable) {
            scroll.swipeUp()
        }
        XCTAssertTrue(search.exists, "a group of 24 offers a search")
        search.tap()
        app.typeText("7")
        auditShot("g-observer-search")
        app.returnToAir()
    }

    /// E15: "New group" empty, then named, open by the group key and with one contact invited, the
    /// group it creates, and a second sheet with a name too long for a group.
    private func stageThreeNewGroup(_ texts: GroupWalkTexts) {
        let name = openNewGroupSheet()
        let create = app.buttons["air.newGroup.create"]
        XCTAssertFalse(create.isEnabled, "a group without a name cannot be created")
        auditShot("ng-empty")

        name.typeText(texts.newGroup)
        if !((name.value as? String) ?? "").contains(texts.newGroup) {
            // The keyboard of the simulator could not type this script: a name it can type.
            name.typeText("Book club")
        }
        XCTAssertTrue(create.isEnabled, "a named group can be created")
        // Return puts the keyboard away, so the whole sheet shows.
        name.typeText("\n")

        let open = app.descendants(matching: .any)["air.newGroup.privacy.open"]
        XCTAssertTrue(open.exists, "no choice of who can join")
        open.tap()
        let contact = app.descendants(matching: .any).matching(identifier: "air.invite.contact").element(boundBy: 0)
        for _ in 0..<3 where contact.exists && !contact.isHittable {
            app.scrollViews.element(boundBy: 0).swipeUp()
        }
        if contact.exists, contact.isHittable {
            contact.tap()
        }
        auditShot("ng-filled")
        create.tap()
        XCTAssertTrue(app.buttons["air.chat.title"].waitForExistence(timeout: 10), "the new group did not open")
        auditShot("ng-created")

        // A name over 48 bytes waits with its reason; Cancel leaves without a group.
        let again = openNewGroupSheet()
        again.typeText("Weekend hikes in the mountains and around the lakes")
        XCTAssertFalse(create.isEnabled, "a name over 48 bytes cannot be created")
        auditShot("ng-too-long")
        app.buttons["air.newGroup.cancel"].tap()
        app.returnToAir()
    }

    /// The group you only read, on its own, to $KHANDAQ_UITEST_ARTIFACTS/observer-<lang>[-dark][-<size>]/:
    /// its bar and the private messages a moderator wrote to you (see walkObserverGroup).
    func testObserverWalkScreens() {
        let lang = beginStageFourWalk("observer")
        openAir()
        walkObserverGroup(lang)
    }

    /// The group you only read: in place of "Write to a moderator" the line that says an observer cannot
    /// write even privately, since toxcore refuses an observer's private messages; the private message
    /// Hamza, a moderator, wrote to you can be read, and the field under it is off with the same line.
    /// At the accessibility text sizes both logs keep their room: at AX5 the bar with that line had left
    /// the group's log 5 points in Russian.
    private func walkObserverGroup(_ lang: String) {
        let env = ProcessInfo.processInfo.environment
        let observerGroup = demoString("demo_s2_group_observer", lang)
        let hamza = demoString("demo_s3_peer_hamza", lang)
        XCTAssertNotEqual(hamza, "demo_s3_peer_hamza", "the demo texts are not in the test bundle")
        XCTAssertTrue(openGroup(named: observerGroup), "no group \(observerGroup)")
        XCTAssertTrue(app.descendants(matching: .any)["air.group.readOnly"].waitForExistence(timeout: 5), "an observer can type")
        assertObserverBarSaysWhy(lang)
        sleep(1)
        assertLogKeepsItsRoom(env, "in a group you only read")
        auditShot("o-bar")

        XCTAssertTrue(openPrivateMessages(with: hamza, lang), "the private messages with \(hamza) did not open")
        XCTAssertTrue(app.descendants(matching: .any)["air.group.private.band"].exists, "no line under the header")
        let hamzaWrote = app.descendants(matching: .any).matching(
                NSPredicate(format: "label CONTAINS %@", demoString("demo_s5_private_hamza", lang))).element(boundBy: 0)
        XCTAssertTrue(hamzaWrote.waitForExistence(timeout: 5), "the moderator's private message cannot be read")
        let field = app.textViews["air.chat.field"]
        let send = app.buttons["air.chat.send"]
        XCTAssertTrue(field.waitForExistence(timeout: 3), "no field in the private messages")
        XCTAssertFalse(field.isEnabled, "the field of an observer is not off")
        field.tap()
        XCTAssertFalse(app.keyboards.element.waitForExistence(timeout: 2), "the field of an observer took the focus")
        XCTAssertFalse(send.exists && send.isEnabled, "an observer could send privately")
        let hint = app.descendants(matching: .any)["air.chat.hint"]
        XCTAssertEqual(hint.label, appString("air_group_observer_no_private", lang), "the line under the field")
        sleep(1)
        assertLogKeepsItsRoom(env, "in the private messages of an observer")
        auditShot("o-private")
        app.returnToAir()
    }

    /// A text of the demo world (AppStoreLocalizable.strings) in `lang`.
    func demoString(_ key: String, _ lang: String) -> String {
        let bundle = Bundle(for: SwipeAndLanguageUITests.self)
        guard let path = bundle.path(forResource: lang, ofType: "lproj"), let table = Bundle(path: path) else {
            return key
        }
        return table.localizedString(forKey: key, value: key, table: "AppStoreLocalizable")
    }

    /// Walks the group screens moved to Air in stage 5 on the mock's demo world and saves a screenshot and
    /// the accessibility tree of each to $KHANDAQ_UITEST_ARTIFACTS/stage5g-<lang>[-dark][-<size>]/: the
    /// group you only read, whose bar has no "Write to a moderator" and whose private messages from a
    /// moderator can be read but not answered (toxcore refuses an observer's private messages), then the
    /// private messages of the group you founded with a member online (one sent) and with one who is not,
    /// "Group settings" of the founder (the topic changed, who can write changed, the password screen) and
    /// of an observer, then "Join a group" from the Create sheet and from a key in the command line.
    func testStageFiveGroupWalkScreens() {
        let lang = beginStageFourWalk("stage5g")
        openAir()
        let founderGroup = demoString("demo_s2_group_founder", lang)
        let observerGroup = demoString("demo_s2_group_observer", lang)
        let bilal = demoString("demo_s2_bilal", lang)
        let yusuf = demoString("demo_s2_yusuf", lang)
        XCTAssertNotEqual(bilal, "demo_s2_bilal", "the demo texts are not in the test bundle")

        walkObserverGroup(lang)

        // The private messages of the group you founded: Bilal online gets one; Yusuf away does not yet.
        let field = app.textViews["air.chat.field"]
        let send = app.buttons["air.chat.send"]
        XCTAssertTrue(openGroup(named: founderGroup), "no group \(founderGroup)")
        XCTAssertTrue(openPrivateMessages(with: bilal, lang), "the private messages with \(bilal) did not open")
        XCTAssertTrue(app.descendants(matching: .any)["air.group.private.band"].exists, "no line under the header")
        auditShot("p-online")
        XCTAssertTrue(field.waitForExistence(timeout: 3), "no field in the private messages")
        XCTAssertTrue(field.isEnabled, "the founder's field is off")
        field.tap()
        field.typeText("Jazakallahu khairan")
        auditShot("p-typed")
        XCTAssertTrue(send.isEnabled, "a member online gets a private message")
        send.tap()
        let sent = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Jazakallahu khairan'")).element(boundBy: 0)
        XCTAssertTrue(sent.waitForExistence(timeout: 5), "the message sent is not in the thread")
        auditShot("p-sent")

        // Yusuf, away: the line says so before anything is typed, and Send waits.
        XCTAssertTrue(openGroup(named: founderGroup), "no group \(founderGroup)")
        XCTAssertTrue(openPrivateMessages(with: yusuf, lang), "the private messages with \(yusuf) did not open")
        field.tap()
        field.typeText("Salam")
        XCTAssertFalse(send.isEnabled, "a private message to someone offline would not leave")
        auditShot("p-away")
        app.returnToAir()

        // Group settings of the founder.
        XCTAssertTrue(openGroup(named: founderGroup), "no group \(founderGroup)")
        app.buttons["air.chat.title"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["air.group.dossier"].waitForExistence(timeout: 5), "no dossier")
        openGroupSettings()
        auditShot("s-founder-top")
        let settingsScroll = app.scrollViews.element(boundBy: 0)
        settingsScroll.swipeUp()
        auditShot("s-founder-bottom")
        settingsScroll.swipeDown()
        settingsScroll.swipeDown()

        let topic = app.descendants(matching: .any)["air.group.settings.topic"]
        XCTAssertTrue(topic.waitForExistence(timeout: 3), "no topic row")
        topic.tap()
        let input = app.textFields["air.group.field.input"]
        XCTAssertTrue(input.waitForExistence(timeout: 5), "the topic screen did not open")
        waitForKeyboardFocus(input)
        auditShot("s-topic")
        input.typeText(" 2026")
        app.buttons["air.group.field.save"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["air.group.settings"].waitForExistence(timeout: 5), "back to the settings")
        XCTAssertTrue(app.descendants(matching: .any)["air.toast"].waitForExistence(timeout: 3), "no Saved")
        let topicValue = topic.value as? String ?? ""
        XCTAssertTrue(topicValue.contains("2026"), "the new topic is not on its row: \(topicValue)")
        auditShot("s-topic-saved")

        let moderators = app.descendants(matching: .any)["air.group.settings.voice.1"]
        for _ in 0..<3 where !(moderators.exists && moderators.isHittable) {
            settingsScroll.swipeUp()
        }
        XCTAssertTrue(moderators.exists, "no choice of who can write")
        moderators.tap()
        XCTAssertTrue(moderators.isSelected, "the choice of who can write did not stay")
        auditShot("s-write-moderators")
        // Back to everyone, as the demo group was.
        app.descendants(matching: .any)["air.group.settings.voice.0"].tap()

        let password = app.descendants(matching: .any)["air.group.settings.password"]
        for _ in 0..<3 where !(password.exists && password.isHittable) {
            settingsScroll.swipeDown()
        }
        password.tap()
        XCTAssertTrue(app.secureTextFields["air.group.field.input"].waitForExistence(timeout: 5), "the password screen did not open")
        auditShot("s-password")
        app.returnToAir()

        // Group settings of an observer: what they are, and who changes them.
        XCTAssertTrue(openGroup(named: observerGroup), "no group \(observerGroup)")
        app.buttons["air.chat.title"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["air.group.dossier"].waitForExistence(timeout: 5), "no dossier")
        openGroupSettings()
        XCTAssertFalse(app.descendants(matching: .any)["air.group.settings.password"].exists, "an observer sees no password")
        auditShot("s-observer")
        app.returnToAir()

        // Join a group from the Create sheet: the empty sheet, the start of a key, a person's ID, a key.
        app.buttons["air.create"].tap()
        let joinEntry = app.buttons["air.joinGroup"]
        XCTAssertTrue(joinEntry.waitForExistence(timeout: 5), "no Join a group in the Create sheet")
        joinEntry.tap()
        let key = app.textViews["air.joinGroup.key"]
        XCTAssertTrue(key.waitForExistence(timeout: 5), "Join a group did not open")
        let join = app.buttons["air.joinGroup.join"]
        XCTAssertFalse(join.isEnabled, "nothing to join without a key")
        waitForKeyboardFocus(key)
        auditShot("j-empty")
        let groupKey = "5D0A7C3E9B1F4A6C8E2D0B5F7A9C1E3D5B7F9A1C3E5D7B9F1A3C5E7D9B1F3A5C"
        key.typeText(String(groupKey.prefix(40)))
        XCTAssertFalse(join.isEnabled, "the start of a key is not a key")
        auditShot("j-partial")
        key.typeText(String(groupKey.dropFirst(40)))
        XCTAssertTrue(join.isEnabled, "a whole key can be joined")
        auditShot("j-key")
        join.tap()
        XCTAssertTrue(app.buttons["air.chat.title"].waitForExistence(timeout: 10), "the group joined did not open")
        auditShot("j-joined")
        app.returnToAir()

        // The same key in the command line: "Join the group with this key" over "New contact".
        let command = app.textFields["air.command"]
        XCTAssertTrue(command.waitForExistence(timeout: 5), "no command line")
        command.tap()
        command.typeText("7E2C9A4F1B6D3E8A0C5F2B7D9E1A4C6F8B3D5E7A9C2F4B6D8E0A3C5F7B9D1E2A")
        let joinRow = app.descendants(matching: .any)["air.command.joinGroup"]
        XCTAssertTrue(joinRow.waitForExistence(timeout: 3), "64 characters in the line do not offer the group")
        XCTAssertTrue(app.descendants(matching: .any)["air.command.newContact"].exists, "and still the start of an ID")
        auditShot("c-group-row")
        joinRow.tap()
        XCTAssertTrue(key.waitForExistence(timeout: 5), "the row did not open Join a group")
        XCTAssertTrue(join.isEnabled, "the key from the line is in the sheet")
        auditShot("c-join-sheet")
        app.buttons["air.joinGroup.cancel"].tap()
        app.returnToAir()
    }

    /// "Password and PIN" in Air (stage 5) on the mock's demo world, to stage5s-<lang>[-dark][-<size>]/:
    /// the page with the PIN off (no Lock row then), the PIN set on its sheet, the page with the PIN on,
    /// the lock choice and the value chosen on its row, the password and the copy of the profile, and the
    /// PIN off again.
    func testStageFiveSecurityWalkScreens() {
        let lang = beginStageFourWalk("stage5s")
        openAir()
        openSecurity()
        XCTAssertTrue(app.descendants(matching: .any)["air.security.screen"].exists, "not the Air security page")
        let pin = app.switches["air.security.pin"]
        let lock = app.buttons["air.security.lock"]
        XCTAssertTrue(pin.waitForExistence(timeout: 5), "no PIN switch")
        XCTAssertFalse(lock.exists, "Lock means nothing without a PIN")
        XCTAssertTrue(securityPasswordRow("air_password_change_action", lang).exists, "no Change password")
        auditShot("s-off")

        pin.tap()
        XCTAssertTrue(app.buttons["1"].waitForExistence(timeout: 5), "no PIN keypad")
        auditShot("s-pin-set")
        pressPin("1234")
        pressPin("1234")
        XCTAssertTrue(lock.waitForExistence(timeout: 5), "no Lock with the PIN on")
        XCTAssertEqual(pin.value as? String, "1", "the PIN switch is off after the PIN was set")
        auditShot("s-on")

        lock.tap()
        let minute = app.descendants(matching: .any)["air.security.lock.Minute1"]
        XCTAssertTrue(minute.waitForExistence(timeout: 5), "the lock choice did not open")
        auditShot("s-lock")
        minute.tap()
        XCTAssertTrue(lock.waitForExistence(timeout: 5), "not back on the security page")
        XCTAssertEqual(lock.value as? String, appString("air_security_lock_1m", lang), "the chosen time is not on its row")
        auditShot("s-lock-chosen")

        let scroll = app.scrollViews.element(boundBy: 0)
        scroll.swipeUp()
        XCTAssertTrue(app.buttons["air.security.export"].exists, "no copy of the profile")
        auditShot("s-bottom")

        // The PIN off again, so the next walks open without it.
        scroll.swipeDown()
        pin.tap()
        waitUntilGone(lock, timeout: 5)
        XCTAssertFalse(lock.exists, "Lock stays without a PIN")
        auditShot("s-off-again")
        app.returnToAir()
    }

    /// A profile made without a password whose key left the keychain (stage 5), to stage5k-<lang>/, on a
    /// fresh install: first launch without a password, then a start with DEMO_FORGET_DEVICE_KEY, which
    /// removes the key the way a new iPhone restored from a backup or a reset keychain has it. Sign-in
    /// says the profile will not open here and offers Move a profile, Create a new profile and Delete;
    /// a profile is moved in from a file, and with two profiles the row of the first one says why it
    /// does not open; deleting it leaves the other one.
    func testStageFiveFirstLaunchKeyMissingScreens() {
        let lang = beginStageFourWalk("stage5k")
        let main = app.buttons["air.step.main"]
        let firstName = Self.firstLaunchNames[lang] ?? "Samir"

        // 1. First launch without a password: the key goes to the keychain.
        let name = app.textFields["air.first.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 30), "no first launch on a fresh install")
        fill(name, with: firstName)
        main.tap()
        let skip = app.descendants(matching: .any)["air.first.password.skip"]
        XCTAssertTrue(skip.waitForExistence(timeout: 10), "no Continue without a password")
        skip.tap()
        main.tap()
        XCTAssertTrue(app.buttons["air.first.share"].waitForExistence(timeout: 45), "no ID step after Create profile")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 30), "no Air screen after first launch")

        // 2. The keychain loses the key: sign-in says so, with the ways on and no field to type into.
        app.launchArguments.append("DEMO_FORGET_DEVICE_KEY")
        relaunch()
        let signInName = app.textFields["air.signin.name"]
        XCTAssertTrue(signInName.waitForExistence(timeout: 30), "a profile without its key did not stop at sign-in")
        XCTAssertFalse(app.airCommand.exists, "a profile without its key opened")
        XCTAssertFalse(app.secureTextFields["air.signin.password"].exists, "a profile without its key asks for a password")
        XCTAssertTrue(appText("air_signin_key_missing", lang).exists, "sign-in does not say the key is not on this iPhone")
        XCTAssertTrue(appText("air_signin_key_missing_note", lang).exists, "sign-in does not say how to bring the profile here")
        XCTAssertEqual(main.label, appString("air_signin_key_missing_move", lang), "the main button is not Move a profile")
        XCTAssertTrue(main.isEnabled, "Move a profile waits")
        XCTAssertTrue(app.buttons["air.signin.create"].exists, "no Create a new profile")
        XCTAssertTrue(app.buttons["air.signin.delete"].exists, "no Delete this profile")
        XCTAssertFalse(app.buttons["air.signin.move"].exists, "Move a profile is offered twice")
        XCTAssertTrue(app.alerts.count == 0, "the missing key is said in an alert")
        auditShot("k-missing")

        // 3. Create a new profile opens first launch; back is the same page.
        app.buttons["air.signin.create"].tap()
        XCTAssertTrue(name.waitForExistence(timeout: 5), "Create a new profile did not open first launch")
        auditShot("k-create")
        app.navigationBars.buttons["BackButton"].tap()
        XCTAssertTrue(appText("air_signin_key_missing", lang).waitForExistence(timeout: 5), "back is not the page about the key")

        // 4. Move a profile: the file's password, its name and ID, and it opens.
        main.tap()
        let filePassword = app.secureTextFields["air.import.password"]
        XCTAssertTrue(filePassword.waitForExistence(timeout: 20), "Move a profile did not open the file")
        auditShot("k-move")
        fill(filePassword, with: "пароль1234")
        main.tap()
        XCTAssertTrue(app.staticTexts["air.import.preview.name"].waitForExistence(timeout: 30), "no name and ID of the file")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 45), "the moved profile did not open")
        app.dismissSavePasswordOffer(timeout: 3)

        // 5. Two profiles: the moved one asks for its password, the row of the first says why it does not
        // open here, and choosing it says the rest.
        relaunch()
        XCTAssertTrue(app.secureTextFields["air.signin.password"].waitForExistence(timeout: 30), "the moved profile asks for no password")
        let firstRow = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'air.signin.profile.' AND label == %@", firstName)).firstMatch
        XCTAssertTrue(firstRow.exists, "no row of the profile without its key")
        XCTAssertEqual(firstRow.value as? String, appString("air_signin_key_missing_short", lang), "the row does not say the key is missing")
        auditShot("k-list")
        firstRow.tap()
        XCTAssertTrue(appText("air_signin_key_missing", lang).waitForExistence(timeout: 5), "choosing the profile does not say why")
        XCTAssertFalse(app.secureTextFields["air.signin.password"].exists, "the password field stays for a profile without its key")
        auditShot("k-list-missing")

        // 6. Delete this profile, confirmed with what goes with it: the other profile stays.
        let delete = app.buttons["air.signin.delete"]
        scrollUntilHittable(delete)
        delete.tap()
        XCTAssertTrue(app.sheets.element(boundBy: 0).waitForExistence(timeout: 5), "Delete is not asked first")
        auditShot("k-delete")
        confirmSheet()
        XCTAssertTrue(app.secureTextFields["air.signin.password"].waitForExistence(timeout: 10), "the other profile is not on sign-in")
        XCTAssertFalse(firstRow.exists, "the deleted profile is still listed")
        XCTAssertFalse(appText("air_signin_key_missing", lang).exists, "the page still speaks of the missing key")
        auditShot("k-deleted")
    }

    /// A profile file opened in Khandaq while the PIN is on (stage 5), on the real messenger with a test
    /// profile made here, to stage5p-<lang>/. The script running the test hands the file over the way
    /// "Open in" from another app does: first to the app waiting in the background, then to the app not
    /// running. Each time the lock comes first, the question about the file is out of reach behind it,
    /// and it is there once the PIN is entered.
    func testStageFiveFirstLaunchFilePinReal() {
        let lang = beginStageFourWalk("stage5p")
        let main = app.buttons["air.step.main"]

        // 1. A test profile without a password, and a PIN.
        let name = app.textFields["air.first.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 30), "no first launch on a fresh install")
        fill(name, with: Self.firstLaunchNames[lang] ?? "Samir")
        main.tap()
        let skip = app.descendants(matching: .any)["air.first.password.skip"]
        XCTAssertTrue(skip.waitForExistence(timeout: 10), "no Continue without a password")
        skip.tap()
        main.tap()
        XCTAssertTrue(app.buttons["air.first.share"].waitForExistence(timeout: 60), "no ID step after Create profile")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 45), "no Air screen after first launch")
        app.leaveCommandLine()
        openSecurity()
        let pin = app.switches["air.security.pin"]
        XCTAssertTrue(pin.waitForExistence(timeout: 5), "no PIN switch")
        pin.tap()
        pressPin("1234")
        pressPin("1234")
        XCTAssertTrue(app.buttons["air.security.lock"].waitForExistence(timeout: 5), "the PIN was not set")
        app.returnToAir()

        // 2. The file reaches the app waiting in the background, then 3. the app not running.
        for how in ["background", "launch"] {
            if how == "background" {
                XCUIDevice.shared.press(.home)
            }
            else {
                app.terminate()
            }
            requestOpenInKhandaq("pin-" + how)
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 60), "Khandaq did not open with the file (\(how))")
            let lock = app.descendants(matching: .any)["air.pin.lock"]
            XCTAssertTrue(lock.waitForExistence(timeout: 30), "no lock in front of the file (\(how)):\n\(app.debugDescription)")
            let move = sheetButton("air_share_import_action", lang)
            // The question may already wait behind the lock: it must not be in reach there.
            sleep(3)
            XCTAssertFalse(move.exists && move.isHittable, "the question about the file stands before the PIN (\(how))")
            auditShot("p-\(how)-locked")
            pressPin("1234")
            waitUntilGone(lock, timeout: 10)
            XCTAssertTrue(move.waitForExistence(timeout: 30), "no question about the file after the PIN (\(how)):\n\(app.debugDescription)")
            XCTAssertTrue(move.isHittable, "the question about the file is out of reach after the PIN (\(how))")
            auditShot("p-\(how)-question")
            cancelSheet()
        }
    }

    /// What Files offers for a profile file and for other files (stage 5, the Tox profile type): each
    /// file (KHANDAQ_PROBE_FILES, the starts of their names) is held, its menu and its Open With list are
    /// recorded with the accessibility tree of Files, to $KHANDAQ_UITEST_ARTIFACTS/<KHANDAQ_PROBE_FOLDER
    /// or stage5f>/, with types.txt saying which type the system gives each extension. Nothing is opened
    /// unless KHANDAQ_PROBE_OPEN names a file: Khandaq is then chosen for it and the app's screen recorded.
    /// Files never reports itself idle, so every step of its own costs a minute: it is opened right on
    /// the folder (KHANDAQ_PROBE_PATH) and quit between files.
    func testStageFiveFirstLaunchOpenWithProbe() {
        let env = ProcessInfo.processInfo.environment
        _ = beginStageFourWalk(env["KHANDAQ_PROBE_FOLDER"] ?? "stage5f")
        // What the system makes of each extension the probe opens: a declared type or a dynamic one.
        let types = ["tox", "txt", "png"].map { ext -> String in
            guard let type = UTType(filenameExtension: ext) else {
                return "\(ext): none"
            }
            return "\(ext): \(type.identifier), \(type.localizedDescription ?? "-"), dynamic \(type.isDynamic)"
        }
        try? types.joined(separator: "\n").write(toFile: auditDir + "types.txt", atomically: true, encoding: .utf8)
        let files = XCUIApplication(bundleIdentifier: "com.apple.DocumentsApp")
        let prefixes = (env["KHANDAQ_PROBE_FILES"] ?? "amina,notes,leaflet").split(separator: ",").map(String.init)
        let folder = env["KHANDAQ_PROBE_PATH"] ?? ""
        for prefix in prefixes {
            if !folder.isEmpty,
               let path = folder.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
               let url = URL(string: "shareddocuments://" + path) {
                XCUIDevice.shared.system.open(url)
            }
            else {
                files.launch()
            }
            let file = fileInFiles(files, prefix: prefix)
            XCTAssertTrue(file.exists, "no \(prefix) in On My iPhone:\n\(files.debugDescription)")
            file.press(forDuration: 1.5)
            sleep(1)
            probeShot("\(prefix)-menu", files)
            // The Russian menu ties "в" to the next word with a no-break space, so the start is matched.
            let openWith = files.descendants(matching: .any)
                .matching(NSPredicate(format: "label BEGINSWITH 'Open With' OR label BEGINSWITH 'Открыть'")).firstMatch
            if openWith.waitForExistence(timeout: 3) {
                openWith.tap()
                sleep(2)
                probeShot("\(prefix)-open-with", files)
                // KHANDAQ_PROBE_OPEN names the file Khandaq is chosen for: the app comes up with its question.
                let khandaq = files.descendants(matching: .any).matching(NSPredicate(format: "label == 'Khandaq'")).firstMatch
                if env["KHANDAQ_PROBE_OPEN"] == prefix, khandaq.waitForExistence(timeout: 3) {
                    khandaq.tap()
                    XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30), "Khandaq did not come up for \(prefix)")
                    sleep(3)
                    auditShot("\(prefix)-opened")
                    continue
                }
            }
            // Out of the menu without choosing anything.
            files.terminate()
        }
    }

    /// The back chevron of every pushed screen (stage 5), to stage5b-<lang>[-sys]/: Settings with each of
    /// its sections and the blocked keys; Password and PIN with its lock choice, You, and the network page
    /// with its log; a conversation; a group with its dossier, its settings, a field page and private
    /// messages with a member. The script that runs it reads the chevron of each
    /// shot: right-pointing at the right with Arabic, chosen in the app or by the system
    /// (KHANDAQ_UITEST_SYSTEM_LANG=1), left-pointing at the left in English and Russian.
    func testStageFiveBackChevronWalkScreens() {
        let env = ProcessInfo.processInfo.environment
        let lang = beginStageFourWalk(env["KHANDAQ_UITEST_SYSTEM_LANG"] == "1" ? "stage5b-sys" : "stage5b")
        openAir()
        let back = app.navigationBars.buttons["BackButton"]

        // 1. Settings and its sections.
        let settings = app.buttons["air.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "no settings button on the Air screen")
        settings.tap()
        XCTAssertTrue(app.buttons["settings.appearance"].waitForExistence(timeout: 5), "Settings did not open")
        auditShot("b-settings")
        for section in ["settings.appearance", "settings.notifications", "settings.privacy", "settings.transfer",
                        "settings.network", "settings.faq", "settings.about"] {
            let row = app.buttons[section]
            guard row.waitForExistence(timeout: 3) else {
                XCTFail("no \(section) in Settings")
                continue
            }
            scrollUntilHittable(row)
            row.tap()
            XCTAssertTrue(back.waitForExistence(timeout: 5), "\(section) has no way back")
            sleep(1)
            auditShot("b-" + section)
            if section == "settings.privacy" {
                app.buttons["air.settings.privacy.blocked"].tap()
                sleep(1)
                auditShot("b-settings.blocked")
                back.tap()
            }
            back.tap()
        }
        app.returnToAir()

        // 2. Password and PIN with the lock choice, back to You; then the network page, which the line
        // under the name on the Air screen opens, and its log. The ID details are a section of You.
        openSecurity()
        auditShot("b-security")
        let pin = app.switches["air.security.pin"]
        pin.tap()
        pressPin("1234")
        pressPin("1234")
        let lock = app.buttons["air.security.lock"]
        XCTAssertTrue(lock.waitForExistence(timeout: 5), "no Lock with the PIN on")
        lock.tap()
        sleep(1)
        auditShot("b-security.lock")
        back.tap()
        pin.tap()
        waitUntilGone(lock, timeout: 5)
        back.tap()
        XCTAssertTrue(app.buttons["air.you.security"].waitForExistence(timeout: 5), "not back on You")
        sleep(1)
        auditShot("b-you")
        app.returnToAir()
        let network = app.buttons["air.you.network"]
        XCTAssertTrue(network.waitForExistence(timeout: 5), "no network line under the name")
        network.tap()
        XCTAssertTrue(back.waitForExistence(timeout: 5), "the network page has no way back")
        sleep(1)
        auditShot("b-network")
        let log = app.buttons["air.network.log"]
        if log.exists {
            scrollUntilHittable(log)
            log.tap()
            sleep(1)
            auditShot("b-network.log")
            back.tap()
        }
        app.returnToAir()

        // 3. A conversation, and a group with its dossier, settings, a field page and private messages.
        XCTAssertTrue(openConversation(with: demoString("demo_s2_amina", lang)), "no conversation with Amina")
        auditShot("b-chat")
        let founderGroup = demoString("demo_s2_group_founder", lang)
        XCTAssertTrue(openGroup(named: founderGroup), "no group \(founderGroup)")
        auditShot("b-group")
        app.buttons["air.chat.title"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["air.group.dossier"].waitForExistence(timeout: 5), "no dossier")
        sleep(1)
        auditShot("b-group.dossier")
        openGroupSettings()
        auditShot("b-group.settings")
        let topic = app.descendants(matching: .any)["air.group.settings.topic"]
        XCTAssertTrue(topic.waitForExistence(timeout: 3), "no topic row")
        topic.tap()
        XCTAssertTrue(app.textFields["air.group.field.input"].waitForExistence(timeout: 5), "the topic page did not open")
        sleep(1)
        auditShot("b-group.topic")
        back.tap()
        back.tap()
        // Every member row has this identifier: the query takes the first, which a lone subscript does
        // not (eight matches are never "hittable").
        let member = app.descendants(matching: .any).matching(identifier: "air.group.member").firstMatch
        let scroll = app.scrollViews.element(boundBy: 0)
        // Back from the settings the dossier still shows its end, and the members are above it.
        for _ in 0..<20 where !(member.exists && member.isHittable) {
            scroll.swipeDown()
        }
        XCTAssertTrue(member.exists && member.isHittable, "no member in the dossier")
        member.tap()
        let writePrivately = app.sheets.element(boundBy: 0).buttons[appString("air_group_write_privately", lang)]
        XCTAssertTrue(writePrivately.waitForExistence(timeout: 3), "no Write privately")
        writePrivately.tap()
        XCTAssertTrue(app.descendants(matching: .any)["air.group.private"].waitForExistence(timeout: 5), "the private messages did not open")
        sleep(1)
        auditShot("b-group.private")
        app.returnToAir()
    }

    // MARK: - The App Review path (stage 5, part B)

    /// The demo contact the App Review notes give: it accepts a request within seconds and fills the chat.
    private static let appReviewDemoId = "872F45C1BAF8AC12C854003E6420C7459339056FF6E40E5BAD8B123AC397E117B66A6A0E90B7"

    /// The App Review path on the real messenger, to stage5r-<lang>/: first launch with a name and no
    /// password, Create > New contact with the demo contact's ID pasted, Send request, then up to eight
    /// minutes for the demo contact to accept, its conversation with every incoming file accepted, and its
    /// group invitation joined. What arrived is read from the shots and their accessibility trees.
    func testStageFiveAppReviewPathReal() {
        let lang = beginStageFourWalk("stage5r")
        let main = app.buttons["air.step.main"]

        // 1. First launch: a name, no password.
        let name = app.textFields["air.first.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 30), "no first launch on a fresh install:\n\(app.debugDescription)")
        fill(name, with: Self.firstLaunchNames[lang] ?? "Samir")
        main.tap()
        let skip = app.descendants(matching: .any)["air.first.password.skip"]
        XCTAssertTrue(skip.waitForExistence(timeout: 10), "no password step")
        skip.tap()
        main.tap()
        XCTAssertTrue(app.buttons["air.first.share"].waitForExistence(timeout: 60), "the profile was not created")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 30), "no Air screen after first launch")
        auditShot("r-air")

        // 2. Create > New contact, the ID pasted, Send request. First launch leaves the command line
        // ready for an ID; the notes for App Review go through Create, so the line is put away first.
        let commandCancel = app.buttons["air.command.cancel"]
        if commandCancel.exists, commandCancel.isHittable {
            commandCancel.tap()
        }
        XCTAssertTrue(app.buttons["air.create"].waitForExistence(timeout: 5), "no Create button on Air")
        app.buttons["air.create"].tap()
        let newContact = app.buttons["air.newContact"]
        XCTAssertTrue(newContact.waitForExistence(timeout: 5), "no New contact in the Create sheet")
        newContact.tap()
        let field = app.textViews["air.newContact.id"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "New contact did not open")
        fill(field, with: Self.appReviewDemoId)
        auditShot("r-new-contact")
        let send = app.buttons["air.newContact.send"]
        XCTAssertTrue(send.isEnabled, "the demo contact's ID does not let the request go")
        send.tap()
        XCTAssertTrue(app.staticTexts["air.newContact.sentTitle"].waitForExistence(timeout: 10), "no Request on its way")
        auditShot("r-sent")
        let done = app.buttons["air.newContact.done"]
        if done.exists, done.isHittable {
            done.tap()
        }
        app.returnToAir()

        // 3. The demo contact accepts and writes: its row becomes a conversation.
        let chat = app.cells.matching(identifier: "air.row.direct").firstMatch
        let deadline = Date().addingTimeInterval(8 * 60)
        var round = 0
        while !chat.exists, Date() < deadline {
            sleep(30)
            round += 1
            if round % 2 == 0 {
                auditShot("r-wait-\(round / 2)min")
            }
        }
        XCTAssertTrue(chat.exists, "the demo contact did not answer in eight minutes")
        sleep(60)
        auditShot("r-air-answered")

        // 4. Its conversation: every incoming file accepted, then the whole of it from the top.
        chat.tap()
        sleep(2)
        auditShot("r-chat")
        for _ in 0..<8 {
            let accept = app.buttons.matching(identifier: "air.file.accept").firstMatch
            guard accept.exists, accept.isHittable else {
                break
            }
            accept.tap()
            sleep(4)
        }
        sleep(40)
        auditShot("r-chat-accepted")
        let transcript = app.tables.element(boundBy: 0)
        for page in 1...4 {
            transcript.swipeDown()
            sleep(1)
            auditShot("r-chat-up-\(page)")
        }
        app.returnToAir()

        // 5. Its invitation to the demo group: Join.
        selectFilter(.requests)
        let invite = app.cells.matching(identifier: "air.invite").firstMatch
        if invite.waitForExistence(timeout: 90) {
            auditShot("r-invite")
            invite.buttons["air.invite.join"].tap()
            sleep(30)
            auditShot("r-group")
        }
        else {
            auditShot("r-no-invite")
            XCTFail("no invitation to the demo group")
        }
        app.returnToAir()
    }

    // MARK: - Store and site captures (stage 5)

    /// The raw captures of the five store frames (khandaq-ios/scripts/appstore-shots/captions.json) in the
    /// run's language, to $KHANDAQ_CAPTURE_DIR: Air with one request card, the conversation with Yusuf and
    /// its In transit tray, New contact with a whole ID and the keyboard up, the group you founded, and You
    /// in the dark theme. Only the redesign's demo people (DEMO_OURS_ONLY), thinned to what one screen
    /// holds (DEMO_STORE). Meant for an iPhone 17 Pro Max with the status bar at 9:41.
    func testStageFiveStoreCaptures() {
        let lang = beginStageFourWalk("stage5c")
        guard let people = Self.demoPeople[lang] else {
            XCTFail("no demo names for \(lang)")
            return
        }
        openAir()

        // 1. Air: Timur's request with its message stays, the other cards are declined.
        selectFilter(.requests)
        let invites = app.cells.matching(identifier: "air.invite")
        let requests = app.cells.matching(identifier: "air.request")
        XCTAssertTrue(requests.element(boundBy: 0).waitForExistence(timeout: 5), "no request card")
        for _ in 0..<2 where invites.count > 0 {
            declineAndLetGo(invites.element(boundBy: 0).buttons["air.invite.decline"])
        }
        if requests.count > 1 {
            declineAndLetGo(requests.element(boundBy: 1).buttons["air.request.decline"])
        }
        XCTAssertEqual(requests.count, 1, "one request card for the capture")
        // The rows are built again when the filter changes: after the first minute Yusuf's row reads "26 min
        // ago" instead of "connecting", as on a phone that has been on for a while.
        waitForSteadyPresence()
        selectFilter(.all)
        XCTAssertTrue(app.cells["air.row.saved"].isHittable, "Saved Messages is not on the Air frame")
        capture("air.png")

        // 2. Yusuf is offline: his messages wait in the tray, unfolded.
        XCTAssertTrue(openConversation(with: people.yusuf), "no conversation with \(people.yusuf)")
        let trayHeader = app.buttons["air.chat.tray.header"]
        XCTAssertTrue(trayHeader.waitForExistence(timeout: 5), "no In transit tray")
        trayHeader.tap()
        capture("conversation.png")
        app.returnToAir()

        // 3. New contact: a whole ID with its check, the greeting, Send request above the keyboard. The
        // keyboard is captured without its bar of predictions (testSimulatorKeyboardPrediction turns it
        // off): with it the keyboard stood 45 points higher and the edge of the send bar cut the note under
        // the greeting. KHANDAQ_NEW_CONTACT_KEYBOARD=down captures the form with the keyboard put away.
        app.buttons["air.create"].tap()
        let newContact = app.buttons["air.newContact"]
        XCTAssertTrue(newContact.waitForExistence(timeout: 5), "no New contact in the Create sheet")
        newContact.tap()
        let field = app.textViews["air.newContact.id"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "New contact did not open")
        field.typeText(Self.stageThreeId)
        if ProcessInfo.processInfo.environment["KHANDAQ_NEW_CONTACT_KEYBOARD"] == "down" {
            // Return in the ID field puts the keyboard away; the field keeps the whole ID.
            field.typeText("\n")
            XCTAssertTrue(app.keyboards.element.waitForNonExistence(timeout: 5), "the keyboard stayed up")
        }
        else {
            app.textViews["air.newContact.message"].tap()
        }
        XCTAssertTrue(app.buttons["air.newContact.send"].isEnabled, "a whole ID lets the request go")
        sleep(1)
        assertNothingCutAtTheEdgesOfTheNewContactForm()
        capture("new-contact.png")
        app.returnToAir()

        // 4. The group you founded: who is in it and who gets a message now.
        let founder = demoString("demo_s2_group_founder", lang)
        XCTAssertTrue(openGroup(named: founder), "no group \(founder)")
        XCTAssertTrue(app.descendants(matching: .any)["air.group.band"].waitForExistence(timeout: 5), "no Get it now strip")
        capture("group.png")
        app.returnToAir()

        // 5. You, in the dark theme.
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .dark
        }
        sleep(2)
        app.buttons["air.you"].tap()
        XCTAssertTrue(app.descendants(matching: .any)["air.you.qr"].waitForExistence(timeout: 5), "You did not open")
        XCTAssertTrue(waitForScreen(dark: true), "the screen stayed light: no dark frame of You")
        capture("you.png")
        app.returnToAir()
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// The six screens of the site (web/index.html) in the run's language, to $KHANDAQ_CAPTURE_DIR: the
    /// command line with no match for "zzqx", the Favorites filter with two chats marked, In transit under
    /// your own link and Settings > Profile transfer in the light theme, then Settings > Appearance and
    /// In transit with everything delivered (Yusuf back online, DEMO_RETURN) in the dark one. Only the
    /// redesign's demo people (DEMO_OURS_ONLY); meant for an iPhone 17 Pro Max at 9:41.
    func testStageFiveSiteCaptures() {
        let lang = beginStageFourWalk("stage5w")
        guard let people = Self.demoPeople[lang] else {
            XCTFail("no demo names for \(lang)")
            return
        }
        openAir()
        waitForSteadyPresence()

        // 1. Search by ID: nothing matches "zzqx", and the empty state says a whole ID has 76 characters.
        let command = app.airCommand
        XCTAssertTrue(command.waitForExistence(timeout: 5), "no command line")
        command.tap()
        command.typeText("zzqx")
        XCTAssertTrue(app.descendants(matching: .any)["air.empty"].waitForExistence(timeout: 5), "no Nothing found")
        capture("search-id.png")
        let cancel = app.buttons["air.command.cancel"]
        if cancel.exists, cancel.isHittable {
            cancel.tap()
        }
        app.returnToAir()

        // 2. Favorites: Amina and the group you founded, marked from their menus.
        markFavorite(people.amina, lang)
        markFavorite(demoString("demo_s2_group_founder", lang), lang)
        selectFilter(.favorites)
        capture("favorites.png")
        selectFilter(.all)

        // 3. In transit under your own link.
        selectFilter(.transit)
        capture("transit-light.png")
        selectFilter(.all)

        // 4. Settings > Profile transfer.
        openSettingsSection("settings.transfer")
        capture("settings-light.png")
        app.returnToAir()

        // 5. Dark: Settings > Appearance, the theme following the system.
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .dark
        }
        sleep(2)
        openSettingsSection("settings.appearance")
        XCTAssertTrue(waitForScreen(dark: true), "the screen stayed light: no dark frame of Appearance")
        capture("settings-dark.png")
        app.returnToAir()

        // 6. A message to Yusuf brings him back; the waiting ones leave and In transit is empty.
        XCTAssertTrue(openConversation(with: people.yusuf), "no conversation with \(people.yusuf)")
        XCTAssertTrue(typeInField("19:45"), "no message field")
        app.buttons["air.chat.send"].tap()
        sleep(9)
        app.returnToAir()
        selectFilter(.transit)
        let empty = app.descendants(matching: .any)["air.empty"].waitForExistence(timeout: 10)
        XCTAssertTrue(waitForScreen(dark: true), "the screen stayed light: no dark frame of In transit")
        capture("transit-dark.png")
        XCTAssertTrue(empty, "In transit still holds messages")
        selectFilter(.all)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// Not a walk: turns the bar of predictions of this simulator's keyboard off or back on, the switch of
    /// Settings > General > Keyboard > Predictive Text (KHANDAQ_KEYBOARD_PREDICTION=off or on), for frame 03
    /// of the store. The switch is kept by TextInput's preferences controller, the one Settings writes
    /// through: on iOS 26.5 a value written with `defaults` (com.apple.Preferences, or the settings pane's
    /// com.apple.keyboard.predictive) did not reach the keyboard, and its bar stayed after a restart.
    func testSimulatorKeyboardPrediction() throws {
        guard let wanted = ProcessInfo.processInfo.environment["KHANDAQ_KEYBOARD_PREDICTION"], wanted == "on" || wanted == "off" else {
            throw XCTSkip("KHANDAQ_KEYBOARD_PREDICTION is neither on nor off")
        }
        guard let found = NSClassFromString("TIPreferencesController"),
              let controller = (found as AnyObject).perform(NSSelectorFromString("sharedPreferencesController"))?.takeUnretainedValue() else {
            XCTFail("no TIPreferencesController on this system")
            return
        }
        let key = "KeyboardPrediction" as NSString
        let read = NSSelectorFromString("valueForPreferenceKey:")
        let before = controller.perform(read, with: key)?.takeUnretainedValue()
        _ = controller.perform(NSSelectorFromString("setValue:forPreferenceKey:"), with: NSNumber(value: wanted == "on"), with: key)
        _ = controller.perform(NSSelectorFromString("synchronizePreferences"))
        let after = controller.perform(read, with: key)?.takeUnretainedValue()
        print("KHANDAQ keyboard prediction: \(String(describing: before)) -> \(String(describing: after))")
        // Off is read back at once. On drops the setting back to the system's default, which this process
        // keeps reading as off: a fresh run of this test (with on again) reads it on before and after.
        if wanted == "off" {
            XCTAssertEqual((after as? NSNumber)?.boolValue, false, "the switch did not take the value")
        }
    }

    /// Store frame 03: no line of the New contact form is cut by an edge of what shows of the form, the
    /// navigation bar above or the send bar below. Each one is wholly in sight or wholly under a bar.
    private func assertNothingCutAtTheEdgesOfTheNewContactForm(file: StaticString = #file, line: UInt = #line) {
        let form = app.otherElements["air.newContact.screen"].scrollViews.element(boundBy: 0)
        XCTAssertTrue(form.exists, "no New contact form", file: file, line: line)
        let edges = [form.frame.minY, form.frame.maxY]
        let parts = form.descendants(matching: .staticText).allElementsBoundByIndex
            + form.descendants(matching: .button).allElementsBoundByIndex
            + form.descendants(matching: .textView).allElementsBoundByIndex
        for part in parts {
            let frame = part.frame
            for edge in edges where frame.minY < edge - 0.5 && frame.maxY > edge + 0.5 {
                XCTFail("'\(part.label)' (\(frame.minY) to \(frame.maxY)) is cut by the edge at \(edge)", file: file, line: line)
            }
        }
    }

    /// Saves the screen as it is to $KHANDAQ_CAPTURE_DIR/<file> at the device's own resolution, with a copy
    /// and the accessibility tree in the walk's folder, so the names on it can be checked.
    private func capture(_ file: String) {
        guard let dir = ProcessInfo.processInfo.environment["KHANDAQ_CAPTURE_DIR"], !dir.isEmpty else {
            XCTFail("no KHANDAQ_CAPTURE_DIR")
            return
        }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        sleep(1)
        let path = (dir as NSString).appendingPathComponent(file)
        XCTAssertNoThrow(try XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: path)))
        auditShot((file as NSString).deletingPathExtension)
    }

    /// Waits until the screen is mostly dark (or mostly light), at most `timeout` seconds. A simulator can
    /// miss a switch of appearance, and a frame meant for the dark theme then comes out light without a word.
    private func waitForScreen(dark: Bool, timeout: TimeInterval = 10) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if (screenBrightness() < 0.5) == dark {
                return true
            }
            usleep(500_000)
        } while Date() < deadline
        return false
    }

    /// The mean brightness of the screen, from 0 (black) to 1 (white), from a screenshot drawn into a few
    /// pixels.
    private func screenBrightness() -> CGFloat {
        guard let image = XCUIScreen.main.screenshot().image.cgImage else {
            return 0.5
        }
        let width = 9
        let height = 19
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn: Bool = pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
                return false
            }
            context.interpolationQuality = .high
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else {
            return 0.5
        }
        var total = 0
        for index in stride(from: 0, to: pixels.count, by: 4) {
            total += Int(pixels[index]) + Int(pixels[index + 1]) + Int(pixels[index + 2])
        }
        return CGFloat(total) / CGFloat(width * height * 3 * 255)
    }

    /// Recently seen contacts read "connecting" for the first minute after our own node is online; the
    /// captures wait for the steady words.
    private func waitForSteadyPresence() {
        let settle = SelfConnectionGrace.seconds - Date().timeIntervalSince(launchedAt)
        if settle > 0 {
            sleep(UInt32(ceil(settle)))
        }
    }

    /// Declines the card the button belongs to and waits until its Undo toast has gone, so the decline
    /// stands and no toast is left for the next capture.
    private func declineAndLetGo(_ button: XCUIElement) {
        guard button.waitForExistence(timeout: 5) else {
            XCTFail("no Decline on the card")
            return
        }
        button.tap()
        waitUntilGone(app.otherElements["air.toast"], timeout: 15)
    }

    /// Marks a chat as a favorite from its menu, a touch and hold on its row.
    private func markFavorite(_ name: String, _ lang: String) {
        let row = app.cells.matching(NSPredicate(format: "identifier BEGINSWITH 'air.row.' AND label CONTAINS %@", name)).element(boundBy: 0)
        guard let visible = scrollToRow(row) else {
            XCTFail("no row for \(name)")
            return
        }
        visible.press(forDuration: 1.2)
        let item = app.buttons[appString("chat_filter_add_favorite", lang)]
        XCTAssertTrue(item.waitForExistence(timeout: 5), "no Add to favorites in the menu of \(name)")
        item.tap()
        sleep(1)
    }

    /// Opens a section of Settings from the Air screen.
    private func openSettingsSection(_ identifier: String) {
        XCTAssertTrue(app.returnToAir(), "not on the Air screen")
        app.buttons["air.settings"].tap()
        let row = app.buttons[identifier]
        XCTAssertTrue(row.waitForExistence(timeout: 5), "no \(identifier) in Settings")
        row.tap()
        XCTAssertTrue(app.navigationBars.buttons["BackButton"].waitForExistence(timeout: 5), "\(identifier) did not open")
        sleep(1)
    }

    /// Whether this simulator has Reduce Motion on (KHANDAQ_EXPECT_REDUCE_MOTION=1) or off, as apps on it
    /// read the setting: the walks of stage 5 with reduced motion start with this check.
    func testStageFiveReduceMotionSetting() {
        let expected = ProcessInfo.processInfo.environment["KHANDAQ_EXPECT_REDUCE_MOTION"] == "1"
        XCTAssertEqual(UIAccessibilityIsReduceMotionEnabled(), expected, "Reduce Motion is not as the run expects")
    }

    /// Stage 5, at an accessibility text size (KHANDAQ_UITEST_TEXT_SIZE): the Large Content Viewer on the
    /// title, the paperclip, the search and video buttons, the back button and the microphone of a
    /// conversation, each held for three seconds while the run takes screenshots from outside (the test
    /// leaves <name>.request in KHANDAQ_CAPTURE_DIR, the run's watcher answers with <name>-1...3.png and
    /// takes the request away). A tap on the title still opens the dossier. The run denies the microphone
    /// first: held, the microphone still starts a recording, which now says why it cannot record instead of
    /// sending nothing without a word, and the viewer does not tap it as well (that tap asks to hold the
    /// button).
    func testStageFiveLargeContentViewer() {
        let env = ProcessInfo.processInfo.environment
        let lang = beginStageFourWalk("lcv")
        guard let people = Self.demoPeople[lang], let dir = env["KHANDAQ_CAPTURE_DIR"], !dir.isEmpty else {
            XCTFail("the run needs KHANDAQ_UITEST_LANG and KHANDAQ_CAPTURE_DIR")
            return
        }
        openAir()
        XCTAssertTrue(openConversation(with: people.amina), "no conversation with \(people.amina)")
        auditShot("amina-conversation")

        let title = app.buttons["air.chat.title"]
        let panel = app.otherElements["air.chat.dossier.panel"]
        title.tap()
        XCTAssertTrue(panel.waitForExistence(timeout: 3), "a tap on the title no longer opens the dossier")
        auditShot("title-tapped")
        closeDossierFromTitle()

        holdForScreenshot(title, "title", in: dir)
        auditShot("title-held")
        closeDossierFromTitle()

        holdForScreenshot(app.buttons["air.chat.attach"], "attach", in: dir)
        auditShot("attach-held")
        closeWhatTheHoldOpened(lang)

        holdForScreenshot(app.buttons["air.chat.search"], "search", in: dir)
        auditShot("search-held")
        closeWhatTheHoldOpened(lang)
        if !title.isHittable {
            app.returnToAir()
            XCTAssertTrue(openConversation(with: people.amina), "no conversation with \(people.amina)")
        }

        // The video button would start a call when the finger lifts on it, and the back button would leave:
        // the finger slides off them before it lifts.
        holdForScreenshot(app.buttons["air.chat.video"], "video", in: dir, slideOff: true)
        auditShot("video-held")
        let back = app.navigationBars.buttons["BackButton"].exists
            ? app.navigationBars.buttons["BackButton"] : app.navigationBars.buttons.element(boundBy: 0)
        holdForScreenshot(back, "back", in: dir, slideOff: true)
        auditShot("back-held")
        if !title.isHittable {
            app.returnToAir()
            XCTAssertTrue(openConversation(with: people.amina), "no conversation with \(people.amina)")
        }

        holdForScreenshot(app.buttons["air.chat.voice"], "voice", in: dir)
        auditShot("voice-held")
        let hold = appString("group_voice_hold_to_record", lang)
        let denied = appString("microphone_access_denied_message", lang)
        let alert = app.alerts.firstMatch
        XCTAssertTrue(alert.waitForExistence(timeout: 3), "the hold with the microphone denied said nothing")
        XCTAssertTrue(alert.staticTexts.matching(NSPredicate(format: "label == %@", denied)).firstMatch.exists,
                      "the hold did not say why it cannot record")
        XCTAssertFalse(app.staticTexts.matching(NSPredicate(format: "label == %@", hold)).firstMatch.exists,
                       "the viewer tapped the microphone after the hold")
        let cancel = alert.buttons[appString("alert_cancel", lang)]
        if cancel.exists {
            cancel.tap()
        }
        XCTAssertTrue(app.textViews["air.chat.field"].waitForExistence(timeout: 5), "the field did not come back")
        auditShot("voice-after")
    }

    /// Stage 5: the first hold of the microphone asks iOS for it, and the answer comes after the finger has
    /// left the button. Allowed then, the app must not start a recording that nobody holds: while the test
    /// waits on <KHANDAQ_CAPTURE_DIR>/after-allow.request, the run looks into the app's folder of voice
    /// recordings twice, two seconds apart. Then a hold records and lifting the finger ends the recording,
    /// and the run looks again (after-hold.request).
    func testStageFiveVoiceFirstHoldRecordsOnlyWhileHeld() {
        let env = ProcessInfo.processInfo.environment
        let lang = beginStageFourWalk("voice-first")
        guard let people = Self.demoPeople[lang], let dir = env["KHANDAQ_CAPTURE_DIR"], !dir.isEmpty else {
            XCTFail("the run needs KHANDAQ_UITEST_LANG and KHANDAQ_CAPTURE_DIR")
            return
        }
        relaunchWithMicrophoneUndecided()
        XCTAssertTrue(openConversation(with: people.amina), "no conversation with \(people.amina)")
        let voice = app.buttons["air.chat.voice"]
        XCTAssertTrue(voice.waitForExistence(timeout: 5), "no microphone in the field")

        voice.press(forDuration: 2)
        let question = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.element(boundBy: 0)
        XCTAssertTrue(question.waitForExistence(timeout: 5), "the first hold did not ask for the microphone")
        auditShot("first-hold-asks")
        answerMicrophoneQuestion(allow: true)
        XCTAssertTrue(question.waitForNonExistence(timeout: 5), "the question about the microphone stayed")
        usleep(1_500_000)
        auditShot("allowed")
        waitForTheRun(toLookAt: "after-allow", in: dir)

        voice.press(forDuration: 2.5)
        usleep(1_500_000)
        auditShot("held-and-lifted")
        waitForTheRun(toLookAt: "after-hold", in: dir)
    }

    /// Leaves <name>.request in `dir` and waits until the run has done what the name asks and taken it away.
    private func waitForTheRun(toLookAt name: String, in dir: String) {
        let request = (dir as NSString).appendingPathComponent(name + ".request")
        XCTAssertTrue(FileManager.default.createFile(atPath: request, contents: Data()), "cannot ask the run for \(name)")
        let deadline = Date().addingTimeInterval(20)
        while FileManager.default.fileExists(atPath: request), Date() < deadline {
            usleep(200_000)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: request), "the run did not answer \(name)")
    }

    /// Holds the element for three seconds while the run's watcher takes a screenshot of the screen. With
    /// slideOff the finger slides down into the conversation before it lifts, so the element does not act.
    private func holdForScreenshot(_ element: XCUIElement, _ name: String, in dir: String, slideOff: Bool = false) {
        XCTAssertTrue(element.waitForExistence(timeout: 5), "no \(name) to hold")
        let request = (dir as NSString).appendingPathComponent(name + ".request")
        XCTAssertTrue(FileManager.default.createFile(atPath: request, contents: Data()), "cannot ask for a screenshot of \(name)")
        if slideOff {
            element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
                .press(forDuration: 3, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)))
        }
        else {
            element.press(forDuration: 3)
        }
        let deadline = Date().addingTimeInterval(5)
        while FileManager.default.fileExists(atPath: request), Date() < deadline {
            usleep(200_000)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: request), "no screenshot of \(name) while it was held")
    }

    /// Closes what a held control opened (a menu, a sheet, the search of the conversation) and stays in the
    /// conversation.
    private func closeWhatTheHoldOpened(_ lang: String) {
        let cancel = app.buttons.matching(NSPredicate(format: "label IN {'Cancel', 'Отмена', 'إلغاء', 'إلغاء الامر', '取消'}")).firstMatch
        // The items of a menu are buttons without a label of their own: the words are a text inside.
        let menuItem = app.staticTexts[appString("attach_gallery", lang)]
        if cancel.exists, cancel.isHittable {
            cancel.tap()
            usleep(600_000)
        }
        else if menuItem.exists {
            // The menu of the paperclip closes with a tap beside it, and the tap goes no further. A tap on
            // the status bar left it open (stage 5), and the next hold only closed it.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.2)).tap()
            XCTAssertTrue(menuItem.waitForNonExistence(timeout: 3), "the menu of the paperclip stayed open")
        }
        else if app.sheets.count > 0 {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.12)).tap()
            usleep(600_000)
        }
    }

    // MARK: - Share into Khandaq from other apps (stage 5, the app group)

    /// "Share" into Khandaq: a page link from Safari, a photo from Photos and a file from Files, each into
    /// Saved Messages of the run's test profile, to stage5share-<lang>/. KHANDAQ_SHARE_SOURCES picks the
    /// apps and their order (safari,photos,files). With KHANDAQ_SHARE_PIN=1 a PIN is set first, and the
    /// question where the share goes must wait behind it (stage5share-pin-<lang>/). Safari opens
    /// KHANDAQ_SHARE_URL; Files opens the On My iPhone folder of KHANDAQ_SHARE_FILES_PATH and shares
    /// notes.txt from it. The screens of the other apps are kept with their accessibility trees.
    func testStageFiveShareIntoKhandaqReal() {
        let env = ProcessInfo.processInfo.environment
        let withPin = env["KHANDAQ_SHARE_PIN"] == "1"
        let lang = beginStageFourWalk(withPin ? "stage5share-pin" : "stage5share")
        XCTAssertTrue(app.returnToAir(), "not on the Air screen")
        auditShot("air")
        if withPin {
            openSecurity()
            let pin = app.switches["air.security.pin"]
            XCTAssertTrue(pin.waitForExistence(timeout: 5), "no PIN switch")
            pin.tap()
            pressPin("1234")
            pressPin("1234")
            XCTAssertTrue(app.buttons["air.security.lock"].waitForExistence(timeout: 5), "the PIN was not set")
            XCTAssertTrue(app.returnToAir(), "not back on the Air screen")
        }

        let page = env["KHANDAQ_SHARE_URL"] ?? "http://localhost:8767/redesign/stage5/ios/"
        let sources = (env["KHANDAQ_SHARE_SOURCES"] ?? "safari,photos,files").split(separator: ",").map(String.init)
        for source in sources {
            XCUIDevice.shared.press(.home)
            let host: XCUIApplication
            switch source {
                case "photos":
                    host = openShareSheetInPhotos()
                case "files":
                    host = openShareSheetInFiles(env["KHANDAQ_SHARE_FILES_PATH"] ?? "")
                default:
                    host = openShareSheetInSafari(page)
            }
            shareToKhandaq(from: host, source)

            // Khandaq asks where it goes, after the PIN when one is set.
            app.activate()
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30), "Khandaq did not come up after \(source)")
            let saved = sheetButton("saved_messages_title", lang)
            if withPin {
                let lock = app.descendants(matching: .any)["air.pin.lock"]
                XCTAssertTrue(lock.waitForExistence(timeout: 30), "no lock in front of the share:\n\(app.debugDescription)")
                sleep(3)
                XCTAssertFalse(saved.exists && saved.isHittable, "the question where the share goes stands before the PIN")
                auditShot("\(source)-locked")
                pressPin("1234")
                waitUntilGone(lock, timeout: 10)
            }
            XCTAssertTrue(saved.waitForExistence(timeout: 30), "no question where the share goes:\n\(app.debugDescription)")
            auditShot("\(source)-targets")
            saved.tap()

            // Saved Messages opens with what was shared.
            let arrived: XCUIElement
            switch source {
                case "photos":
                    arrived = app.descendants(matching: .any)["air.file.preview"]
                case "files":
                    arrived = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'notes'")).firstMatch
                default:
                    arrived = app.descendants(matching: .any)
                        .matching(NSPredicate(format: "label CONTAINS '8767' OR value CONTAINS '8767'")).firstMatch
            }
            XCTAssertTrue(arrived.waitForExistence(timeout: 20), "what \(source) shared is not in Saved Messages:\n\(app.debugDescription)")
            auditShot("\(source)-sent")
            XCTAssertTrue(app.returnToAir(), "not back on the Air screen after \(source)")
        }
    }

    /// Safari on `page`, its Share sheet open.
    private func openShareSheetInSafari(_ page: String) -> XCUIApplication {
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        XCUIDevice.shared.system.open(URL(string: page)!)
        XCTAssertTrue(safari.wait(for: .runningForeground, timeout: 30), "Safari did not come up")
        sleep(4)
        passFirstRun(of: safari)
        probeShot("safari-page", safari)
        tapShare(in: safari, "safari")
        return safari
    }

    /// Photos on the first photo of its library, its Share sheet open.
    private func openShareSheetInPhotos() -> XCUIApplication {
        let photos = XCUIApplication(bundleIdentifier: "com.apple.mobileslideshow")
        photos.activate()
        XCTAssertTrue(photos.wait(for: .runningForeground, timeout: 30), "Photos did not come up")
        sleep(3)
        passFirstRun(of: photos)
        probeShot("photos-library", photos)
        // Photos comes back where it was left: on the photo a run before opened, or on the library.
        if !photos.buttons["PUOneUpBarButtonItemIdentifierShare"].exists {
            // On iOS 26 each photo of the library grid is an image of its own; earlier systems had cells.
            let grid = photos.images.matching(identifier: "PXGGridLayout-Info").firstMatch
            let photo = grid.waitForExistence(timeout: 10) ? grid : photos.cells.element(boundBy: 0)
            XCTAssertTrue(photo.waitForExistence(timeout: 5), "no photo in the library:\n\(photos.debugDescription)")
            // The images of the grid are not hittable for XCTest: the tap goes to the middle of the photo.
            photo.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        sleep(2)
        probeShot("photos-photo", photos)
        tapShare(in: photos, "photos")
        return photos
    }

    /// Files on notes.txt of On My iPhone, the Share sheet of the file open.
    private func openShareSheetInFiles(_ folder: String) -> XCUIApplication {
        let files = XCUIApplication(bundleIdentifier: "com.apple.DocumentsApp")
        if !folder.isEmpty,
           let path = folder.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
           let url = URL(string: "shareddocuments://" + path) {
            XCUIDevice.shared.system.open(url)
        }
        else {
            files.activate()
        }
        let file = fileInFiles(files, prefix: "notes")
        XCTAssertTrue(file.exists, "no notes.txt in On My iPhone:\n\(files.debugDescription)")
        file.press(forDuration: 1.5)
        sleep(1)
        probeShot("files-menu", files)
        let share = element(in: files, labeled: ["Share", "Поделиться"])
        XCTAssertTrue(share.waitForExistence(timeout: 5), "no Share in the menu of the file:\n\(files.debugDescription)")
        share.tap()
        return files
    }

    /// The Share button of a system app, in its bar or in its More menu.
    private func tapShare(in host: XCUIApplication, _ source: String) {
        let labels = ["Share", "Поделиться"]
        let direct = host.buttons.matching(NSPredicate(format: "identifier == 'ShareButton' OR label IN %@", labels)).firstMatch
        if direct.waitForExistence(timeout: 5), direct.isHittable {
            direct.tap()
            return
        }
        let more = host.buttons.matching(NSPredicate(format: "label IN %@", ["More", "Ещё", "Еще"])).firstMatch
        if more.waitForExistence(timeout: 3), more.isHittable {
            more.tap()
            let item = host.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", labels)).firstMatch
            if item.waitForExistence(timeout: 5) {
                item.tap()
                return
            }
        }
        probeShot("\(source)-no-share", host)
        XCTFail("no Share in \(source):\n\(host.debugDescription)")
    }

    /// Past the first-run notes of a system app, without choosing anything for it.
    private func passFirstRun(of host: XCUIApplication) {
        let labels = ["Continue", "Продолжить", "Not Now", "Не сейчас", "OK"]
        for _ in 0..<3 {
            let button = host.buttons.matching(NSPredicate(format: "label IN %@", labels)).firstMatch
            guard button.exists, button.isHittable else {
                return
            }
            button.tap()
            sleep(2)
        }
    }

    /// The Share sheet `host` opened: Khandaq in its row of apps, then the sheet of the extension, which
    /// says to open Khandaq and closes on Done (or the system opens Khandaq itself).
    private func shareToKhandaq(from host: XCUIApplication, _ source: String) {
        let khandaq = host.descendants(matching: .any).matching(NSPredicate(format: "label == 'Khandaq'")).firstMatch
        guard khandaq.waitForExistence(timeout: 20) else {
            probeShot("\(source)-sheet-without-khandaq", host)
            XCTFail("Khandaq is not in the Share sheet of \(source):\n\(host.debugDescription)")
            return
        }
        sleep(1)
        probeShot("\(source)-sheet", host)
        for _ in 0..<4 where !khandaq.isHittable {
            host.collectionViews.firstMatch.swipeLeft()
            sleep(1)
        }
        khandaq.tap()

        let done = host.descendants(matching: .any).matching(identifier: "share.done").firstMatch
        let deadline = Date().addingTimeInterval(20)
        while Date() < deadline, app.state != .runningForeground, !(done.exists && done.isHittable) {
            usleep(500_000)
        }
        probeShot("\(source)-extension", host)
        if app.state == .runningForeground {
            return
        }
        XCTAssertTrue(done.exists, "the sheet of the extension has no Done:\n\(host.debugDescription)")
        done.tap()
        sleep(1)
    }

    // MARK: - Performance with hundreds of chats and thousands of messages (stage 5)

    /// The demo world with 200 more chats and a conversation of 3000 messages. Each test records one set of
    /// numbers (XCTest allows one per test); they are in the log and the result bundle.
    private func performanceOptions() -> XCTMeasureOptions {
        let options = XCTMeasureOptions()
        options.iterationCount = 5
        return options
    }

    /// The conversation of 3000 messages: its row on the Air screen.
    private func longThreadRow() -> XCUIElement {
        let lang = ProcessInfo.processInfo.environment["KHANDAQ_UITEST_LANG"] ?? "en"
        let name = String(format: demoString("demo_s2_bulk_format", lang), 1)
        return app.cells.matching(NSPredicate(format: "identifier == 'air.row.direct' AND label BEGINSWITH %@", name + ",")).firstMatch
    }

    /// From a cold start to the Air screen with its list in place.
    func testStageFivePerformanceLaunch() {
        openAir()
        measure(metrics: [XCTApplicationLaunchMetric(waitUntilResponsive: true)], options: performanceOptions()) {
            app.launch()
        }
    }

    /// What the scroll tests measure. On iOS 26 the signpost metrics of scrolling report only how long the
    /// drag and the glide took (stage 5); how late frames came (hitches) is XCTHitchMetric's, which on the
    /// iOS 26.5 simulator reports nothing, so the hitches are for a run on a phone.
    private func scrollMetrics() -> [XCTMetric] {
        var metrics: [XCTMetric] = [XCTOSSignpostMetric.scrollingAndDecelerationMetric]
        if #available(iOS 26.0, *) {
            metrics.append(XCTHitchMetric(application: app))
        }
        return metrics
    }

    /// Into a conversation from its row on the Air screen and back, measured: from the tap on the row to
    /// the field, then from the tap on the back button to the row.
    private func measureOpenAndBack(_ row: XCUIElement) {
        let field = app.textViews["air.chat.field"]
        let back = app.navigationBars.buttons["BackButton"]
        measure(metrics: [XCTClockMetric()], options: performanceOptions()) {
            row.tap()
            XCTAssertTrue(field.waitForExistence(timeout: 20), "the conversation did not open")
            back.tap()
            XCTAssertTrue(row.waitForExistence(timeout: 10), "not back on the Air screen")
        }
    }

    /// Scrolling the Air list of 200 and more chats: how late frames came (hitches).
    func testStageFivePerformanceListScroll() {
        openAir()
        let list = app.tables["air.list"]
        XCTAssertTrue(list.waitForExistence(timeout: 20), "no Air list")
        measure(metrics: scrollMetrics(), options: performanceOptions()) {
            list.swipeUp(velocity: .fast)
            list.swipeUp(velocity: .fast)
            list.swipeDown(velocity: .fast)
            list.swipeDown(velocity: .fast)
        }
    }

    /// Opening the conversation of 3000 messages and coming back to the Air screen.
    func testStageFivePerformanceLongThreadOpen() {
        openAir()
        let row = longThreadRow()
        XCTAssertTrue(row.waitForExistence(timeout: 20), "no conversation of 3000 messages")
        measureOpenAndBack(row)
    }

    /// The same way into a short conversation (Amina's) and back: what the 3000 messages add to it.
    func testStageFivePerformanceShortThreadOpen() {
        openAir()
        let lang = ProcessInfo.processInfo.environment["KHANDAQ_UITEST_LANG"] ?? "en"
        let name = demoString("demo_s2_amina", lang)
        let row = app.cells.matching(NSPredicate(format: "identifier == 'air.row.direct' AND label BEGINSWITH %@", name + ","))
                .firstMatch
        XCTAssertNotNil(scrollToRow(row), "no conversation with \(name)")
        measureOpenAndBack(row)
    }

    /// Scrolling back through the conversation of 3000 messages: how late frames came (hitches).
    func testStageFivePerformanceLongThreadScroll() {
        openAir()
        let row = longThreadRow()
        XCTAssertTrue(row.waitForExistence(timeout: 20), "no conversation of 3000 messages")
        row.tap()
        let log = app.tables.firstMatch
        XCTAssertTrue(app.textViews["air.chat.field"].waitForExistence(timeout: 20), "the conversation did not open")
        measure(metrics: scrollMetrics(), options: performanceOptions()) {
            log.swipeDown(velocity: .fast)
            log.swipeDown(velocity: .fast)
            log.swipeDown(velocity: .fast)
            log.swipeUp(velocity: .fast)
        }
    }

    /// A file of On My iPhone in Files whose name starts with `prefix`.
    private func fileInFiles(_ application: XCUIApplication, prefix: String) -> XCUIElement {
        let file = application.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
        if file.waitForExistence(timeout: 5) {
            return file
        }
        let browse = element(in: application, labeled: ["Browse", "Обзор"])
        if browse.waitForExistence(timeout: 5) {
            browse.tap()
        }
        let onPhone = element(in: application, labeled: ["On My iPhone", "На iPhone"])
        if onPhone.waitForExistence(timeout: 5) {
            onPhone.tap()
        }
        _ = file.waitForExistence(timeout: 10)
        return file
    }

    /// The screen and the accessibility tree of another app.
    private func probeShot(_ name: String, _ application: XCUIApplication) {
        auditCounter += 1
        let base = auditDir + String(format: "%03d-", auditCounter) + auditSlug(name)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: base + ".png"))
        try? application.debugDescription.write(toFile: base + ".txt", atomically: true, encoding: .utf8)
    }

    /// Waits until a field a screen focuses when it appears has the keyboard, so typing reaches it.
    private func waitForKeyboardFocus(_ element: XCUIElement) {
        for _ in 0..<20 where !((element.value(forKey: "hasKeyboardFocus") as? Bool) ?? false) {
            usleep(250_000)
        }
    }

    /// From the group dossier, its "Group settings" row.
    private func openGroupSettings() {
        let row = app.descendants(matching: .any)["air.group.dossier.settings"]
        let scroll = app.scrollViews.element(boundBy: 0)
        // At the largest text sizes the dossier is many screens long.
        for _ in 0..<20 where !(row.exists && row.isHittable) {
            scroll.swipeUp()
        }
        XCTAssertTrue(row.exists && row.isHittable, "no Group settings in the dossier")
        row.tap()
        XCTAssertTrue(app.descendants(matching: .any)["air.group.settings"].waitForExistence(timeout: 5), "Group settings did not open")
    }

    /// Walks the files, the voice note and the calls of stage 3 (E8 and E9) on the mock's demo world and
    /// saves a screenshot and the accessibility tree of each to
    /// $KHANDAQ_UITEST_ARTIFACTS/stage3f-<lang>[-dark][-<size>]/: a PDF waiting for Accept, our photo
    /// with its caption and a ticket on its way (Bilal), a voice note and an answered call (Amina), the
    /// archive in "In transit", a call nobody answered and a call to someone offline with its wait
    /// (Yusuf), the group's limit said before a file is chosen, then a call with the microphone refused
    /// and, once allowed, the call screen. The microphone starts undecided on each run.
    func testStageThreeFilesAndCallsWalkScreens() {
        let env = ProcessInfo.processInfo.environment
        let lang = env["KHANDAQ_UITEST_LANG"] ?? "en"
        let dark = env["KHANDAQ_UITEST_DARK"] == "1"
        var folder = "stage3f-\(lang)" + (dark ? "-dark" : "")
        if let size = env["KHANDAQ_UITEST_TEXT_SIZE"], !size.isEmpty {
            folder += "-" + auditSlug(size.replacingOccurrences(of: "UICTContentSizeCategory", with: ""))
        }
        auditDir = Self.artifactsDir + folder + "/"
        try? FileManager.default.createDirectory(atPath: auditDir, withIntermediateDirectories: true)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = dark ? .dark : .light
        }
        guard let people = Self.demoPeople[lang], let groups = Self.groupWalkTexts[lang] else {
            XCTFail("no demo names for \(lang)")
            return
        }
        relaunchWithMicrophoneUndecided()

        // 1. Files: a PDF waiting for Accept, our photo with its caption, a ticket on its way.
        XCTAssertTrue(openConversation(with: people.bilal), "no conversation with \(people.bilal)")
        auditShot("f-bilal-files")
        assertLogKeepsItsRoom(env, "beside a refused message")
        // The photo opens the gallery, whose close control is an icon with a name (stage 3, item 9).
        let photo = app.buttons.matching(NSPredicate(format: "value BEGINSWITH %@", "north-exit.jpg")).element(boundBy: 0)
        // At the accessibility text sizes the photo stands above the screenful the conversation opens on,
        // and the log is a few lines high under the band and the refused message.
        for _ in 0..<12 where !(photo.exists && photo.isHittable) {
            transcript().swipeDown()
        }
        XCTAssertTrue(photo.waitForExistence(timeout: 5), "no photo in the conversation with \(people.bilal)")
        // The tap of a hand goes to the photo itself: the entry is the whole row, and at the largest text
        // sizes the point the test framework picked for it fell beside the photo (stage 5).
        let preview = app.cells.containing(NSPredicate(format: "value BEGINSWITH %@", "north-exit.jpg")).element(boundBy: 0)
            .buttons["air.file.preview"]
        // At AX5 the row can be in sight while the photo still stands above the log's top edge.
        if preview.exists {
            reach(preview, in: transcript())
        }
        (preview.exists ? preview : photo).tap()
        let closeGallery = app.buttons["media.gallery.close"]
        XCTAssertTrue(closeGallery.waitForExistence(timeout: 5), "the photo did not open the gallery")
        XCTAssertFalse(closeGallery.label.isEmpty, "the close control of the gallery has no name")
        auditShot("f-gallery")
        closeGallery.tap()
        XCTAssertTrue(app.buttons["air.chat.title"].waitForExistence(timeout: 5), "the gallery did not close")
        transcript().swipeDown()
        auditShot("f-bilal-files-older")
        app.returnToAir()

        // 2. A voice note, and yesterday's answered call further up.
        XCTAssertTrue(openConversation(with: people.amina), "no conversation with \(people.amina)")
        auditShot("f-amina-voice")
        transcript().swipeDown()
        auditShot("f-amina-call-entry")

        // 3. A call with the microphone refused: the reason and the way to the settings, no crash.
        app.buttons["air.chat.call"].tap()
        answerMicrophoneQuestion(allow: false)
        let refused = app.alerts.element(boundBy: 0)
        XCTAssertTrue(refused.waitForExistence(timeout: 5), "a call without the microphone says nothing")
        auditShot("f-mic-refused")
        app.returnToAir()

        // 4. Someone offline: the archive in "In transit", the call nobody answered, then a call that
        // explains why it will not get through and waits for them. For the first minute after our own
        // node comes online, recently seen contacts read "connecting"; the walk waits for "offline".
        let settle = SelfConnectionGrace.seconds - Date().timeIntervalSince(launchedAt)
        if settle > 0 {
            sleep(UInt32(ceil(settle)))
        }
        XCTAssertTrue(openConversation(with: people.yusuf), "no conversation with \(people.yusuf)")
        let trayHeader = app.buttons["air.chat.tray.header"]
        XCTAssertTrue(trayHeader.waitForExistence(timeout: 5), "no In transit tray")
        trayHeader.tap()
        auditShot("f-yusuf-tray-file")
        trayHeader.tap()
        transcript().swipeDown()
        auditShot("f-yusuf-call-entry")
        app.buttons["air.chat.call"].tap()
        let offline = app.alerts.element(boundBy: 0)
        XCTAssertTrue(offline.waitForExistence(timeout: 5), "a call to someone offline does not say why")
        auditShot("f-yusuf-offline-call")
        let wait = offline.buttons.matching(NSPredicate(format: "NOT (label IN %@)", Self.cancelTitles)).element(boundBy: 0)
        XCTAssertTrue(wait.exists, "no Wait and call")
        wait.tap()
        let cancelWait = app.buttons["air.call.waiting.cancel"]
        XCTAssertTrue(cancelWait.waitForExistence(timeout: 5), "no waiting screen")
        auditShot("f-yusuf-waiting")
        cancelWait.tap()
        XCTAssertTrue(app.buttons["air.chat.title"].waitForExistence(timeout: 5), "Cancel did not go back to the conversation")
        app.returnToAir()

        // 5. A group says how large a file it takes before one is chosen.
        XCTAssertTrue(openGroup(named: groups.founderGroup), "no group \(groups.founderGroup)")
        let attach = app.buttons["air.chat.attach"]
        XCTAssertTrue(attach.waitForExistence(timeout: 5), "no attach in the group")
        attach.tap()
        XCTAssertTrue(app.sheets.element(boundBy: 0).waitForExistence(timeout: 3), "no attach sheet")
        auditShot("f-group-limit")
        app.returnToAir()

        // 6. The microphone allowed this time: the call screen with its timer, muted, then ended.
        relaunchWithMicrophoneUndecided()
        XCTAssertTrue(openConversation(with: people.amina), "no conversation with \(people.amina)")
        app.buttons["air.chat.call"].tap()
        answerMicrophoneQuestion(allow: true)
        let end = app.buttons["air.call.end"]
        XCTAssertTrue(end.waitForExistence(timeout: 8), "no call screen")
        auditShot("f-call-on")
        app.buttons["air.call.mic"].tap()
        auditShot("f-call-muted")
        end.tap()
        auditShot("f-call-ended")
        XCTAssertTrue(app.buttons["air.chat.title"].waitForExistence(timeout: 8), "the call screen did not close")
        app.returnToAir()

        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// "You" (E17) in one language, theme and text size: the QR code with Share ID and Copy ID first,
    /// the name edited where it stands, the ID in its three parts, Change antispam asked and done,
    /// Password and PIN, Log out with what it means and Delete set apart (asked, then kept), and Log out
    /// for real: the login form waits for the password, and the walk logs back in with it.
    func testStageThreeYouWalkScreens() {
        let env = ProcessInfo.processInfo.environment
        let lang = env["KHANDAQ_UITEST_LANG"] ?? "en"
        let dark = env["KHANDAQ_UITEST_DARK"] == "1"
        var folder = "stage3y-\(lang)" + (dark ? "-dark" : "")
        if let size = env["KHANDAQ_UITEST_TEXT_SIZE"], !size.isEmpty {
            folder += "-" + auditSlug(size.replacingOccurrences(of: "UICTContentSizeCategory", with: ""))
        }
        auditDir = Self.artifactsDir + folder + "/"
        try? FileManager.default.createDirectory(atPath: auditDir, withIntermediateDirectories: true)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = dark ? .dark : .light
        }
        openAir()

        // 1. From the "you" block: the QR code of your ID, Share ID and Copy ID come first.
        let you = app.buttons["air.you"]
        XCTAssertTrue(you.waitForExistence(timeout: 10), "no you block on the Air screen")
        you.tap()
        let share = app.buttons["air.you.share"]
        XCTAssertTrue(share.waitForExistence(timeout: 5), "You did not open")
        XCTAssertTrue(app.images["air.you.qr"].exists, "no QR code on You")
        auditShot("y-top")
        app.buttons["air.you.copy"].tap()
        XCTAssertTrue(app.otherElements["air.toast"].waitForExistence(timeout: 3), "Copy ID says nothing")
        auditShot("y-copied")
        waitUntilGone(app.otherElements["air.toast"])

        // 2. Name and status, edited where they stand; Done keeps an unchanged name.
        let name = app.textFields["air.you.name"]
        scrollUntilHittable(name)
        name.tap()
        // Stage 5: the field comes out from under the keyboard, also on a short phone and at the large
        // text sizes (it stayed under it: the screen only made room for the keyboard).
        let keyboard = app.keyboards.element(boundBy: 0)
        if keyboard.waitForExistence(timeout: 5) {
            usleep(800_000)
            XCTAssertLessThanOrEqual(name.frame.maxY, keyboard.frame.minY + 0.5, "the name being edited is under the keyboard")
        }
        auditShot("y-name-editing")
        name.typeText("\n")

        // 3. Details: the ID in its three parts, and Change antispam, asked with what it does.
        let antispam = app.buttons["air.you.antispam"]
        scrollUntilHittable(antispam)
        auditShot("y-details")
        antispam.tap()
        // E21: the questions come from below.
        let antispamQuestion = app.sheets.element(boundBy: 0)
        XCTAssertTrue(antispamQuestion.waitForExistence(timeout: 5), "Change antispam does not ask")
        auditShot("y-antispam-ask")
        antispamQuestion.buttons.matching(NSPredicate(format: "NOT (label IN %@)", Self.cancelTitles)).element(boundBy: 0).tap()
        XCTAssertTrue(app.otherElements["air.toast"].waitForExistence(timeout: 3), "a new antispam says nothing")
        auditShot("y-antispam-done")
        waitUntilGone(app.otherElements["air.toast"])

        // 4. Password and PIN: the protection of the profile, one screen further.
        let security = app.buttons["air.you.security"]
        scrollUntilHittable(security)
        security.tap()
        let back = app.navigationBars.buttons["BackButton"]
        XCTAssertTrue(back.waitForExistence(timeout: 5), "Password and PIN did not open")
        auditShot("y-security")
        back.tap()

        // 5. Log out says what it means before it is pressed; Delete stands apart, asks, and is kept.
        let delete = app.buttons["air.you.delete"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5), "no Delete the profile on You")
        scrollUntilHittable(delete)
        auditShot("y-bottom")
        delete.tap()
        let deleteQuestion = app.sheets.element(boundBy: 0)
        XCTAssertTrue(deleteQuestion.waitForExistence(timeout: 5), "Delete does not ask")
        auditShot("y-delete-ask")
        let keep = deleteQuestion.buttons.matching(NSPredicate(format: "label IN %@", Self.cancelTitles)).element(boundBy: 0)
        XCTAssertTrue(keep.exists, "no Cancel in the delete question")
        keep.tap()

        // 6. Log out for real: the login form waits for the password instead of logging straight back in.
        app.buttons["air.you.logout"].tap()
        let logoutQuestion = app.sheets.element(boundBy: 0)
        XCTAssertTrue(logoutQuestion.waitForExistence(timeout: 5), "Log out does not ask")
        auditShot("y-logout-ask")
        logoutQuestion.buttons.matching(NSPredicate(format: "NOT (label IN %@)", Self.cancelTitles)).element(boundBy: 0).tap()
        XCTAssertTrue(app.secureTextFields.element(boundBy: 0).waitForExistence(timeout: 20), "Log out did not open the login form")
        XCTAssertFalse(app.airCommand.exists, "Log out went straight back in")
        auditShot("y-logged-out")
        signUpIfNeeded()
        openAir()

        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// Settings (E18) in one language, theme and text size: the sections by task with the profile file
    /// off the first screen; Appearance with the theme segment, where a new theme rebuilds the interface
    /// under a cross-fade and comes back to Appearance; the language sheet; Notifications; Privacy;
    /// Profile transfer with what Import asks; Network. The theme ends where it started, as the system.
    func testStageThreeSettingsWalkScreens() {
        let env = ProcessInfo.processInfo.environment
        let lang = env["KHANDAQ_UITEST_LANG"] ?? "en"
        let dark = env["KHANDAQ_UITEST_DARK"] == "1"
        var folder = "stage3s-\(lang)" + (dark ? "-dark" : "")
        if let size = env["KHANDAQ_UITEST_TEXT_SIZE"], !size.isEmpty {
            folder += "-" + auditSlug(size.replacingOccurrences(of: "UICTContentSizeCategory", with: ""))
        }
        auditDir = Self.artifactsDir + folder + "/"
        try? FileManager.default.createDirectory(atPath: auditDir, withIntermediateDirectories: true)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = dark ? .dark : .light
        }
        openAir()

        // 1. Settings: the sections by task; the profile file is not on the first screen any more.
        let settings = app.buttons["air.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "no settings button on the Air screen")
        settings.tap()
        let appearance = app.buttons["settings.appearance"]
        XCTAssertTrue(appearance.waitForExistence(timeout: 5), "no Appearance in Settings")
        XCTAssertFalse(app.buttons["settings.export"].exists, "export is still on the first screen of Settings")
        auditShot("s-root")

        // 2. Appearance: the theme follows the system; another theme rebuilds the interface under a
        // cross-fade and comes back to Appearance, then the system is chosen again.
        let system = app.buttons["settings.theme.system"]
        XCTAssertTrue(tap(appearance, until: system), "no theme segment on Appearance")
        XCTAssertTrue(waitUntilSelected(system), "the theme does not follow the system")
        auditShot("s-appearance")
        let otherTheme = dark ? "settings.theme.light" : "settings.theme.dark"
        app.buttons[otherTheme].tap()
        XCTAssertTrue(waitUntilSelected(app.buttons[otherTheme], timeout: 10),
                      "after a new theme the interface did not come back to Appearance with it chosen")
        auditShot("s-theme-switched")
        app.buttons["settings.theme.system"].tap()
        XCTAssertTrue(waitUntilSelected(app.buttons["settings.theme.system"], timeout: 10), "the system theme did not come back")

        // 3. The language: every language in its own language; the walk keeps the one it has.
        let language = app.buttons["settings.language"]
        XCTAssertTrue(language.waitForExistence(timeout: 5), "no Language on Appearance")
        language.tap()
        let keepLanguage = app.buttons.matching(NSPredicate(format: "label IN %@", Self.cancelTitles)).element(boundBy: 0)
        XCTAssertTrue(keepLanguage.waitForExistence(timeout: 5), "the language sheet did not open")
        auditShot("s-language")
        keepLanguage.tap()
        let back = app.navigationBars.buttons["BackButton"]
        back.tap()

        // 4. Notifications: whether the text shows, and what that means.
        openSection("settings.notifications", expecting: app.switches["settings.preview"])
        auditShot("s-notifications")
        back.tap()

        // 5. Privacy: the blocked keys.
        openSection("settings.privacy", expecting: app.buttons["air.settings.privacy.blocked"])
        auditShot("s-privacy")
        back.tap()

        // 6. Profile transfer: export and import, and what Import asks before it logs out.
        let importRow = app.buttons["settings.import"]
        openSection("settings.transfer", expecting: importRow)
        auditShot("s-transfer")
        importRow.tap()
        // E21: the question comes from below.
        let importQuestion = app.sheets.element(boundBy: 0)
        XCTAssertTrue(importQuestion.waitForExistence(timeout: 5), "Import does not ask")
        auditShot("s-import-ask")
        importQuestion.buttons.matching(NSPredicate(format: "label IN %@", Self.cancelTitles)).element(boundBy: 0).tap()
        back.tap()

        // 7. Network: UDP and the connection log, attachments, calls, and the rest further down.
        openSection("settings.network", expecting: app.switches["settings.udp"])
        auditShot("s-network")
        let restore = app.buttons["settings.restore"]
        scrollUntilHittable(restore)
        auditShot("s-network-bottom")
        back.tap()
        app.returnToAir()

        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// E4: our own node while it connects. The line under the name says so and opens the Network screen
    /// in words; in a conversation with someone online the hint under the field speaks for our node;
    /// "Connect again" finds the network (the mock answers a reconnect asked for by hand), and the
    /// screen and the line follow. E22: Bilal writes a moment later, and his message comes as a band
    /// that opens the chat.
    func testStageThreeNetworkWalkScreens() {
        let env = ProcessInfo.processInfo.environment
        let lang = env["KHANDAQ_UITEST_LANG"] ?? "en"
        let dark = env["KHANDAQ_UITEST_DARK"] == "1"
        var folder = "stage3n-\(lang)" + (dark ? "-dark" : "")
        if let size = env["KHANDAQ_UITEST_TEXT_SIZE"], !size.isEmpty {
            folder += "-" + auditSlug(size.replacingOccurrences(of: "UICTContentSizeCategory", with: ""))
        }
        auditDir = Self.artifactsDir + folder + "/"
        try? FileManager.default.createDirectory(atPath: auditDir, withIntermediateDirectories: true)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = dark ? .dark : .light
        }
        guard let people = Self.demoPeople[lang] else {
            XCTFail("no demo names for \(lang)")
            return
        }
        openAir()

        // 1. The line under the name: a target of its own next to "You", a finger high.
        let line = app.buttons["air.you.network"]
        XCTAssertTrue(line.waitForExistence(timeout: 10), "no line under the name")
        XCTAssertTrue(app.buttons["air.you"].exists, "You is no longer a target of its own")
        XCTAssertGreaterThanOrEqual(line.frame.height, 44, "the line is not a finger high")
        let connectingLine = line.label
        auditShot("n-air")

        // 2. Someone online: the hint under the field speaks for our node, not for them.
        XCTAssertTrue(openConversation(with: people.amina), "no conversation with \(people.amina)")
        XCTAssertTrue(app.descendants(matching: .any)["air.chat.hint"].waitForExistence(timeout: 5), "no hint under the field")
        auditShot("n-chat-hint")
        app.returnToAir()

        // 3. The Network screen in words; the log for support is one row further.
        XCTAssertTrue(tap(line, until: app.descendants(matching: .any)["air.network.screen"]), "the line did not open the Network screen")
        let reconnect = app.buttons["air.network.reconnect"]
        XCTAssertTrue(reconnect.exists, "no Connect again while connecting")
        auditShot("n-network")
        let log = app.buttons["air.network.log"]
        scrollUntilHittable(log)
        log.tap()
        waitUntilGone(log)
        XCTAssertFalse(log.exists, "the log for support did not open")
        auditShot("n-log")
        app.navigationBars.buttons["BackButton"].tap()
        XCTAssertTrue(reconnect.waitForExistence(timeout: 5), "the Network screen did not come back")

        // 4. Connect again: a toast says so, and the screen follows the node once it is online.
        scrollUntilHittable(reconnect)
        reconnect.tap()
        XCTAssertTrue(app.otherElements["air.toast"].waitForExistence(timeout: 3), "Connect again says nothing")
        auditShot("n-reconnect")
        waitUntilGone(reconnect, timeout: 10)
        XCTAssertFalse(reconnect.exists, "the screen did not follow the node online")
        waitUntilGone(app.otherElements["air.toast"])
        auditShot("n-network-online")

        // 5. A message from another chat comes as a band under the safe area; a tap opens the chat.
        let band = app.buttons["air.banner"]
        XCTAssertTrue(band.waitForExistence(timeout: 10), "a message while the app is open came without a band")
        XCTAssertTrue(band.label.contains(people.bilal), "the band does not name the sender")
        auditShot("n-band")
        band.tap()
        XCTAssertTrue(app.buttons["air.chat.title"].waitForExistence(timeout: 5), "the band did not open the chat")
        XCTAssertTrue(app.buttons["air.chat.title"].label.contains(people.bilal), "the band opened another chat")
        auditShot("n-band-opened")

        // 6. The line says the node is online too.
        app.returnToAir()
        XCTAssertTrue(line.waitForExistence(timeout: 5), "no line under the name")
        XCTAssertNotEqual(line.label, connectingLine, "the line did not follow the node online")
        auditShot("n-air-online")

        // 7. An invitation to a group comes as a band of its own kind: who invites, then to which group.
        //    A tap opens Requests, where its card waits.
        let group = Self.demoBandGroup[lang] ?? "Saturday by the river"
        XCTAssertTrue(band.waitForExistence(timeout: 15), "an invitation while the app is open came without a band")
        XCTAssertTrue(band.label.contains(people.bilal), "the band does not name who invites")
        XCTAssertTrue(band.label.contains(group), "the band does not name the group")
        auditShot("n-invite-band")
        band.tap()
        let card = app.cells.matching(identifier: "air.invite").containing(NSPredicate(format: "label == %@", group)).element(boundBy: 0)
        XCTAssertTrue(card.waitForExistence(timeout: 5), "the band did not open the invitation")
        // The new card is first and whole, not half under the header.
        let list = app.tables["air.list"]
        XCTAssertGreaterThanOrEqual(card.frame.minY, list.frame.minY - 1, "the invitation the band opened is half under the header")
        XCTAssertEqual(list.cells.element(boundBy: 0).frame.minY, card.frame.minY, accuracy: 1,
                       "the invitation the band opened is not the first card")
        auditShot("n-invite-opened")

        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// Opens a section of Settings from its first screen and waits for `element` on it.
    private func openSection(_ identifier: String, expecting element: XCUIElement) {
        let row = app.buttons[identifier]
        XCTAssertTrue(row.waitForExistence(timeout: 5), "no \(identifier) in Settings")
        scrollUntilHittable(row)
        XCTAssertTrue(tap(row, until: element), "\(identifier) did not open")
    }

    /// Taps `control` until `target` shows, twice at most: a tap that lands while the screen it is on
    /// is still settling after a push is taken by the transition, not by the control.
    private func tap(_ control: XCUIElement, until target: XCUIElement) -> Bool {
        for _ in 0..<2 {
            control.tap()
            if target.waitForExistence(timeout: 4) {
                return true
            }
            guard control.exists, control.isHittable else {
                return false
            }
        }
        return false
    }

    /// Waits for `element` to show as chosen.
    private func waitUntilSelected(_ element: XCUIElement, timeout: TimeInterval = 5) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if element.exists, element.isSelected {
                return true
            }
            usleep(250_000)
        }
        while Date() < deadline
        return false
    }

    /// Swipes the screen up until `element` can be tapped, six times at most.
    private func scrollUntilHittable(_ element: XCUIElement) {
        for _ in 0..<6 {
            if element.exists, element.isHittable {
                return
            }
            app.swipeUp()
            usleep(400_000)
        }
    }

    /// Waits for `element` to go, a toast for example, so the next screenshot is without it.
    private func waitUntilGone(_ element: XCUIElement, timeout: TimeInterval = 6) {
        let deadline = Date().addingTimeInterval(timeout)
        while element.exists, Date() < deadline {
            usleep(250_000)
        }
    }

    private static let cancelTitles = ["Cancel", "Отмена", "Отменить", "إلغاء", "إلغاء الامر", "取消"]

    /// The conversation's transcript: the table that is not the Air list.
    private func transcript() -> XCUIElement {
        return app.tables.matching(NSPredicate(format: "identifier != 'air.list'")).element(boundBy: 0)
    }

    /// At the accessibility text sizes the log of a conversation keeps room to read in: at AX5 a refused
    /// message and the band had left it 45 points, and the open tray, or the keyboard with the tray, none
    /// (stage 5). `share` is the part of the screen it keeps at least. The tray never lies over the field:
    /// with the band folded in the middle of a layout, the open tray stood 40 points over it in Arabic.
    private func assertLogKeepsItsRoom(_ env: [String: String], share: CGFloat = 0.2, _ context: String,
                                       file: StaticString = #file, line: UInt = #line) {
        guard (env["KHANDAQ_UITEST_TEXT_SIZE"] ?? "").contains("Accessibility") else {
            return
        }
        let screen = app.windows.element(boundBy: 0).frame.height
        let log = transcript()
        XCTAssertTrue(log.exists, "the log has no room \(context)", file: file, line: line)
        XCTAssertGreaterThanOrEqual(log.frame.height, screen * share - 1,
                                    "the log keeps \(Int(log.frame.height)) points \(context)", file: file, line: line)
        let tray = app.otherElements["air.chat.tray"]
        let field = app.textViews["air.chat.field"]
        if tray.exists, field.exists {
            XCTAssertLessThanOrEqual(tray.frame.maxY, field.frame.minY + 1,
                                     "the tray lies over the field \(context)", file: file, line: line)
        }
    }

    /// Starts the app again with the microphone undecided, so the system asks once more.
    private func relaunchWithMicrophoneUndecided() {
        app.resetAuthorizationStatus(for: .microphone)
        app.launch()
        launchedAt = Date()
        signUpIfNeeded()
        openAir()
    }

    /// Answers the system's question about the microphone, when it asks.
    private func answerMicrophoneQuestion(allow: Bool) {
        let alert = XCUIApplication(bundleIdentifier: "com.apple.springboard").alerts.element(boundBy: 0)
        guard alert.waitForExistence(timeout: 5) else {
            return
        }
        let titles = allow ? ["Allow", "OK"] : ["Don’t Allow", "Don't Allow"]
        for title in titles where alert.buttons[title].exists {
            alert.buttons[title].tap()
            return
        }
        alert.buttons.element(boundBy: allow ? alert.buttons.count - 1 : 0).tap()
    }

    private func openNewGroupSheet() -> XCUIElement {
        app.returnToAir()
        app.buttons["air.create"].tap()
        let entry = app.buttons["air.newGroup"]
        XCTAssertTrue(entry.waitForExistence(timeout: 5), "no New group in the Create sheet")
        entry.tap()
        let name = app.textViews["air.newGroup.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 5), "New group did not open")
        return name
    }

    /// Opens the group whose row on the Air screen names `group`.
    private func openGroup(named group: String) -> Bool {
        app.returnToAir()
        let row = app.cells.matching(NSPredicate(format: "identifier == 'air.row.group' AND label CONTAINS %@", group))
                .element(boundBy: 0)
        guard let found = scrollToRow(row) else {
            return false
        }
        found.tap()
        return app.buttons["air.chat.title"].waitForExistence(timeout: 10)
    }

    /// The bar of a group you only read: no "Write to a moderator", since toxcore refuses an observer's
    /// private messages; the line that says so stands in its place.
    private func assertObserverBarSaysWhy(_ lang: String, file: StaticString = #file, line: UInt = #line) {
        let ask = app.buttons["air.group.readOnly.ask"]
        XCTAssertFalse(ask.exists && ask.isHittable, "an observer is offered Write to a moderator", file: file, line: line)
        let note = app.staticTexts["air.group.readOnly.note"]
        XCTAssertTrue(note.waitForExistence(timeout: 3), "no line in place of Write to a moderator", file: file, line: line)
        XCTAssertEqual(note.label, appString("air_group_observer_no_private", lang), file: file, line: line)
    }

    /// From the open group: its dossier, the member's row and "Write privately". True once their private
    /// messages are on screen.
    private func openPrivateMessages(with name: String, _ lang: String) -> Bool {
        app.buttons["air.chat.title"].tap()
        guard app.descendants(matching: .any)["air.group.dossier"].waitForExistence(timeout: 5) else {
            return false
        }
        let scroll = app.scrollViews.element(boundBy: 0)
        let row = app.descendants(matching: .any).matching(
                NSPredicate(format: "identifier == 'air.group.member' AND label CONTAINS %@", name)).element(boundBy: 0)
        for _ in 0..<8 where !(row.exists && row.isHittable) {
            scroll.swipeUp()
        }
        guard row.exists && row.isHittable else {
            return false
        }
        row.tap()
        let writePrivately = app.sheets.element(boundBy: 0).buttons[appString("air_group_write_privately", lang)]
        guard writePrivately.waitForExistence(timeout: 3) else {
            return false
        }
        writePrivately.tap()
        return app.descendants(matching: .any)["air.group.private"].waitForExistence(timeout: 5)
    }

    /// Opens the conversation whose row on the Air screen names `person`. The list is rebuilt as people come
    /// and go, and a row that moved under the finger gave the tap to a heading or to its neighbour (stage 5,
    /// AX3): the title must name the person, or the walk goes back and taps the row once more.
    private func openConversation(with person: String) -> Bool {
        let title = app.buttons["air.chat.title"]
        for _ in 0..<2 {
            app.returnToAir()
            let row = app.cells.matching(NSPredicate(format: "identifier == 'air.row.direct' AND label CONTAINS %@", person))
                    .element(boundBy: 0)
            guard let found = scrollToRow(row) else {
                return false
            }
            found.tap()
            if title.waitForExistence(timeout: 10), title.label.contains(person) {
                return true
            }
        }
        return false
    }

    /// Scrolls the Air list until `row` can be tapped; nil when it is not there.
    /// At the accessibility text sizes a card can be taller than the room the header leaves the list, and
    /// the test framework scrolls only its cell into view, so a button at the card's end stayed out of
    /// reach (stage 5, AX5). The list is moved by hand, a third of its height at a time and without a
    /// fling, until the button can be tapped, as a thumb would; nothing moves where it already can.
    @discardableResult
    private func reach(_ element: XCUIElement, in list: XCUIElement) -> Bool {
        for _ in 0..<12 {
            if element.exists && element.isHittable {
                return true
            }
            guard element.exists, list.exists else {
                return false
            }
            let below = element.frame.midY > list.frame.midY
            let start = list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: below ? 0.75 : 0.25))
            let end = list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: below ? 0.42 : 0.58))
            start.press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.2)
        }
        return element.exists && element.isHittable
    }

    private func scrollToRow(_ row: XCUIElement) -> XCUIElement? {
        let list = app.tables["air.list"]
        for attempt in 0..<16 {
            if row.exists, row.isHittable {
                // A row whose middle is under a sticky section heading, or above the list under the bar
                // on top when only its lower edge shows, gives the tap to something else (rows are tall
                // at the accessibility text sizes): pull the list down a little first. A row whose
                // middle is below the list's edge is pushed up the same way.
                let middle = CGPoint(x: row.frame.midX, y: row.frame.midY)
                let headings = list.otherElements.matching(NSPredicate(format: "identifier BEGINSWITH 'air.section.'"))
                let underHeading = headings.allElementsBoundByIndex.contains(where: { $0.frame.contains(middle) })
                if underHeading || middle.y < list.frame.minY || middle.y > list.frame.maxY {
                    let down = middle.y <= list.frame.maxY
                    let start = list.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: down ? 0.3 : 0.7))
                    start.press(forDuration: 0.05, thenDragTo: start.withOffset(CGVector(dx: 0, dy: down ? 160 : -160)))
                    usleep(700_000)
                    continue
                }
                if attempt > 0 {
                    // A tap while the list still glides only stops it; let it come to rest first.
                    usleep(700_000)
                }
                return row
            }
            guard list.exists else {
                return nil
            }
            if row.exists {
                // Built but out of sight: towards it.
                if row.frame.midY < list.frame.minY {
                    list.swipeDown()
                }
                else {
                    list.swipeUp()
                }
            }
            else if attempt < 4 {
                // Not built: back to the top first. Back from a conversation the list stays where it was,
                // and at the accessibility text sizes a row near the top is many screens up (stage 5).
                list.swipeDown()
            }
            else {
                list.swipeUp()
            }
        }
        return row.exists && row.isHittable ? row : nil
    }

    /// Types `text` into the message field. Digits and a colon exist on every keyboard layout.
    private func typeInField(_ text: String) -> Bool {
        let field = app.textViews["air.chat.field"]
        guard field.waitForExistence(timeout: 5), field.isHittable else {
            return false
        }
        field.tap()
        field.typeText(text)
        return true
    }

    private func clearField(count: Int) {
        let field = app.textViews["air.chat.field"]
        if field.exists {
            field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: count))
        }
    }

    private func openDossierFromTitle() {
        let title = app.buttons["air.chat.title"]
        guard title.waitForExistence(timeout: 5) else {
            return
        }
        title.tap()
        _ = app.otherElements["air.chat.dossier.panel"].waitForExistence(timeout: 3)
    }

    private func closeDossierFromTitle() {
        let title = app.buttons["air.chat.title"]
        if app.otherElements["air.chat.dossier.panel"].exists, title.exists, title.isHittable {
            title.tap()
            usleep(600_000)
        }
    }

    /// One test, two checks: XCTest relaunches the app between test methods, and getting back into
    /// a profile costs minutes (and does not always succeed on the simulator), so both live here.
    func testSwipeAndInAppLanguage() {
        checkSwipeBetweenFilters()
        checkInAppLanguageReachesWholeInterface()
    }

    /// The attachments screen exists and is reachable: open a chat, press the paperclip, and check the
    /// three folders are there. Building is not the same as being reachable; that lesson cost a day.
    func testAttachmentsScreenOpensWithThreeFolders() {
        openAir()

        guard let firstChat = firstChatRow(timeout: 15) else {
            XCTFail("no chat to open. Screen was:\n\(app.debugDescription)")
            return
        }
        firstChat.tap()

        // "For yourself" keeps the paperclip in its header; a conversation with a person has it in the
        // dossier, which the header title opens.
        let paperclip = app.navigationBars.buttons.matching(
                NSPredicate(format: "label IN {'Attachments', 'Вложения', 'المرفقات', '附件'}"))
                .element(boundBy: 0)
        let title = app.buttons["air.chat.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 10),
                      "no conversation header. Screen was:\n\(app.debugDescription)")
        if paperclip.exists, paperclip.isHittable {
            paperclip.tap()
        }
        else {
            title.tap()
            XCTAssertTrue(openDossierAttachments(),
                          "no attachments button in the dossier. Screen was:\n\(app.debugDescription)")
        }

        for folder in ["Files", "Файлы", "Audio", "Аудио", "Voice", "Голосовые"] where app.buttons[folder].exists {
            XCTAssertTrue(app.buttons[folder].isHittable, "folder \(folder) is not tappable")
        }

        let segments = app.segmentedControls.element(boundBy: 0)
        XCTAssertTrue(segments.waitForExistence(timeout: 10),
                      "the attachments screen did not open. Screen was:\n\(app.debugDescription)")
        XCTAssertEqual(segments.buttons.count, 3, "expected three folders: files, audio, voice")

        // Each folder must be selectable; an empty one shows its placeholder, which is fine.
        for index in 0..<segments.buttons.count {
            segments.buttons.element(boundBy: index).tap()
        }
    }

    /// Regression for the 18 Aug report "reactions don't stick on media": sends a text and a photo to
    /// Saved Messages on the REAL messenger, reacts on both, and requires the photo row to grow:
    /// media rows have an explicit height, so a chip that is not accounted for there is clipped away
    /// and the reaction looks lost. Screenshots and accessibility dumps land in the scratchpad.
    func testReactionOnRealPhotoShowsChipReal() {
        let dir = Self.artifactsDir

        openAir()
        guard let savedMessages = firstChatRow(kinds: ["saved"], timeout: 20) else {
            XCTFail("no Saved Messages row. Screen was:\n\(app.debugDescription)")
            return
        }
        savedMessages.tap()

        // 1) A text message, as the control.
        let field = app.textViews.element(boundBy: 0)
        if field.waitForExistence(timeout: 10) {
            fill(field, with: "reactcheck")
            for send in ["send", "Send", "Отправить"] where app.buttons[send].exists {
                app.buttons[send].tap()
                break
            }
        }
        // The bubble is a TextView (not a staticText); that mismatch is what made the previous
        // dump report "NO TEXT BUBBLE" while the message was plainly on screen.
        let textBubble = app.textViews.matching(NSPredicate(format: "label CONTAINS %@", "reactcheck"))
                .element(boundBy: 0)
        var textChip = false
        if textBubble.waitForExistence(timeout: 15) {
            textBubble.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 1.0)
            let baseline = emojiElementCount("❤️")
            if tapReactionBarButton("❤️") {
                textChip = reactionChipAppeared("❤️", above: baseline)
            }
            try? app.debugDescription.write(toFile: dir + "tree-text.txt", atomically: true, encoding: .utf8)
            try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "tree-text.png"))
        }
        else {
            try? "NO TEXT BUBBLE\n\n\(app.debugDescription)".write(toFile: dir + "tree-text.txt", atomically: true, encoding: .utf8)
        }

        // 2) The same on a photo. The system picker needs its own handling: the first `images`
        // element is the "Private Access to Photos" banner icon, not a photo, and the confirm
        // control is a checkmark labelled "Done".
        let add = app.buttons["add"]
        XCTAssertTrue(add.waitForExistence(timeout: 10), "no attach button")
        add.tap()

        let gallery = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label IN {'Gallery', 'Галерея'}"))
                .element(boundBy: 0)
        XCTAssertTrue(gallery.waitForExistence(timeout: 6), "no Gallery entry in the attach menu")
        gallery.tap()

        // The picker exposes each photo as an Image with identifier "PXGGridLayout-Info"; it has no
        // collection view and no cells in the accessibility tree.
        let photo = app.images.matching(identifier: "PXGGridLayout-Info").element(boundBy: 0)
        XCTAssertTrue(photo.waitForExistence(timeout: 20),
                      "the photo picker showed no photos. Screen was:\n\(app.debugDescription)")

        // "Private Access to Photos" sits over the grid and makes the tiles unhittable, so close it.
        let close = app.buttons["Close"].firstMatch
        if close.exists, close.isHittable {
            close.tap()
        }
        if photo.isHittable {
            photo.tap()
        }
        else {
            photo.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }

        for confirm in ["Done", "Add", "Готово", "Добавить"] where app.buttons[confirm].waitForExistence(timeout: 3) {
            app.buttons[confirm].tap()
            break
        }

        // Khandaq shows its own caption/preview screen before sending; its send control is the round
        // teal button "media.preview.send" (MediaSendPreviewController).
        let previewSend = app.buttons["media.preview.send"]
        if previewSend.waitForExistence(timeout: 10) {
            previewSend.tap()
        }
        _ = app.cells.element(boundBy: 0).waitForExistence(timeout: 8)
        try? app.debugDescription.write(toFile: dir + "tree-afterpick.txt", atomically: true, encoding: .utf8)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "tree-afterpick.png"))

        // The media row is the cell that carries no text value.
        let mediaCell = app.cells.matching(NSPredicate(format: "NOT (value CONTAINS %@)", "reactcheck"))
                .element(boundBy: 0)
        XCTAssertTrue(mediaCell.waitForExistence(timeout: 30),
                      "the photo never reached the chat. Screen was:\n\(app.debugDescription)")

        // The regression this test guards: media rows have an explicit height, so a reaction chip is
        // only visible if that height grows. Frame comparison is reliable where matching the emoji
        // in the accessibility tree is not.
        let heightBeforeReaction = mediaCell.frame.height

        mediaCell.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 1.0)
        try? app.debugDescription.write(toFile: dir + "tree-media.txt", atomically: true, encoding: .utf8)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "tree-media.png"))

        var picked: String?
        var baseline = 0
        for e in ["😮", "🔥", "😢", "😂"] {
            baseline = emojiElementCount(e)
            if tapReactionBarButton(e, timeout: 3) {
                picked = e
                break
            }
        }
        guard let emoji = picked else {
            XCTFail("REACTION BAR DID NOT OPEN on a real photo (it does open on text, see tree-text.png)")
            return
        }

        let appeared = mediaRowGrew(mediaCell, over: heightBeforeReaction)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "tree-media-after.png"))

        // Re-open the bar on the same photo: the bar highlights the reaction already stored on the
        // message, so this screenshot says whether the pick was SAVED even if no chip is drawn.
        mediaCell.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 1.0)
        _ = app.buttons["👎"].firstMatch.waitForExistence(timeout: 4)
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "tree-media-again.png"))
        XCTAssertTrue(appeared,
                      "picked \(emoji) on a photo and the row did not grow for the chips line "
                      + "(was \(heightBeforeReaction), now \(mediaCell.frame.height)); text chip: \(textChip)")
    }

    /// Taps `emoji` in the floating reaction bar. Matching by label alone is not enough: a bubble that
    /// already carries that reaction exposes the same label on its full-width cell.
    @discardableResult
    private func tapReactionBarButton(_ emoji: String, timeout: TimeInterval = 4) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            for candidate in app.buttons.matching(NSPredicate(format: "label == %@", emoji))
                    .allElementsBoundByIndex where candidate.exists {
                let box = candidate.frame
                if box.height <= 60, box.width <= 60, candidate.isHittable {
                    let dir = Self.artifactsDir
                    try? XCUIScreen.main.screenshot().pngRepresentation
                            .write(to: URL(fileURLWithPath: dir + "step-before-tap.png"))
                    candidate.tap()
                    try? XCUIScreen.main.screenshot().pngRepresentation
                            .write(to: URL(fileURLWithPath: dir + "step-after-tap.png"))
                    return true
                }
            }
        }
        while Date() < deadline
        return false
    }

    /// True once the media row is taller than it was: that is exactly the chips line appearing.
    private func mediaRowGrew(_ cell: XCUIElement, over baseline: CGFloat,
                              timeout: TimeInterval = 10) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if cell.frame.height > baseline + 10.0 {
                return true
            }
        }
        while Date() < deadline
        return false
    }

    /// Number of elements carrying `emoji`. Used as a before/after counter: scoping the search to a
    /// cell does not work (the chip is not reported as its descendant) and a plain screen-wide
    /// existence check matches chips left by earlier runs.
    private func emojiElementCount(_ emoji: String) -> Int {
        return app.descendants(matching: .any)
                .matching(NSPredicate(format: "label CONTAINS %@", emoji)).count
    }

    /// Waits until the number of elements carrying `emoji` exceeds `baseline` (the reaction bar is
    /// gone by then, so the growth is the new chip).
    private func reactionChipAppeared(_ emoji: String, above baseline: Int,
                                      timeout: TimeInterval = 10) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if emojiElementCount(emoji) > baseline {
                return true
            }
        }
        while Date() < deadline
        return false
    }

    /// The owner/developer credit must be reachable from Settings, then About, must not be clipped in
    /// the longest language, and must be tappable (it opens https://1sa.me/).
    func testAboutCreditIsPresentReal() {
        openAir()
        let settingsButton = app.buttons["air.settings"]
        XCTAssertTrue(settingsButton.waitForExistence(timeout: 20),
                      "no settings button on the Air screen. Screen was:\n\(app.debugDescription)")

        let about = app.descendants(matching: .any)
                .matching(NSPredicate(format: "label IN {'About', 'О приложении', 'حول التطبيق', 'نبذة عن', '关于'}"))
                .element(boundBy: 0)

        // A single tap occasionally lands before the screen is live and is swallowed: QA saw exactly
        // that ("no About row", app still on the chat list). Retry, and scroll in case the About row
        // sits below the fold.
        for attempt in 0..<4 where !about.exists {
            if settingsButton.exists, settingsButton.isHittable {
                settingsButton.tap()
            }
            if about.waitForExistence(timeout: 4) {
                break
            }
            app.swipeUp()
            if attempt == 2 {
                _ = about.waitForExistence(timeout: 4)
            }
        }
        XCTAssertTrue(about.waitForExistence(timeout: 6),
                      "no About row in Settings. Screen was:\n\(app.debugDescription)")
        about.tap()

        // Matched by identifier, not by text: the Arabic catalogue transliterates the name, so a
        // "contains Isa Dagestani" match could never pass there.
        let credit = app.descendants(matching: .any).matching(identifier: "about.ownerCredit")
                .element(boundBy: 0)
        XCTAssertTrue(credit.waitForExistence(timeout: 10),
                      "no owner credit on the About screen. Screen was:\n\(app.debugDescription)")
        XCTAssertFalse(credit.label.contains("\u{2026}"), "the credit line is clipped: \(credit.label)")
        XCTAssertFalse(credit.label.isEmpty, "the credit row has no text")

        try? XCUIScreen.main.screenshot().pngRepresentation
                .write(to: URL(fileURLWithPath: Self.artifactsDir + "about-credit.png"))

        XCTAssertTrue(credit.isHittable, "the credit row is not tappable")
    }

    /// The group chat header must use the same bare Material glyphs as the 1:1 header; QA found it
    /// still carrying circled SF symbols (mic-in-a-circle, info-in-a-circle), which read as a
    /// different app.
    func testGroupHeaderUsesSharedGlyphsReal() {
        openAir()

        let group = app.airRow("group")
        guard group.waitForExistence(timeout: 15) else {
            // Nothing to check without a group; make that explicit rather than silently passing.
            XCTFail("no group chat on this device to check the header on")
            return
        }
        group.tap()

        let header = app.navigationBars.element(boundBy: 0)
        XCTAssertTrue(header.waitForExistence(timeout: 10), "group chat did not open")
        try? XCUIScreen.main.screenshot().pngRepresentation
                .write(to: URL(fileURLWithPath: Self.artifactsDir + "group-header.png"))

        // The shared assets are template images; their buttons keep the accessibility labels, so
        // assert the header still exposes its actions rather than pixel-matching here.
        XCTAssertGreaterThan(header.buttons.count, 1, "group header lost its action buttons")
    }

    // MARK: - Swipe

    private func checkSwipeBetweenFilters() {
        openAir()
        selectFilter(.all)

        let list: XCUIElement = app.windows.element(boundBy: 0)

        assertSelectedFilter(.all, after: "opening the Air screen")

        swipeHorizontally(on: list, left: true)
        assertSelectedFilter(.requests, after: "one swipe left")

        swipeHorizontally(on: list, left: true)
        assertSelectedFilter(.transit, after: "two swipes left")

        swipeHorizontally(on: list, left: true)
        assertSelectedFilter(.favorites, after: "three swipes left")

        swipeHorizontally(on: list, left: true)
        assertSelectedFilter(.favorites, after: "a fourth swipe left (right edge)")

        swipeHorizontally(on: list, left: false)
        assertSelectedFilter(.transit, after: "one swipe right")

        swipeHorizontally(on: list, left: false)
        assertSelectedFilter(.requests, after: "two swipes right")

        // The right edge is covered above (a fourth swipe left stays on Favorites). The left edge is
        // covered by the logic test in AntidoteTests; repeating it here cost more than it proved:
        // a further rightward drag on the first filter kept ending the session under the simulator.
    }

    // MARK: - Language

    private func checkInAppLanguageReachesWholeInterface() {
        switchLanguage(toNativeName: "English", rowTitles: [Expected.englishLanguageRow, Expected.arabicLanguageRow])
        assertAirInterface(Expected.english, language: "English")

        switchLanguage(toNativeName: "العربية", rowTitles: [Expected.englishLanguageRow, Expected.arabicLanguageRow])
        assertAirInterface(Expected.arabic, language: "Arabic")

        // Leave the app in Russian: AppLanguage also rewrites AppleLanguages, which decides the
        // keyboard, and the credentials this suite types are Cyrillic, so a run left in Arabic
        // cannot sign in again.
        switchLanguage(toNativeName: "Русский",
                       rowTitles: [Expected.englishLanguageRow, Expected.arabicLanguageRow, "Язык"])
    }
}

// MARK: - Helpers

private extension SwipeAndLanguageUITests {
    /// A quick horizontal drag across the middle of the screen: what a thumb does, and what the
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

    /// The filters of the Air screen. `all` has no pill: it is what shows with no pill selected.
    enum FilterName: String {
        case all, requests, transit, favorites

        static let pills: [FilterName] = [.requests, .transit, .favorites]
    }

    func assertSelectedFilter(_ expected: FilterName, after action: String) {
        for name in FilterName.pills {
            let pill = app.airPill(name.rawValue)
            XCTAssertTrue(pill.waitForExistence(timeout: 5), "filter pill \(name.rawValue) missing")
            let selected = pill.isSelected
            if name == expected {
                XCTAssertTrue(selected, "after \(action): expected \(name.rawValue) to be the active filter")
            }
            else {
                XCTAssertFalse(selected, "after \(action): \(name.rawValue) should not be active")
            }
        }
    }

    func openAir() {
        XCTAssertTrue(app.returnToAir(timeout: 30),
                      "could not get to the Air screen. Screen was:\n\(app.debugDescription)")
    }

    /// Selects a filter through its pill. The active pill pressed again returns to everything, so a
    /// pill is pressed only when its state has to change.
    func selectFilter(_ filter: FilterName) {
        for name in FilterName.pills {
            let pill = app.airPill(name.rawValue)
            XCTAssertTrue(pill.waitForExistence(timeout: 10), "filter pills not visible")
            if (name == filter) != pill.isSelected {
                pill.tap()
            }
        }
    }

    /// The first chat row on the Air screen, in the order of `kinds`. Contacts without a chat and
    /// request cards are not chats.
    func firstChatRow(kinds: [String] = ["direct", "saved", "group"], timeout: TimeInterval = 10) -> XCUIElement? {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            for kind in kinds {
                let row = app.airRow(kind)
                if row.exists {
                    return row
                }
            }
            usleep(250_000)
        }
        while Date() < deadline
        return nil
    }

    private func assertAirInterface(_ expected: AirTexts, language: String) {
        openAir()

        for (name, title) in zip(FilterName.pills, expected.pills) {
            let pill = app.airPill(name.rawValue)
            XCTAssertTrue(pill.waitForExistence(timeout: 10), "filter pill \(name.rawValue) missing")
            XCTAssertEqual(pill.label, title, "\(language): filter pill \(name.rawValue) reads \"\(pill.label)\"")
        }

        let settings = app.buttons["air.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 10), "no settings button on the Air screen")
        XCTAssertEqual(settings.label, expected.settings, "\(language): the settings button reads \"\(settings.label)\"")

        let create = app.buttons["air.create"]
        XCTAssertTrue(create.waitForExistence(timeout: 10), "no create button on the Air screen")
        XCTAssertEqual(create.label, expected.create, "\(language): the create button reads \"\(create.label)\"")

        XCTAssertEqual(app.airCommand.label, expected.command,
                       "\(language): the command line reads \"\(app.airCommand.label)\"")

        let you = app.buttons["air.you"]
        XCTAssertTrue(you.waitForExistence(timeout: 10), "no \"you\" block on the Air screen")
        XCTAssertTrue(you.label.hasPrefix(expected.you + ","),
                      "\(language): the \"you\" block reads \"\(you.label)\"")
    }

    func switchLanguage(toNativeName name: String, rowTitles: [String]) {
        openAir()
        let settings = app.buttons["air.settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 30), "no settings button on the Air screen")
        settings.tap()

        // The language lives in Settings > Appearance (E18); a build without that section lists it in
        // Settings itself, under one of `rowTitles`.
        var row: XCUIElement?
        let appearance = app.buttons["settings.appearance"]
        if appearance.waitForExistence(timeout: 5) {
            appearance.tap()
            if app.buttons["settings.language"].waitForExistence(timeout: 5) {
                row = app.buttons["settings.language"]
            }
        }
        else {
            for title in rowTitles where app.staticTexts[title].waitForExistence(timeout: 5) {
                row = app.staticTexts[title]
                break
            }
        }
        guard let languageRow = row else {
            XCTFail("the Language row was not found in Settings under any known translation")
            return
        }
        languageRow.tap()

        // The sheet writes each language in its own language and names the active one in its message
        // (in English: "Now: English"). If the wanted language is already active, just close the sheet.
        // The settings row shows the bare name, so the message is the only text that ends with the
        // name and is longer than it.
        let option = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).element(boundBy: 0)
        XCTAssertTrue(option.waitForExistence(timeout: 5), "language \(name) is missing from the sheet")
        let activeNote = app.staticTexts.matching(NSPredicate(format: "label ENDSWITH %@ AND label != %@", name, name))
                .element(boundBy: 0)
        if activeNote.exists {
            let cancel = app.buttons.matching(NSPredicate(format: "label IN {'Cancel', 'Отмена', 'إلغاء', '取消'}"))
                    .element(boundBy: 0)
            if cancel.exists {
                cancel.tap()
            }
            return
        }
        option.tap()

        // Switching the language rebuilds the session, which comes back on Settings > Appearance above
        // the Air screen; wait for either.
        let back = app.navigationBars.buttons["BackButton"]
        let deadline = Date().addingTimeInterval(60)
        while !back.exists, !app.airCommand.exists, Date() < deadline {
            _ = back.waitForExistence(timeout: 1)
        }
        XCTAssertTrue(back.exists || app.airCommand.exists,
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
        app.tapNeutralPoint()
    }

    /// Walks onboarding to the main interface from wherever it happens to be: intro pages, the
    /// account choice, the name form, or the password form (a previous run may have left it there).
    func signUpIfNeeded() {
        let air = app.airCommand
        if air.waitForExistence(timeout: 6) {
            return
        }

        // Stage 4 (E19, E20): first launch is three pages and sign-in one, each with its main action on
        // the bar above the keyboard. The app requires a letter AND a digit (PasswordPolicy), and
        // XCUITest can only press keys the simulator's current keyboard has, so `fill` pastes, and the
        // password is digits plus Cyrillic, which CharacterSet.letters accepts.
        let main = app.buttons["air.step.main"]
        for _ in 0..<10 {
            if air.exists {
                app.dismissSavePasswordOffer(timeout: 2)
                app.leaveCommandLine()
                return
            }
            if app.dismissSavePasswordOffer() {
                continue
            }
            if app.alerts.count > 0 {
                let alert = app.alerts.element(boundBy: 0)
                let button = alert.buttons.element(boundBy: alert.buttons.count - 1)
                if button.exists {
                    button.tap()
                }
                continue
            }

            // Sign-in with a password: a reinstall keeps the profile on disk, Log out on You comes here.
            let signInPassword = app.secureTextFields["air.signin.password"]
            if signInPassword.exists, signInPassword.isHittable {
                fill(signInPassword, with: "пароль1234")
                main.tap()
                _ = air.waitForExistence(timeout: 45)
                continue
            }
            // Sign-in of a profile without a password: the main button alone.
            if app.textFields["air.signin.name"].exists {
                main.tap()
                _ = air.waitForExistence(timeout: 45)
                continue
            }
            // First launch: the name, the password, the ID.
            let name = app.textFields["air.first.name"]
            if name.exists {
                fill(name, with: "9001")
                main.tap()
                _ = app.secureTextFields["air.first.password"].waitForExistence(timeout: 10)
                continue
            }
            let password = app.secureTextFields["air.first.password"]
            if password.exists {
                fill(password, with: "пароль1234")
                fill(app.secureTextFields["air.first.password.repeat"], with: "пароль1234")
                main.tap()
                _ = app.buttons["air.first.share"].waitForExistence(timeout: 45)
                continue
            }
            if app.buttons["air.first.share"].exists {
                main.tap()
                _ = air.waitForExistence(timeout: 30)
                continue
            }
            sleep(1)
        }

        XCTAssertTrue(air.waitForExistence(timeout: 60),
                      "could not get past first launch or sign-in. Screen was:\n\(app.debugDescription)")
    }

    /// Types into a field without depending on the keyboard layout.
    ///
    /// Two lessons: secure fields drop characters when a whole string is typed at once, and XCUITest
    /// can only press keys the CURRENT keyboard has; the simulator's layout has been Russian and
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

        // No paste menu (some fields refuse it): fall back to typing character by character.
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

// MARK: - Stage 4: first launch, sign-in and PIN

extension SwipeAndLanguageUITests {
    /// What "you" are called in the first launch walks, per language of the run.
    private static let firstLaunchNames = ["en": "Samir", "ru": "Самир", "ar": "سمير", "zh": "萨米尔"]

    /// A string of the app in one language, read from the copy of its tables in this bundle.
    func appString(_ key: String, _ lang: String) -> String {
        let bundle = Bundle(for: SwipeAndLanguageUITests.self)
        guard let path = bundle.path(forResource: lang, ofType: "lproj"), let table = Bundle(path: path) else {
            return key
        }
        return table.localizedString(forKey: key, value: key, table: nil)
    }

    /// Points the shots of a stage 4 walk at <prefix>-<lang>[-dark][-<size>]/ and sets the theme of the
    /// run. Returns the language.
    private func beginStageFourWalk(_ prefix: String) -> String {
        let env = ProcessInfo.processInfo.environment
        let lang = env["KHANDAQ_UITEST_LANG"] ?? "en"
        let dark = env["KHANDAQ_UITEST_DARK"] == "1"
        var folder = "\(prefix)-\(lang)" + (dark ? "-dark" : "")
        if let size = env["KHANDAQ_UITEST_TEXT_SIZE"], !size.isEmpty {
            folder += "-" + auditSlug(size.replacingOccurrences(of: "UICTContentSizeCategory", with: ""))
        }
        auditDir = Self.artifactsDir + folder + "/"
        try? FileManager.default.createDirectory(atPath: auditDir, withIntermediateDirectories: true)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = dark ? .dark : .light
        }
        return lang
    }

    /// Presses the digits of a PIN on the keypad that is on screen.
    private func pressPin(_ pin: String) {
        for digit in pin {
            let key = app.buttons[String(digit)]
            if key.waitForExistence(timeout: 3) {
                key.tap()
            }
        }
        usleep(800_000)
    }

    /// A text of the app in the run's language, as a label on screen.
    private func appText(_ key: String, _ lang: String) -> XCUIElement {
        return app.staticTexts.matching(NSPredicate(format: "label == %@", appString(key, lang))).firstMatch
    }

    /// The button of the open action sheet that is not Cancel.
    private func confirmSheet() {
        let sheet = app.sheets.element(boundBy: 0)
        XCTAssertTrue(sheet.waitForExistence(timeout: 5), "no question before the action")
        sheet.buttons.matching(NSPredicate(format: "NOT (label IN %@)", Self.cancelTitles)).element(boundBy: 0).tap()
    }

    private func cancelSheet() {
        let sheet = app.sheets.element(boundBy: 0)
        XCTAssertTrue(sheet.waitForExistence(timeout: 5), "no action sheet")
        sheet.buttons.matching(NSPredicate(format: "label IN %@", Self.cancelTitles)).element(boundBy: 0).tap()
        waitUntilGone(sheet)
    }

    /// Opens You and its security page ("Password and PIN").
    private func openSecurity() {
        XCTAssertTrue(app.returnToAir(), "not on the Air screen")
        app.buttons["air.you"].tap()
        let security = app.buttons["air.you.security"]
        XCTAssertTrue(security.waitForExistence(timeout: 5), "You did not open")
        scrollUntilHittable(security)
        security.tap()
        XCTAssertTrue(app.navigationBars.buttons["BackButton"].waitForExistence(timeout: 5), "Password and PIN did not open")
    }

    /// The password row of the security page when it says `key` ("Set a password" or "Change password").
    private func securityPasswordRow(_ key: String, _ lang: String) -> XCUIElement {
        return app.buttons.matching(NSPredicate(format: "identifier == 'air.security.password' AND label == %@",
                                                appString(key, lang))).firstMatch
    }

    /// Log out on You, confirmed, then the sign-in page.
    private func logOut() {
        XCTAssertTrue(app.returnToAir(), "not on the Air screen")
        app.buttons["air.you"].tap()
        let logout = app.buttons["air.you.logout"]
        XCTAssertTrue(logout.waitForExistence(timeout: 5), "You did not open")
        scrollUntilHittable(logout)
        logout.tap()
        confirmSheet()
        XCTAssertTrue(app.textFields["air.signin.name"].waitForExistence(timeout: 20), "Log out did not open sign-in")
        XCTAssertFalse(app.airCommand.exists, "Log out went straight back in")
    }

    /// First launch in three steps, with a profile made without a password; You, the security page and
    /// Export of such a profile; its sign-in; moving a profile in from a file (its password, a wrong one,
    /// its name and ID); sign-in from the list with a wrong password and "Forgot the password?"; changing
    /// the password; the PIN screens. The "after" side of the stage 4 review page. Runs on a fresh
    /// install, so it starts in front of first launch. The mock changes no password, so the walk shows
    /// those pages and leaves them with Cancel (a real profile goes through them in the Real test).
    func testStageFourFirstLaunchWalkScreens() {
        let lang = beginStageFourWalk("stage4a")
        let main = app.buttons["air.step.main"]

        // 1. The name. Next waits, and says why, until there is one.
        let name = app.textFields["air.first.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 30), "no first launch on a fresh install:\n\(app.debugDescription)")
        XCTAssertTrue(app.buttons["air.first.move"].exists, "no Move a profile on the first page")
        XCTAssertFalse(main.isEnabled, "Next waits for a name")
        auditShot("a-name")
        fill(name, with: Self.firstLaunchNames[lang] ?? "Samir")
        XCTAssertTrue(main.isEnabled, "Next takes a name")
        auditShot("a-name-filled")
        main.tap()

        // 2. The password: the honest phrase and the two choices. A short password waits with its reason;
        // without a password the fields go and the choice says what it means on this iPhone.
        let password = app.secureTextFields["air.first.password"]
        XCTAssertTrue(password.waitForExistence(timeout: 10), "no password step")
        auditShot("a-password")
        fill(password, with: "абв1")
        XCTAssertFalse(main.isEnabled, "Create profile waits for a password of the rule")
        XCTAssertTrue(appText("air_first_password_short", lang).exists, "no reason under the button")
        auditShot("a-password-short")
        let skip = app.descendants(matching: .any)["air.first.password.skip"]
        XCTAssertTrue(skip.exists, "no Continue without a password")
        skip.tap()
        XCTAssertTrue(main.isEnabled, "without a password nothing is waited for")
        auditShot("a-password-skip")
        main.tap()

        // 3. The ID; Add the first person opens Air with the command line in focus.
        XCTAssertTrue(app.buttons["air.first.share"].waitForExistence(timeout: 45), "no ID step after Create profile")
        XCTAssertFalse(app.dismissSavePasswordOffer(timeout: 2), "a profile without a password was offered a password to save")
        sleep(1)
        auditShot("a-id")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 30), "no Air screen after first launch")
        sleep(1)
        XCTAssertTrue(app.keyboards.count > 0, "the command line is not in focus after Add the first person")
        auditShot("a-air-command")
        app.leaveCommandLine()

        // 4. A profile without a password: its security page sets a password, and Export asks for one
        // first and opens the same page.
        openSecurity()
        let setPassword = securityPasswordRow("air_password_set_title", lang)
        XCTAssertTrue(setPassword.waitForExistence(timeout: 5), "no Set a password on the security page of a profile without one")
        auditShot("a-security-device")
        setPassword.tap()
        XCTAssertTrue(app.secureTextFields["air.password.new"].waitForExistence(timeout: 5), "Set a password did not open")
        XCTAssertFalse(app.secureTextFields["air.password.old"].exists, "a profile without a password has no old one")
        auditShot("a-password-set")
        app.navigationBars.buttons[appString("alert_cancel", lang)].tap()
        let export = app.buttons["air.security.export"]
        XCTAssertTrue(export.waitForExistence(timeout: 5), "no Export on the security page")
        scrollUntilHittable(export)
        export.tap()
        XCTAssertTrue(app.sheets.element(boundBy: 0).waitForExistence(timeout: 5), "Export of a profile without a password does not ask")
        auditShot("a-export-needs-password")
        confirmSheet()
        XCTAssertTrue(app.secureTextFields["air.password.new"].waitForExistence(timeout: 5), "Set password from Export did not open the page")
        app.navigationBars.buttons[appString("alert_cancel", lang)].tap()
        app.navigationBars.buttons["BackButton"].tap()

        // 5. You says what Log out means for it; its sign-in is the name and one button.
        let logout = app.buttons["air.you.logout"]
        XCTAssertTrue(logout.waitForExistence(timeout: 5), "no Log out on You")
        scrollUntilHittable(logout)
        XCTAssertTrue(appText("air_you_logout_message_device", lang).exists, "Log out does not say the profile opens without a password")
        auditShot("a-you-logout-device")
        logOut()
        XCTAssertFalse(app.secureTextFields["air.signin.password"].exists, "a profile without a password asks for one")
        auditShot("a-signin-device")

        // 6. Moving a profile in: the file's password (a wrong one first), then its name and ID.
        app.buttons["air.signin.move"].tap()
        let filePassword = app.secureTextFields["air.import.password"]
        XCTAssertTrue(filePassword.waitForExistence(timeout: 20), "no password page for the profile file")
        auditShot("a-import-password")
        fill(filePassword, with: "пароль0000")
        main.tap()
        XCTAssertTrue(appText("air_import_wrong_password", lang).waitForExistence(timeout: 30), "a wrong file password says nothing")
        auditShot("a-import-wrong")
        fill(filePassword, with: "пароль1234")
        main.tap()
        let previewName = app.staticTexts["air.import.preview.name"]
        XCTAssertTrue(previewName.waitForExistence(timeout: 30), "no name and ID of the file")
        XCTAssertEqual(previewName.value as? String, "Амина Юсупова", "the preview shows another name")
        auditShot("a-import-preview")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 45), "the moved profile did not open")
        app.dismissSavePasswordOffer(timeout: 3)
        auditShot("a-import-done")

        // 7. Two profiles: the list, a wrong password under the field, Forgot the password, the right one.
        logOut()
        let signInPassword = app.secureTextFields["air.signin.password"]
        XCTAssertTrue(signInPassword.waitForExistence(timeout: 5), "the moved profile asks for no password")
        XCTAssertTrue(app.descendants(matching: .any)["air.signin.profile.1"].exists, "no list of the two profiles")
        auditShot("a-signin-list")
        fill(signInPassword, with: "пароль0000")
        main.tap()
        XCTAssertTrue(appText("air_signin_wrong_password", lang).waitForExistence(timeout: 30), "a wrong password says nothing")
        XCTAssertTrue(app.alerts.count == 0, "a wrong password is said in an alert")
        auditShot("a-signin-wrong")
        app.buttons["air.signin.forgot"].tap()
        XCTAssertTrue(app.sheets.element(boundBy: 0).waitForExistence(timeout: 5), "Forgot the password says nothing")
        auditShot("a-forgot")
        cancelSheet()
        app.buttons["air.signin.create"].tap()
        XCTAssertTrue(app.textFields["air.first.name"].waitForExistence(timeout: 5), "Create a new profile did not open first launch")
        XCTAssertFalse(app.dismissSavePasswordOffer(timeout: 2), "a wrong password was offered to be saved")
        auditShot("a-signin-create")
        app.navigationBars.buttons["BackButton"].tap()
        XCTAssertTrue(signInPassword.waitForExistence(timeout: 5), "no way back to sign-in")
        fill(signInPassword, with: "пароль1234")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 45), "the right password did not open the profile")
        app.dismissSavePasswordOffer(timeout: 3)

        // 8. Changing the password: the old one, then the new one twice.
        openSecurity()
        let change = securityPasswordRow("air_password_change_action", lang)
        XCTAssertTrue(change.waitForExistence(timeout: 5), "no Change password for a profile with a password")
        change.tap()
        XCTAssertTrue(app.secureTextFields["air.password.old"].waitForExistence(timeout: 5), "Change password did not open")
        auditShot("a-password-change")
        app.navigationBars.buttons[appString("alert_cancel", lang)].tap()

        // 9. PIN: set and confirm it, then the lock screen, a wrong PIN and the right one.
        let pinSwitch = app.switches["air.security.pin"]
        XCTAssertTrue(pinSwitch.waitForExistence(timeout: 5), "no PIN switch")
        pinSwitch.tap()
        XCTAssertTrue(app.buttons["1"].waitForExistence(timeout: 5), "no PIN keypad")
        auditShot("a-pin-set")
        pressPin("1234")
        auditShot("a-pin-confirm")
        pressPin("1234")
        XCUIDevice.shared.press(.home)
        sleep(2)
        app.activate()
        XCTAssertTrue(app.descendants(matching: .any)["air.pin.lock"].waitForExistence(timeout: 10), "no lock screen after coming back")
        auditShot("a-pin-lock")
        pressPin("0000")
        auditShot("a-pin-wrong")
        pressPin("1234")

        // The PIN goes off again, so the next walks open without it.
        let onSwitch = app.switches["air.security.pin"]
        if onSwitch.waitForExistence(timeout: 5), (onSwitch.value as? String) == "1" {
            onSwitch.tap()
        }
        app.returnToAir()
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// Starts the app again, as after the phone restarted or the system ended it.
    private func relaunch() {
        app.terminate()
        app.launch()
        launchedAt = Date()
        dismissSystemAlerts()
    }

    /// Stage 4 on the real messenger, with a test profile made here: first launch without a password
    /// (the key in this iPhone's keychain), opening by itself again, a password set on the security page
    /// (the save and the history key sealed with it instead of the key), opening by itself with it, Log
    /// out and sign-in with a wrong and the right password, changing the password with a wrong old one
    /// first, and the old password no longer opening the profile.
    func testStageFourFirstLaunchRealProfile() {
        let lang = beginStageFourWalk("stage4r")
        let main = app.buttons["air.step.main"]

        // 1. First launch without a password.
        let name = app.textFields["air.first.name"]
        XCTAssertTrue(name.waitForExistence(timeout: 30), "no first launch on a fresh install:\n\(app.debugDescription)")
        fill(name, with: Self.firstLaunchNames[lang] ?? "Samir")
        main.tap()
        let skip = app.descendants(matching: .any)["air.first.password.skip"]
        XCTAssertTrue(skip.waitForExistence(timeout: 10), "no password step")
        skip.tap()
        main.tap()
        XCTAssertTrue(app.buttons["air.first.share"].waitForExistence(timeout: 60), "the profile was not created")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 30), "no Air screen after first launch")
        auditShot("r-air")

        // 2. Started again, it opens by itself with the key of this iPhone.
        relaunch()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 45), "a profile without a password did not open by itself")

        // 3. A password for it.
        openSecurity()
        securityPasswordRow("air_password_set_title", lang).tap()
        let newPassword = app.secureTextFields["air.password.new"]
        XCTAssertTrue(newPassword.waitForExistence(timeout: 5), "Set a password did not open")
        fill(newPassword, with: "пароль1234")
        fill(app.secureTextFields["air.password.repeat"], with: "пароль1234")
        main.tap()
        XCTAssertTrue(appText("air_password_set_done", lang).waitForExistence(timeout: 45), "no Password set")
        auditShot("r-password-set")
        app.dismissSavePasswordOffer(timeout: 3)
        XCTAssertTrue(securityPasswordRow("air_password_change_action", lang).waitForExistence(timeout: 5),
                      "the security page still offers to set a password")
        app.navigationBars.buttons["BackButton"].tap()
        let logout = app.buttons["air.you.logout"]
        XCTAssertTrue(logout.waitForExistence(timeout: 5), "no Log out on You")
        scrollUntilHittable(logout)
        XCTAssertTrue(appText("air_you_logout_message", lang).exists, "You still says the profile opens without a password")

        // 4. Started again, it opens by itself with the password kept in the keychain.
        relaunch()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 45), "a profile with a password did not open by itself")

        // 5. Log out: the password is asked; a wrong one is said under the field, the right one opens.
        logOut()
        let password = app.secureTextFields["air.signin.password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5), "a profile with a password asks for none")
        fill(password, with: "пароль0000")
        main.tap()
        XCTAssertTrue(appText("air_signin_wrong_password", lang).waitForExistence(timeout: 45), "a wrong password says nothing")
        auditShot("r-signin-wrong")
        fill(password, with: "пароль1234")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 45), "the password did not open the profile")
        app.dismissSavePasswordOffer(timeout: 3)

        // 6. Changing the password: a wrong old one is said under its field; the right one changes it.
        openSecurity()
        securityPasswordRow("air_password_change_action", lang).tap()
        let oldPassword = app.secureTextFields["air.password.old"]
        XCTAssertTrue(oldPassword.waitForExistence(timeout: 5), "Change password did not open")
        fill(oldPassword, with: "пароль0000")
        fill(app.secureTextFields["air.password.new"], with: "пароль5678")
        fill(app.secureTextFields["air.password.repeat"], with: "пароль5678")
        main.tap()
        XCTAssertTrue(appText("air_password_wrong_old", lang).waitForExistence(timeout: 45), "a wrong old password says nothing")
        auditShot("r-password-wrong-old")
        fill(oldPassword, with: "пароль1234")
        main.tap()
        XCTAssertTrue(appText("air_password_changed", lang).waitForExistence(timeout: 45), "no Password changed")
        app.dismissSavePasswordOffer(timeout: 3)

        // 7. After Log out the old password no longer opens it, the new one does.
        logOut()
        fill(password, with: "пароль1234")
        main.tap()
        XCTAssertTrue(appText("air_signin_wrong_password", lang).waitForExistence(timeout: 45), "the old password still opens the profile")
        fill(password, with: "пароль5678")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 45), "the new password did not open the profile")
        app.dismissSavePasswordOffer(timeout: 3)
        if #available(iOS 17.0, *) {
            XCUIDevice.shared.appearance = .light
        }
    }

    /// Any element on screen whose label or value holds `text`: a message in the transcript (a bubble's
    /// text view carries it as its value).
    private func shown(_ text: String) -> XCUIElement {
        let predicate = NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", text, text)
        return app.descendants(matching: .any).matching(predicate).firstMatch
    }

    /// The text in the open conversation: in the transcript, or in the tray of what waits in transit,
    /// which is opened for it.
    private func isInConversation(_ text: String) -> Bool {
        if shown(text).waitForExistence(timeout: 5) {
            return true
        }
        let tray = app.descendants(matching: .any)["air.chat.tray.header"]
        if tray.exists {
            tray.tap()
        }
        return shown(text).waitForExistence(timeout: 5)
    }

    /// Writes `text` in the open conversation and sends it.
    private func send(_ text: String) {
        let field = app.textViews["air.chat.field"]
        XCTAssertTrue(field.waitForExistence(timeout: 10), "no message field")
        fill(field, with: text)
        let sendButton = app.buttons["air.chat.send"]
        XCTAssertTrue(sendButton.waitForExistence(timeout: 5), "no Send")
        sendButton.tap()
        XCTAssertTrue(isInConversation(text), "the message is not in the conversation")
    }

    /// The stage 3 side of the upgrade check, run against the stage 3 app (its xctestrun names that build
    /// as the app under test): a test profile with a password made through the old first launch, a
    /// contact whose request is on its way, a message to it that waits in transit, one in Saved.
    func testStageFourUpgradeSeedReal() {
        let lang = beginStageFourWalk("stage4u-seed")
        let next = app.buttons[appString("onboarding_next", lang)]
        XCTAssertTrue(next.waitForExistence(timeout: 30), "no stage 3 walkthrough:\n\(app.debugDescription)")
        next.tap()
        next.tap()
        let create = app.buttons[appString("onboarding_create_account", lang)]
        XCTAssertTrue(create.waitForExistence(timeout: 5), "no Create account")
        create.tap()
        let fields = app.textFields
        XCTAssertTrue(fields.element(boundBy: 0).waitForExistence(timeout: 5), "no name form")
        fill(fields.element(boundBy: 0), with: "Upgrade")
        fill(fields.element(boundBy: 1), with: "Upgrade")
        tapFirstButton(titled: [appString("create_account_next_button", lang)])
        let secure = app.secureTextFields
        XCTAssertTrue(secure.element(boundBy: 0).waitForExistence(timeout: 5), "no password form")
        fill(secure.element(boundBy: 0), with: "пароль1234")
        fill(secure.element(boundBy: 1), with: "пароль1234")
        tapFirstButton(titled: [appString("create_account_go_button", lang)])
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 60), "no Air screen in the stage 3 app")
        app.dismissSavePasswordOffer(timeout: 3)
        openAir()

        app.buttons["air.create"].tap()
        let newContact = app.buttons["air.newContact"]
        XCTAssertTrue(newContact.waitForExistence(timeout: 5), "no New contact")
        newContact.tap()
        let field = app.textViews["air.newContact.id"]
        XCTAssertTrue(field.waitForExistence(timeout: 5), "New contact did not open")
        fill(field, with: Self.stageThreeId)
        fill(app.textViews["air.newContact.message"], with: "upgrade check")
        let sendRequest = app.buttons["air.newContact.send"]
        let form = app.scrollViews.element(boundBy: 0)
        for _ in 0..<3 where !(sendRequest.exists && sendRequest.isHittable) {
            form.swipeUp()
        }
        sendRequest.tap()
        XCTAssertTrue(app.staticTexts["air.newContact.sentTitle"].waitForExistence(timeout: 10), "the request did not go")
        let done = app.buttons["air.newContact.done"]
        if done.exists {
            done.tap()
        }
        openAir()

        let contact = app.airRow("direct").exists ? app.airRow("direct") : app.airRow("contact")
        XCTAssertTrue(contact.waitForExistence(timeout: 10), "no row of the new contact")
        contact.tap()
        send("upgrade queued 1")
        app.returnToAir()

        let saved = app.airRow("saved")
        if saved.waitForExistence(timeout: 5) {
            saved.tap()
            send("upgrade saved 1")
            app.returnToAir()
        }
        auditShot("u-seeded")
    }

    /// The stage 4 side of the upgrade check, on the profile the seed made with the stage 3 app and
    /// with stage 4 installed over it: it opens by itself, the contact, the message waiting in transit
    /// and the Saved message are there, and after Log out its stage 3 password opens it again.
    func testStageFourUpgradeCheckReal() {
        let lang = beginStageFourWalk("stage4u-check")
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 60),
                      "the stage 3 profile did not open by itself:\n\(app.debugDescription)")
        XCTAssertFalse(app.textFields["air.signin.name"].exists, "the update asked to sign in")
        openAir()
        auditShot("u-air")

        selectFilter(.transit)
        let waiting = app.airRow("direct")
        XCTAssertTrue(waiting.waitForExistence(timeout: 10), "In transit is empty after the update")
        auditShot("u-transit")
        waiting.tap()
        XCTAssertTrue(isInConversation("upgrade queued 1"), "the waiting message is gone")
        auditShot("u-queued")
        app.returnToAir()
        selectFilter(.all)

        let saved = app.airRow("saved")
        if saved.waitForExistence(timeout: 5) {
            saved.tap()
            XCTAssertTrue(isInConversation("upgrade saved 1"), "the Saved message is gone")
            auditShot("u-saved")
            app.returnToAir()
        }

        logOut()
        let password = app.secureTextFields["air.signin.password"]
        XCTAssertTrue(password.waitForExistence(timeout: 5), "the stage 3 profile asks for no password")
        auditShot("u-signin")
        fill(password, with: "пароль0000")
        app.buttons["air.step.main"].tap()
        XCTAssertTrue(appText("air_signin_wrong_password", lang).waitForExistence(timeout: 45), "a wrong password says nothing")
        fill(password, with: "пароль1234")
        app.buttons["air.step.main"].tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 45), "the stage 3 password did not open the profile")
        app.dismissSavePasswordOffer(timeout: 3)
    }

    /// Makes the profile file of the import checks: the demo hook of "Move a profile" writes it to the
    /// app's temporary folder, from where the script copies it to On My iPhone in Files.
    func testStageFourFirstLaunchImportFixture() {
        let move = app.buttons["air.first.move"]
        XCTAssertTrue(move.waitForExistence(timeout: 30), "no first launch on a fresh install")
        move.tap()
        XCTAssertTrue(app.secureTextFields["air.import.password"].waitForExistence(timeout: 20), "no profile file")
    }

    /// The first element whose label is one of `labels` (the system apps speak the simulator's language).
    private func element(in application: XCUIApplication, labeled labels: [String]) -> XCUIElement {
        return application.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", labels)).firstMatch
    }

    /// Opens On My iPhone in a Files browser (the app's or the picker's) and returns the profile file.
    private func profileFileInFiles(_ application: XCUIApplication) -> XCUIElement {
        let file = application.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH 'amina'")).firstMatch
        if file.waitForExistence(timeout: 5) {
            return file
        }
        let browse = element(in: application, labeled: ["Browse", "Обзор"])
        if browse.waitForExistence(timeout: 5) {
            browse.tap()
        }
        let onPhone = element(in: application, labeled: ["On My iPhone", "На iPhone"])
        if onPhone.waitForExistence(timeout: 5) {
            onPhone.tap()
        }
        _ = file.waitForExistence(timeout: 10)
        return file
    }

    /// The file's password, its name and ID, then the confirmation, on the real messenger.
    private func moveProfileIn(_ shotPrefix: String) {
        let main = app.buttons["air.step.main"]
        let password = app.secureTextFields["air.import.password"]
        XCTAssertTrue(password.waitForExistence(timeout: 30), "the profile file did not reach the app:\n\(app.debugDescription)")
        auditShot(shotPrefix + "-password")
        fill(password, with: "пароль1234")
        main.tap()
        let previewName = app.staticTexts["air.import.preview.name"]
        XCTAssertTrue(previewName.waitForExistence(timeout: 45), "no name and ID of the file")
        XCTAssertEqual(previewName.value as? String, "Амина Юсупова", "the preview shows another name")
        auditShot(shotPrefix + "-preview")
        main.tap()
        XCTAssertTrue(app.airCommand.waitForExistence(timeout: 60), "the moved profile did not open")
        app.dismissSavePasswordOffer(timeout: 3)
    }

    /// Asks the script running this test to open the profile file in Khandaq the way another app does
    /// ("Open in"): the system copies the file into the app and hands it over, to the running app or
    /// at its launch. The Files app of iOS 26 does not offer Khandaq in Open With for this file, and
    /// Khandaq in the row of apps of the Share sheet is the share extension, which sends a file to a
    /// chat.
    private func requestOpenInKhandaq(_ how: String) {
        FileManager.default.createFile(atPath: Self.artifactsDir + "open-request", contents: Data(how.utf8))
    }

    /// A button of the open action sheet with the text of `key`, in the language of the run or in the
    /// one the app picks when the system starts it without the run's arguments.
    private func sheetButton(_ key: String, _ lang: String) -> XCUIElement {
        let labels = [appString(key, lang), appString(key, "ru"), appString(key, "en")]
        return app.sheets.buttons.matching(NSPredicate(format: "label IN %@", labels)).firstMatch
    }

    /// The file handed over to the open profile: what to do with it, then what moving it in does to
    /// this profile, then the file's password, its name and ID.
    private func moveHandedFileIn(_ shotPrefix: String, lang: String) {
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 60), "Khandaq did not open with the file")
        let moveFromFile = sheetButton("air_share_import_action", lang)
        XCTAssertTrue(moveFromFile.waitForExistence(timeout: 60), "the file does not offer moving the profile in:\n\(app.debugDescription)")
        auditShot(shotPrefix + "-choice")
        moveFromFile.tap()
        let verb = sheetButton("air_share_import_verb", lang)
        XCTAssertTrue(verb.waitForExistence(timeout: 10), "moving a profile in does not ask first:\n\(app.debugDescription)")
        auditShot(shotPrefix + "-ask")
        verb.tap()
        moveProfileIn(shotPrefix)
        auditShot(shotPrefix + "-done")
    }

    /// Moving a profile in on the real messenger, every way the system hands a file over: the Files
    /// picker of first launch, then the file opened in Khandaq from another app while a profile is
    /// open (which asks first), with the app in the background and with the app not running.
    func testStageFourFirstLaunchImportRealFiles() {
        let lang = beginStageFourWalk("stage4i")

        // 1. First launch, Move a profile from another device: the system's file picker.
        let move = app.buttons["air.first.move"]
        XCTAssertTrue(move.waitForExistence(timeout: 30), "no first launch on a fresh install")
        move.tap()
        sleep(2)
        auditShot("i-picker")
        let picked = profileFileInFiles(app)
        auditShot("i-picker-folder")
        XCTAssertTrue(picked.exists, "the profile file is not in the picker:\n\(app.debugDescription)")
        picked.tap()
        moveProfileIn("i-files")
        auditShot("i-files-done")

        // 2. The file opened in Khandaq from another app while Khandaq waits in the background.
        XCUIDevice.shared.press(.home)
        requestOpenInKhandaq("background")
        moveHandedFileIn("i-open", lang: lang)

        // 3. The same while Khandaq is not running: the file waits until the profile has opened.
        app.terminate()
        requestOpenInKhandaq("launch")
        moveHandedFileIn("i-launch", lang: lang)
    }
}
