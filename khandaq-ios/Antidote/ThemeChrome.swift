// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

enum ThemeChrome {
    static let transitionDuration: TimeInterval = 0.35

    static func themedTableFooter(for theme: Theme) -> UIView {
        let footer = UIView()
        footer.backgroundColor = theme.colorForType(.NormalBackground)
        return footer
    }

    static func installZeroHeightTableFooter(in tableView: UITableView, theme: Theme) {
        let footer = themedTableFooter(for: theme)
        footer.frame = CGRect(x: 0, y: 0, width: tableView.bounds.width, height: .leastNonzeroMagnitude)
        tableView.tableFooterView = footer
    }

    static func applyGlobalAppearance(_ theme: Theme) {
        let linkTextColor = theme.colorForType(.LinkText)
        let backgroundColor = theme.colorForType(.NormalBackground)
        let textColor = theme.colorForType(.NormalText)

        UIButton.appearance().tintColor = linkTextColor
        UISwitch.appearance().onTintColor = linkTextColor
        UINavigationBar.appearance().tintColor = linkTextColor
        UINavigationBar.appearance().barTintColor = backgroundColor
        UINavigationBar.appearance().titleTextAttributes = [.foregroundColor: textColor]
        UITabBar.appearance().barTintColor = backgroundColor
        UITabBar.appearance().tintColor = theme.colorForType(.TabItemActive)
        UITabBar.appearance().unselectedItemTintColor = theme.colorForType(.TabItemInactive)

        if #available(iOS 13.0, *) {
            let navigationAppearance = UINavigationBarAppearance()
            navigationAppearance.configureWithOpaqueBackground()
            navigationAppearance.backgroundColor = backgroundColor
            navigationAppearance.titleTextAttributes = [.foregroundColor: textColor]
            UINavigationBar.appearance().standardAppearance = navigationAppearance
            UINavigationBar.appearance().scrollEdgeAppearance = navigationAppearance
            UINavigationBar.appearance().compactAppearance = navigationAppearance

            let tabAppearance = UITabBarAppearance()
            tabAppearance.configureWithOpaqueBackground()
            tabAppearance.backgroundColor = backgroundColor
            tabAppearance.backgroundEffect = nil
            UITabBar.appearance().standardAppearance = tabAppearance
            if #available(iOS 15.0, *) {
                UITabBar.appearance().scrollEdgeAppearance = tabAppearance
            }
        }
    }

    static func applyWindowStyle(_ window: UIWindow?, theme: Theme) {
        if #available(iOS 13.0, *) {
            // Drive system-drawn UI (UIAlertController alerts & action sheets, text fields, the keyboard,
            // pickers, switches) to match the app's custom theme. With .unspecified these followed the
            // SYSTEM appearance and rendered light ("white patches") while the app was in dark mode.
            window?.overrideUserInterfaceStyle = ThemeAppearance.isDarkMode ? .dark : .light
        }
        window?.backgroundColor = theme.colorForType(.NormalBackground)
    }

    static func apply(to root: UIViewController?, theme: Theme) {
        guard let root = root else {
            return
        }

        if let navigation = root as? UINavigationController {
            apply(to: navigation, theme: theme)
        } else if let tabBar = root as? UITabBarController {
            apply(to: tabBar, theme: theme)
        } else {
            root.view.backgroundColor = theme.colorForType(.NormalBackground)
            apply(to: root.view, theme: theme)
        }

        for child in root.childViewControllers {
            apply(to: child, theme: theme)
        }

        if let presented = root.presentedViewController {
            apply(to: presented, theme: theme)
        }
    }

    static func apply(to navigation: UINavigationController, theme: Theme) {
        let backgroundColor = theme.colorForType(.NormalBackground)
        let textColor = theme.colorForType(.NormalText)
        let linkTextColor = theme.colorForType(.LinkText)

        navigation.navigationBar.isTranslucent = false
        navigation.navigationBar.tintColor = linkTextColor
        navigation.navigationBar.barTintColor = backgroundColor
        navigation.navigationBar.titleTextAttributes = [.foregroundColor: textColor]

        if #available(iOS 13.0, *) {
            let appearance = UINavigationBarAppearance()
            appearance.configureWithOpaqueBackground()
            appearance.backgroundColor = backgroundColor
            appearance.titleTextAttributes = [.foregroundColor: textColor]
            navigation.navigationBar.standardAppearance = appearance
            navigation.navigationBar.scrollEdgeAppearance = appearance
            navigation.navigationBar.compactAppearance = appearance
        }

        navigation.view.backgroundColor = backgroundColor
        apply(to: navigation.view, theme: theme)
    }

    static func apply(to tabBarController: UITabBarController, theme: Theme) {
        let backgroundColor = theme.colorForType(.NormalBackground)
        tabBarController.view.backgroundColor = backgroundColor

        if let themedTabBar = tabBarController as? TabBarController {
            themedTabBar.applyTheme(theme)
        } else {
            let backgroundColor = theme.colorForType(.NormalBackground)
            tabBarController.tabBar.isTranslucent = false
            tabBarController.tabBar.barTintColor = backgroundColor
            tabBarController.tabBar.tintColor = theme.colorForType(.TabItemActive)
            tabBarController.tabBar.unselectedItemTintColor = theme.colorForType(.TabItemInactive)
        }

        tabBarController.viewControllers?.forEach { controller in
            apply(to: controller, theme: theme)
        }

        apply(to: tabBarController.view, theme: theme)
    }

    static func apply(to view: UIView?, theme: Theme) {
        guard let view = view else {
            return
        }

        if let tableView = view as? UITableView {
            let isGrouped = tableView.style == .grouped
            tableView.backgroundColor = isGrouped
                ? theme.colorForType(.SettingsBackground)
                : theme.colorForType(.NormalBackground)
            ThemeChrome.installZeroHeightTableFooter(in: tableView, theme: theme)
        } else if let textView = view as? UITextView {
            if textView.backgroundColor == nil
                || textView.backgroundColor == .white
                || textView.backgroundColor == .clear {
                textView.backgroundColor = theme.colorForType(.NormalBackground)
            }
        } else if view is UITableViewCell {
            // Cell backgrounds are applied per-cell.
        } else if !(view is UIControl) {
            if view.backgroundColor == nil
                || view.backgroundColor == .white {
                view.backgroundColor = theme.colorForType(.NormalBackground)
            }
        }

        for subview in view.subviews {
            apply(to: subview, theme: theme)
        }
    }

    // KHANDAQ design (Figma): navigation-bar action buttons sit in a soft grey capsule (iOS 26
    // Liquid Glass look) — a circle for icon buttons (e.g. "+") and a pill for text buttons (e.g.
    // "Править"). Applied selectively per screen, NOT globally, so back buttons stay plain as in
    // the design.
    static func makeCircleNavButton(theme: Theme, image: UIImage?, target: Any?, action: Selector, tint: UIColor? = nil) -> UIBarButtonItem {
        let size: CGFloat = 32.0
        let button = UIButton(type: .system)
        button.backgroundColor = theme.colorForType(.TabSelection)
        button.tintColor = tint ?? theme.colorForType(.LinkText)
        button.layer.cornerRadius = size / 2.0
        button.layer.masksToBounds = true
        button.setImage(image, for: .normal)
        button.imageView?.contentMode = .scaleAspectFit
        // Keep the glyph clear of the circle edge regardless of the source image's natural size.
        button.imageEdgeInsets = UIEdgeInsets(top: 7.0, left: 7.0, bottom: 7.0, right: 7.0)
        button.addTarget(target, action: action, for: .touchUpInside)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.widthAnchor.constraint(equalToConstant: size).isActive = true
        button.heightAnchor.constraint(equalToConstant: size).isActive = true
        return UIBarButtonItem(customView: button)
    }

    static func makeCircleNavButton(theme: Theme, systemImage: String, fallback: UIImage?, target: Any?, action: Selector) -> UIBarButtonItem {
        var image = fallback
        if #available(iOS 13.0, *) {
            let config = UIImage.SymbolConfiguration(pointSize: 16.0, weight: .semibold)
            image = UIImage(systemName: systemImage, withConfiguration: config) ?? fallback
        }
        return makeCircleNavButton(theme: theme, image: image, target: target, action: action)
    }

    /// Resizable grey capsule used as a bar-button background image — lets a SYSTEM bar button (e.g.
    /// the auto-localized Edit/Done editButtonItem) keep its behaviour while gaining the design's pill.
    static func navCapsuleBackgroundImage(theme: Theme) -> UIImage {
        let height: CGFloat = 30.0
        let cap = height / 2.0
        let size = CGSize(width: cap * 2.0 + 1.0, height: height)
        let renderer = UIGraphicsImageRenderer(size: size)
        let image = renderer.image { _ in
            let rect = CGRect(origin: .zero, size: size)
            let path = UIBezierPath(roundedRect: rect, cornerRadius: cap)
            theme.colorForType(.TabSelection).setFill()
            path.fill()
        }
        return image
            .resizableImage(withCapInsets: UIEdgeInsets(top: cap, left: cap, bottom: cap, right: cap))
            .withRenderingMode(.alwaysOriginal)
    }

    // KHANDAQ design (Figma): subtle line-art doodle wallpaper behind the (transparent) chat cells.
    // Rendered as a template image tinted per theme so the doodles read as a soft tone on the chat
    // background. Caller adds it behind the table and pins its edges.
    static func makeChatDoodleBackgroundView() -> UIImageView {
        let imageView = UIImageView(image: UIImage(named: "chat-doodle")?.withRenderingMode(.alwaysTemplate))
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.tintColor = ThemeAppearance.isDarkMode
            ? UIColor(red: 0.20, green: 0.27, blue: 0.23, alpha: 1.0)   // subtle lighter green on dark bg
            : UIColor(red: 0.66, green: 0.77, blue: 0.70, alpha: 1.0)   // subtle sage on pale-green bg
        return imageView
    }

    static func applyCellBackground(_ theme: Theme, to cell: UITableViewCell, grouped: Bool = false) {
        let color = grouped
            ? theme.colorForType(.SettingsBackground)
            : theme.colorForType(.NormalBackground)
        cell.backgroundColor = color
        cell.contentView.backgroundColor = color
    }
}
