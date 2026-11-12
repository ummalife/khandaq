// KHANDAQ (#248): in-app language switching.
//
// iOS normally only lets the user change an app's language from the system Settings app. Khandaq
// ships four languages and the picker has to live inside the app, next to the theme switch, so we
// resolve the bundle ourselves: the chosen language is stored, Bundle.main is swizzled to read from
// that language's .lproj, and Arabic additionally forces a right-to-left layout.

import Foundation
import UIKit

@objc final class AppLanguage: NSObject {
    /// Languages we actually ship translations for. Order is the order shown in the picker.
    @objc static let supportedCodes = ["en", "ru", "ar", "zh"]

    /// Name of each language written in that language — nobody looking for their own language
    /// recognises it spelled in someone else's.
    @objc static let nativeNames = ["English", "Русский", "العربية", "中文"]

    static let storageKey = "khandaq_app_language"

    /// The language in effect: the user's pick, or the phone's language when we speak it, English
    /// otherwise (matching the Android side).
    @objc static var current: String {
        if let saved = UserDefaults.standard.string(forKey: storageKey), supportedCodes.contains(saved) {
            return saved
        }
        return systemPreferred()
    }

    @objc static func nativeName(for code: String) -> String {
        guard let i = supportedCodes.firstIndex(of: code) else { return nativeNames[0] }
        return nativeNames[i]
    }

    @objc static var isRightToLeft: Bool {
        return current == "ar"
    }

    /// Locale for formatting what the user reads: dates ("Today", "Yesterday", month names) and the
    /// plural forms in Localizable.stringsdict. Foundation takes both from the phone's locale and the
    /// process language — not ours when the language was picked in the app (until the next launch) or
    /// when the phone's language is one we don't ship. Keeps the phone's region, and Western digits for
    /// Arabic, as everywhere else in the app.
    static var locale: Locale {
        return locale(forLanguage: current, system: Locale.current)
    }

    static func locale(forLanguage language: String, system: Locale) -> Locale {
        let components = NSLocale.components(fromLocaleIdentifier: system.identifier)
        if components[NSLocale.Key.languageCode.rawValue] == language {
            return system
        }
        var identifier = language
        if let region = components[NSLocale.Key.countryCode.rawValue] {
            identifier += "_" + region
        }
        if language == "ar" {
            identifier += "@numbers=latn"
        }
        return Locale(identifier: identifier)
    }

    /// Nearest shipped language for the phone's preferred languages, English if none match.
    private static func systemPreferred() -> String {
        for tag in Locale.preferredLanguages {
            let base = tag.split(separator: "-").first.map(String.init) ?? tag
            if supportedCodes.contains(base) {
                return base
            }
        }
        return "en"
    }

    /// Call once, before any UI is built.
    @objc static func applyAtLaunch() {
        apply(current)
    }

    /// Store the pick and make it take effect immediately.
    @objc static func set(_ code: String) {
        guard supportedCodes.contains(code) else { return }
        UserDefaults.standard.set(code, forKey: storageKey)
        // Keep the system list in sync too, so anything reading it directly (and the next cold
        // start, before our swizzle installs) agrees with us.
        UserDefaults.standard.set([code], forKey: "AppleLanguages")
        UserDefaults.standard.synchronize()
        apply(code)
    }

    private static func apply(_ code: String) {
        let path = Bundle.main.path(forResource: code, ofType: "lproj")
            ?? Bundle.main.path(forResource: "en", ofType: "lproj")
        Bundle.setLanguageBundle(path.flatMap(Bundle.init(path:)))
        // The share extension speaks it too (stage 5).
        ShareInboxStorage.appLanguage = code

        // The search bar's Cancel is UIKit's own string, which follows the language the app was
        // launched in. The appearance proxy reaches the bar button item of every search bar built from
        // here on, and a language switch rebuilds the session, so its search bars come up new.
        UIBarButtonItem.appearance(whenContainedInInstancesOf: [UISearchBar.self]).title = String(localized: "alert_cancel")

        let direction: UISemanticContentAttribute = (code == "ar") ? .forceRightToLeft : .forceLeftToRight
        UIView.appearance().semanticContentAttribute = direction
        UINavigationBar.appearance().semanticContentAttribute = direction
        UITableView.appearance().semanticContentAttribute = direction
        UICollectionView.appearance().semanticContentAttribute = direction
        for case let scene as UIWindowScene in UIApplication.shared.connectedScenes {
            scene.windows.forEach { applyDirection(to: $0) }
        }
    }

    /// KHANDAQ (redesign stage 5): the views follow the in-app language through
    /// `semanticContentAttribute`, but UIKit draws its own arrows (the back chevron of the navigation
    /// bar, the "backward" symbols) by the trait direction, which comes from the language the process
    /// was started in. Arabic picked in the app on an English iPhone (until the next launch, which
    /// starts in Arabic) put the back button on the right with its chevron pointing left, and English
    /// on an Arabic iPhone the reverse. Each window is told the direction of the in-app language, so
    /// the two agree. iOS 17 and later; an earlier system keeps the process direction until the next
    /// launch.
    static func applyDirection(to window: UIWindow?) {
        guard let window = window else {
            return
        }
        if #available(iOS 17.0, *) {
            window.traitOverrides.layoutDirection = isRightToLeft ? .rightToLeft : .leftToRight
        }
    }
}

private var languageBundleKey: UInt8 = 0

extension Bundle {
    /// Swap Bundle.main's localization lookup for the chosen language. Swizzled once; after that
    /// every NSLocalizedString / String(localized:) in the app resolves through the stored bundle.
    static func setLanguageBundle(_ bundle: Bundle?) {
        struct Once { static var done = false }
        if !Once.done {
            Once.done = true
            object_setClass(Bundle.main, KhandaqLanguageBundle.self)
        }
        objc_setAssociatedObject(Bundle.main, &languageBundleKey, bundle, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    }
}

private final class KhandaqLanguageBundle: Bundle, @unchecked Sendable {
    override func localizedString(forKey key: String, value: String?, table tableName: String?) -> String {
        guard let bundle = objc_getAssociatedObject(self, &languageBundleKey) as? Bundle else {
            return super.localizedString(forKey: key, value: value, table: tableName)
        }
        return bundle.localizedString(forKey: key, value: value, table: tableName)
    }
}
