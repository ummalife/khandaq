// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

extension UIViewController {
    func loadViewWithBackgroundColor(_ backgroundColor: UIColor) {
        let frame = CGRect(origin: CGPoint.zero, size: UIScreen.main.bounds.size)

        view = UIView(frame: frame)
        view.backgroundColor = backgroundColor
    }

    /// KHANDAQ (#92): brief success toast (checkmark + message, auto-dismiss) — e.g. after copying MyID.
    func showCopiedHUD(_ message: String) {
        guard let hud = JGProgressHUD(style: ThemeAppearance.isDarkMode ? .light : .dark) else {
            return
        }
        hud.indicatorView = JGProgressHUDSuccessIndicatorView()
        hud.textLabel.text = message
        hud.show(in: view)
        hud.dismiss(afterDelay: 1.3)
    }
}
