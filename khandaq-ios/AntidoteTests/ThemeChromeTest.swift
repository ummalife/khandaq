// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/// Stands in for a view UIKit keeps to itself, such as _UIFloatingBarContainerView: defined outside
/// the app and named with a leading underscore.
@objc(_KhandaqTestPrivateContainerView)
private class PrivateContainerView: UIView {}

/**
 iOS 27 ignores UIDesignRequiresCompatibility. With the iOS 26 design every navigation controller view
 holds a full-screen _UIFloatingBarContainerView above its content. ThemeChrome painted its clear
 background with the theme background, and every tab showed a blank screen.
 */
class ThemeChromeTest: XCTestCase {
    private var theme: Theme!

    override func setUp() {
        super.setUp()

        // The dark theme: its background is not white, so a view ThemeChrome painted stands out.
        let filepath = Bundle.main.path(forResource: "dark-theme", ofType: "yaml")!
        let yamlString = try! String(contentsOfFile: filepath, encoding: .utf8)
        theme = try! Theme(yamlString: yamlString)
    }

    func testAViewUIKitKeepsToItselfIsLeftAsItIs() {
        let root = UIView()
        let container = PrivateContainerView()
        let content = UIView()
        container.addSubview(content)
        root.addSubview(container)

        ThemeChrome.apply(to: root, theme: theme)

        XCTAssertNil(container.backgroundColor)
        XCTAssertNil(content.backgroundColor)
    }

    func testOtherViewsArePaintedAsBefore() {
        let root = UIView()
        let child = UIView()
        child.backgroundColor = .white
        root.addSubview(child)

        ThemeChrome.apply(to: root, theme: theme)

        XCTAssertEqual(root.backgroundColor, theme.colorForType(.NormalBackground))
        XCTAssertEqual(child.backgroundColor, theme.colorForType(.NormalBackground))
    }

    func testThemingANavigationControllerLeavesItsPrivateViewsAlone() {
        // In a window, so that the navigation bar builds its private views.
        let navigation = UINavigationController(rootViewController: UIViewController())
        let window = makeWindow()
        window.rootViewController = navigation
        window.isHidden = false
        window.layoutIfNeeded()
        defer {
            window.isHidden = true
        }

        let views = allViews(in: navigation.view)
        let privateViews = views.filter(ThemeChrome.isUIKitPrivateView)
        let colorsBefore = privateViews.map { $0.backgroundColor }

        ThemeChrome.apply(to: navigation, theme: theme)

        XCTAssertFalse(privateViews.isEmpty, views.map { NSStringFromClass(type(of: $0)) }.joined(separator: ", "))
        for (view, colorBefore) in zip(privateViews, colorsBefore) {
            XCTAssertEqual(view.backgroundColor, colorBefore, "\(type(of: view))")
        }
    }

    private func makeWindow() -> UIWindow {
        let frame = CGRect(x: 0, y: 0, width: 402, height: 874)
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            return UIWindow(frame: frame)
        }

        let window = UIWindow(windowScene: scene)
        window.frame = frame
        return window
    }

    private func allViews(in view: UIView) -> [UIView] {
        return [view] + view.subviews.flatMap { allViews(in: $0) }
    }
}
