// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// Cross-platform @mention metadata in group message text (mirrors Android GroupMentionHelper).
enum GroupMentionHelper {
    static let mentionBlockPrefix = "[KQ|m|"
    static let mentionBlockSuffix = "][KQ/m]"
    private static let legacyMentionBlockPrefix = "[KQ|m]"
    private static let legacyMentionBlockSuffix = "[|KQ|m]"

    struct MentionEntry {
        var pubkey: String = ""
        var handle: String = ""
    }

    struct ParsedGroupText {
        var reply: MessageReplyHelper.ReplyMeta?
        var mentions: [MentionEntry] = []
        var bodyText: String = ""
    }

    static func parse(_ rawText: String?) -> ParsedGroupText {
        var parsed = ParsedGroupText()
        let replyParsed = MessageReplyHelper.parse(rawText)
        parsed.reply = replyParsed.reply

        var body = replyParsed.bodyText
        guard !body.isEmpty else {
            parsed.bodyText = body
            return parsed
        }

        if body.hasPrefix(mentionBlockPrefix) {
            if let blockEnd = body.range(of: mentionBlockSuffix) {
                let payloadStart = body.index(body.startIndex, offsetBy: mentionBlockPrefix.count)
                let payload = String(body[payloadStart..<blockEnd.lowerBound])
                parsed.mentions = parseMentionPayload(payload)
                let bodyStart = blockEnd.upperBound
                body = bodyStart < body.endIndex ? trimLeading(String(body[bodyStart...])) : ""
            }
            else if let lineEnd = body.firstIndex(of: "\n") {
                body = trimLeading(String(body[body.index(after: lineEnd)...]))
            }
        }
        else if body.hasPrefix(legacyMentionBlockPrefix),
                let blockEnd = body.range(of: legacyMentionBlockSuffix) {
            let payloadStart = body.index(body.startIndex, offsetBy: legacyMentionBlockPrefix.count)
            let payload = String(body[payloadStart..<blockEnd.lowerBound])
            parsed.mentions = parseLegacyMentionPayload(payload)
            let bodyStart = blockEnd.upperBound
            body = bodyStart < body.endIndex ? trimLeading(String(body[bodyStart...])) : ""
        }

        parsed.bodyText = body
        return parsed
    }

    static func notificationPreviewText(_ rawText: String?) -> String {
        let body = parse(rawText).bodyText.trimmingCharacters(in: .whitespacesAndNewlines)
        return body.replacingOccurrences(of: "\n", with: " ")
    }

    // MARK: Handles, the "@" of the field and the block of a message (DESIGN-group-contacts-private-mentions.md)

    /// A member the field offers after "@", with the handle that goes into the text.
    struct Candidate: Equatable {
        let member: AirGroupMember
        let handle: String
    }

    /// The characters a mention is made of after its "@": letters, marks, digits and "_" of any script, the token
    /// `@([\p{L}\p{M}\p{N}_]+)` of every client.
    static func isTokenScalar(_ scalar: Unicode.Scalar) -> Bool {
        if scalar == "_" {
            return true
        }
        switch scalar.properties.generalCategory {
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
                 .nonspacingMark, .spacingMark, .enclosingMark,
                 .decimalNumber, .letterNumber, .otherNumber:
                return true
            default:
                return false
        }
    }

    /// The handle a name in the group makes, the same on every client: letters and digits of any script are kept,
    /// and a mark right after a kept character; a run of spaces, "-" or "." becomes one "_"; everything else is
    /// dropped and "_" at the end is trimmed. What a name has from " (" on is left out. Empty when nothing is kept.
    static func baseHandle(fromName name: String) -> String {
        var source = Substring(name)
        if let paren = name.range(of: " ("), paren.lowerBound > name.startIndex {
            source = name[..<paren.lowerBound]
        }
        var handle = String.UnicodeScalarView()
        var lastKept = false
        var lastUnderscore = false
        for scalar in source.unicodeScalars {
            switch scalar.properties.generalCategory {
                case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber:
                    handle.append(scalar)
                    lastKept = true
                    lastUnderscore = false
                case .nonspacingMark, .spacingMark, .enclosingMark:
                    // A mark belongs to the letter before it (Arabic vowels, a Cyrillic accent): kept with it.
                    if lastKept {
                        handle.append(scalar)
                    }
                default:
                    if (scalar == " " || scalar == "-" || scalar == "."), !lastUnderscore, !handle.isEmpty {
                        handle.append("_")
                        lastUnderscore = true
                    }
                    lastKept = false
            }
        }
        while handle.last == "_" {
            handle.removeLast()
        }
        return String(handle)
    }

    /// The handle of every member with a key, by the key in upper case: `baseHandle`, else "u" and the first 8
    /// characters of the key; a handle two members would share (in any case of its letters) gets "_" and the first
    /// 4 characters of the key, for each of them, and the first 8 when that still leaves two alike, so every
    /// client names them alike (Android `mentionHandles`, desktop `mentionHandles`).
    static func handles(for members: [AirGroupMember]) -> [String: String] {
        var base: [String: String] = [:]
        for member in members where !member.publicKey.isEmpty {
            let key = member.publicKey.uppercased()
            guard base[key] == nil else {
                continue
            }
            var handle = baseHandle(fromName: member.name)
            if handle.isEmpty {
                handle = "u" + String(key.prefix(8))
            }
            base[key] = handle
        }
        var result = base
        for digits in [4, 8] {
            var uses: [String: Int] = [:]
            for handle in result.values {
                uses[handle.lowercased(), default: 0] += 1
            }
            for (key, handle) in result where (uses[handle.lowercased()] ?? 0) > 1 {
                result[key] = (base[key] ?? handle) + "_" + String(key.prefix(digits))
            }
        }
        return result
    }

    /// What follows each "@" of `text` for as long as the characters of a handle go, in order.
    static func tokens(in text: String) -> [String] {
        let scalars = Array(text.unicodeScalars)
        var result: [String] = []
        var index = 0
        while index < scalars.count {
            guard scalars[index] == "@" else {
                index += 1
                continue
            }
            var end = index + 1
            while end < scalars.count, isTokenScalar(scalars[end]) {
                end += 1
            }
            if end > index + 1 {
                var token = String.UnicodeScalarView()
                token.append(contentsOf: scalars[(index + 1)..<end])
                result.append(String(token))
            }
            index = end
        }
        return result
    }

    /// The members `body` mentions: every "@token" that is the handle of a member now (in any case of its letters),
    /// once per member, as it was typed.
    static func mentions(in body: String, handles: [String: String]) -> [MentionEntry] {
        guard body.contains("@"), !handles.isEmpty else {
            return []
        }
        var keyByHandle: [String: String] = [:]
        for (key, handle) in handles.sorted(by: { $0.key < $1.key }) where keyByHandle[handle.lowercased()] == nil {
            keyByHandle[handle.lowercased()] = key
        }
        var seen = Set<String>()
        var result: [MentionEntry] = []
        for token in tokens(in: body) {
            guard let key = keyByHandle[token.lowercased()], !seen.contains(key) else {
                continue
            }
            seen.insert(key)
            result.append(MentionEntry(pubkey: key, handle: token))
        }
        return result
    }

    /// The block that lists `mentions` before a body, with its line break; empty when there are none.
    static func block(for mentions: [MentionEntry]) -> String {
        guard !mentions.isEmpty else {
            return ""
        }
        let payload = mentions.map { $0.pubkey.uppercased() + ":" + $0.handle }.joined(separator: ";")
        return mentionBlockPrefix + payload + mentionBlockSuffix + "\n"
    }

    /// Where each mention listed in a message stands in the text it shows: every "@handle" (in any case) that no
    /// further character of a handle follows, as ranges of `text` with the member's key.
    static func mentionRanges(in text: String, mentions: [MentionEntry]) -> [(range: NSRange, key: String)] {
        let string = text as NSString
        var result: [(range: NSRange, key: String)] = []
        for mention in mentions where !mention.handle.isEmpty {
            let token = "@" + mention.handle
            var searchFrom = 0
            while searchFrom < string.length {
                let found = string.range(of: token, options: [.caseInsensitive], range: NSRange(location: searchFrom, length: string.length - searchFrom))
                guard found.location != NSNotFound else {
                    break
                }
                searchFrom = NSMaxRange(found)
                if searchFrom < string.length {
                    let next = string.substring(with: string.rangeOfComposedCharacterSequence(at: searchFrom))
                    if let scalar = next.unicodeScalars.first, isTokenScalar(scalar) {
                        continue
                    }
                }
                result.append((found, mention.pubkey))
            }
        }
        return result.sorted { $0.range.location < $1.range.location }
    }

    /// The mention being typed at `caret` (a place in `text` as the field counts it, in UTF-16): the range from its
    /// "@" to the end of the word and what is typed between the "@" and the caret. nil unless the caret is in a word
    /// of handle characters that starts with "@" at the start of the text, after a space or after a line break.
    static func typedMention(in text: String, caret: Int) -> (range: NSRange, query: String)? {
        let string = text as NSString
        guard caret >= 0, caret <= string.length else {
            return nil
        }
        var start = caret
        var foundAt = false
        while start > 0 {
            let piece = string.rangeOfComposedCharacterSequence(at: start - 1)
            let characters = string.substring(with: piece)
            if characters == "@" {
                foundAt = true
                break
            }
            guard characters.unicodeScalars.allSatisfy(isTokenScalar) else {
                return nil
            }
            start = piece.location
        }
        guard foundAt else {
            return nil
        }
        let at = start - 1
        if at > 0 {
            let before = string.substring(with: string.rangeOfComposedCharacterSequence(at: at - 1))
            guard before.unicodeScalars.allSatisfy({ CharacterSet.whitespacesAndNewlines.contains($0) }) else {
                return nil
            }
        }
        var end = caret
        while end < string.length {
            let piece = string.rangeOfComposedCharacterSequence(at: end)
            guard string.substring(with: piece).unicodeScalars.allSatisfy(isTokenScalar) else {
                break
            }
            end = NSMaxRange(piece)
        }
        return (NSRange(location: at, length: end - at), string.substring(with: NSRange(location: start, length: caret - start)))
    }

    /// The members the field offers for `query` (what follows the "@"): everyone but you who has a key and a handle,
    /// the handle starting with the query or the name holding it, case and diacritics ignored; online first, then by
    /// name.
    static func candidates(from members: [AirGroupMember], handles: [String: String], query: String) -> [Candidate] {
        let wanted = fold(query)
        let list = members.compactMap { member -> Candidate? in
            guard !member.isSelf, let handle = handles[member.publicKey.uppercased()] else {
                return nil
            }
            guard wanted.isEmpty || fold(handle).hasPrefix(wanted) || fold(member.name).contains(wanted) else {
                return nil
            }
            return Candidate(member: member, handle: handle)
        }
        return list.sorted { left, right in
            if left.member.isOnline != right.member.isOnline {
                return left.member.isOnline
            }
            let order = left.member.name.localizedStandardCompare(right.member.name)
            if order != .orderedSame {
                return order == .orderedAscending
            }
            return left.member.publicKey < right.member.publicKey
        }
    }

    private static func fold(_ text: String) -> String {
        return text.folding(options: [.caseInsensitive, .diacriticInsensitive, .widthInsensitive], locale: nil)
    }

    static func attributedMessage(_ message: String,
                                  mentionHandles: [String],
                                  textColor: UIColor,
                                  mentionColor: UIColor,
                                  font: UIFont) -> NSAttributedString {
        let baseAttributes: [NSAttributedString.Key: Any] = [
            .foregroundColor: textColor,
            .font: font,
        ]
        let result = NSMutableAttributedString(string: message, attributes: baseAttributes)

        guard !mentionHandles.isEmpty else {
            return result
        }

        let boldFont = UIFont.boldSystemFont(ofSize: font.pointSize)
        for handle in mentionHandles where !handle.isEmpty {
            let token = "@" + handle
            var searchFrom = message.startIndex
            while searchFrom < message.endIndex,
                  let range = message.range(of: token, range: searchFrom..<message.endIndex) {
                let nsRange = NSRange(range, in: message)
                result.addAttribute(.foregroundColor, value: mentionColor, range: nsRange)
                result.addAttribute(.font, value: boldFont, range: nsRange)
                searchFrom = range.upperBound
            }
        }

        return result
    }

    private static func parseMentionPayload(_ payload: String) -> [MentionEntry] {
        guard !payload.isEmpty else {
            return []
        }

        return payload.split(separator: ";", omittingEmptySubsequences: false).compactMap { part in
            let text = String(part)
            guard let colon = text.firstIndex(of: ":"), colon > text.startIndex else {
                return nil
            }
            var entry = MentionEntry()
            entry.pubkey = String(text[..<colon]).uppercased()
            entry.handle = String(text[text.index(after: colon)...])
            return entry.handle.isEmpty ? nil : entry
        }
    }

    private static func parseLegacyMentionPayload(_ payload: String) -> [MentionEntry] {
        guard !payload.isEmpty else {
            return []
        }

        if let colon = payload.firstIndex(of: ":"), colon > payload.startIndex {
            var entry = MentionEntry()
            entry.pubkey = String(payload[..<colon]).uppercased()
            entry.handle = String(payload[payload.index(after: colon)...])
            return entry.handle.isEmpty ? [] : [entry]
        }

        return []
    }

    private static func trimLeading(_ text: String) -> String {
        var index = text.startIndex
        while index < text.endIndex, text[index] == "\n" || text[index] == "\r" {
            index = text.index(after: index)
        }
        return index < text.endIndex ? String(text[index...]) : ""
    }
}

/// KHANDAQ (Figma): deterministic per-peer colors for group sender names (Telegram-style).
/// Keyed by the display name (frozen on the message at receipt → stable across restarts).
/// Palette tuned to read on both the light and the dark incoming bubble.
enum GroupPeerColors {
    private static let palette: [UIColor] = [
        UIColor(red: 0.88, green: 0.44, blue: 0.46, alpha: 1.0),   // red
        UIColor(red: 0.91, green: 0.62, blue: 0.31, alpha: 1.0),   // orange
        UIColor(red: 0.65, green: 0.58, blue: 0.91, alpha: 1.0),   // violet
        UIColor(red: 0.48, green: 0.78, blue: 0.38, alpha: 1.0),   // green
        UIColor(red: 0.43, green: 0.79, blue: 0.80, alpha: 1.0),   // cyan
        UIColor(red: 0.40, green: 0.67, blue: 0.87, alpha: 1.0),   // blue
        UIColor(red: 0.93, green: 0.48, blue: 0.68, alpha: 1.0),   // pink
    ]

    static func color(forName name: String) -> UIColor {
        var hash: UInt32 = 5381
        for byte in name.utf8 {
            hash = hash &* 33 &+ UInt32(byte)
        }
        return palette[Int(hash % UInt32(palette.count))]
    }
}
