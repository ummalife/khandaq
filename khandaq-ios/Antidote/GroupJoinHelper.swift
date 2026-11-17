// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import UIKit

enum GroupJoinHelper {
    private static let prefix = "khandaq:group:"

    static func normalizedGroupChatIdHex(from string: String) -> String? {
        var candidate = string.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = candidate.lowercased()

        if lower.hasPrefix(prefix) {
            candidate = String(candidate.dropFirst(prefix.count))
        }
        else if lower.hasPrefix("khandaq://group/") {
            candidate = String(candidate.dropFirst("khandaq://group/".count))
        }
        else if let url = URL(string: candidate), url.scheme?.lowercased() == "khandaq", url.host?.lowercased() == "group" {
            candidate = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            if candidate.isEmpty, let last = url.pathComponents.last, last != "/" {
                candidate = last
            }
        }

        let hex = candidate.filter { character in
            guard let scalar = character.unicodeScalars.first else {
                return false
            }
            return CharacterSet(charactersIn: "0123456789ABCDEFabcdef").contains(scalar)
        }

        guard hex.count == 64 else {
            return nil
        }

        return hex.uppercased()
    }

    /// The name a profile has in a group it joins: its Tox name, else the name of the profile, the app's
    /// name only when both are empty. KHANDAQ (#15): the Tox name can be empty (an account made without
    /// one), and every member then saw "Khandaq", the name of the founder of Khandaq Community too.
    static func peerName(userName: String?, profileName: String?) -> String {
        if let name = userName, !name.isEmpty {
            return name
        }
        if let profile = profileName, !profile.isEmpty {
            return profile
        }
        return "Khandaq"
    }

    /// Read clipboard only from an explicit user action (e.g. Join by ID). iOS 16+ prompts on pasteboard access.
    static func readGroupIdFromClipboard() -> String? {
        guard let raw = UIPasteboard.general.string else {
            return nil
        }

        return normalizedGroupChatIdHex(from: raw)
    }
}
