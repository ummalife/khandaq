// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 "You" (redesign stage 3, E17): the ID in its three parts, a new antispam that gives a new ID, and Log
 out that keeps the profile waiting for its password instead of logging straight back in. The flag is
 checked before anything else, so these tests never read or write the keychain.
 */
class AirYouTests: XCTestCase {
    private var loggedOutBefore = false

    override func setUp() {
        super.setUp()
        loggedOutBefore = SessionAutoLogin.isLoggedOutByUser
    }

    override func tearDown() {
        SessionAutoLogin.isLoggedOutByUser = loggedOutBefore
        super.tearDown()
    }

    func testAWholeIdSplitsIntoKeyAntispamAndCheck() {
        let parts = AirYouId.parts(of: OCTManagerMock.demoOwnAddress)
        XCTAssertEqual(parts?.key, String(OCTManagerMock.demoOwnAddress.prefix(64)))
        XCTAssertEqual(parts?.antispam, "1C0FFEE5")
        XCTAssertEqual(parts?.check, "42E3")
    }

    func testAnIdInLowerCaseOrInGroupsSplitsTheSame() {
        let grouped = AirToxId.grouped(OCTManagerMock.demoOwnAddress.lowercased())
        XCTAssertEqual(AirYouId.parts(of: grouped), AirYouId.parts(of: OCTManagerMock.demoOwnAddress))
    }

    func testAnythingButAWholeIdHasNoParts() {
        XCTAssertNil(AirYouId.parts(of: ""))
        XCTAssertNil(AirYouId.parts(of: "123"))
        XCTAssertNil(AirYouId.parts(of: String(OCTManagerMock.demoOwnAddress.prefix(64))))
    }

    func testANewAntispamIsNeverTheOneInUse() {
        for current in [OCTToxNoSpam.min, OCTManagerMock.demoOwnAntispam, OCTToxNoSpam.max] {
            for _ in 0..<200 {
                XCTAssertNotEqual(AirYouId.newAntispam(replacing: current), current)
            }
        }
    }

    /// The demo world changes its ID as toxcore does, so the walk shows a real new ID: the same key,
    /// the new antispam, and a check that New contact accepts.
    func testTheDemoIdChangesWithItsAntispamAsToxcoreDoes() {
        let user = OCTSubmanagerUserMock()
        user.userAddress = OCTManagerMock.demoOwnAddress
        user.nospam = OCTManagerMock.demoOwnAntispam
        XCTAssertEqual(user.userAddress, OCTManagerMock.demoOwnAddress)
        XCTAssertTrue(AirToxId.checkMatches(user.userAddress))

        user.nospam = 0x0000_0001
        let parts = AirYouId.parts(of: user.userAddress)
        XCTAssertEqual(parts?.key, String(OCTManagerMock.demoOwnAddress.prefix(64)))
        XCTAssertEqual(parts?.antispam, "00000001")
        XCTAssertTrue(AirToxId.checkMatches(user.userAddress))
        XCTAssertNotEqual(user.userAddress, OCTManagerMock.demoOwnAddress)
    }

    /// The opening lays the tile over the card and the card over the tile with transforms alone.
    func testTheOpeningLaysOneFrameOverTheOther() {
        let tile = CGRect(x: 16, y: 58, width: 40, height: 40)
        let card = CGRect(x: 16, y: 116, width: 358, height: 358)
        let grow = AirYouTransition.transform(laying: tile, over: card)
        XCTAssertEqual(grow.a, 358.0 / 40.0, accuracy: 0.0001)
        XCTAssertEqual(grow.d, 358.0 / 40.0, accuracy: 0.0001)
        XCTAssertEqual(grow.tx, card.midX - tile.midX, accuracy: 0.0001)
        XCTAssertEqual(grow.ty, card.midY - tile.midY, accuracy: 0.0001)

        let shrink = AirYouTransition.transform(laying: card, over: tile)
        XCTAssertEqual(shrink.a * grow.a, 1, accuracy: 0.0001)
        XCTAssertEqual(shrink.ty, -grow.ty, accuracy: 0.0001)
    }

    func testLogOutOnYouKeepsTheProfileWaitingForItsPassword() {
        SessionAutoLogin.isLoggedOutByUser = true
        XCTAssertFalse(SessionAutoLogin.canAttemptAutoLogin())
    }

    func testTheWaitOutlivesARestartUntilALoginClearsIt() {
        SessionAutoLogin.isLoggedOutByUser = true
        // A fresh manager reads what the last run left, as the next start of the app does.
        XCTAssertTrue(UserDefaultsManager().loggedOutByUser)

        SessionAutoLogin.isLoggedOutByUser = false
        XCTAssertFalse(UserDefaultsManager().loggedOutByUser)
    }
}

/**
 KHANDAQ (ideas 1): the first page of first launch takes an optional photo beside the name, made into the
 avatar Tox carries as You makes one, and the profile made from the page keeps it as its avatar.
 */
final class AirFirstPhotoTests: XCTestCase {
    private final class AvatarUser: OCTSubmanagerUserMock {
        var avatar: Data?
        var avatarWrites = 0

        override func setUserAvatar(_ avatar: Data?) throws {
            self.avatar = avatar
            avatarWrites += 1
        }

        override func userAvatar() -> Data? {
            return avatar
        }
    }

    func testTheNamePageHasTheTileBesideTheFieldAndAddAPhoto() throws {
        let page = AirFirstNameController()
        page.loadViewIfNeeded()
        let tile = try XCTUnwrap(view("air.first.photo", in: page) as? AirYouPhotoButton)
        let row = try XCTUnwrap(tile.superview as? UIStackView)
        XCTAssertEqual(row.axis, .horizontal)
        XCTAssertEqual(row.arrangedSubviews, [tile, page.nameField])
        XCTAssertEqual(tile.accessibilityLabel, String(localized: "air_you_photo"))
        XCTAssertEqual(tile.accessibilityHint, String(localized: "air_first_photo_hint"))
        XCTAssertEqual(view("air.first.photo.action", in: page)?.accessibilityLabel, String(localized: "air_first_photo_add"))
        XCTAssertNil(page.photo)
        assertTranslated(["air_first_photo_add", "air_first_photo_remove", "air_first_photo_hint"])
    }

    /// The photo library with the square crop of You. No camera here: it would ask for its permission
    /// during first launch, and the library asks for none.
    func testTheTileOpensTheLibraryWithTheSquareCrop() throws {
        let page = AirFirstNameController()
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        window.rootViewController = page
        window.isHidden = false
        defer {
            window.isHidden = true
        }
        page.loadViewIfNeeded()
        let tile = try XCTUnwrap(view("air.first.photo", in: page) as? UIControl)

        tile.sendActions(for: .touchUpInside)

        // The library comes from another process: the picker stands in only once its view has come, and it
        // goes away within the test, after its presentation (a dismissal cut into it is dropped).
        let deadline = Date().addingTimeInterval(10)
        while (page.presentedViewController == nil || page.presentedViewController?.isBeingPresented == true) && Date() < deadline {
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        let picker = try XCTUnwrap(page.presentedViewController as? UIImagePickerController, "the tile opened nothing")
        XCTAssertEqual(picker.sourceType, .photoLibrary)
        XCTAssertTrue(picker.allowsEditing)
        XCTAssertTrue((picker.delegate as AnyObject?) === page)

        let gone = expectation(description: "the library went away")
        picker.dismiss(animated: false) {
            gone.fulfill()
        }
        wait(for: [gone], timeout: 5)
    }

    /// The crop the person made wins over the whole photo, and Next hands the avatar on with the name.
    func testAPickedPhotoGoesOnWithTheName() throws {
        let page = AirFirstNameController()
        page.loadViewIfNeeded()
        var handed: [(String, Data?)] = []
        page.onNext = { handed.append(($0, $1)) }

        page.imagePickerController(UIImagePickerController(), didFinishPickingMediaWithInfo: [
            UIImagePickerControllerEditedImage: stripes([(100, .red)], height: 100),
            UIImagePickerControllerOriginalImage: stripes([(300, .blue)], height: 200),
        ])

        let photo = try XCTUnwrap(page.photo)
        XCTAssertEqual(UIImage(data: photo)?.size, CGSize(width: 100, height: 100))
        assertColor(pixel(of: try XCTUnwrap(UIImage(data: photo)), x: 50, y: 50), [255, 0, 0])
        XCTAssertEqual(view("air.first.photo.action", in: page)?.accessibilityLabel, String(localized: "air_first_photo_remove"))

        page.nameField.text = "  Amina "
        page.mainTapped()
        XCTAssertEqual(handed.count, 1)
        XCTAssertEqual(handed.first?.0, "Amina")
        XCTAssertEqual(handed.first?.1, photo)
    }

    /// The photo is optional: Next goes on without one, and Remove the photo takes a chosen one away.
    func testThePhotoCanBeSkippedOrRemoved() throws {
        let page = AirFirstNameController()
        page.loadViewIfNeeded()
        var handed: [(String, Data?)] = []
        page.onNext = { handed.append(($0, $1)) }
        page.nameField.text = "Amina"
        page.mainTapped()
        XCTAssertEqual(handed.count, 1)
        XCTAssertNil(handed.last?.1 ?? nil, "a photo went on that nobody chose")

        XCTAssertTrue(page.takePhoto(stripes([(80, .red)], height: 80)))
        XCTAssertNotNil(page.photo)
        let action = try XCTUnwrap(view("air.first.photo.action", in: page) as? UIControl)
        action.sendActions(for: .touchUpInside)
        XCTAssertNil(page.photo)
        XCTAssertNil(page.presentedViewController, "Remove the photo opened the library")
        XCTAssertEqual(action.accessibilityLabel, String(localized: "air_first_photo_add"))
        page.mainTapped()
        XCTAssertEqual(handed.count, 2)
        XCTAssertNil(handed.last?.1 ?? nil, "a removed photo still went on")
    }

    /// The new profile keeps the name, and the photo as its avatar; a skipped photo writes no avatar.
    func testTheProfileKeepsTheNameAndThePhoto() {
        let photo = Data([0x89, 0x50, 0x4E, 0x47])
        let user = AvatarUser()
        AirFirstNameController.keep(name: "Amina", photo: photo, in: user)
        XCTAssertEqual(user.userName(), "Amina")
        XCTAssertEqual(user.userAvatar(), photo)

        let skipped = AvatarUser()
        AirFirstNameController.keep(name: "Amina", photo: nil, in: skipped)
        XCTAssertEqual(skipped.userName(), "Amina")
        XCTAssertEqual(skipped.avatarWrites, 0)
    }

    /// A photo that is not square loses its sides (or top and bottom) to the middle square, as on You.
    func testAWidePhotoKeepsItsMiddleSquare() throws {
        let wide = stripes([(50, .red), (200, .green), (50, .blue)], height: 200)
        let square = AirAvatarImage.square(wide)
        XCTAssertEqual(square.size, CGSize(width: 200, height: 200))
        for x in [2, 100, 197] {
            assertColor(pixel(of: square, x: x, y: 100), [0, 255, 0], "x \(x)")
        }
        let already = stripes([(64, .red)], height: 64)
        XCTAssertTrue(AirAvatarImage.square(already) === already)
    }

    /// However large the photo, the avatar stays within what Tox carries.
    func testALargePhotoFitsWhatToxCarries() throws {
        let data = try AirAvatarImage.pngData(AirAvatarImage.square(noise(width: 600, height: 400)))
        XCTAssertLessThanOrEqual(OCTToxFileSize(data.count), kOCTManagerMaxAvatarSize)
        let image = try XCTUnwrap(UIImage(data: data))
        XCTAssertEqual(image.size.width, image.size.height)
        XCTAssertGreaterThan(image.size.width, 64)
    }

    // MARK: - Helpers

    private func view(_ identifier: String, in page: UIViewController) -> UIView? {
        var queue: [UIView] = [page.view]
        while !queue.isEmpty {
            let view = queue.removeFirst()
            if view.accessibilityIdentifier == identifier {
                return view
            }
            queue.append(contentsOf: view.subviews)
        }
        return nil
    }

    /// Bands of colour from the leading edge, each its width wide, at scale 1.
    private func stripes(_ bands: [(CGFloat, UIColor)], height: CGFloat) -> UIImage {
        let width = bands.reduce(0) { $0 + $1.0 }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
            var x: CGFloat = 0
            for (bandWidth, color) in bands {
                color.setFill()
                context.fill(CGRect(x: x, y: 0, width: bandWidth, height: height))
                x += bandWidth
            }
        }
    }

    /// Opaque noise, which PNG cannot pack small.
    private func noise(width: Int, height: Int) -> UIImage {
        var bytes = [UInt8](repeating: 255, count: width * height * 4)
        var seed: UInt32 = 0x1234_5678
        for index in 0..<bytes.count where index % 4 != 3 {
            seed = seed &* 1_664_525 &+ 1_013_904_223
            bytes[index] = UInt8(truncatingIfNeeded: seed >> 24)
        }
        let provider = CGDataProvider(data: Data(bytes) as CFData)!
        let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                            space: CGColorSpaceCreateDeviceRGB(),
                            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
        return UIImage(cgImage: image)
    }

    private func assertColor(_ pixel: [UInt8], _ expected: [Int], _ message: String = "", line: UInt = #line) {
        XCTAssertEqual(pixel.count, 4, message, line: line)
        for (channel, value) in zip(pixel, expected) {
            XCTAssertLessThanOrEqual(abs(Int(channel) - value), 8, "\(pixel) is not \(expected) \(message)", line: line)
        }
    }

    /// The RGBA bytes of one pixel, counted from the top left.
    private func pixel(of image: UIImage, x: Int, y: Int) -> [UInt8] {
        guard let cgImage = image.cgImage else {
            return []
        }
        var pixel = [UInt8](repeating: 0, count: 4)
        pixel.withUnsafeMutableBytes { buffer in
            let context = CGContext(data: buffer.baseAddress, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
                                    space: CGColorSpaceCreateDeviceRGB(),
                                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)
            context?.draw(cgImage, in: CGRect(x: -x, y: y - (cgImage.height - 1), width: cgImage.width, height: cgImage.height))
        }
        return pixel
    }
}
