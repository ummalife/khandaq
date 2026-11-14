// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The two media screens kept from the fork (redesign stage 3, item 9): the preview before sending and the
 gallery. Their controls were characters typed into buttons (an angle mark, a check mark, an arrow, a
 smiley and a bin), which the brief bans and VoiceOver read out by their character names. They are
 icons now: every control shows an image and no title, and every control VoiceOver reaches has a name.
 */
class MediaScreensSnapshotTest: SnapshotBaseTest {
    private var window: UIWindow?

    override func tearDown() {
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    func testThePreviewBeforeSending() {
        let controller = MediaSendPreviewController(items: [.image(MediaScreensSnapshotTest.photo(), fileName: "north-exit.jpg")])
        host(controller)
        assertEveryControlIsAnIconWithAName(in: controller.view)
        // The caption shows no placeholder, so VoiceOver has only its label to name it by.
        let captions = controller.view.textViews
        XCTAssertEqual(captions.count, 1, "the preview has no caption, or more than one")
        XCTAssertEqual(captions.first?.accessibilityLabel, String(localized: "media_send_caption_hint"),
                       "the caption has no name")
        verifyView(controller.view)
    }

    /// No snapshot: the gallery's date follows the app language and the time zone of the machine.
    func testTheGalleryClosesWithAnIconThatHasAName() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("media-screens-test.png")
        try XCTUnwrap(UIImagePNGRepresentation(MediaScreensSnapshotTest.photo())).write(to: url)
        defer {
            try? FileManager.default.removeItem(at: url)
        }
        let item = GalleryItem(url: url, isVideo: false, senderName: "Amina", date: Date(timeIntervalSince1970: 1_790_000_000))
        let controller = MediaGalleryViewController(items: [item], startIndex: 0)
        host(controller)
        assertEveryControlIsAnIconWithAName(in: controller.view)

        let close = controller.view.buttons.first { $0.accessibilityIdentifier == "media.gallery.close" }
        XCTAssertEqual(close?.accessibilityLabel, String(localized: "qr_close_button"))
        XCTAssertEqual(close?.isHidden, false)
    }

    private func assertEveryControlIsAnIconWithAName(in view: UIView, file: StaticString = #file, line: UInt = #line) {
        let buttons = view.buttons
        XCTAssertFalse(buttons.isEmpty, "no controls on the screen", file: file, line: line)
        for button in buttons {
            let title = button.title(for: .normal) ?? ""
            XCTAssertTrue(title.isEmpty, "a control still types its mark: \(title)", file: file, line: line)
            XCTAssertNotNil(button.image(for: .normal), "a control without its icon", file: file, line: line)
            if button.isAccessibilityElement {
                XCTAssertFalse((button.accessibilityLabel ?? "").isEmpty, "a control VoiceOver reaches has no name",
                               file: file, line: line)
            }
        }
    }

    /// In a window of its own, as the screens are shown full screen in the app.
    private func host(_ controller: UIViewController) {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = controller
        window.isHidden = false
        controller.view.frame = window.bounds
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        self.window = window
    }

    /// A plain picture with a horizon, the same on every run.
    private static func photo() -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: 300, height: 400)
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor(red: 0.20, green: 0.42, blue: 0.55, alpha: 1).setFill()
            context.fill(CGRect(origin: .zero, size: size))
            UIColor(red: 0.93, green: 0.80, blue: 0.55, alpha: 1).setFill()
            context.fill(CGRect(x: 0, y: 260, width: 300, height: 140))
        }
    }
}

private extension UIView {
    /// Every button under this view, hidden ones included.
    var buttons: [UIButton] {
        var found: [UIButton] = []
        for view in subviews {
            if let button = view as? UIButton {
                found.append(button)
            }
            found += view.buttons
        }
        return found
    }

    /// Every text view under this view.
    var textViews: [UITextView] {
        return subviews.flatMap { view -> [UITextView] in
            (view as? UITextView).map { [$0] } ?? view.textViews
        }
    }
}
