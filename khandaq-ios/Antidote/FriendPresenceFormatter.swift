// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

struct FriendPresence {
    let text: String
    let isOnline: Bool
}

/// KHANDAQ (#178): friend links take 10–60s to re-establish after our own Tox connection comes up,
/// so right after launch/reconnect an actually-online friend still reads as offline with a stale
/// "last seen". Track our own connection so presence text can say "connecting…" during that window.
enum SelfConnectionTracker {
    static let friendReconnectGrace: TimeInterval = 60

    private(set) static var isSelfOnline = false
    private(set) static var selfOnlineSince: Date?

    static func update(isOnline: Bool) {
        if isOnline {
            if !isSelfOnline || selfOnlineSince == nil {
                selfOnlineSince = Date()
            }
            isSelfOnline = true
        } else {
            isSelfOnline = false
            selfOnlineSince = nil
        }
    }

    static var friendLinksMayStillBeConnecting: Bool {
        guard isSelfOnline, let since = selfOnlineSince else {
            return true
        }
        return Date().timeIntervalSince(since) < friendReconnectGrace
    }
}

enum FriendPresenceFormatter {
    static func presence(for friend: OCTFriend) -> FriendPresence {
        let userStatus = UserStatus(connectionStatus: friend.connectionStatus, userStatus: friend.status)

        switch userStatus {
        case .online:
            return FriendPresence(text: String(localized: "status_online"), isOnline: true)
        case .away:
            return FriendPresence(text: String(localized: "status_away"), isOnline: true)
        case .busy:
            return FriendPresence(text: String(localized: "status_busy"), isOnline: true)
        case .offline:
            // Only for recently-active friends — someone gone for days is genuinely offline and
            // should keep the honest "last seen" even while our own link is settling.
            if SelfConnectionTracker.friendLinksMayStillBeConnecting && wasRecentlyActive(friend) {
                return FriendPresence(text: String(localized: "connecting_label"), isOnline: false)
            }
            return FriendPresence(text: lastSeenText(for: friend), isOnline: false)
        }
    }

    private static func wasRecentlyActive(_ friend: OCTFriend) -> Bool {
        guard friend.lastSeenOnlineInterval > 0, let date = friend.lastSeenOnline() else {
            return false
        }
        return Date().timeIntervalSince(date) < 86400
    }

    static func lastSeenText(for friend: OCTFriend) -> String {
        guard friend.lastSeenOnlineInterval > 0, let date = friend.lastSeenOnline() else {
            return String(localized: "status_last_seen_long_ago")
        }

        let elapsed = Date().timeIntervalSince(date)

        if elapsed < 60 {
            return String(localized: "status_last_seen_just_now")
        }
        if elapsed < 3600 {
            let minutes = max(1, Int(elapsed / 60))
            return String(localized: "status_last_seen_minutes_ago", minutes)
        }
        if elapsed < 86400 {
            let hours = max(1, Int(elapsed / 3600))
            return String(localized: "status_last_seen_hours_ago", hours)
        }
        if elapsed < 86400 * 7 {
            let formatter = DateFormatter(type: .relativeDateAndTime)
            return String(localized: "contact_last_seen", formatter.string(from: date))
        }

        return String(localized: "status_last_seen_long_ago")
    }
}
