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
