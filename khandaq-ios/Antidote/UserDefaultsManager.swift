// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

enum ChatListFilterTab: Int {
    case direct = 0
    case groups = 1
    case favorites = 2
}

struct ChatListFilterUnreadCounts {
    var direct: Int = 0
    var groups: Int = 0
    var favorites: Int = 0
}

struct ChatFavoritesStore {
    private static let userDefaults = UserDefaultsManager()

    static func favoriteKey(for chat: OCTChat) -> String {
        // KHANDAQ (audit #16): key on the STABLE uniqueIdentifier. The old scheme keyed groups on
        // groupChatIdHex, which is empty right after create/join and only fills to 64 chars once the
        // NGC id syncs — so a group favorited early was stored under "chat:<uid>" but later looked up
        // under "group:<hex>", silently dropping the star and orphaning the entry.
        return (chat.isGroup ? "group:" : "chat:") + chat.uniqueIdentifier
    }

    static func isFavorite(chat: OCTChat) -> Bool {
        return userDefaults.favoriteChatKeys.contains(favoriteKey(for: chat))
    }

    @discardableResult
    static func toggle(chat: OCTChat) -> Bool {
        var keys = userDefaults.favoriteChatKeys
        let key = favoriteKey(for: chat)
        let nowFavorite: Bool
        if keys.contains(key) {
            keys.remove(key)
            nowFavorite = false
        }
        else {
            keys.insert(key)
            nowFavorite = true
        }
        userDefaults.favoriteChatKeys = keys
        return nowFavorite
    }
}

class UserDefaultsManager {
    var lastActiveProfile: String? {
        get {
            return stringForKey(Keys.LastActiveProfile)
        }
        set {
            setObject(newValue as AnyObject?, forKey: Keys.LastActiveProfile)
        }
    }

    var UDPEnabled: Bool {
        get {
            // KHANDAQ (#5 slow-connect fix): default UDP ON to match Android and get fast DHT connect.
            // With UDP off, tox_bootstrap (UDP DHT) is a no-op and first connect is slow. The Advanced
            // toggle still lets privacy-conscious users turn it back off (setBool persists their choice).
            return boolForKey(Keys.UDPEnabled, defaultValue: true)
        }
        set {
            setBool(newValue, forKey: Keys.UDPEnabled)
        }
    }

    var EchobotAdded: Bool {
        get {
            return boolForKey(Keys.EchobotAdded, defaultValue: false)
        }
        set {
            setBool(newValue, forKey: Keys.EchobotAdded)
        }
    }

    var DebugMode: Bool {
        get {
            return boolForKey(Keys.DebugMode, defaultValue: false)
        }
        set {
            setBool(newValue, forKey: Keys.DebugMode)
        }
    }

    var DateonmessageMode: Bool {
        get {
            return boolForKey(Keys.DateonmessageMode, defaultValue: true)
        }
        set {
            setBool(newValue, forKey: Keys.DateonmessageMode)
        }
    }

    var LongerbgMode: Bool {
        get {
            return boolForKey(Keys.LongerbgMode, defaultValue: false)
        }
        set {
            setBool(newValue, forKey: Keys.LongerbgMode)
        }
    }

    var showNotificationPreview: Bool {
        get {
            return boolForKey(Keys.ShowNotificationsPreview, defaultValue: true)
        }
        set {
            setBool(newValue, forKey: Keys.ShowNotificationsPreview)
        }
    }

    var groupShowSystemMessages: Bool {
        get {
            return boolForKey(Keys.GroupShowSystemMessages, defaultValue: false)
        }
        set {
            setBool(newValue, forKey: Keys.GroupShowSystemMessages)
        }
    }

    var darkModeEnabled: Bool {
        get {
            return boolForKey(Keys.DarkModeEnabled, defaultValue: false)
        }
        set {
            setBool(newValue, forKey: Keys.DarkModeEnabled)
        }
    }

    var chatListFilterTab: ChatListFilterTab {
        get {
            let raw = intForKey(Keys.ChatListFilterTab, defaultValue: ChatListFilterTab.direct.rawValue)
            return ChatListFilterTab(rawValue: raw) ?? .direct
        }
        set {
            setInt(newValue.rawValue, forKey: Keys.ChatListFilterTab)
        }
    }

    var favoriteChatKeys: Set<String> {
        get {
            guard let array = stringArrayForKey(Keys.FavoriteChatKeys) else {
                return []
            }
            return Set(array)
        }
        set {
            setObject(Array(newValue) as AnyObject?, forKey: Keys.FavoriteChatKeys)
        }
    }

    enum AutodownloadImages: String {
        case Never
        case UsingWiFi
        case Always
    }

    var autodownloadImages: AutodownloadImages {
        get {
            let defaultValue = AutodownloadImages.Never //Easy enough to reach option for users. Reverting change since there is an extremely high risk of people getting in trouble since an attacked can get you in prison by sending you cp.

            guard let string = stringForKey(Keys.AutodownloadImages) else {
                return defaultValue
            }
            return AutodownloadImages(rawValue: string) ?? defaultValue
        }
        set {
            setObject(newValue.rawValue as AnyObject?, forKey: Keys.AutodownloadImages)
        }
    }

    func resetUDPEnabled() {
        removeObjectForKey(Keys.UDPEnabled)
    }
}

private extension UserDefaultsManager {
    struct Keys {
        static let LastActiveProfile = "user-info/last-active-profile"
        static let EchobotAdded = "user-info/echobot-added"
        static let DebugMode = "user-info/debug-mode"
        static let DateonmessageMode = "user-info/dateonmessage-mode"
        static let UDPEnabled = "user-info/udp-enabled"
        static let ShowNotificationsPreview = "user-info/snow-notification-preview"
        static let LongerbgMode = "user-info/longerbg-mode"
        static let AutodownloadImages = "user-info/autodownload-images"
        static let GroupShowSystemMessages = "user-info/group-show-system-messages"
        static let DarkModeEnabled = "user-info/dark-mode-enabled"
        static let ChatListFilterTab = "user-info/chat-list-filter-tab"
        static let FavoriteChatKeys = "user-info/favorite-chat-keys"
    }

    func setObject(_ object: AnyObject?, forKey key: String) {
        let defaults = UserDefaults.standard
        defaults.set(object, forKey:key)
        defaults.synchronize()
    }

    func stringForKey(_ key: String) -> String? {
        let defaults = UserDefaults.standard
        return defaults.string(forKey: key)
    }

    func setBool(_ value: Bool, forKey key: String) {
        let defaults = UserDefaults.standard
        defaults.set(value, forKey: key)
        defaults.synchronize()
    }

    func boolForKey(_ key: String, defaultValue: Bool) -> Bool {
        let defaults = UserDefaults.standard

        if let result = defaults.object(forKey: key) {
            return (result as AnyObject).boolValue
        }
        else {
            return defaultValue
        }
    }

    func removeObjectForKey(_ key: String) {
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: key)
        defaults.synchronize()
    }

    func intForKey(_ key: String, defaultValue: Int) -> Int {
        let defaults = UserDefaults.standard
        if defaults.object(forKey: key) != nil {
            return defaults.integer(forKey: key)
        }
        return defaultValue
    }

    func setInt(_ value: Int, forKey key: String) {
        let defaults = UserDefaults.standard
        defaults.set(value, forKey: key)
        defaults.synchronize()
    }

    func stringArrayForKey(_ key: String) -> [String]? {
        let defaults = UserDefaults.standard
        return defaults.stringArray(forKey: key)
    }
}
