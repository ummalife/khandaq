// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// KHANDAQ (0.2.57): Report, on a message someone else wrote in a group, any group (SPEC-community, part B,
/// item 5): a mail to support for the person to send from their own mail. App Review asks for it on iOS
/// (guideline 1.2, content from strangers); desktop and Android write the same mail. Its words are support's,
/// in English: the person reads them before sending, and nothing is sent by the app.
struct GroupReport: Equatable {
    /// Where reports go.
    static let address = "support@khandaq.org"
    /// The text of the message goes up to this many characters; none is cut in half.
    static let textLimit = 500

    let groupName: String
    /// The author's key in the group, in hexadecimal; the mail names its first 8 characters.
    let authorKey: String
    /// When the message was written.
    let time: Date
    /// The words of the message; for a file, its name.
    let text: String

    /// The group's name on one line, as the mail names it.
    var group: String {
        return groupName.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
    }

    /// The key the entry kept of its author when it came: a text from the group's history on its text, a file
    /// on its file, a live text or a private message on the entry itself. Never the key of whoever has the
    /// author's number now: toxcore gives a freed number to the next member who comes. Empty for an entry
    /// that kept none.
    static func authorKey(of message: OCTMessageAbstract) -> String {
        if let key = message.messageText?.groupSenderPubkey, !key.isEmpty {
            return key
        }
        if let key = message.messageFile?.groupSenderPubkey, !key.isEmpty {
            return key
        }
        // On an incoming entry it is the other member's key: the author's.
        if !message.isOutgoing(), let key = message.groupPrivatePeerPubkey, !key.isEmpty {
            return key
        }
        return ""
    }

    /// The first 8 characters of the author's key, in upper case; "unknown" for an entry that kept none.
    var author: String {
        let key = authorKey.trimmingCharacters(in: .whitespacesAndNewlines)
        return key.isEmpty ? "unknown" : String(key.prefix(8)).uppercased()
    }

    var subject: String {
        return "Report: " + group
    }

    /// The body, its lines ended with LF, for the mail composer.
    var body: String {
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.formatOptions = [.withInternetDateTime]
        // A Character of Swift is what a person sees as one, a pair of surrogates or a letter with its marks
        // alike, so the cut never splits one.
        let words = String(text.prefix(GroupReport.textLimit))
        return GroupReport.lines("Group: \(group)\nAuthor: \(author)\nTime: \(formatter.string(from: time))\nMessage:\n\(words)",
                                 end: "\n")
    }

    /// mailto: the address, with the subject and the body each percent-encoded and the lines of the body
    /// ended with CRLF (RFC 6068), so &, =, #, + and line breaks in a name or a message stay where they are.
    var mailtoURL: URL? {
        let crlfBody = GroupReport.lines(body, end: "\r\n")
        return URL(string: "mailto:\(GroupReport.address)?subject=\(GroupReport.encoded(subject))&body=\(GroupReport.encoded(crlfBody))")
    }

    /// Every line end, CRLF, CR or LF, as `end`.
    static func lines(_ text: String, end: String) -> String {
        return text.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .replacingOccurrences(of: "\n", with: end)
    }

    /// Everything but the unreserved characters of RFC 3986, as UTF-8 in %XX.
    static func encoded(_ text: String) -> String {
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return text.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
    }
}
