// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class TabBarController: UITabBarController {
    fileprivate let theme: Theme

    // KHANDAQ: use the NATIVE system tab bar. On iOS 26 it renders as Liquid Glass; on earlier iOS
    // it is the standard blur material (Telegram-style). The previous build faked an iOS-26 island
    // with a custom white capsule over a force-transparent bar — that is what looked "off" and left
    // a white strip behind the bar on non-white screens (Settings/Profile). See configureNativeTabBarAppearance.

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

        if #available(iOS 18.0, *) {
            mode = .tabBar
        }

        applyTheme(theme)
    }

    /// iOS 18+ defers tab child layout; preload avoids a blank content area on first paint.
    func preloadChildTabViews() {
        viewControllers?.forEach { controller in
            controller.loadViewIfNeeded()
            if let navigation = controller as? UINavigationController {
                navigation.viewControllers.forEach { $0.loadViewIfNeeded() }
            }
        }
    }

    /// Select tab after all child navigation stacks are populated (required on iOS 26).
    func selectTab(at index: Int) {
        guard let viewControllers = viewControllers, index >= 0, index < viewControllers.count else {
            return
        }

        let target = viewControllers[index]
        selectedViewController = target
        selectedIndex = index
    }

    func applyTheme(_ theme: Theme) {
        configureNativeTabBarAppearance(theme: theme)
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
    func configureNativeTabBarAppearance(theme: Theme) {
        let active = theme.colorForType(.TabItemActive)
        let inactive = theme.colorForType(.TabItemInactive)
        tabBar.tintColor = active
        tabBar.unselectedItemTintColor = inactive
        tabBar.isTranslucent = true
        // No custom selection indicator: the native bar (Liquid Glass on iOS 26) tints the active
        // item green on its own — a drawn pill is exactly the "off" look testers reported.
        tabBar.selectionIndicatorImage = nil

        if #available(iOS 13.0, *) {
            // Default (system) background: Liquid Glass on iOS 26, standard blur on earlier iOS.
            // Deliberately NOT transparent — a transparent bar exposes the (white) controller view
            // behind it and leaves a white strip on grey screens (Settings/Profile).
            let appearance = UITabBarAppearance()
            appearance.configureWithDefaultBackground()

            let normalAttributes: [NSAttributedString.Key: Any] = [.foregroundColor: inactive]
            let selectedAttributes: [NSAttributedString.Key: Any] = [.foregroundColor: active]

            // Cover every layout (stacked on iPhone, inline/compact on iPad/landscape) so the green
            // active / grey inactive tint holds regardless of size class.
            for itemAppearance in [appearance.stackedLayoutAppearance,
                                   appearance.inlineLayoutAppearance,
                                   appearance.compactInlineLayoutAppearance] {
                itemAppearance.normal.iconColor = inactive
                itemAppearance.selected.iconColor = active
                itemAppearance.normal.titleTextAttributes = normalAttributes
                itemAppearance.selected.titleTextAttributes = selectedAttributes
            }

            tabBar.standardAppearance = appearance

            if #available(iOS 15.0, *) {
                tabBar.scrollEdgeAppearance = appearance
            }
        }
    }
}
