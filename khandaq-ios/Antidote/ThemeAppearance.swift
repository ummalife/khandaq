// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

enum ThemeAppearance {
    static let didChangeNotification = Notification.Name("KhandaqThemeAppearanceDidChange")

    static var isDarkMode: Bool {
        get {
            return UserDefaultsManager().darkModeEnabled
        }
        set {
            guard newValue != isDarkMode else {
                return
            }

            UserDefaultsManager().darkModeEnabled = newValue
            NotificationCenter.default.post(name: didChangeNotification, object: nil)
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
