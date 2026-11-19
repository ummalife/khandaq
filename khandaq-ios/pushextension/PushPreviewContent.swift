// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CryptoKit
import Foundation
import Security
import UserNotifications

/// KHANDAQ (0.2.64): what a push preview says (docs/PUSH_PREVIEWS.md). The file is built into the
/// notification service extension, which opens previews, and into the app, which writes the settings the
/// extension reads and words its own notifications the same way.
struct PushPreview: Equatable {
    enum Kind: String {
        case text, voice, video, photo, file, reaction
    }

    var kind: Kind
    var body: String?
    var reactedKind: Kind?
    var emoji: String?
    var senderName: String?
    var groupName: String?
    var groupId: String?
    var sentAt: TimeInterval
    var eventKey: String?
}

enum PushPreviewCrypto {
    static let keyLabel = Data("khandaq push preview key v1".utf8)
    static let associatedData = Data("khandaq push preview v1".utf8)
    static let sealVersion: UInt8 = 1
    static let maxSealedCharacters = 1400
    static let maxPlaintextBytes = 900

    /// `K = HMAC-SHA256(M, "khandaq push preview key v1" || P)`.
    static func contactKey(masterKey: Data, publicKeyHex: String) -> SymmetricKey? {
        guard masterKey.count == 32, let publicKey = data(fromHex: publicKeyHex), publicKey.count == 32 else {
            return nil
        }
        let code = HMAC<SHA256>.authenticationCode(for: keyLabel + publicKey, using: SymmetricKey(data: masterKey))
        return SymmetricKey(data: Data(code))
    }

    /// The plaintext of `base64url(0x01 || nonce || ciphertext || tag)`, nil when it does not open.
    static func open(_ sealed: String, key: SymmetricKey) -> Data? {
        guard sealed.count <= maxSealedCharacters,
              let raw = base64URLDecoded(sealed),
              raw.count > 1 + 12 + 16,
              raw[raw.startIndex] == sealVersion else {
            return nil
        }
        do {
            let box = try ChaChaPoly.SealedBox(combined: raw.dropFirst())
            return try ChaChaPoly.open(box, using: key, authenticating: associatedData)
        }
        catch {
            return nil
        }
    }

    /// The preview a plaintext describes; nil for anything this version does not read.
    static func decode(_ plaintext: Data) -> PushPreview? {
        guard plaintext.count <= maxPlaintextBytes,
              let object = try? JSONSerialization.jsonObject(with: plaintext, options: []),
              let fields = object as? [String: Any],
              (fields["v"] as? NSNumber)?.intValue == 1,
              let kindName = fields["k"] as? String,
              let kind = PushPreview.Kind(rawValue: kindName) else {
            return nil
        }

        func text(_ name: String, limit: Int) -> String? {
            guard let value = fields[name] as? String else {
                return nil
            }
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            return trimmed.isEmpty ? nil : String(trimmed.prefix(limit))
        }

        var groupId = text("gid", limit: 64)?.uppercased()
        if let id = groupId, id.count != 64 || data(fromHex: id) == nil {
            groupId = nil
        }

        return PushPreview(kind: kind,
                           body: text("b", limit: 200),
                           reactedKind: (fields["rk"] as? String).flatMap { PushPreview.Kind(rawValue: $0) },
                           emoji: text("e", limit: 8),
                           senderName: text("n", limit: 64),
                           groupName: text("g", limit: 64),
                           groupId: groupId,
                           sentAt: (fields["ts"] as? NSNumber)?.doubleValue ?? 0,
                           eventKey: text("h", limit: 200))
    }

    static func data(fromHex hex: String) -> Data? {
        guard hex.count % 2 == 0 else {
            return nil
        }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else {
                return nil
            }
            bytes.append(byte)
            index = next
        }
        return Data(bytes)
    }

    static func base64URLDecoded(_ text: String) -> Data? {
        var base64 = text.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        let remainder = base64.count % 4
        if remainder == 1 {
            return nil
        }
        if remainder > 0 {
            base64 += String(repeating: "=", count: 4 - remainder)
        }
        return Data(base64Encoded: base64)
    }
}

/// This device's preview items in the Keychain, shared by the app and the extension: the master key and
/// the allowed senders (written by objcTox, OCTPushPreview), and the "show message preview" setting and the
/// language, which the app mirrors here because the extension cannot read the app's own settings.
enum PushPreviewStore {
    static let service = "org.khandaq.push.preview"

    static func masterKey() -> Data? {
        guard let key = read("master"), key.count == 32 else {
            return nil
        }
        return key
    }

    /// `show`: "1" or "0"; missing means on, as the app's own setting defaults to on.
    static func showsContent() -> Bool {
        guard let value = read("show") else {
            return true
        }
        return String(data: value, encoding: .utf8) != "0"
    }

    /// `lang`: the app's language; missing means the phone's, if the app speaks it.
    static func language() -> String {
        if let value = read("lang"), let code = String(data: value, encoding: .utf8), PushPreviewText.languages.contains(code) {
            return code
        }
        for tag in Locale.preferredLanguages {
            let base = tag.split(separator: "-").first.map(String.init) ?? tag
            if PushPreviewText.languages.contains(base) {
                return base
            }
        }
        return "en"
    }

    /// `allow`: the public keys, 32 bytes each, of the contacts this device handed its key to and still has
    /// (written by objcTox, OCTPushPreview). Nil when there is none or it cannot be read: then nothing is shown.
    static func allowedSenders() -> Data? {
        return read("allow")
    }

    /// True when `senderHex` (64 hex) is on that list. A contact removed or blocked keeps the key it was given,
    /// so the key alone does not let a preview through.
    static func allows(_ allowed: Data?, senderHex: String) -> Bool {
        guard let allowed = allowed, allowed.count % 32 == 0,
              let sender = PushPreviewCrypto.data(fromHex: senderHex), sender.count == 32 else {
            return false
        }
        var offset = allowed.startIndex
        while offset < allowed.endIndex {
            if allowed[offset..<(offset + 32)] == sender {
                return true
            }
            offset += 32
        }
        return false
    }

    /// Called by the app at launch and whenever either setting changes.
    static func mirror(showContent: Bool, language: String) {
        write("show", Data((showContent ? "1" : "0").utf8))
        write("lang", Data(language.utf8))
    }

    /// kSecAttrAccessible stays out of the query: in a search it is a predicate (see KhandaqPush.swift).
    private static func read(_ account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess else {
            return nil
        }
        return item as? Data
    }

    private static func write(_ account: String, _ value: Data) {
        if read(account) == value {
            return
        }
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = value
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }
}

/// How a preview reads, in the four languages the app ships.
enum PushPreviewText {
    static let languages = ["en", "ru", "ar", "zh"]

    struct Strings {
        let newMessage: String
        let newReaction: String
        let voice: String
        let video: String
        let photo: String
        let file: String
        /// `{b}` is the file name.
        let namedFile: String
        /// `{e}` is the emoji, `{b}` the text reacted to.
        let reactedText: String
        let reactedVoice: String
        let reactedVideo: String
        let reactedPhoto: String
        let reactedFile: String
        let reactedMessage: String
    }

    static func strings(_ language: String) -> Strings {
        switch language {
            case "ru":
                return Strings(newMessage: "Новое сообщение",
                               newReaction: "Новая реакция",
                               voice: "🎤 Голосовое сообщение",
                               video: "🎬 Видео",
                               photo: "📷 Фото",
                               file: "📎 Файл",
                               namedFile: "📎 {b}",
                               reactedText: "Реакция {e} на «{b}»",
                               reactedVoice: "Реакция {e} на ваше голосовое сообщение",
                               reactedVideo: "Реакция {e} на ваше видео",
                               reactedPhoto: "Реакция {e} на ваше фото",
                               reactedFile: "Реакция {e} на ваш файл",
                               reactedMessage: "Реакция {e} на ваше сообщение")
            case "ar":
                return Strings(newMessage: "رسالة جديدة",
                               newReaction: "تفاعل جديد",
                               voice: "🎤 رسالة صوتية",
                               video: "🎬 فيديو",
                               photo: "📷 صورة",
                               file: "📎 ملف",
                               namedFile: "📎 {b}",
                               reactedText: "تفاعل {e} مع «{b}»",
                               reactedVoice: "تفاعل {e} مع رسالتك الصوتية",
                               reactedVideo: "تفاعل {e} مع الفيديو الخاص بك",
                               reactedPhoto: "تفاعل {e} مع صورتك",
                               reactedFile: "تفاعل {e} مع ملفك",
                               reactedMessage: "تفاعل {e} مع رسالتك")
            case "zh":
                return Strings(newMessage: "新消息",
                               newReaction: "新的表情回应",
                               voice: "🎤 语音消息",
                               video: "🎬 视频",
                               photo: "📷 照片",
                               file: "📎 文件",
                               namedFile: "📎 {b}",
                               reactedText: "对“{b}”回应了 {e}",
                               reactedVoice: "对你的语音消息回应了 {e}",
                               reactedVideo: "对你的视频回应了 {e}",
                               reactedPhoto: "对你的照片回应了 {e}",
                               reactedFile: "对你的文件回应了 {e}",
                               reactedMessage: "对你的消息回应了 {e}")
            default:
                return Strings(newMessage: "New message",
                               newReaction: "New reaction",
                               voice: "🎤 Voice message",
                               video: "🎬 Video",
                               photo: "📷 Photo",
                               file: "📎 File",
                               namedFile: "📎 {b}",
                               reactedText: "Reacted {e} to \"{b}\"",
                               reactedVoice: "Reacted {e} to your voice message",
                               reactedVideo: "Reacted {e} to your video",
                               reactedPhoto: "Reacted {e} to your photo",
                               reactedFile: "Reacted {e} to your file",
                               reactedMessage: "Reacted {e} to your message")
        }
    }

    /// The line for a message of `kind`: the text itself, or what kind of file it is.
    static func messageBody(kind: PushPreview.Kind, text: String?, language: String) -> String {
        let s = strings(language)
        switch kind {
            case .text:
                return text ?? s.newMessage
            case .voice:
                return s.voice
            case .video:
                return s.video
            case .photo:
                return s.photo
            case .file:
                return text.map { fill(s.namedFile, emoji: nil, text: $0) } ?? s.file
            case .reaction:
                return s.newReaction
        }
    }

    /// The line for a reaction `emoji` to a message of `reactedKind` (its text in `text`).
    static func reactionBody(emoji: String, reactedKind: PushPreview.Kind?, text: String?, language: String) -> String {
        let s = strings(language)
        let template: String
        switch reactedKind {
            case .some(.text) where text != nil:
                template = s.reactedText
            case .some(.voice):
                template = s.reactedVoice
            case .some(.video):
                template = s.reactedVideo
            case .some(.photo):
                template = s.reactedPhoto
            case .some(.file):
                template = s.reactedFile
            default:
                template = s.reactedMessage
        }
        return fill(template, emoji: emoji, text: text)
    }

    /// Title and body the extension shows for a preview that opened.
    static func content(for preview: PushPreview, showsContent: Bool, language: String) -> (title: String, body: String) {
        let s = strings(language)
        let isGroup = preview.groupId != nil
        let title = (isGroup ? preview.groupName : preview.senderName) ?? "Khandaq"

        guard showsContent else {
            return (title, preview.kind == .reaction ? s.newReaction : s.newMessage)
        }

        var body: String
        if preview.kind == .reaction {
            if let emoji = preview.emoji {
                body = reactionBody(emoji: emoji, reactedKind: preview.reactedKind, text: preview.body, language: language)
            }
            else {
                body = s.newReaction
            }
        }
        else {
            body = messageBody(kind: preview.kind, text: preview.body, language: language)
        }
        if isGroup, let sender = preview.senderName {
            body = sender + ": " + body
        }
        return (title, body)
    }

    /// Templates take `{e}` and `{b}` by plain replacement, so a `%` in a message is never a format.
    private static func fill(_ template: String, emoji: String?, text: String?) -> String {
        var result = template
        if let text = text {
            result = result.replacingOccurrences(of: "{b}", with: text)
        }
        if let emoji = emoji {
            result = result.replacingOccurrences(of: "{e}", with: emoji)
        }
        return result
    }
}

enum PushPreviewPresenter {
    /// A preview older than this is old news: the relay's own text stays.
    static let maxAge: TimeInterval = 48 * 3600

    /// Replaces the relay's generic text with what the sealed preview in `userInfo` says, when it comes from
    /// a contact on the allowed list and opens with this device's key. On anything else the content is left
    /// as it came.
    @discardableResult
    static func apply(to content: UNMutableNotificationContent,
                      userInfo: [AnyHashable: Any],
                      masterKey: Data? = PushPreviewStore.masterKey(),
                      allowedSenders: Data? = PushPreviewStore.allowedSenders(),
                      showsContent: Bool = PushPreviewStore.showsContent(),
                      language: String = PushPreviewStore.language(),
                      now: Date = Date()) -> Bool {
        guard let sealed = userInfo["khq_pv"] as? String,
              let sender = userInfo["sender_pubkey"] as? String,
              PushPreviewStore.allows(allowedSenders, senderHex: sender),
              let masterKey = masterKey,
              let key = PushPreviewCrypto.contactKey(masterKey: masterKey, publicKeyHex: sender),
              let plaintext = PushPreviewCrypto.open(sealed, key: key),
              let preview = PushPreviewCrypto.decode(plaintext),
              now.timeIntervalSince1970 - preview.sentAt <= maxAge else {
            return false
        }

        let shown = PushPreviewText.content(for: preview, showsContent: showsContent, language: language)
        content.title = shown.title
        content.body = shown.body
        content.threadIdentifier = preview.groupId ?? sender.uppercased()

        var info = content.userInfo
        if let eventKey = preview.eventKey {
            info["khq_ev"] = eventKey
        }
        if let groupId = preview.groupId {
            info["khq_gid"] = groupId
        }
        content.userInfo = info
        return true
    }
}
