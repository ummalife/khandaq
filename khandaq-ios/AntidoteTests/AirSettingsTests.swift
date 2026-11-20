// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Settings (redesign stage 3, E18): the sections by task with the profile file in a section of its own,
 the theme as a segment with the system first that says only a real change, and the attachment choice
 that stores what it shows. Files download by themselves from 0.2.57: on a new profile, once on an
 existing one, and the person's own choice after that, also for a file offered again.
 */
class AirSettingsTests: XCTestCase {
    private var attachmentsBefore = UserDefaultsManager.AutodownloadImages.Never

    override func setUp() {
        super.setUp()
        attachmentsBefore = UserDefaultsManager().autodownloadImages
    }

    override func tearDown() {
        UserDefaultsManager().autodownloadImages = attachmentsBefore
        super.tearDown()
    }

    func testSettingsListsTheSectionsByTaskWithTheProfileFileInItsOwn() {
        let settings = SettingsMainController(theme: ThemeAppearance.resolvedTheme(), submanagerObjects: nil)
        settings.loadViewIfNeeded()
        let rows = settings.view.allSubviews.compactMap { $0 as? AirSettingRow }.map { $0.accessibilityIdentifier ?? "" }
        XCTAssertEqual(Array(rows.prefix(8)), ["settings.appearance", "settings.main.language", "settings.notifications",
                                               "settings.privacy", "settings.transfer", "settings.network", "settings.faq",
                                               "settings.about"])
        // Debugging is the one more row, last, and only in a debug build.
        XCTAssertTrue(rows.count == 8 || (rows.count == 9 && rows.last == "settings.debug"), "\(rows)")

        let transfer = AirTransferController()
        transfer.loadViewIfNeeded()
        let transferRows = transfer.view.allSubviews.compactMap { $0 as? AirSettingRow }.map { $0.accessibilityIdentifier ?? "" }
        XCTAssertEqual(transferRows, ["settings.export", "settings.import"])
    }

    /// KHANDAQ (ideas 1): the language on the first screen of Settings, the one in effect beside it, and
    /// the sheet of Appearance behind it: each language in its own words, the current one named.
    func testSettingsHasTheLanguageWithItsSheet() throws {
        let settings = SettingsMainController(theme: ThemeAppearance.resolvedTheme(), submanagerObjects: nil)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = settings
        window.isHidden = false
        defer {
            settings.presentedViewController?.dismiss(animated: false, completion: nil)
            window.isHidden = true
        }
        settings.loadViewIfNeeded()
        settings.viewWillAppear(false)
        let row = try XCTUnwrap(settings.view.allSubviews.compactMap { $0 as? AirSettingRow }
                                    .first { $0.accessibilityIdentifier == "settings.main.language" })
        let current = AppLanguage.nativeName(for: AppLanguage.current)
        XCTAssertEqual(row.accessibilityLabel, String(localized: "settings_language"))
        XCTAssertEqual(row.accessibilityValue, current)

        row.sendActions(for: .touchUpInside)
        let sheet = try XCTUnwrap(settings.presentedViewController as? UIAlertController, "the row opened no sheet")
        XCTAssertEqual(sheet.preferredStyle, .actionSheet)
        XCTAssertEqual(sheet.title, String(localized: "settings_language_title"))
        XCTAssertEqual(sheet.message, String(localized: "settings_choice_current", current))
        XCTAssertEqual(sheet.actions.map { $0.title ?? "" },
                       AppLanguage.supportedCodes.map { AppLanguage.nativeName(for: $0) } + [String(localized: "alert_cancel")])
        XCTAssertEqual(sheet.actions.last?.style, .cancel)
    }

    /// KHANDAQ (ideas 1): the app ships the tables of the languages it speaks, and its Info.plist names
    /// those four; the old Antidote translations are no longer in the build.
    func testEveryLanguageOfTheSheetShipsItsTables() {
        for language in AppLanguage.supportedCodes {
            for table in ["Localizable", "InfoPlist"] {
                XCTAssertNotNil(Bundle.main.path(forResource: table, ofType: "strings", inDirectory: nil, forLocalization: language),
                                "\(language) has no \(table).strings")
            }
        }
        let declared = Bundle.main.object(forInfoDictionaryKey: "CFBundleLocalizations") as? [String]
        XCTAssertEqual(declared.map { Set($0) }, Set(AppLanguage.supportedCodes))
    }

    func testTheThemeIsASegmentWithTheSystemFirstShowingTheStoredChoice() {
        let appearance = AirAppearanceController(submanagerObjects: nil)
        appearance.loadViewIfNeeded()
        let options = appearance.themeControl.allSubviews.compactMap { $0.accessibilityIdentifier }
        XCTAssertEqual(options, ["settings.theme.system", "settings.theme.light", "settings.theme.dark"])
        XCTAssertEqual(appearance.themeControl.selectedIndex, ThemePreference.allCases.firstIndex(of: ThemeAppearance.preference))
    }

    func testTheSegmentSaysOnlyAChoiceThatChanges() {
        let segment = AirSegmentedControl(label: "Theme", titles: ["System", "Light", "Dark"],
                                          identifiers: ["system", "light", "dark"], selectedIndex: 0)
        var changes = 0
        segment.onValueChange {
            changes += 1
        }
        let options = segment.allSubviews.compactMap { $0 as? UIControl }.filter { $0.accessibilityIdentifier != nil }
        XCTAssertEqual(options.count, 3)

        options[1].sendActions(for: .touchUpInside)
        XCTAssertEqual(segment.selectedIndex, 1)
        XCTAssertEqual(changes, 1)
        XCTAssertTrue(options[1].accessibilityTraits & UIAccessibilityTraitSelected != 0)
        XCTAssertTrue(options[0].accessibilityTraits & UIAccessibilityTraitSelected == 0)

        options[1].sendActions(for: .touchUpInside)
        XCTAssertEqual(changes, 1, "the option already chosen is no change")

        segment.select(2)
        XCTAssertEqual(segment.selectedIndex, 2)
        XCTAssertEqual(changes, 1, "a choice shown from the stored value is not sent back")
    }

    func testTheSegmentStandsInAColumnWhenItsWordsDoNotFit() {
        XCTAssertFalse(laidOutSegment(width: 320, titles: ["System", "Light", "Dark"]).isStacked)
        XCTAssertTrue(laidOutSegment(width: 140, titles: ["As in the system", "Light", "Dark"]).isStacked)
    }

    func testAnAttachmentChoiceIsStoredAndMarked() {
        UserDefaultsManager().autodownloadImages = .Never
        let network = SettingsAdvancedController(theme: ThemeAppearance.resolvedTheme())
        network.loadViewIfNeeded()
        let rows = network.view.allSubviews.compactMap { $0 as? AirChoiceRow }
        XCTAssertEqual(rows.map { $0.accessibilityIdentifier ?? "" },
                       ["settings.attachments.never", "settings.attachments.wifi", "settings.attachments.always"])
        XCTAssertEqual(rows.map { $0.isSelected }, [true, false, false])

        rows[1].sendActions(for: .touchUpInside)
        XCTAssertEqual(UserDefaultsManager().autodownloadImages, .UsingWiFi)
        XCTAssertEqual(rows.map { $0.isSelected }, [false, true, false])
    }

    func testAValueStandsOnTheTrailingSideNextToTheChevron() {
        let row = AirSettingRow(title: "Appearance", value: "Match system")
        laidOut(row, width: 390)
        let value = row.valueLabel.convert(row.valueLabel.bounds, to: row)
        let chevron = row.allSubviews.first(where: { $0 is UIImageView })!
        let chevronFrame = chevron.convert(chevron.bounds, to: row)
        XCTAssertEqual(row.valueLabel.textAlignment, .right)
        XCTAssertEqual(value.maxX, chevronFrame.minX - KQSpace.x8, accuracy: 0.5, "the value does not end at the chevron")
        XCTAssertEqual(atan2(chevron.transform.b, chevron.transform.a), -.pi / 2, accuracy: 0.01, "the chevron does not point right")

        // Read right to left (a view turned after it was laid out, as the snapshot tests do): the value
        // keeps to the left side and the chevron points left.
        ([row] + row.allSubviews).forEach { $0.semanticContentAttribute = .forceRightToLeft }
        XCTAssertEqual(row.valueLabel.textAlignment, .left)
        XCTAssertEqual(atan2(chevron.transform.b, chevron.transform.a), .pi / 2, accuracy: 0.01, "the chevron does not point left")
    }

    func testALongTitleBesideASwitchWrapsInsteadOfBeingCut() {
        let title = "Подавление эха и выравнивание громкости в звонках с плохой связью"
        let row = AirSettingRow.toggle(title: title, isOn: true, identifier: "settings.test") { _ in }
        laidOut(row, width: 320)
        let label = row.titleLabel
        let needed = label.sizeThatFits(CGSize(width: label.bounds.width, height: .greatestFiniteMagnitude)).height
        XCTAssertGreaterThan(label.bounds.height, label.font.lineHeight * 1.5, "the title stays on one line")
        XCTAssertGreaterThanOrEqual(label.bounds.height + 0.5, needed, "the title is cut")
    }

    func testAChoiceAloneInItsRowStandsLevelWithItsMark() {
        let row = AirChoiceRow(title: "Using Wi-Fi")
        laidOut(row, width: 390)
        let mark = row.subviews.first(where: { $0.layer.borderWidth > 0 })!
        let title = row.allSubviews.compactMap { $0 as? UILabel }.first!
        let titleFrame = title.convert(title.bounds, to: row)
        XCTAssertEqual(mark.frame.midY, titleFrame.midY, accuracy: 1.5, "the mark is not level with the words")
        XCTAssertEqual(row.bounds.height, KQSize.rowDesk, accuracy: 0.5, "a one-line choice is not one row high")
        XCTAssertEqual(titleFrame.height, ceil(title.intrinsicContentSize.height), accuracy: 1, "the title is stretched")
    }

    // MARK: - Privacy switches (ideas 1)

    private static let receiptsKey = "user-info/send-read-receipts"
    private static let typingKey = "user-info/send-typing-indicator"

    func testReadReceiptsAndTypingAreOnUntilTurnedOff() {
        withStoredPrivacy(receipts: nil, typing: nil) {
            XCTAssertTrue(UserDefaultsManager().sendReadReceipts)
            XCTAssertTrue(UserDefaultsManager().sendTypingIndicator)
        }
        withStoredPrivacy(receipts: false, typing: false) {
            XCTAssertFalse(UserDefaultsManager().sendReadReceipts)
            XCTAssertFalse(UserDefaultsManager().sendTypingIndicator)
        }
    }

    func testPrivacyHasBothSwitchesAboveBlockedAndAppliesThem() throws {
        try withStoredPrivacy(receipts: nil, typing: nil) {
            let privacy = AirPrivacyController()
            var applied: [Bool] = []
            privacy.onReadReceiptsChange = { applied.append($0) }
            privacy.loadViewIfNeeded()
            let switches = privacy.view.allSubviews.compactMap { $0 as? UISwitch }
            XCTAssertEqual(switches.map { $0.accessibilityIdentifier ?? "" }, ["settings.read-receipts", "settings.typing"])
            XCTAssertEqual(switches.map { $0.isOn }, [true, true])
            let blocked = try XCTUnwrap(privacy.view.allSubviews.first { $0.accessibilityIdentifier == "air.settings.privacy.blocked" })
            privacy.view.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
            privacy.view.layoutIfNeeded()
            XCTAssertLessThan(switches[1].convert(switches[1].bounds, to: privacy.view).minY,
                              blocked.convert(blocked.bounds, to: privacy.view).minY, "the switches are not above Blocked")

            switches[0].isOn = false
            switches[0].sendActions(for: .valueChanged)
            XCTAssertFalse(UserDefaultsManager().sendReadReceipts)
            XCTAssertEqual(applied, [false], "the running session was not told")

            switches[1].isOn = false
            switches[1].sendActions(for: .valueChanged)
            XCTAssertFalse(UserDefaultsManager().sendTypingIndicator)
            XCTAssertEqual(applied, [false], "typing is not a receipts change")
        }
    }

    func testThePrivacyWordsAreInEveryLanguage() {
        assertTranslated(["air_privacy_read_receipts", "air_privacy_read_receipts_detail", "air_privacy_typing"])
    }

    /// Runs `body` over the stored privacy switches as given (nil: nothing stored), then puts back what
    /// was stored.
    private func withStoredPrivacy(receipts: Bool?, typing: Bool?, _ body: () throws -> Void) rethrows {
        let defaults = UserDefaults.standard
        let keys = [AirSettingsTests.receiptsKey, AirSettingsTests.typingKey]
        let saved = keys.map { defaults.object(forKey: $0) }
        defer {
            for (key, value) in zip(keys, saved) {
                defaults.set(value, forKey: key)
            }
        }
        defaults.set(receipts, forKey: AirSettingsTests.receiptsKey)
        defaults.set(typing, forKey: AirSettingsTests.typingKey)
        try body()
    }

    /// Lays `row` out at `width` the way a column does: pinned at the top and the sides, as tall as it
    /// asks to be. The second pass is the one a label that wraps gets in the app on the next frame.
    // MARK: - Files download by themselves (0.2.57)

    private static let attachmentsKey = "user-info/autodownload-images"
    private static let appliedKey = "user-info/auto_download_default_v0257_applied"

    func testANewProfileDownloadsFilesByThemselves() {
        withStoredDownloads(attachments: nil, applied: nil) {
            XCTAssertEqual(UserDefaultsManager().autodownloadImages, .Always)
        }
    }

    func testTheFirstStartOf0257TurnsDownloadsOnOnceAndThePersonsChoiceStays() {
        withStoredDownloads(attachments: "Never", applied: nil) {
            // An existing profile with everything off.
            XCTAssertEqual(UserDefaultsManager().autodownloadImages, .Never)
            XCTAssertTrue(UserDefaultsManager().applyAutoDownloadDefaultOnce())
            XCTAssertEqual(UserDefaultsManager().autodownloadImages, .Always)

            // The person switches it off again: every later start keeps it off.
            UserDefaultsManager().autodownloadImages = .Never
            XCTAssertFalse(UserDefaultsManager().applyAutoDownloadDefaultOnce())
            XCTAssertFalse(UserDefaultsManager().applyAutoDownloadDefaultOnce())
            XCTAssertEqual(UserDefaultsManager().autodownloadImages, .Never)
        }
        withStoredDownloads(attachments: "UsingWiFi", applied: nil) {
            XCTAssertTrue(UserDefaultsManager().applyAutoDownloadDefaultOnce())
            XCTAssertEqual(UserDefaultsManager().autodownloadImages, .Always)
        }
    }

    func testTheSettingsShowTheNewStateAndSwitchItOff() {
        withStoredDownloads(attachments: nil, applied: nil) {
            UserDefaultsManager().applyAutoDownloadDefaultOnce()
            let network = SettingsAdvancedController(theme: ThemeAppearance.resolvedTheme())
            network.loadViewIfNeeded()
            let rows = network.view.allSubviews.compactMap { $0 as? AirChoiceRow }
            XCTAssertEqual(rows.map { $0.isSelected }, [false, false, true])

            rows[0].sendActions(for: .touchUpInside)
            XCTAssertFalse(UserDefaultsManager().applyAutoDownloadDefaultOnce())
            XCTAssertEqual(UserDefaultsManager().autodownloadImages, .Never)
        }
    }

    /// A file of theirs offered again after the connection went makes no new message, so the rule reaches it
    /// through objcTox: up to 200 MB with Always, voice notes only with Never.
    func testAFileOfferedAgainGoesByThePersonsRule() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("auto-download-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        defer {
            try? FileManager.default.removeItem(at: directory)
        }
        let realmManagerClass = try appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
        let realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("profile.realm"),
                                                                encryptionKey: nil))
        let files = OCTSubmanagerFilesMock()
        let automation = AutomationCoordinator(submanagerObjects: OCTSubmanagerObjectsMock(realm: realmManager.realm),
                                               submanagerFiles: files)
        let policy = try XCTUnwrap(files.reofferAutoAcceptPolicy)
        let messageClass = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self)
        let fileClass = try appClass("OCTMessageFile", as: OCTMessageFile.Type.self)
        func offered(_ name: String, bytes: OCTToxFileSize) -> OCTMessageAbstract {
            let file = fileClass.init()
            file.fileName = name
            file.fileSize = bytes
            let message = messageClass.init()
            message.messageFile = file
            return message
        }
        let cap: OCTToxFileSize = 200 * 1024 * 1024

        withStoredDownloads(attachments: "Always", applied: true) {
            XCTAssertTrue(policy(offered("trip.mov", bytes: cap)))
            XCTAssertFalse(policy(offered("trip.mov", bytes: cap + 1)), "a bigger file waits for a tap")
        }
        withStoredDownloads(attachments: "Never", applied: true) {
            XCTAssertFalse(policy(offered("trip.mov", bytes: 1_000)))
            XCTAssertTrue(policy(offered("note.file.m4a", bytes: 90_000)), "voice notes keep their exemption")
        }
        withExtendedLifetime(automation) {}
    }

    /// Runs `body` over the stored download choice and the 0.2.57 marker as given (nil: nothing stored), then
    /// puts back what was stored.
    private func withStoredDownloads(attachments: String?, applied: Bool?, _ body: () throws -> Void) rethrows {
        let defaults = UserDefaults.standard
        let keys = [AirSettingsTests.attachmentsKey, AirSettingsTests.appliedKey]
        let saved = keys.map { defaults.object(forKey: $0) }
        defer {
            for (key, value) in zip(keys, saved) {
                defaults.set(value, forKey: key)
            }
        }
        defaults.set(attachments, forKey: AirSettingsTests.attachmentsKey)
        defaults.set(applied, forKey: AirSettingsTests.appliedKey)
        try body()
    }

    /// The app's own class of objcTox by its name: the test bundle links a copy of its own.
    private func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }

    @discardableResult
    private func laidOut(_ row: UIView, width: CGFloat) -> UIView {
        let column = UIView(frame: CGRect(x: 0, y: 0, width: width, height: 800))
        column.addSubview(row)
        row.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            row.topAnchor.constraint(equalTo: column.topAnchor),
            row.leadingAnchor.constraint(equalTo: column.leadingAnchor),
            row.trailingAnchor.constraint(equalTo: column.trailingAnchor),
        ])
        column.layoutIfNeeded()
        column.layoutIfNeeded()
        return column
    }

    private func laidOutSegment(width: CGFloat, titles: [String]) -> AirSegmentedControl {
        let segment = AirSegmentedControl(label: "Theme", titles: titles, identifiers: titles, selectedIndex: 0)
        segment.frame = CGRect(x: 0, y: 0, width: width, height: KQSize.targetIos)
        segment.setNeedsLayout()
        segment.layoutIfNeeded()
        return segment
    }
}

private extension UIView {
    /// Every view under this one, depth first, in the order they were added.
    var allSubviews: [UIView] {
        return subviews.flatMap { [$0] + $0.allSubviews }
    }
}

extension XCTestCase {
    /// KHANDAQ (ideas 1): every one of `keys` has its own words in each language the app ships, read from
    /// that language's table rather than through the app's language switch.
    func assertTranslated(_ keys: [String], file: StaticString = #file, line: UInt = #line) {
        for language in AppLanguage.supportedCodes {
            guard let path = Bundle.main.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: language),
                  let table = NSDictionary(contentsOfFile: path) as? [String: String] else {
                XCTFail("no Localizable.strings for \(language)", file: file, line: line)
                continue
            }
            for key in keys {
                let words = table[key] ?? ""
                XCTAssertFalse(words.isEmpty, "\(language): no \(key)", file: file, line: line)
                XCTAssertNotEqual(words, key, "\(language): \(key) is its own key", file: file, line: line)
            }
        }
    }
}
