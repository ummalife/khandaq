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
    /// The file of the photo of the snapshots: none is read, its picture waits in the preview's own cache.
    private let photoPath = "/khandaq-preview-snapshot/photo.jpg"

    override func tearDown() {
        window = nil
        ChatPreviewController.thumbnails.removeObject(forKey: photoPath as NSString)
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

    /// KHANDAQ (inbox #28, 28.11): a reply on either side with the quote of the message it answers, and a photo as the
    /// conversation shows it: bare, in the shape of its picture, not "Outgoing file: Photo_….jpg".
    func testAPreviewQuotesRepliesAndShowsPhotos() {
        ChatPreviewController.thumbnails.setObject(picture(), forKey: photoPath as NSString)
        let entries = [
            ChatPreviewEntry(text: "Yes, I will bring it.", side: .incoming,
                             reply: quote(of: "You", saying: "Can you bring the second volume on Thursday?")),
            ChatPreviewEntry(text: "Thank you!", side: .outgoing,
                             reply: quote(of: "Amina", saying: "Yes, I will bring it.")),
            ChatPreviewEntry(text: "", side: .outgoing, media: .photo(path: photoPath, pixelSize: CGSize(width: 4, height: 3))),
        ]
        let controller = preview(title: "Amina", tile: .person(initials: "A", tone: 2), entries: entries, theme: theme)
        host(controller, style: .light)
        verifyView(controller.view)
    }

    /// A member's video in a group under their name, with the glyph of a video while its frame is read, and a place
    /// with the glyph of its map while the map is made and its coordinates under it, as the conversation writes them.
    func testAPreviewShowsVideosAndPlaces() {
        let controller = preview(title: "Book club", tile: .group(initials: "BC", tone: 3),
                                 entries: videoAndPlace(latitude: 21.42250, longitude: 39.82620), theme: theme)
        host(controller, style: .light)
        verifyView(controller.view)
    }

    func testAPreviewShowsVideosAndPlacesInTheDark() {
        // Another place than the light one: a map made for that one while this runs must not stand in for the glyph.
        let controller = preview(title: "Book club", tile: .group(initials: "BC", tone: 3),
                                 entries: videoAndPlace(latitude: 41.00824, longitude: 28.97836), theme: darkTheme())
        host(controller, style: .dark)
        FBSnapshotVerifyView(controller.view, identifier: "dark")
    }

    private func videoAndPlace(latitude: Double, longitude: Double) -> [ChatPreviewEntry] {
        let bilal = AirTileView.Style.person(initials: "B", tone: 5)
        return [
            ChatPreviewEntry(text: "", side: .incoming, author: "Bilal", authorColor: AirTone.color(at: 5), tile: bilal,
                             showsTile: true, media: .video(path: "/khandaq-preview-snapshot/clip.mov", pixelSize: nil)),
            ChatPreviewEntry(text: String(format: "%.5f, %.5f", latitude, longitude), side: .outgoing,
                             media: .location(latitude: latitude, longitude: longitude)),
        ]
    }

    private func quote(of name: String, saying words: String) -> MessageReplyHelper.ReplyMeta {
        var meta = MessageReplyHelper.ReplyMeta()
        meta.senderName = name
        meta.previewText = words
        return meta
    }

    /// A picture of a photo: bands of two tones, the same on every run.
    private func picture() -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: 240, height: 180)
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor(red: 0.17, green: 0.42, blue: 0.36, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIColor(red: 0.85, green: 0.78, blue: 0.62, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 120, width: 240, height: 60))
        }
    }

    private func preview(title: String, tile: AirTileView.Style, entries: [ChatPreviewEntry], theme: Theme) -> ChatPreviewController {
        return ChatPreviewController(theme: theme, title: title, tile: tile, photo: nil, entries: entries)
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
