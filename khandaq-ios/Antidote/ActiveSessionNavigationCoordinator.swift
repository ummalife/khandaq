// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class ActiveSessionNavigationCoordinator: NSObject {
    let theme: Theme
    let navigationController: UINavigationController

    init(theme: Theme) {
        self.theme = theme
        self.navigationController = UINavigationController()
        super.init()
        ThemeChrome.apply(to: navigationController, theme: theme)
    }

    init(theme: Theme, navigationController: UINavigationController) {
        self.theme = theme
        self.navigationController = navigationController
        super.init()
        ThemeChrome.apply(to: navigationController, theme: theme)
    }

    func startWithOptions(_ options: CoordinatorOptions?) {
        preconditionFailure("This method must be overridden")
    }

    func installRootViewController(_ controller: UIViewController) {
        navigationController.setViewControllers([controller], animated: false)
    }
}
