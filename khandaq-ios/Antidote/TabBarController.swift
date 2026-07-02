// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class TabBarController: UITabBarController {
    fileprivate let theme: Theme

    // KHANDAQ design (Figma): the bar is a floating rounded "island" (capsule with side margins +
    // shadow), not an edge-to-edge bar. The system bar background is made transparent and this
    // capsule is laid behind the tab items.
    fileprivate lazy var floatingCapsule: UIView = {
        let view = UIView()
        view.backgroundColor = theme.colorForType(.TabBarCapsule)
        view.isUserInteractionEnabled = false
        view.layer.shadowColor = UIColor.black.cgColor
        view.layer.shadowOpacity = 0.16
        view.layer.shadowRadius = 12.0
        view.layer.shadowOffset = CGSize(width: 0, height: 4)
        return view
    }()

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

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        layoutFloatingCapsule()
    }

    /// Position the floating capsule behind the tab items: side margins + lifted above the home
    /// indicator, fully rounded ends. Kept at the back so the item icons/labels draw on top of it.
    private func layoutFloatingCapsule() {
        let sideInset: CGFloat = 12.0
        let topInset: CGFloat = 4.0
        // Lift the capsule clear of the home indicator; on legacy devices leave a small gap.
        let bottomLift = max(view.safeAreaInsets.bottom - 6.0, 6.0)

        let bounds = tabBar.bounds
        let height = max(bounds.height - topInset - bottomLift, 0.0)
        floatingCapsule.frame = CGRect(
            x: sideInset,
            y: topInset,
            width: bounds.width - sideInset * 2.0,
            height: height)
        floatingCapsule.layer.cornerRadius = height / 2.0

        if floatingCapsule.superview !== tabBar {
            tabBar.insertSubview(floatingCapsule, at: 0)
        }
        else {
            tabBar.sendSubview(toBack: floatingCapsule)
        }
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
        floatingCapsule.backgroundColor = theme.colorForType(.TabBarCapsule)
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
    // KHANDAQ design (Figma): the active tab sits in a fully-rounded GREY pill (Fills-Vibrant/Tertiary
    // #EDEDED light), enclosing icon + label — only the icon/label themselves turn green.
    func makeSelectionPillImage(theme: Theme) -> UIImage {
        // Fixed natural-size pill drawn centered behind the active tab. NOT resizable — a resizable
        // indicator gets stretched by UIKit and its rounded ends bleed into the neighbouring tabs.
        let size = CGSize(width: 78.0, height: 46.0)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { _ in
            let rect = CGRect(origin: .zero, size: size)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: size.height / 2.0)
            theme.colorForType(.TabSelection).setFill()
            path.fill()
        }
        return image.withRenderingMode(.alwaysOriginal)
    }

    func configureNativeTabBarAppearance(theme: Theme) {
        tabBar.tintColor = theme.colorForType(.TabItemActive)
        tabBar.unselectedItemTintColor = theme.colorForType(.TabItemInactive)
        tabBar.isTranslucent = true
        tabBar.selectionIndicatorImage = makeSelectionPillImage(theme: theme)

        if #available(iOS 13.0, *) {
            // Figma: the bar itself is invisible — only the floating capsule (laid in
            // layoutFloatingCapsule) is drawn. Make the system background/shadow transparent.
            let appearance = UITabBarAppearance()
            appearance.configureWithTransparentBackground()
            appearance.backgroundColor = .clear
            appearance.shadowColor = .clear

            // Figma: labels SF Pro Semibold 10; inactive icon grey + near-black label; active green.
            let font = UIFont.systemFont(ofSize: 10.0, weight: .semibold)
            let normalAttributes: [NSAttributedString.Key: Any] = [
                .foregroundColor: theme.colorForType(.NormalText),
                .font: font,
            ]
            let selectedAttributes: [NSAttributedString.Key: Any] = [
                .foregroundColor: theme.colorForType(.TabItemActive),
                .font: font,
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
