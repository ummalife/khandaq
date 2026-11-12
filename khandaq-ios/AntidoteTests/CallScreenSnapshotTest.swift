// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The call screens of redesign stage 3 (E9), dark in both themes: an incoming call with Decline,
 Answer with video and Answer; a call that is on, with its timer in tabular figures, the link and the
 row of controls; the wait for someone offline. The row of four controls must fit the narrowest phone
 the app runs on (320 pt) without its circles touching.
 */
class CallScreenSnapshotTest: SnapshotBaseTest {
    private var window: UIWindow?

    override func tearDown() {
        window?.isHidden = true
        window = nil
        super.tearDown()
    }

    func testIncomingCall() {
        let controller = CallIncomingController(theme: theme, callerName: "Amina Karimova")
        controller.link = .direct
        verifyScreen(controller)
    }

    func testACallThatIsOn() {
        let controller = CallActiveController(theme: theme, callerName: "Amina Karimova")
        controller.link = .relay
        controller.state = .active(duration: 252)
        verifyScreen(controller)
    }

    func testWaitingForSomeoneOffline() {
        let controller = AirCallWaitingController(name: "Yusuf Rahman", shortName: "Yusuf", hasPush: true)
        verifyScreen(controller)
    }

    func testTheRowOfControlsFitsTheNarrowestPhone() {
        let controller = CallActiveController(theme: theme, callerName: "Amina Karimova")
        controller.state = .active(duration: 5)
        host(controller, size: CGSize(width: 320, height: 568))

        let identifiers = ["air.call.mic", "air.call.speaker", "air.call.camera", "air.call.end"]
        let frames = identifiers.compactMap { identifier -> CGRect? in
            guard let button = controller.view.firstSubview(withIdentifier: identifier) else {
                XCTFail("no \(identifier) on the call screen")
                return nil
            }
            return button.convert(button.bounds, to: controller.view)
        }
        XCTAssertEqual(frames.count, identifiers.count)
        for frame in frames {
            XCTAssertEqual(frame.width, 64, accuracy: 0.5, "a control lost its 64 pt circle")
            XCTAssertGreaterThanOrEqual(frame.minX, 0)
            XCTAssertLessThanOrEqual(frame.maxX, 320)
        }
        for (left, right) in zip(frames, frames.dropFirst()) {
            XCTAssertLessThan(left.maxX, right.minX, "two controls touch")
        }
        FBSnapshotVerifyView(controller.view, identifier: "320")
    }

    private func verifyScreen(_ controller: UIViewController) {
        host(controller, size: CGSize(width: 390, height: 844))
        verifyView(controller.view)
    }

    /// In a window of its own, so the dark override of the call screens reaches every view as it does
    /// in the app.
    private func host(_ controller: UIViewController, size: CGSize) {
        let window = UIWindow(frame: CGRect(origin: .zero, size: size))
        window.rootViewController = controller
        window.isHidden = false
        controller.view.frame = window.bounds
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        self.window = window
    }
}

private extension UIView {
    func firstSubview(withIdentifier identifier: String) -> UIView? {
        if accessibilityIdentifier == identifier {
            return self
        }
        for view in subviews {
            if let found = view.firstSubview(withIdentifier: identifier) {
                return found
            }
        }
        return nil
    }
}
