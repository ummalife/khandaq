// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The menus of a conversation for VoiceOver. Their rows were buttons without a title, with the words in a
 label inside, so no row had a name of its own. In the menu of the paperclip each row is now one button
 named by its words, with an identifier that is the same in every language; the dim behind the menu is
 no element; the menu is modal; and the escape gesture (a two-finger Z) closes it. The rows of the menu of
 a message are named by their actions.
 */
final class MenuAccessibilityTests: XCTestCase {
    private var window: UIWindow?

    override func tearDown() {
        window?.rootViewController?.dismiss(animated: false, completion: nil)
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    func testEveryItemOfTheAttachMenuIsOneButtonNamedByItsWords() throws {
        let menu = try attachMenu()
        menu.loadViewIfNeeded()

        let rows = menu.view.descendantButtons.filter { $0.accessibilityIdentifier?.hasPrefix("air.attach.") == true }
        XCTAssertEqual(rows.map { $0.accessibilityLabel ?? "" }, ["Gallery", "Audio", "File", "Location"])
        XCTAssertEqual(rows.map { $0.accessibilityIdentifier ?? "" },
                       ["air.attach.gallery", "air.attach.audio", "air.attach.file", "air.attach.location"])
        for row in rows {
            let name = row.accessibilityLabel ?? ""
            XCTAssertTrue(row.isAccessibilityElement, "\(name) is not an element")
            XCTAssertTrue(row.accessibilityTraits & UIAccessibilityTraitButton != 0, "\(name) is not read as a button")
            let inner = row.descendants.filter { $0 is UILabel || $0 is UIImageView }
            XCTAssertFalse(inner.isEmpty, "\(name) lost its icon and its words")
            for view in inner {
                XCTAssertFalse(view.isAccessibilityElement, "\(name) has a \(type(of: view)) read on its own")
            }
        }
    }

    /// UIKit makes a button an element only while an assistive technology runs, so a unit test sees what
    /// the menu sets itself: nothing but its rows is an element.
    func testTheDimOfTheAttachMenuIsNoElementAndTheMenuIsModal() throws {
        let menu = try attachMenu()
        menu.loadViewIfNeeded()

        XCTAssertTrue(menu.view.accessibilityViewIsModal, "VoiceOver can leave the menu for the conversation under it")
        let dim = try XCTUnwrap(menu.view.subviews.first as? UIButton, "no dim behind the menu")
        XCTAssertNil(dim.accessibilityIdentifier, "the first view of the menu is not its dim")
        XCTAssertFalse(dim.isAccessibilityElement, "the dim is an element without a name")
        let elements = menu.view.descendantButtons.filter { $0.isAccessibilityElement }
        XCTAssertEqual(elements.map { $0.accessibilityIdentifier ?? "" },
                       ["air.attach.gallery", "air.attach.audio", "air.attach.file", "air.attach.location"])
    }

    func testTheEscapeGestureClosesTheAttachMenu() throws {
        let menu = try attachMenu()
        let host = UIViewController()
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = host
        window.isHidden = false
        self.window = window

        host.present(menu, animated: false, completion: nil)
        XCTAssertTrue(host.presentedViewController === menu, "the menu did not open")

        XCTAssertTrue(menu.accessibilityPerformEscape(), "the escape gesture is not handled")
        let closed = expectation(for: NSPredicate { _, _ in host.presentedViewController == nil }, evaluatedWith: nil)
        wait(for: [closed], timeout: 5)
    }

    func testEveryActionOfTheMessageMenuHasAName() {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let window = UIWindow(frame: host.frame)
        window.addSubview(host)
        window.isHidden = false
        self.window = window

        let titles = ["Reply", "Copy", "Delete"]
        let actions = titles.enumerated().map { index, title in
            ChatContextMenuAction(title: title, systemImageName: ["arrowshape.turn.up.left", "doc.on.doc", "trash"][index],
                                  destructive: index == 2) {}
        }
        let popup = ChatReactionPopup()
        popup.presentMenu(in: host, aroundRect: CGRect(x: 40, y: 300, width: 200, height: 60), currentEmoji: nil,
                          dark: false, actions: actions, onPick: { _ in })
        defer {
            popup.dismiss(animated: false)
        }

        let rows = host.descendantButtons.filter { titles.contains($0.accessibilityLabel ?? "") }
        XCTAssertEqual(rows.map { $0.accessibilityLabel ?? "" }, titles)
        for row in rows {
            for view in row.descendants where view is UILabel || view is UIImageView {
                XCTAssertFalse(view.isAccessibilityElement,
                               "\(row.accessibilityLabel ?? "") has a \(type(of: view)) read on its own")
            }
        }
    }

    /// As the conversation fills it on a phone without a camera (the simulator).
    private func attachMenu() throws -> AttachmentMenuController {
        let path = try XCTUnwrap(Bundle.main.path(forResource: "default-theme", ofType: "yaml"))
        let theme = try Theme(yamlString: String(contentsOfFile: path, encoding: .utf8))
        let items: [AttachmentMenuController.Item] = [
            .init(title: "Gallery", systemImage: "photo.on.rectangle", identifier: "air.attach.gallery") {},
            .init(title: "Audio", systemImage: "music.note", identifier: "air.attach.audio") {},
            .init(title: "File", systemImage: "doc", identifier: "air.attach.file") {},
            .init(title: "Location", systemImage: "location", identifier: "air.attach.location") {},
        ]
        return AttachmentMenuController(theme: theme, items: items, sourceView: UIView())
    }
}

private extension UIView {
    /// Every view under this one, hidden ones included.
    var descendants: [UIView] {
        return subviews + subviews.flatMap { $0.descendants }
    }

    var descendantButtons: [UIButton] {
        return descendants.compactMap { $0 as? UIButton }
    }
}
