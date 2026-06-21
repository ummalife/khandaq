// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// Telegram-style reply metadata embedded in plain message text (cross-platform with Android).
enum MessageReplyHelper {
    static let headerPrefix = "[KQ|"
    static let headerSuffix = "]"
    static let footer = "[KQ/end]"
    private static let legacyPrefix = "----\n"
    private static let legacySuffix = "\n----"

    struct ReplyMeta {
        var localMessageId: Int64 = 0
        var senderPubkeyTail: String = ""
        var sortTimestampMs: Int64 = 0
        var senderName: String = ""
        var previewText: String = ""
        var legacyQuote = false
    }

    struct ParsedMessage {
        var reply: ReplyMeta?
        var bodyText: String = ""
    }

    static func parse(_ rawText: String?) -> ParsedMessage {
        var parsed = ParsedMessage()
        guard let rawText = rawText, !rawText.isEmpty else {
            return parsed
        }

        if rawText.hasPrefix(headerPrefix),
           let headerEnd = rawText.range(of: headerSuffix),
           let footerRange = rawText.range(of: footer),
           headerEnd.upperBound <= footerRange.lowerBound {
            let headerBody = String(rawText[rawText.index(rawText.startIndex, offsetBy: headerPrefix.count)..<headerEnd.lowerBound])
            if var meta = parseHeader(headerBody) {
                let previewStart = headerEnd.upperBound
                if previewStart < footerRange.lowerBound {
                    meta.previewText = String(rawText[previewStart..<footerRange.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
                }
                parsed.reply = meta
                let bodyStart = footerRange.upperBound
                parsed.bodyText = bodyStart < rawText.endIndex
                    ? trimLeading(String(rawText[bodyStart...]))
                    : ""
                return parsed
            }
        }

        if rawText.hasPrefix(legacyPrefix),
           let endRange = rawText.range(of: legacySuffix) {
            var meta = ReplyMeta()
            meta.legacyQuote = true
            meta.previewText = String(rawText[legacyPrefix.endIndex..<endRange.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
            parsed.reply = meta
            var bodyStart = endRange.upperBound
            while bodyStart < rawText.endIndex, rawText[bodyStart].isWhitespace {
                bodyStart = rawText.index(after: bodyStart)
            }
            parsed.bodyText = bodyStart < rawText.endIndex ? String(rawText[bodyStart...]) : ""
            return parsed
        }

        parsed.bodyText = rawText
        return parsed
    }

    static func encode(_ meta: ReplyMeta?, bodyText: String?) -> String {
        guard let meta = meta else {
            return bodyText ?? ""
        }

        let preview = truncatePreview(meta.previewText)
        let safeName = sanitizeField(meta.senderName)
        let tail = meta.senderPubkeyTail.uppercased()
        let header = "\(headerPrefix)\(meta.localMessageId)|\(tail)|\(meta.sortTimestampMs)|\(safeName)\(headerSuffix)\n\(preview)\n\(footer)\n"
        return header + (bodyText ?? "")
    }

    static func previewForDisplay(_ text: String?, maxLen: Int) -> String {
        let single = (text ?? "")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard single.count > maxLen, maxLen > 1 else {
            return single
        }
        return String(single.prefix(maxLen - 1)).trimmingCharacters(in: .whitespacesAndNewlines) + "…"
    }

    static func pubkeyTail(_ pubkey: String?) -> String {
        guard let pubkey = pubkey, pubkey.count >= 8 else {
            return pubkey?.uppercased() ?? ""
        }
        return String(pubkey.suffix(8)).uppercased()
    }

    static func stableLocalId(_ uniqueIdentifier: String) -> Int64 {
        var hash: UInt64 = 5381
        for byte in uniqueIdentifier.utf8 {
            hash = ((hash << 5) &+ hash) &+ UInt64(byte)
        }
        return Int64(bitPattern: hash & 0x7FFFFFFFFFFFFFFF)
    }

    static func previewText(for message: OCTMessageAbstract) -> String? {
        if let text = message.messageText?.text, !text.isEmpty {
            let body = GroupMentionHelper.parse(text).bodyText.trimmingCharacters(in: .whitespacesAndNewlines)
            return body.isEmpty ? text.trimmingCharacters(in: .whitespacesAndNewlines) : body
        }
        if let fileName = message.messageFile?.fileName, !fileName.isEmpty {
            return fileName
        }
        return nil
    }

    /// KHANDAQ (#38): the user-visible body of a message for copy/forward — reply-quote markup
    /// (`[KQ|…]…[KQ/end]`) AND mention markup (`[KQ|m|…][KQ/m]`) stripped. Mirrors what the bubble
    /// renders, so copying never leaks the raw wire markup. Falls back to the file name for media.
    static func plainBody(for message: OCTMessageAbstract) -> String? {
        if let text = message.messageText?.text, !text.isEmpty {
            let afterReply = parse(text).bodyText
            let body = GroupMentionHelper.parse(afterReply).bodyText.trimmingCharacters(in: .whitespacesAndNewlines)
            return body.isEmpty ? afterReply.trimmingCharacters(in: .whitespacesAndNewlines) : body
        }
        if let fileName = message.messageFile?.fileName, !fileName.isEmpty {
            return fileName
        }
        return nil
    }

    private static func parseHeader(_ headerBody: String) -> ReplyMeta? {
        let parts = headerBody.split(separator: "|", maxSplits: 3, omittingEmptySubsequences: false)
        guard parts.count >= 4,
              let localId = Int64(parts[0].trimmingCharacters(in: .whitespaces)),
              let sortTs = Int64(parts[2].trimmingCharacters(in: .whitespaces)) else {
            return nil
        }
        var meta = ReplyMeta()
        meta.localMessageId = localId
        meta.senderPubkeyTail = String(parts[1]).trimmingCharacters(in: .whitespaces)
        meta.sortTimestampMs = sortTs
        meta.senderName = String(parts[3]).trimmingCharacters(in: .whitespaces)
        return meta
    }

    private static func truncatePreview(_ text: String) -> String {
        previewForDisplay(text, maxLen: 200)
    }

    private static func sanitizeField(_ value: String) -> String {
        value
            .replacingOccurrences(of: "|", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func trimLeading(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
