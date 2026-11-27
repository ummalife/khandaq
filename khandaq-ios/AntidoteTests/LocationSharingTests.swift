// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CoreLocation
import XCTest
@testable import Antidote

/// KHANDAQ (owner, 28.11): the place a person shares is where they are now, and every screen names a place as what it
/// is, not as the text it travels in ("khandaq-location:…").
final class LocationSharingTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_764_000_000)

    // MARK: The fix

    func testAFixFromBeforeTheShareIsNotWhereThePersonIs() {
        var picker = LocationFixPicker()
        picker.consider(fix(accuracy: 5, secondsAgo: 60), now: now)

        XCTAssertNil(picker.best, "the last fix the system had, a minute old, may be from another town")
        XCTAssertFalse(picker.isSettled(elapsed: LocationFixPicker.maxWait))
    }

    func testAFixWithoutAnAccuracyIsNotTaken() {
        var picker = LocationFixPicker()
        picker.consider(fix(accuracy: -1, secondsAgo: 0), now: now)

        XCTAssertNil(picker.best)
    }

    func testTheMostAccurateFreshFixWins() {
        var picker = LocationFixPicker()
        picker.consider(fix(accuracy: 1_400, secondsAgo: 1, latitude: 41.1), now: now)
        picker.consider(fix(accuracy: 35, secondsAgo: 0, latitude: 41.0082), now: now)
        picker.consider(fix(accuracy: 90, secondsAgo: 0, latitude: 41.2), now: now)

        XCTAssertEqual(picker.best?.coordinate.latitude, 41.0082)
        XCTAssertEqual(picker.best?.horizontalAccuracy, 35)
    }

    func testAGoodFixEndsTheWaitAtOnceAndAFairOneOncePatienceRunsOut() {
        var picker = LocationFixPicker()
        picker.consider(fix(accuracy: 50, secondsAgo: 0), now: now)
        XCTAssertFalse(picker.isSettled(elapsed: 0.5), "a fair fix waits a moment for a better one")
        XCTAssertTrue(picker.isSettled(elapsed: LocationFixPicker.patience))

        picker.consider(fix(accuracy: 12, secondsAgo: 0), now: now)
        XCTAssertTrue(picker.isSettled(elapsed: 0.5), "a good fix goes at once")
    }

    func testACoarseFixWaitsTillTheEndOfTheWait() {
        var picker = LocationFixPicker()
        picker.consider(fix(accuracy: 3_000, secondsAgo: 0), now: now)

        XCTAssertFalse(picker.isSettled(elapsed: LocationFixPicker.patience + 1))
        XCTAssertNotNil(picker.best, "when the wait ends, the best fresh fix there is still goes")
    }

    // MARK: How a place is named

    func testTheQuoteOfAPlaceNamesItWhileTheHeaderThatTravelsKeepsItsWireText() {
        let place = LocationMessage.payload(latitude: 41.0082, longitude: 28.9784)
        let caption = String(localized: "chat_location_title") + " · 41.00820, 28.97840"
        var meta = MessageReplyHelper.ReplyMeta()
        meta.senderName = "Amina"
        meta.previewText = place

        XCTAssertEqual(MessageReplyHelper.quoteForDisplay(place, maxLen: 160), caption)
        XCTAssertEqual(MessageReplyHelper.quoteForDisplay("At the library", maxLen: 160), "At the library")
        XCTAssertEqual(MessageReplyHelper.previewForDisplay(place, maxLen: 160), place)
        XCTAssertTrue(MessageReplyHelper.encode(meta, bodyText: "On my way").contains(place),
                      "the header carries the wire text, so each reader names the place in its own language")

        let spoken = MessageReplyHelper.spokenQuote(meta)
        XCTAssertEqual(spoken?.contains(caption), true, String(describing: spoken))
        XCTAssertEqual(spoken?.contains("khandaq-location"), false, String(describing: spoken))
    }

    func testTheListOfChatsNamesAPlace() throws {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()

        chat.lastMessage = try message(LocationMessage.payload(latitude: -22.90685, longitude: -43.17290))
        XCTAssertEqual(ChatListContent.preview(in: chat, friend: nil),
                       ChatListContent.Preview(text: String(localized: "chat_location_title"), kind: .message))

        chat.lastMessage = try message("See you at six")
        XCTAssertEqual(ChatListContent.preview(in: chat, friend: nil).text, "See you at six")
    }

    func testThePinStandsWithItsPointOnThePlace() {
        let size = CGSize(width: 260, height: 120)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let ground = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        let place = CGPoint(x: 130, y: 60)

        let pinned = ChatBaseTextCell.drawPin(on: ground, at: place)

        XCTAssertEqual(pinned.size, ground.size)
        XCTAssertEqual(pinned.scale, ground.scale)
        XCTAssertTrue(isRed(pixel(of: pinned, at: CGPoint(x: place.x, y: place.y - 6))), "the point of the pin is on the place")
        XCTAssertTrue(isRed(pixel(of: pinned, at: CGPoint(x: place.x - 7, y: place.y - 22))), "its head stands over the place")
        XCTAssertFalse(isRed(pixel(of: pinned, at: CGPoint(x: place.x, y: place.y + 8))), "nothing of it under the place")
    }
}

private extension LocationSharingTests {
    func fix(accuracy: CLLocationAccuracy, secondsAgo: TimeInterval, latitude: Double = 41.0082) -> CLLocation {
        return CLLocation(coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: 28.9784),
                          altitude: 0,
                          horizontalAccuracy: accuracy,
                          verticalAccuracy: -1,
                          timestamp: now.addingTimeInterval(-secondsAgo))
    }

    /// A text message outside any Realm, made from the app's class: objcTox is in the app and in this bundle.
    func message(_ text: String) throws -> OCTMessageAbstract {
        let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        let body = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        body.text = text
        message.messageText = body
        return message
    }

    func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }

    /// The colour of one point of `image` (scale 1), as red, green and blue from 0 to 255.
    func pixel(of image: UIImage, at point: CGPoint) -> (red: Int, green: Int, blue: Int) {
        var rgba = [UInt8](repeating: 0, count: 4)
        let space = CGColorSpaceCreateDeviceRGB()
        guard let cgImage = image.cgImage,
              let context = CGContext(data: &rgba, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4, space: space,
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
            return (0, 0, 0)
        }
        context.draw(cgImage, in: CGRect(x: -point.x, y: point.y - CGFloat(cgImage.height) + 1,
                                         width: CGFloat(cgImage.width), height: CGFloat(cgImage.height)))
        return (Int(rgba[0]), Int(rgba[1]), Int(rgba[2]))
    }

    func isRed(_ color: (red: Int, green: Int, blue: Int)) -> Bool {
        return color.red > 150 && color.green < 90 && color.blue < 90
    }
}
