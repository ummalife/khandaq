// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

struct LocationMessage {
    private static let prefix = "khandaq-location:"
    private static let legacyPattern = #"my\s+Location:\s*([-\d.]+)\s*,\s*([-\d.]+)"#

    static func payload(latitude: Double, longitude: Double) -> String {
        String(format: "\(prefix)%.5f,%.5f", latitude, longitude)
    }

    static func parse(_ text: String) -> (latitude: Double, longitude: Double)? {
        if text.hasPrefix(prefix) {
            let coordinates = String(text.dropFirst(prefix.count))
            return coordinatesFromPair(coordinates)
        }

        if let regex = try? NSRegularExpression(pattern: legacyPattern, options: [.caseInsensitive]),
           let match = regex.firstMatch(in: text, options: [], range: NSRange(text.startIndex..., in: text)),
           match.numberOfRanges == 3,
           let latRange = Range(match.range(at: 1), in: text),
           let lonRange = Range(match.range(at: 2), in: text) {
            return coordinatesFromPair("\(text[latRange]),\(text[lonRange])")
        }

        return nil
    }

    private static func coordinatesFromPair(_ pair: String) -> (latitude: Double, longitude: Double)? {
        let parts = pair.split(separator: ",", maxSplits: 1).map(String.init)
        guard parts.count == 2,
              let latitude = Double(parts[0].trimmingCharacters(in: .whitespaces)),
              let longitude = Double(parts[1].trimmingCharacters(in: .whitespaces)) else {
            return nil
        }
        return (latitude, longitude)
    }
}

/// KHANDAQ (#192): renders OCTMessageAbstract.reactionsJSON ([{"e":emoji,"p":[actors]}]) as the
/// compact chips line shown under the bubble text, e.g. "❤️ 2  👍". Returns nil when empty.
enum ChatReactionsFormat {
    static func display(from reactionsJSON: String?) -> String? {
        guard let json = reactionsJSON, !json.isEmpty,
              let data = json.data(using: .utf8),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            return nil
        }
        var parts: [String] = []
        for entry in arr {
            guard let emoji = entry["e"] as? String,
                  let actors = entry["p"] as? [String], !actors.isEmpty else {
                continue
            }
            parts.append(actors.count > 1 ? "\(emoji) \(actors.count)" : emoji)
        }
        return parts.isEmpty ? nil : parts.joined(separator: "  ")
    }

    /// The emoji the local user currently reacted with (the "-OWN-" actor), or nil.
    static func ownEmoji(from reactionsJSON: String?) -> String? {
        guard let json = reactionsJSON, !json.isEmpty,
              let data = json.data(using: .utf8),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            return nil
        }
        for entry in arr {
            if let actors = entry["p"] as? [String], actors.contains("-OWN-") {
                return entry["e"] as? String
            }
        }
        return nil
    }
}

class ChatBaseTextCellModel: ChatMovableDateCellModel {
    var message: String = ""
    /// @handles parsed from wire-format mention blocks (group chat).
    var mentionHandles: [String] = []
    var searchHighlight: String?
    var locationLatitude: Double?
    var locationLongitude: Double?
    var replyMeta: MessageReplyHelper.ReplyMeta?
    var onReplyQuoteTap: (() -> Void)?
    /// KHANDAQ (Figma): group sender header ("Name · Role") prefix length inside `message`,
    /// tinted with the per-peer color. 0 = no header line.
    var senderHeaderLength: Int = 0
    var senderColor: UIColor?
    /// KHANDAQ (#192): compact reactions line under the text ("❤️ 2  👍"), nil = none.
    var reactionsDisplay: String?
    /// KHANDAQ (#208): own outgoing text message that can be edited (msgV3 hash present, not a reply).
    var canEdit: Bool = false
    /// KHANDAQ (#208): message was edited — render an «изменено» marker after the text.
    var edited: Bool = false

    var hasLocation: Bool {
        locationLatitude != nil && locationLongitude != nil
    }
}
