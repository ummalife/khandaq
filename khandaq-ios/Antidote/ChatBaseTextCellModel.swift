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

    /// KHANDAQ (owner, 28.11): what a place sent as a message is called on screen: over its coordinates in the
    /// conversation, in the quote of a reply to it, in the list of chats and in Maps.
    static var title: String {
        String(localized: "chat_location_title")
    }

    /// The coordinates as every client shows them: five decimals, a point for the decimal mark in any language.
    static func coordinatesText(latitude: Double, longitude: Double) -> String {
        String(format: "%.5f, %.5f", latitude, longitude)
    }

    /// The quote of a reply to a place, and anything else that names one in a line: "Location · 41.00824, 28.97836".
    /// The header of a reply carries the wire text from every client, and each reader words it in its own language.
    static func caption(latitude: Double, longitude: Double) -> String {
        title + " · " + coordinatesText(latitude: latitude, longitude: longitude)
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

/// KHANDAQ (0.2.61): one reaction under an entry as its chip shows it: the emoji, how many people chose
/// it, and whether we are one of them.
struct AirReactionChip: Equatable {
    let emoji: String
    let count: Int
    let own: Bool

    /// What the chip says: the emoji, with the count once more than one person chose it.
    var text: String {
        return count > 1 ? "\(emoji) \(count)" : emoji
    }
}

/// KHANDAQ (#192): reads OCTMessageAbstract.reactionsJSON ([{"e":emoji,"p":[actors]}]) for the chips under
/// an entry (AirReactionChipsView), and says them in words, e.g. "❤️ 2  👍".
enum ChatReactionsFormat {
    /// The actor that stands for us in reactionsJSON.
    static let ownActor = "-OWN-"

    /// The reactions in their order, each with the people who chose it; none for none.
    static func chips(from reactionsJSON: String?) -> [AirReactionChip] {
        guard let json = reactionsJSON, !json.isEmpty,
              let data = json.data(using: .utf8),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            return []
        }
        return arr.compactMap { entry in
            guard let emoji = entry["e"] as? String, !emoji.isEmpty,
                  let actors = entry["p"] as? [String], !actors.isEmpty else {
                return nil
            }
            return AirReactionChip(emoji: emoji, count: actors.count, own: actors.contains(ownActor))
        }
    }

    /// The chips in words, "❤️ 2  👍"; nil for none.
    static func display(of chips: [AirReactionChip]) -> String? {
        return chips.isEmpty ? nil : chips.map { $0.text }.joined(separator: "  ")
    }

    static func display(from reactionsJSON: String?) -> String? {
        return display(of: chips(from: reactionsJSON))
    }

    /// The emoji the local user currently reacted with (the "-OWN-" actor), or nil.
    static func ownEmoji(from reactionsJSON: String?) -> String? {
        guard let json = reactionsJSON, !json.isEmpty,
              let data = json.data(using: .utf8),
              let arr = (try? JSONSerialization.jsonObject(with: data)) as? [[String: Any]] else {
            return nil
        }
        for entry in arr {
            if let actors = entry["p"] as? [String], actors.contains(ownActor) {
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
    /// KHANDAQ (#192, 0.2.61): the reactions under the text, as chips; none for none.
    var reactionChips: [AirReactionChip] = []
    /// The reactions in words ("❤️ 2  👍"), nil for none: what VoiceOver says of the chips.
    var reactionsDisplay: String? {
        return ChatReactionsFormat.display(of: reactionChips)
    }
    /// KHANDAQ (#208): own outgoing text message that can be edited (msgV3 hash present, not a reply).
    var canEdit: Bool = false
    /// KHANDAQ (#208): message was edited — render an «изменено» marker after the text.
    var edited: Bool = false
    /// KHANDAQ (audit#2 finding 1): the row came out of someone else's history relay and no signature
    /// proves who wrote it — render the "sender not verified" note. Android shows the same thing as an
    /// orange dot in the status slot; here it is a metadata sub-row, because an incoming bubble has no
    /// status slot to put a dot in.
    var senderUnverified: Bool = false

    var hasLocation: Bool {
        locationLatitude != nil && locationLongitude != nil
    }
}
