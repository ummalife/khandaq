// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import ObjectiveC

private var khandaqPopDelegateKey: UInt8 = 0

/// KHANDAQ: keeps the interactive swipe-back gesture working after a custom (non-system) back button
/// replaces it. Allows the swipe only when there is something to pop and no transition is in flight,
/// which avoids the classic "swipe on root freezes the nav" bug. One instance is retained per nav stack.
final class KhandaqPopGestureDelegate: NSObject, UIGestureRecognizerDelegate {
    private weak var navigationController: UINavigationController?

    init(navigationController: UINavigationController) {
        self.navigationController = navigationController
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let nav = navigationController else {
            return false
        }
        return nav.viewControllers.count > 1 && nav.transitionCoordinator == nil
    }
}

extension UIViewController {
    /// KHANDAQ design (Figma): pushed sub-screens use a grey circle back button with a dark chevron
    /// (replacing the green system back text). Swipe-back is preserved via a per-nav gesture delegate.
    func installKhandaqCircleBackButton(theme: Theme) {
        guard let nav = navigationController, nav.viewControllers.count > 1 else {
            return
        }

        let chevron: UIImage?
        if #available(iOS 13.0, *) {
            let config = UIImage.SymbolConfiguration(pointSize: 16.0, weight: .semibold)
            // KHANDAQ (redesign stage 5): "backward", not "left": the symbol turns with the layout
            // direction, so in Arabic the chevron at the right points right, as the system one does.
            chevron = UIImage(systemName: "chevron.backward", withConfiguration: config)
        } else {
            chevron = nil
        }

        let item = ThemeChrome.makeCircleNavButton(
            theme: theme,
            image: chevron,
            target: self,
            action: #selector(khandaqCircleBackTapped),
            tint: theme.colorForType(.NormalText))
        // Mirror the system back button's a11y label (the previous screen's title). The item wraps a
        // custom view, and VoiceOver and UI tests read that view, so it carries the label too, the name
        // the bar shows in the Large Content Viewer, and the identifier the system back button has.
        let previousTitle = nav.viewControllers.dropLast().last?.title
        item.accessibilityLabel = previousTitle
        item.customView?.accessibilityLabel = previousTitle
        item.customView?.largeContentTitle = previousTitle
        item.customView?.accessibilityIdentifier = "BackButton"
        navigationItem.leftBarButtonItem = item
        navigationItem.hidesBackButton = true

        if !(nav.interactivePopGestureRecognizer?.delegate is KhandaqPopGestureDelegate) {
            let delegate = KhandaqPopGestureDelegate(navigationController: nav)
            objc_setAssociatedObject(nav, &khandaqPopDelegateKey, delegate, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
            nav.interactivePopGestureRecognizer?.delegate = delegate
        }
        nav.interactivePopGestureRecognizer?.isEnabled = true
    }

    @objc func khandaqCircleBackTapped() {
        navigationController?.popViewController(animated: true)
    }
}
