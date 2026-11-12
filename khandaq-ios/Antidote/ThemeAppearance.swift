// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// Which theme the person chose. `system` follows the phone's appearance and is the default.
enum ThemePreference: String, CaseIterable {
    case system
    case light
    case dark

    var localizedName: String {
        switch self {
            case .system:
                return String(localized: "settings_theme_system")
            case .light:
                return String(localized: "settings_theme_light")
            case .dark:
                return String(localized: "settings_theme_dark")
        }
    }
}

enum ThemeAppearance {
    static let didChangeNotification = Notification.Name("KhandaqThemeAppearanceDidChange")

    static var preference: ThemePreference {
        get {
            return UserDefaultsManager().themePreference
        }
        set {
            let defaults = UserDefaultsManager()
            guard newValue != defaults.themePreference else {
                return
            }

            defaults.themePreference = newValue
            NotificationCenter.default.post(name: didChangeNotification, object: nil)
        }
    }

    /// Whether the dark palette is in effect right now. Setting it makes an explicit choice, which is
    /// what the debug command and older call sites mean by it.
    static var isDarkMode: Bool {
        get {
            switch preference {
                case .system:
                    return systemIsDark
                case .light:
                    return false
                case .dark:
                    return true
            }
        }
        set {
            preference = newValue ? .dark : .light
        }
    }

    /// The phone's own appearance, independent of any style the app puts on its windows.
    static var systemIsDark: Bool {
        return UIScreen.main.traitCollection.userInterfaceStyle == .dark
    }

    /// The style for the app's windows: unspecified while following the system, so system-drawn
    /// controls and the trait-based design tokens switch together with the phone.
    static var windowInterfaceStyle: UIUserInterfaceStyle {
        switch preference {
            case .system:
                return .unspecified
            case .light:
                return .light
            case .dark:
                return .dark
        }
    }

    static func loadTheme() throws -> Theme {
        let resource = isDarkMode ? "dark-theme" : "default-theme"
        guard let filepath = Bundle.main.path(forResource: resource, ofType: "yaml") else {
            throw ErrorTheme.cannotParseFile(resource)
        }

        let yamlString = try String(contentsOfFile: filepath, encoding: .utf8)
        return try Theme(yamlString: yamlString)
    }

    /// Loads the active theme, falling back to default-theme if dark-theme fails to parse.
    static func resolvedTheme() -> Theme {
        if let theme = try? loadTheme() {
            return theme
        }

        if isDarkMode,
           let filepath = Bundle.main.path(forResource: "default-theme", ofType: "yaml"),
           let yamlString = try? String(contentsOfFile: filepath, encoding: .utf8),
           let theme = try? Theme(yamlString: yamlString) {
            return theme
        }

        if let filepath = Bundle.main.path(forResource: "default-theme", ofType: "yaml"),
           let yamlString = try? String(contentsOfFile: filepath, encoding: .utf8),
           let theme = try? Theme(yamlString: yamlString) {
            return theme
        }

        return Theme.fallbackTheme(isDarkMode: isDarkMode)
    }

    static func applyUserInterfaceStyle(to window: UIWindow?) {
        ThemeChrome.applyWindowStyle(window, theme: resolvedTheme())
    }

    static var placeholderBackgroundColor: UIColor {
        return (try? loadTheme().colorForType(.NormalBackground))
            ?? (isDarkMode ? UIColor(white: 0.11, alpha: 1) : .white)
    }

    static var placeholderSecondaryTextColor: UIColor {
        return (try? loadTheme().colorForType(.EmptyScreenPlaceholderText))
            ?? UIColor(white: isDarkMode ? 0.65 : 0.45, alpha: 1)
    }
}

/// An invisible view that lives in the main window and reports when the phone switches between
/// light and dark. Only trait changes reach it, so it costs nothing while nothing changes.
final class ThemeSystemAppearanceWatcher: UIView {
    var onChange: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isHidden = true
        isUserInteractionEnabled = false
        isAccessibilityElement = false
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard traitCollection.userInterfaceStyle != previousTraitCollection?.userInterfaceStyle else {
            return
        }
        onChange?()
    }
}
