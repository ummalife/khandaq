// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

struct FriendPresence {
    let text: String
    let isOnline: Bool
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
            return FriendPresence(text: lastSeenText(for: friend), isOnline: false)
        }
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
            return String(format: String(localized: "status_last_seen_minutes_ago"), minutes)
        }
        if elapsed < 86400 {
            let hours = max(1, Int(elapsed / 3600))
            return String(format: String(localized: "status_last_seen_hours_ago"), hours)
        }
        if elapsed < 86400 * 7 {
            let formatter = DateFormatter(type: .relativeDateAndTime)
            return String(format: String(localized: "contact_last_seen"), formatter.string(from: date))
        }

        return String(localized: "status_last_seen_long_ago")
    }
}
