// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import MessageUI
import UIKit

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

    /// The key the entry kept of its author when it came (AirGroupTranscript.authorKey(of:)). Empty for an
    /// entry that kept none.
    ///
    /// An entry from the group's history came from the member who relayed it, and the key on it is that
    /// member's claim, signed by nobody (OCTMessageText groupSenderPubkey): it names the author only when a
    /// signature proved this entry (`authorVerified`, OCTSubmanagerGroups isGroupMessageAuthorVerified). Else it
    /// names nobody, and the mail says "unknown": a forged entry must not send support after a member who
    /// never wrote it. A live entry keeps its key: the transport vouched for its sender.
    static func authorKey(of message: OCTMessageAbstract, authorVerified: Bool) -> String {
        if message.groupHistorySync && !authorVerified {
            return ""
        }
        return AirGroupTranscript.authorKey(of: message)
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

extension GroupReport {
    /// The report of `message`, an entry of the group `chat` or a private message in it. `groups` tells whether
    /// a signature proved the author of an entry from the group's history; without it, none did.
    init(chat: OCTChat, message: OCTMessageAbstract, groups: OCTSubmanagerGroups?) {
        let authorVerified = groups?.isGroupMessageAuthorVerified(message, in: chat) ?? false
        let stored = KhandaqCommunity.displayName(for: chat) ?? chat.groupName ?? ""
        let text: String
        if let file = message.messageFile {
            // A file says what it is by its name; what is in it stays on this phone.
            text = file.fileName ?? ""
        }
        else {
            text = MessageReplyHelper.plainBody(for: message) ?? message.messageText?.text ?? ""
        }
        self.init(groupName: stored.isEmpty ? ChatListContent.groupTitle(for: chat) : stored,
                  authorKey: GroupReport.authorKey(of: message, authorVerified: authorVerified),
                  time: message.date(),
                  text: text)
    }

    /// The mail composer with the report written, or, without a mail account on the phone, the same mail
    /// through a mailto: link. The person sends it themselves. When no app takes the link either, the
    /// address is shown, so the person still knows where to write.
    func present(from controller: UIViewController) {
        // Who and where, never the words of the message.
        log("Report: a mail to support about a message of \(author) in a group")

        if MFMailComposeViewController.canSendMail() {
            let composer = MFMailComposeViewController()
            composer.mailComposeDelegate = GroupReportMailDelegate.shared
            composer.setToRecipients([GroupReport.address])
            composer.setSubject(subject)
            composer.setMessageBody(body, isHTML: false)
            controller.present(composer, animated: true, completion: nil)
            return
        }
        guard let url = mailtoURL else {
            log("Report: the mail could not be written as a link")
            GroupReport.showAddress(from: controller)
            return
        }
        UIApplication.shared.open(url, options: [:]) { [weak controller] opened in
            if !opened, let controller = controller {
                log("Report: no mail app took the mail")
                GroupReport.showAddress(from: controller)
            }
        }
    }

    /// The address of support, under the title of the action: no new words to translate.
    private static func showAddress(from controller: UIViewController) {
        let alert = UIAlertController(title: String(localized: "chat_report_action"),
                                      message: GroupReport.address,
                                      preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "alert_ok"), style: .cancel, handler: nil))
        controller.present(alert, animated: true, completion: nil)
    }
}

/// Closes the composer whatever the person did there: sending is theirs.
private final class GroupReportMailDelegate: NSObject, MFMailComposeViewControllerDelegate {
    static let shared = GroupReportMailDelegate()

    func mailComposeController(_ controller: MFMailComposeViewController,
                               didFinishWith result: MFMailComposeResult,
                               error: Error?) {
        controller.dismiss(animated: true, completion: nil)
    }
}
