// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import QuickLook
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
        // As a chat with a person has it: "Add" top right (tester 8265), the pencil by the caption (8264).
        let controller = MediaSendPreviewController(items: [.image(MediaScreensSnapshotTest.photo(), fileName: "north-exit.jpg")],
                                                    maxItemCount: ChatInputViewManager.mediaPerSendLimit)
        host(controller)
        assertEveryControlIsAnIconWithAName(in: controller.view)
        // The caption shows no placeholder, so VoiceOver has only its label to name it by.
        let captions = controller.view.textViews
        XCTAssertEqual(captions.count, 1, "the preview has no caption, or more than one")
        XCTAssertEqual(captions.first?.accessibilityLabel, String(localized: "media_send_caption_hint"),
                       "the caption has no name")
        verifyView(controller.view)
    }

    /// KHANDAQ (ideas 1): one Send, the arrow by the caption (the round check at the top sat where the
    /// photo picker has its own button), and it hands the items over once however often it is tapped.
    func testThePreviewSendsFromItsArrowOnlyAndOnce() {
        let controller = MediaSendPreviewController(items: [.image(MediaScreensSnapshotTest.photo(), fileName: "north-exit.jpg")])
        let delegate = CountingPreviewDelegate()
        controller.delegate = delegate
        host(controller)

        let sends = controller.view.buttons.filter { $0.accessibilityLabel == String(localized: "air_compose_send") }
        XCTAssertEqual(sends.map { $0.accessibilityIdentifier ?? "" }, ["media.preview.send"])
        sends.first?.sendActions(for: .touchUpInside)
        sends.first?.sendActions(for: .touchUpInside)
        XCTAssertEqual(delegate.confirmed, 1, "a second tap sent the photo again")
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

    /// KHANDAQ (tester 8263): the gallery hands the file shown to the share sheet (Save Video or Image, Save to
    /// Files, another app), as QuickLook does for the other files.
    func testTheGallerySharesTheFileShown() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("media-screens-share-\(UUID().uuidString).png")
        try XCTUnwrap(UIImagePNGRepresentation(MediaScreensSnapshotTest.photo())).write(to: url)
        defer {
            try? FileManager.default.removeItem(at: url)
        }
        let item = GalleryItem(url: url, isVideo: false, senderName: "Amina", date: Date(timeIntervalSince1970: 1_790_000_000))
        let controller = MediaGalleryViewController(items: [item], startIndex: 0)
        host(controller)

        let share = try XCTUnwrap(controller.view.buttons.first { $0.accessibilityIdentifier == "media.gallery.share" })
        XCTAssertEqual(share.accessibilityLabel, String(localized: "air_first_share"))
        XCTAssertFalse(share.isHidden)
        XCTAssertEqual(share.allTargets.count, 1, "Share does nothing")
        XCTAssertNotNil(controller.makeShareSheet(), "no share sheet for the photo shown")

        // A file that is not on this device (any more) has nothing to share.
        try FileManager.default.removeItem(at: url)
        XCTAssertNil(controller.makeShareSheet())
    }

    /// KHANDAQ (tester 8263): a picked video keeps its extension, so Saved Messages shows and opens it as a
    /// video: the picker suggests "Clip", and the video was kept as a file of data under that name.
    func testAPickedVideoIsNamedWithItsExtension() {
        let staged = URL(fileURLWithPath: "/tmp/9F1C7E.mp4")
        XCTAssertEqual(PickedMediaLoader.videoFileName(suggested: "Clip-QA", staged: staged), "Clip-QA.mp4")
        XCTAssertEqual(PickedMediaLoader.videoFileName(suggested: "Clip-QA.MP4", staged: staged), "Clip-QA.MP4")
        XCTAssertEqual(PickedMediaLoader.videoFileName(suggested: "Trip.2024", staged: staged), "Trip.2024.mp4")
        XCTAssertNil(PickedMediaLoader.videoFileName(suggested: nil, staged: staged))
        XCTAssertNil(PickedMediaLoader.videoFileName(suggested: "", staged: staged))
    }

    /// KHANDAQ (tester 8264): the pencil by the caption, where the smiley only opened the keyboard, offers
    /// crop and markup at once; a video has nothing to edit.
    func testThePencilByTheCaptionOffersCropAndMarkupForAPhotoOnly() throws {
        let controller = MediaSendPreviewController(items: [.image(MediaScreensSnapshotTest.photo(), fileName: "north-exit.jpg")])
        host(controller)

        let edit = try XCTUnwrap(controller.view.buttons.first { $0.accessibilityIdentifier == "media.preview.edit" })
        XCTAssertEqual(edit.accessibilityLabel, String(localized: "chat_edit_action"))
        XCTAssertTrue(edit.showsMenuAsPrimaryAction, "a tap on the pencil does not open its menu")
        XCTAssertEqual(edit.menu?.children.map { $0.title },
                       [String(localized: "media_edit_crop"), String(localized: "media_edit_markup")])
        XCTAssertTrue(edit.isEnabled)
        let caption = try XCTUnwrap(controller.view.textViews.first)
        XCTAssertTrue(edit.superview === caption.superview, "the pencil is not by the caption")
        XCTAssertLessThan(edit.frame.maxX, caption.frame.minX + 1, "the pencil is not before the caption")

        let clip = FileManager.default.temporaryDirectory.appendingPathComponent("media-screens-test-\(UUID().uuidString).mov")
        let video = MediaSendPreviewController(items: [.video(clip, fileName: "clip.mov")])
        host(video)
        let videoEdit = try XCTUnwrap(video.view.buttons.first { $0.accessibilityIdentifier == "media.preview.edit" })
        XCTAssertFalse(videoEdit.isEnabled, "a video offers the editing of a photo")
    }

    /// KHANDAQ (tester 8265): "Add" is there while the send takes more, and what it brings comes after what is
    /// there, up to the limit; a video copied for an item past the limit is removed.
    func testAddBringsMoreUpToTheLimitOfTheSend() throws {
        let single = MediaSendPreviewController(items: [.image(MediaScreensSnapshotTest.photo(), fileName: nil)])
        host(single)
        XCTAssertEqual(single.view.buttons.first { $0.accessibilityIdentifier == "media.preview.add" }?.isHidden, true,
                       "a send of one item at a time offers more")

        let controller = MediaSendPreviewController(items: [.image(MediaScreensSnapshotTest.photo(), fileName: "first.jpg")],
                                                    maxItemCount: 3)
        host(controller)
        let add = try XCTUnwrap(controller.view.buttons.first { $0.accessibilityIdentifier == "media.preview.add" })
        XCTAssertFalse(add.isHidden, "the send takes more, and Add is not there")
        XCTAssertEqual(add.accessibilityLabel, String(localized: "media_send_add_more"))

        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("add-more-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        defer {
            try? FileManager.default.removeItem(at: directory)
        }
        let kept = directory.appendingPathComponent("kept.mov")
        let leftOut = directory.appendingPathComponent("left-out.mov")
        try Data(repeating: 1, count: 16).write(to: kept)
        try Data(repeating: 2, count: 16).write(to: leftOut)

        controller.appendItems([.image(MediaScreensSnapshotTest.photo(), fileName: "second.jpg"),
                                .video(kept, fileName: "kept.mov"),
                                .video(leftOut, fileName: "left-out.mov")])

        XCTAssertEqual(controller.items.count, 3)
        guard case .image(_, let second) = controller.items[1], case .video(let third, _) = controller.items[2] else {
            return XCTFail("the items came in another order: \(controller.items)")
        }
        XCTAssertEqual(second, "second.jpg")
        XCTAssertEqual(third, kept)
        XCTAssertEqual(controller.currentIndex, 1, "the first item that came is not the one shown")
        XCTAssertTrue(add.isHidden, "the send is full, and Add is still there")
        XCTAssertTrue(FileManager.default.fileExists(atPath: kept.path))
        XCTAssertFalse(FileManager.default.fileExists(atPath: leftOut.path), "the video past the limit stayed on the disk")
    }

    /// KHANDAQ (tester 8265): what "Add" was still loading when Close was tapped is not taken, and a video
    /// copied for it is removed.
    func testWhatAddBringsAfterCloseIsLeftOut() throws {
        let controller = MediaSendPreviewController(items: [.image(MediaScreensSnapshotTest.photo(), fileName: nil)],
                                                    maxItemCount: 3)
        let delegate = CountingPreviewDelegate()
        controller.delegate = delegate
        host(controller)
        let close = try XCTUnwrap(controller.view.buttons.first { $0.accessibilityLabel == String(localized: "alert_cancel") })
        close.sendActions(for: .touchUpInside)

        let late = FileManager.default.temporaryDirectory.appendingPathComponent("add-late-\(UUID().uuidString).mov")
        try Data(repeating: 3, count: 16).write(to: late)
        defer {
            try? FileManager.default.removeItem(at: late)
        }
        controller.appendItems([.video(late, fileName: "late.mov")])

        XCTAssertEqual(controller.items.count, 1, "the preview took items after it was closed")
        XCTAssertFalse(FileManager.default.fileExists(atPath: late.path), "the video that came late stayed on the disk")
    }

    /// KHANDAQ (tester 8264): the markup of QuickLook works on a copy in a folder of its own; what it saves is
    /// what goes out, under the photo's name, and the photo it was made from stays as it was. The folder goes
    /// when QuickLook closes.
    func testTheMarkupIsWhatGetsSentAndThePhotoItCameFromStays() throws {
        let original = MediaScreensSnapshotTest.photo()
        let controller = MediaSendPreviewController(items: [.image(original, fileName: "north-exit.jpg")])
        let delegate = CountingPreviewDelegate()
        controller.delegate = delegate
        host(controller)

        var finished = false
        let editor = try XCTUnwrap(MediaMarkupEditor(image: original) { edited in
            controller.replaceImage(at: 0, with: edited)
        })
        editor.onFinished = {
            finished = true
        }
        XCTAssertTrue(editor.fileURL.path.hasPrefix(FileManager.default.temporaryDirectory.path))
        let copied = try XCTUnwrap(UIImage(contentsOfFile: editor.fileURL.path))
        XCTAssertEqual(copied.size, original.size, "QuickLook was handed another picture")

        // What QuickLook does when the markup is saved: the copy is overwritten, then the delegate is told.
        try XCTUnwrap(UIImageJPEGRepresentation(MediaScreensSnapshotTest.marked(copied), 0.95)).write(to: editor.fileURL)
        let quickLook = editor.makePreviewController()
        XCTAssertEqual(editor.previewController(quickLook, editingModeFor: editor.fileURL as NSURL), .updateContents)
        editor.previewController(quickLook, didUpdateContentsOf: editor.fileURL as NSURL)

        let send = try XCTUnwrap(controller.view.buttons.first { $0.accessibilityIdentifier == "media.preview.send" })
        send.sendActions(for: .touchUpInside)
        guard delegate.items.count == 1, case .image(let sent, let name) = delegate.items[0] else {
            return XCTFail("the send handed over \(delegate.items)")
        }
        XCTAssertEqual(name, "north-exit.jpg")
        XCTAssertTrue(MediaScreensSnapshotTest.isRed(sent, at: CGPoint(x: 30, y: 30)), "the mark is not in what was sent")
        XCTAssertFalse(MediaScreensSnapshotTest.isRed(original, at: CGPoint(x: 30, y: 30)), "the photo it came from was changed")

        let folder = editor.fileURL.deletingLastPathComponent()
        editor.previewControllerDidDismiss(quickLook)
        XCTAssertTrue(finished)
        XCTAssertFalse(FileManager.default.fileExists(atPath: folder.path), "the copy stayed after QuickLook closed")
    }

    /// When QuickLook cannot overwrite the copy it hands over an edited copy of its own, which is taken the same.
    func testAnEditedCopyFromQuickLookIsTakenToo() throws {
        let original = MediaScreensSnapshotTest.photo()
        var edits: [UIImage] = []
        let editor = try XCTUnwrap(MediaMarkupEditor(image: original) { edits.append($0) })
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent("markup-copy-\(UUID().uuidString).jpg")
        defer {
            try? FileManager.default.removeItem(at: copy)
        }
        try XCTUnwrap(UIImageJPEGRepresentation(MediaScreensSnapshotTest.marked(original), 0.95)).write(to: copy)

        editor.previewController(editor.makePreviewController(), didSaveEditedCopyOf: editor.fileURL as NSURL, at: copy)

        XCTAssertEqual(edits.count, 1)
        XCTAssertTrue(edits.first.map { MediaScreensSnapshotTest.isRed($0, at: CGPoint(x: 30, y: 30)) } ?? false)
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

    /// `image` with a red square in its top left corner, as a markup would draw it.
    private static func marked(_ image: UIImage) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = image.scale
        return UIGraphicsImageRenderer(size: image.size, format: format).image { context in
            image.draw(at: .zero)
            UIColor.red.setFill()
            context.fill(CGRect(x: 10, y: 10, width: 50, height: 50))
        }
    }

    /// The pixel of `image` at `point` (in points) is red, give or take what JPEG does to it.
    private static func isRed(_ image: UIImage, at point: CGPoint) -> Bool {
        var pixel = [UInt8](repeating: 0, count: 4)
        let drawn = pixel.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
                return false
            }
            UIGraphicsPushContext(context)
            // Flipped to UIKit's coordinates, and moved so that `point` lands on the one pixel.
            context.translateBy(x: 0, y: 1)
            context.scaleBy(x: 1, y: -1)
            image.draw(at: CGPoint(x: -point.x, y: -point.y))
            UIGraphicsPopContext()
            return true
        }
        return drawn && pixel[0] > 200 && pixel[1] < 60 && pixel[2] < 60
    }
}


/// Counts what the preview hands over, and leaves it on screen.
private final class CountingPreviewDelegate: MediaSendPreviewControllerDelegate {
    private(set) var confirmed = 0
    /// What the last Send handed over.
    private(set) var items: [MediaSendPreviewItem] = []

    func mediaSendPreviewControllerDidCancel(_ controller: MediaSendPreviewController) {}

    func mediaSendPreviewController(_ controller: MediaSendPreviewController,
                                    didConfirm items: [MediaSendPreviewItem],
                                    caption: String) {
        confirmed += 1
        self.items = items
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
