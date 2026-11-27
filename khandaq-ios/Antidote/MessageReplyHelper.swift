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

    struct ReplyMeta: Codable {
        var localMessageId: Int64 = 0
        var senderPubkeyTail: String = ""
        var sortTimestampMs: Int64 = 0
        var senderName: String = ""
        var previewText: String = ""
        var legacyQuote = false
        /// KHANDAQ (0.2.56): how the bar above the field names the author when the header names them
        /// otherwise: "You" over a reply to your own message, whose header names you as the others know
        /// you. It never goes on the wire; nil for a reply that arrived.
        var localSenderName: String?
        /// KHANDAQ (inbox #10, 27.11): the message this reply quotes was deleted on this device, so the quote says
        /// so instead of its words. It goes neither on the wire nor into a kept draft (CodingKeys); set where the
        /// reply is shown (ChatDeletedReplyTargets).
        var targetDeleted = false

        private enum CodingKeys: String, CodingKey {
            case localMessageId, senderPubkeyTail, sortTimestampMs, senderName, previewText, legacyQuote, localSenderName
        }
    }

    /// KHANDAQ (inbox #10, 27.11): a message deleted on this device as a reply names it: by the local id a reply
    /// written here carries (stableLocalId of its uniqueIdentifier) and by its date in milliseconds.
    struct ReplyTargetMark: Equatable {
        let localId: Int64
        let dateMs: Int64
    }

    /// How far apart two devices may date one message: the slack scrollToReplyTarget allows.
    static let replyTargetWindowMs: Int64 = 5000

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
           let headerEnd = rawText.range(of: headerSuffix) {
            let headerBody = String(rawText[rawText.index(rawText.startIndex, offsetBy: headerPrefix.count)..<headerEnd.lowerBound])
            if var meta = parseHeader(headerBody) {
                let previewStart = headerEnd.upperBound

                if let footerRange = rawText.range(of: footer), headerEnd.upperBound <= footerRange.lowerBound {
                    // Normal form: preview lives between the header and the [KQ/end] footer; body follows it.
                    if previewStart < footerRange.lowerBound {
                        meta.previewText = String(rawText[previewStart..<footerRange.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                    parsed.reply = meta
                    let bodyStart = footerRange.upperBound
                    parsed.bodyText = bodyStart < rawText.endIndex
                        ? trimLeading(String(rawText[bodyStart...]))
                        : ""
                }
                else {
                    // KHANDAQ (#146): the [KQ/end] footer is missing (a peer build that omits it, or a
                    // truncated/split message). parseHeader already validated the int64 localId + timestamp,
                    // so this IS a reply — don't leak the raw "[KQ|…]" marker as the message body. Take the
                    // first line after the header as the quoted preview and the rest as the actual body.
                    let after = String(rawText[previewStart...]).drop(while: { $0 == "\n" || $0 == "\r" })
                    if let nl = after.firstIndex(where: { $0 == "\n" || $0 == "\r" }) {
                        meta.previewText = String(after[..<nl]).trimmingCharacters(in: .whitespacesAndNewlines)
                        parsed.bodyText = trimLeading(String(after[after.index(after: nl)...]))
                    }
                    else {
                        meta.previewText = String(after).trimmingCharacters(in: .whitespacesAndNewlines)
                        parsed.bodyText = ""
                    }
                    parsed.reply = meta
                }
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

        parsed.bodyText = readableBody(rawText)
        return parsed
    }

    /// Khandaq markup this build cannot read (a newer reply form, a block another client added) is not
    /// shown as a service string (redesign stage 3, item 10). A message shaped like a reply keeps its
    /// body; one that is nothing but such markup says so in words. Mention blocks pass untouched: the
    /// group parser reads them after this.
    static func readableBody(_ rawText: String) -> String {
        guard rawText.hasPrefix(headerPrefix),
              !rawText.hasPrefix(GroupMentionHelper.mentionBlockPrefix),
              !rawText.hasPrefix("[KQ|m]") else {
            return rawText
        }
        let rest: String
        if let footerRange = rawText.range(of: footer) {
            rest = String(rawText[footerRange.upperBound...])
        }
        else if let headerEnd = rawText.range(of: headerSuffix) {
            rest = String(rawText[headerEnd.upperBound...])
        }
        else {
            rest = ""
        }
        let body = trimLeading(rest)
        return body.isEmpty ? String(localized: "air_message_unknown_format") : body
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

    /// KHANDAQ (#161): drop control characters (most importantly U+0000). Messages received by
    /// older builds carry a trailing NUL; if it leaks into reply markup, the peer's receive path
    /// truncates the whole message at that NUL and the reply body arrives empty. `keepNewlines`
    /// preserves line breaks for message bodies.
    static func stripControlChars(_ text: String, keepNewlines: Bool = false) -> String {
        String(text.unicodeScalars.filter {
            !CharacterSet.controlCharacters.contains($0) || (keepNewlines && ($0 == "\n" || $0 == "\r"))
        }.map(Character.init))
    }

    /// One line: a line break becomes a space, as on Android. The control characters went first and took the
    /// breaks with them, so the words on each side of a break ran together ("on\nfor" read "onfor"), in the
    /// quote on screen and in the preview `encode` sends (QA live 0.2.61).
    static func previewForDisplay(_ text: String?, maxLen: Int) -> String {
        let single = stripControlChars(text ?? "", keepNewlines: true)
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard single.count > maxLen, maxLen > 1 else {
            return single
        }
        return String(single.prefix(maxLen - 1)).trimmingCharacters(in: .whitespacesAndNewlines) + "…"
    }

    /// KHANDAQ (owner, 28.11): the quoted words as the screen shows them (the quote over a reply, the bar over the
    /// field, VoiceOver): a quoted place is named with its coordinates (LocationMessage.caption), not its wire text
    /// "khandaq-location:…". Only the screen: the header that travels keeps the wire text (encode), so each reader
    /// names the place in its own language.
    static func quoteForDisplay(_ text: String?, maxLen: Int) -> String {
        let shown = previewForDisplay(text, maxLen: maxLen)
        guard let place = LocationMessage.parse(shown) else {
            return shown
        }
        return LocationMessage.caption(latitude: place.latitude, longitude: place.longitude)
    }

    /// What VoiceOver says of the quote over a reply: "In reply to Amina, the quoted words", as the quote
    /// shows them (ChatReplyQuoteView). Nil when the entry shows no quote. The entry is read as one element,
    /// so without this a reply was read as a message of its own (QA live 0.2.61).
    static func spokenQuote(_ meta: ReplyMeta?) -> String? {
        guard let meta = meta else {
            return nil
        }
        let preview = quoteForDisplay(meta.previewText, maxLen: 160)
        guard !preview.isEmpty else {
            return nil
        }
        let name = meta.senderName.isEmpty ? String(localized: "chat_reply_unknown_sender") : meta.senderName
        // KHANDAQ (inbox #10, 27.11): as the quote shows it, of a message deleted here.
        let quoted = meta.targetDeleted ? String(localized: "chat_reply_deleted") : preview
        return String(localized: "chat_reply_header", name) + ", " + quoted
    }

    /// KHANDAQ (inbox #10, 27.11): a message's date in milliseconds as a reply carries it (Int64(dateInterval * 1000)),
    /// nil for one that is no time at all.
    static func replyDateMs(_ dateInterval: TimeInterval) -> Int64? {
        let ms = dateInterval * 1000
        guard ms.isFinite, ms >= 1, ms < 9.0e18 else {
            return nil
        }
        return Int64(ms)
    }

    /// KHANDAQ (inbox #10, 27.11): the marks objcTox keeps of a chat's deleted messages
    /// (OCTSubmanagerChats.deletedReplyTargets), "<date in ms> <uniqueIdentifier>" lines; one it cannot read is left out.
    static func replyTargetMarks(fromLines lines: [String]) -> [ReplyTargetMark] {
        return lines.compactMap { line in
            let field = line.split(separator: " ", maxSplits: 1)
            guard field.count == 2, let dateMs = Int64(field[0]), dateMs > 0 else {
                return nil
            }
            let uniqueIdentifier = field[1].trimmingCharacters(in: .whitespaces)
            guard !uniqueIdentifier.isEmpty else {
                return nil
            }
            return ReplyTargetMark(localId: stableLocalId(uniqueIdentifier), dateMs: dateMs)
        }
    }

    /// KHANDAQ (inbox #10, 27.11): whether the message a reply quotes was deleted on this device. The header names it
    /// by a local id, ours only when the reply was written here, and by its date, which the devices of a chat set a
    /// little apart. So the quote is of a deleted message when a deletion here bears the reply's id, or lies nearer the
    /// date than every message still here; a message still here that bears the id is the one quoted. The reply's own
    /// row is no candidate, nor a row a deletion is taking away right now. With the live messages unknown (nil), only
    /// the id tells. The same rule as Android's ReplyTargetTombstones.isDeleted.
    static func isTargetDeleted(_ meta: ReplyMeta,
                                deleted: [ReplyTargetMark],
                                replyLocalId: Int64,
                                live: (Int64) -> [ReplyTargetMark]?) -> Bool {
        guard !meta.legacyQuote, meta.sortTimestampMs > 0, !deleted.isEmpty else {
            return false
        }
        let date = meta.sortTimestampMs
        let ours = meta.localMessageId > 0
        var deletedIds = Set<Int64>()
        var byId = false
        var nearestDeleted = Int64.max
        for mark in deleted where mark.dateMs > 0 {
            deletedIds.insert(mark.localId)
            let apart = abs(mark.dateMs - date)
            guard apart <= replyTargetWindowMs else {
                continue
            }
            byId = byId || (ours && mark.localId == meta.localMessageId)
            nearestDeleted = min(nearestDeleted, apart)
        }
        guard nearestDeleted != Int64.max else {
            return false
        }
        guard let rows = live(date) else {
            return byId
        }
        var nearestLive = Int64.max
        for row in rows where row.dateMs > 0 && row.localId != replyLocalId && !deletedIds.contains(row.localId) {
            let apart = abs(row.dateMs - date)
            guard apart <= replyTargetWindowMs else {
                continue
            }
            if ours && row.localId == meta.localMessageId {
                return false
            }
            nearestLive = min(nearestLive, apart)
        }
        return byId || nearestDeleted < nearestLive
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

    // KHANDAQ (#iOS forward): a peer/legacy name that arrived as "(null)"/"null"/empty is not a real name.
    static func cleanForwardName(_ raw: String) -> String? {
        let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || t == "(null)" || t.lowercased() == "null" { return nil }
        return t
    }

    /// The mark that opens a forward header inside the message text. It travels on the wire and the
    /// other clients look for it, so it stays exactly this; only the words after it come from the
    /// strings, which carry no arrow (redesign stage 3).
    static let forwardMark = "\u{21AA} "

    // KHANDAQ (#iOS forward): the "↪ Переслано от …" attribution is plain text baked into the body at
    // send time. Drop ALL contiguous leading "↪ " header lines, returning the real body — used before
    // RE-forwarding so headers never stack ("↪ … \n ↪ … \n body").
    static func stripForwardedHeader(_ text: String) -> String {
        var lines = text.components(separatedBy: "\n")
        while let first = lines.first, first.trimmingCharacters(in: .whitespaces).hasPrefix("↪ ") {
            lines.removeFirst()
        }
        return lines.joined(separator: "\n")
    }

    // KHANDAQ (#iOS forward): collapse stacked/legacy "↪ Переслано от …" headers into exactly ONE clean
    // header at DISPLAY time, so incoming cross-platform / pre-fix "(null)" and re-forward stacking are
    // cleaned even without re-sending. Peels every contiguous leading "↪ " line, takes the outermost
    // forwarder name, sanitizes "(null)"/empty away, and re-emits a single header (bare "↪ Переслано" /
    // "↪ Forwarded" when there is no real name). Bodies without a "↪ " prefix pass through unchanged.
    static func normalizeForwardedHeader(_ text: String) -> String {
        var lines = text.components(separatedBy: "\n")
        guard let firstLine = lines.first,
              firstLine.trimmingCharacters(in: .whitespaces).hasPrefix("↪ ") else {
            return text
        }
        let outerHeader = firstLine.trimmingCharacters(in: .whitespaces)
        while let f = lines.first, f.trimmingCharacters(in: .whitespaces).hasPrefix("↪ ") {
            lines.removeFirst()
        }
        let body = lines.joined(separator: "\n")
        var name: String? = nil
        for prefix in ["↪ Переслано от ", "↪ Forwarded from "] {
            if outerHeader.hasPrefix(prefix) {
                name = cleanForwardName(String(outerHeader.dropFirst(prefix.count)))
                break
            }
        }
        let header: String
        if let name = name {
            // String(localized:) formats by itself; formatting its result again had already lost the
            // name, so every forward read "(null)".
            header = forwardMark + String(localized: "chat_forwarded_from_format", name as NSString)
        }
        else {
            header = forwardMark + String(localized: "chat_forwarded_noname")
        }
        return body.isEmpty ? header : header + "\n" + body
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

    /// KHANDAQ (0.2.56): a "]" ends the header on every reader, so a name with one is cut there and the rest
    /// spills into the quote; Android takes it out of the name too.
    private static func sanitizeField(_ value: String) -> String {
        stripControlChars(value)
            .replacingOccurrences(of: "]", with: " ")
            .replacingOccurrences(of: "|", with: " ")
            .replacingOccurrences(of: "\n", with: " ")
            .replacingOccurrences(of: "\r", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func trimLeading(_ value: String) -> String {
        stripControlChars(value, keepNewlines: true).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
