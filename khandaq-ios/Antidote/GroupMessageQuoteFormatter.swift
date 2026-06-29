// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

enum GroupMessageQuoteFormatter {
    private static let prefix = "----\n"
    private static let suffix = "\n----"

    static func quotedBlock(for text: String) -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return ""
        }
        return "\(prefix)\(trimmed)\(suffix)\n"
    }

    static func appendQuote(_ quoteText: String, to existingInput: String) -> String {
        let block = quotedBlock(for: quoteText)
        guard !block.isEmpty else {
            return existingInput
        }

        if existingInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return block
        }
        return existingInput + "\n" + block
    }
}

enum MessageSearchHighlighter {
    static func highlightedText(_ text: String, query: String, textColor: UIColor) -> NSAttributedString {
        let attributed = NSMutableAttributedString(
                string: text,
                attributes: [.foregroundColor: textColor])

        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmedQuery.count > 0 else {
            return attributed
        }

        let haystack = text as NSString
        let needle = trimmedQuery as NSString
        var searchRange = NSRange(location: 0, length: haystack.length)

        while searchRange.location < haystack.length {
            let found = haystack.range(of: needle as String, options: [.caseInsensitive], range: searchRange)
            if found.location == NSNotFound {
                break
            }
            attributed.addAttribute(.backgroundColor, value: UIColor.systemYellow.withAlphaComponent(0.45), range: found)
            searchRange.location = found.location + found.length
            searchRange.length = haystack.length - searchRange.location
        }

        return attributed
    }

    static func messageMatches(_ message: OCTMessageAbstract, query: String) -> Bool {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return true
        }

        if message.groupSystemMessage {
            return false
        }

        if let text = message.messageText?.text {
            return text.range(of: trimmed, options: [.caseInsensitive]) != nil
        }

        if let fileName = message.messageFile?.fileName {
            return fileName.range(of: trimmed, options: [.caseInsensitive]) != nil
        }

        return false
    }
}
