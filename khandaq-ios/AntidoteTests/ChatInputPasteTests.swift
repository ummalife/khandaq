// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 KHANDAQ (0.2.60): a photo pasted into the field of a message goes as a photo, through the preview a photo from the
 library goes through; text pastes as before. No pasteboard is used here: the field reads it through blocks, which
 these tests replace.
 */
final class ChatInputPasteTests: XCTestCase {
    private func field(images: [UIImage], strings: Bool) -> ChatInputTextView {
        let field = ChatInputTextView()
        field.pasteboardHasImages = { !images.isEmpty }
        field.pasteboardHasStrings = { strings }
        field.pasteboardImages = { images }
        return field
    }

    private func image() -> UIImage {
        return UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }
    }

    func testAPastedPhotoGoesToTheChatAndNotIntoTheText() {
        let photo = image()
        let field = self.field(images: [photo], strings: false)
        var pasted: [[UIImage]] = []
        field.onPasteImages = { pasted.append($0) }

        XCTAssertTrue(field.canPerformAction(#selector(UIResponderStandardEditActions.paste(_:)), withSender: nil),
                      "Paste is offered for a photo alone")
        field.paste(nil)

        XCTAssertEqual(pasted.count, 1)
        XCTAssertTrue(pasted.first?.first === photo)
        XCTAssertEqual(field.text, "")
    }

    /// A chat that sends no photos leaves Paste to the text view (not asked here: it reads the general pasteboard).
    func testAChatThatSendsNoPhotosTakesNoPastedPhoto() {
        let field = self.field(images: [image()], strings: false)

        XCTAssertFalse(field.pastesPhotos)
    }

    /// The text view's own paste, which a copy with text takes, reads the general pasteboard: it is not called
    /// here, only what the field decides.
    func testACopyWithTextBesidesThePictureIsPastedAsText() {
        let field = self.field(images: [image()], strings: true)
        field.onPasteImages = { _ in }

        XCTAssertFalse(field.pastesPhotos)
        XCTAssertTrue(self.field(images: [], strings: true).pastesPhotos == false, "text alone pastes as text")
    }
}
