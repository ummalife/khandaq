// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Our own node (redesign stage 3, E4): the Network screen while the app connects, with "Connect again",
 and with no network at all in the dark on the narrowest phone. The in-app band (E22) over the top of
 a screen, light and dark, with the tile of the chat and with the dashed tile of a request.
 */
class AirNetworkSnapshotTest: SnapshotBaseTest {
    private var window: UIWindow?

    override func tearDown() {
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    func testNetworkWhileConnecting() {
        let controller = AirNetworkController(theme: theme, state: { .connecting }, reconnect: {})
        host(controller, size: CGSize(width: 390, height: 600), style: .light)
        XCTAssertEqual(controller.report?.offersReconnect, true)
        verifyView(controller.view)
    }

    func testNetworkWithoutANetworkInTheDarkOnTheNarrowestPhone() {
        let controller = AirNetworkController(theme: theme, state: { .offline }, reconnect: {})
        host(controller, size: CGSize(width: 320, height: 600), style: .dark)
        XCTAssertEqual(controller.report?.isAlarm, true)
        FBSnapshotVerifyView(controller.view, identifier: "dark-320")
    }

    func testTheBandOfAMessage() {
        let screen = bandScreen(title: "Amina Safina",
                                body: "See you at the station at 5:45, the train leaves at six sharp and the next one only at nine",
                                tile: .person(initials: "AS", tone: 2), style: .light)
        verifyView(screen)
    }

    func testTheBandOfARequestInTheDark() {
        let screen = bandScreen(title: "Incoming contact request", body: "Hello, this is Bilal from the study circle",
                                tile: .pending, style: .dark)
        FBSnapshotVerifyView(screen, identifier: "dark")
    }

    /// The top of a screen with the band where the band's window puts it.
    private func bandScreen(title: String, body: String, tile: AirTileView.Style, style: UIUserInterfaceStyle) -> UIView {
        let controller = UIViewController()
        host(controller, size: CGSize(width: 390, height: 140), style: style)
        let screen = controller.view!
        screen.backgroundColor = KQColor.surface
        let band = NotificationBannerView(title: title, body: body, tile: tile, photo: nil, hint: String(localized: "air_banner_hint"))
        screen.addSubview(band)
        band.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            band.topAnchor.constraint(equalTo: screen.topAnchor, constant: KQSpace.x8),
            band.leadingAnchor.constraint(equalTo: screen.leadingAnchor, constant: KQSpace.x12),
            band.trailingAnchor.constraint(equalTo: screen.trailingAnchor, constant: -KQSpace.x12),
        ])
        screen.setNeedsLayout()
        screen.layoutIfNeeded()
        screen.layoutIfNeeded()
        return screen
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
        // A label that wraps settles on the second pass, as it does in the app on the next frame.
        controller.view.layoutIfNeeded()
        self.window = window
    }
}
