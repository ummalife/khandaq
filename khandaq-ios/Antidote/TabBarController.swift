// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class TabBarController: UITabBarController {
    fileprivate let theme: Theme

    init(theme: Theme, controllers: [UINavigationController]) {
        self.theme = theme

        super.init(nibName: nil, bundle: nil)

        viewControllers = controllers
        delegate = self
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        configureNativeTabBarAppearance()
    }

    static func makeProfileTabBarImage(
            theme: Theme,
            userImage: UIImage?,
            userStatus: UserStatus,
            connectionStatus: ConnectionStatus) -> UIImage {
        let size: CGFloat = 32
        let container = ImageViewWithStatus()
        container.frame = CGRect(x: 0, y: 0, width: size, height: size)
        container.userStatusView.theme = theme
        container.userStatusView.userStatus = userStatus
        container.userStatusView.connectionStatus = connectionStatus

        if let userImage = userImage {
            container.imageView.image = userImage
        }
        else {
            container.imageView.image = UIImage.templateNamed("tab-bar-profile")
            container.imageView.tintColor = theme.colorForType(.TabItemInactive)
        }

        container.setNeedsLayout()
        container.layoutIfNeeded()

        UIGraphicsBeginImageContextWithOptions(container.bounds.size, false, 0)
        if let context = UIGraphicsGetCurrentContext() {
            container.layer.render(in: context)
        }
        let image = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()

        return image?.withRenderingMode(.alwaysOriginal) ?? UIImage.templateNamed("tab-bar-profile")
    }
}

extension TabBarController: UITabBarControllerDelegate {
    func tabBarController(_ tabBarController: UITabBarController, shouldSelect viewController: UIViewController) -> Bool {
        if viewController == tabBarController.selectedViewController {
            if let navigation = viewController as? UINavigationController {
                navigation.popToRootViewController(animated: true)
            }

            return false
        }

        return true
    }
}

private extension TabBarController {
    func configureNativeTabBarAppearance() {
        tabBar.tintColor = theme.colorForType(.TabItemActive)
        tabBar.unselectedItemTintColor = theme.colorForType(.TabItemInactive)
        tabBar.barTintColor = theme.colorForType(.NormalBackground)
        tabBar.isTranslucent = false

        if #available(iOS 13.0, *) {
            let appearance = UITabBarAppearance()
            appearance.configureWithOpaqueBackground()
            appearance.backgroundColor = theme.colorForType(.NormalBackground)
            appearance.shadowColor = theme.colorForType(.SeparatorsAndBorders)

            let normalAttributes: [NSAttributedString.Key: Any] = [
                .foregroundColor: theme.colorForType(.TabItemInactive)
            ]
            let selectedAttributes: [NSAttributedString.Key: Any] = [
                .foregroundColor: theme.colorForType(.TabItemActive)
            ]

            appearance.stackedLayoutAppearance.normal.iconColor = theme.colorForType(.TabItemInactive)
            appearance.stackedLayoutAppearance.selected.iconColor = theme.colorForType(.TabItemActive)
            appearance.stackedLayoutAppearance.normal.titleTextAttributes = normalAttributes
            appearance.stackedLayoutAppearance.selected.titleTextAttributes = selectedAttributes

            tabBar.standardAppearance = appearance

            if #available(iOS 15.0, *) {
                tabBar.scrollEdgeAppearance = appearance
            }
        }
    }
}
