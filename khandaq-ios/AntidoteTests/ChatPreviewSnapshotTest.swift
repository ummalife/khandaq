// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/// KHANDAQ (inbox #24, 27.11): the long-press preview of a group as ChatPreviewController draws the entries that
/// ChatPreviewEntriesTests checks: the author over the first entry of another member's run, the member's tile beside
/// its last, a notice of the group in the middle, our own entries on the other side, an author nobody confirmed, and
/// the tile of the chat's row in the header.
final class ChatPreviewSnapshotTest: SnapshotBaseTest {
    private var window: UIWindow?

    override func tearDown() {
        window = nil
        super.tearDown()
    }

    func testAGroupsPreviewNamesItsAuthors() {
        let controller = preview(theme: theme)
        host(controller, style: .light)
        verifyView(controller.view)
    }

    func testAGroupsPreviewInTheDark() {
        let controller = preview(theme: darkTheme())
        host(controller, style: .dark)
        FBSnapshotVerifyView(controller.view, identifier: "dark")
    }

    /// The theme the app loads in the dark (ThemeAppearance.loadTheme).
    private func darkTheme() -> Theme {
        let path = Bundle.main.path(forResource: "dark-theme", ofType: "yaml")!
        return try! Theme(yamlString: try! String(contentsOfFile: path, encoding: .utf8))
    }

    private func preview(theme: Theme) -> ChatPreviewController {
        let amina = AirTileView.Style.person(initials: "A", tone: 2)
        let bilal = AirTileView.Style.person(initials: "B", tone: 5)
        let entries = [
            ChatPreviewEntry(text: "Bilal named the group Book club", side: .notice),
            ChatPreviewEntry(text: "Salam! Are we still on for Thursday?", side: .incoming,
                             author: "Amina", authorColor: AirTone.color(at: 2), tile: amina),
            ChatPreviewEntry(text: "I can bring the second volume.", side: .incoming, tile: amina, showsTile: true),
            ChatPreviewEntry(text: "Yes, after maghrib at my place.", side: .outgoing),
            ChatPreviewEntry(text: "I will be a little late, start without me.", side: .incoming,
                             author: "Bilal", authorColor: AirTone.color(at: 5), tile: bilal, showsTile: true),
            ChatPreviewEntry(text: "Is there room for one more?", side: .incoming,
                             author: String(localized: "air_group_author_unconfirmed"), authorColor: KQColor.ink2,
                             tile: .unconfirmed, showsTile: true),
        ]
        return ChatPreviewController(theme: theme,
                                     title: "Book club",
                                     tile: .group(initials: "BC", tone: 3),
                                     photo: nil,
                                     entries: entries)
    }

    /// In a window of its own at the size the preview asks for, so the theme reaches every view as in the app.
    private func host(_ controller: UIViewController, style: UIUserInterfaceStyle) {
        let window = UIWindow(frame: CGRect(origin: .zero, size: controller.preferredContentSize))
        window.overrideUserInterfaceStyle = style
        window.rootViewController = controller
        window.isHidden = false
        controller.view.frame = window.bounds
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        // A label that wraps settles on the second pass, as it does in the app on the next frame.
        controller.view.layoutIfNeeded()
        self.window = window
    }
}
