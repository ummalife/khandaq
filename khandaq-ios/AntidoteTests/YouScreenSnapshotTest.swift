// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 "You" (redesign stage 3, E17): the QR code of your ID first with Share ID and Copy ID, then the photo,
 name and status, the ID in its three parts, Log out with what it means, and Delete set apart at the
 bottom. The QR card stays white in the dark theme, and on the narrowest phone (320 pt) everything
 keeps inside the margins.
 */
class YouScreenSnapshotTest: SnapshotBaseTest {
    private var window: UIWindow?
    /// The screen holds its submanager weakly, as it does the real one.
    private var user: OCTSubmanagerUserMock?
    /// The profile the device opened last, put back after each test.
    private var lastProfileBefore: String?

    override func setUp() {
        super.setUp()
        // The note under Log out follows the profile open now (with a password, or opened by this iPhone's
        // key); the references show the one of a profile with a password, whatever the simulator holds.
        let defaults = UserDefaultsManager()
        lastProfileBefore = defaults.lastActiveProfile
        defaults.lastActiveProfile = nil
    }

    override func tearDown() {
        window?.isHidden = true
        window = nil
        user = nil
        UserDefaultsManager().lastActiveProfile = lastProfileBefore
        super.tearDown()
    }

    func testYou() {
        let controller = makeController()
        host(controller, size: CGSize(width: 390, height: 1500), style: .light)
        verifyView(controller.view)
    }

    func testYouInTheDark() {
        let controller = makeController()
        host(controller, size: CGSize(width: 390, height: 1500), style: .dark)
        FBSnapshotVerifyView(controller.view, identifier: "dark")
    }

    func testEverythingKeepsInsideTheMarginsOfTheNarrowestPhone() {
        let controller = makeController()
        let width: CGFloat = 320
        host(controller, size: CGSize(width: width, height: 1700), style: .light)

        let view: UIView = controller.view
        let identifiers = ["air.you.qr", "air.you.share", "air.you.copy", "air.you.photo", "air.you.id",
                           "air.you.logout", "air.you.delete"]
        for identifier in identifiers {
            guard let found = view.firstSubview(withAccessibilityIdentifier: identifier) else {
                XCTFail("no \(identifier) on You")
                continue
            }
            let frame = found.convert(found.bounds, to: view)
            XCTAssertGreaterThanOrEqual(frame.minX, KQSpace.x16 - 0.5, "\(identifier) crosses the leading margin")
            XCTAssertLessThanOrEqual(frame.maxX, width - KQSpace.x16 + 0.5, "\(identifier) crosses the trailing margin")
        }
        FBSnapshotVerifyView(view, identifier: "320")
    }

    private func makeController() -> ProfileMainController {
        let user = OCTSubmanagerUserMock()
        user.userAddress = OCTManagerMock.demoOwnAddress
        user.nospam = OCTManagerMock.demoOwnAntispam
        try? user.setUserName("Samir Valiev")
        try? user.setUserStatusMessage("Replying in the evening")
        self.user = user
        return ProfileMainController(theme: theme, submanagerUser: user)
    }

    /// In a window of its own, so the theme reaches every view as it does in the app.
    private func host(_ controller: UIViewController, size: CGSize, style: UIUserInterfaceStyle) {
        let window = UIWindow(frame: CGRect(origin: .zero, size: size))
        window.overrideUserInterfaceStyle = style
        window.rootViewController = controller
        window.isHidden = false
        controller.view.frame = window.bounds
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        self.window = window
    }
}

private extension UIView {
    func firstSubview(withAccessibilityIdentifier identifier: String) -> UIView? {
        if accessibilityIdentifier == identifier {
            return self
        }
        for view in subviews {
            if let found = view.firstSubview(withAccessibilityIdentifier: identifier) {
                return found
            }
        }
        return nil
    }
}
