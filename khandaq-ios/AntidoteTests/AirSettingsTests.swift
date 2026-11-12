// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Settings (redesign stage 3, E18): the sections by task with the profile file in a section of its own,
 the theme as a segment with the system first that says only a real change, and the attachment choice
 that stores what it shows.
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
        XCTAssertEqual(Array(rows.prefix(7)), ["settings.appearance", "settings.notifications", "settings.privacy",
                                               "settings.transfer", "settings.network", "settings.faq", "settings.about"])
        // Debugging is the one more row, last, and only in a debug build.
        XCTAssertTrue(rows.count == 7 || (rows.count == 8 && rows.last == "settings.debug"), "\(rows)")

        let transfer = AirTransferController()
        transfer.loadViewIfNeeded()
        let transferRows = transfer.view.allSubviews.compactMap { $0 as? AirSettingRow }.map { $0.accessibilityIdentifier ?? "" }
        XCTAssertEqual(transferRows, ["settings.export", "settings.import"])
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

    /// Lays `row` out at `width` the way a column does: pinned at the top and the sides, as tall as it
    /// asks to be. The second pass is the one a label that wraps gets in the app on the next frame.
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
