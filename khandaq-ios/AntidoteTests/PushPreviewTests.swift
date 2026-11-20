// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CryptoKit
import UserNotifications
import XCTest
@testable import Antidote

/// KHANDAQ (0.2.64): push previews, against the test vectors of docs/PUSH_PREVIEWS.md. The sender half is
/// objcTox (OCTPushPreview, libsodium), the recipient half the push extension's PushPreviewContent.swift
/// (CryptoKit); Android and the relay check the same vectors.
final class PushPreviewTests: XCTestCase {
    private let masterKey = data("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private let publicKey = "7E5A6B1C2D3E4F50617283940A1B2C3D4E5F60718293A4B5C6D7E8F901122334"
    private let contactKey = data("76437c9f9a1e86d7731ab94a0b7329c55129122bd40e4f05c71c22a7b3d88e75")
    private let keyPacket = data("BE010176437C9F9A1E86D7731AB94A0B7329C55129122BD40E4F05C71C22A7B3D88E75")
    private let nonce = data("000102030405060708090a0b")
    private let eventKey = "t:" + String(repeating: "AB", count: 32)
    private let sealed = "AQABAgMEBQYHCAkKC0cuZhE-761wu62F31CwYSNfERS9di10rfHn1Jnd2SQOJlEMElxEyACoX6lIA3nRn3Sl9GcJwRsv9OPSe7vzhOealnmCAJTD3uSj8rkONYeGSckX8WD6qi-WbLXRv9rUwHr2UexIOPXc-ua7_kVhtnjMbOHSMf4h1pSNZa27AUhSAv9ipJnIDtdsoVdBinsLSmml2n6AJdWU5TGF3hQ"
    private let sentAt: TimeInterval = 1763571600

    private var plaintext: String {
        return "{\"v\":1,\"k\":\"text\",\"b\":\"Привет 👋\",\"n\":\"Isa\",\"ts\":1763571600,\"h\":\"\(eventKey)\"}"
    }

    private var textFields: [String: Any] {
        return ["k": "text", "b": "Привет 👋", "n": "Isa", "ts": NSNumber(value: Int64(sentAt)), "h": eventKey]
    }

    // MARK: Keys

    func testContactKeyMatchesTheVectorOnBothSides() {
        XCTAssertEqual(OCTPushPreview.perContactKey(withMasterKey: masterKey, publicKeyHex: publicKey), contactKey)
        XCTAssertEqual(OCTPushPreview.perContactKey(withMasterKey: masterKey, publicKeyHex: publicKey.lowercased()), contactKey)

        let key = PushPreviewCrypto.contactKey(masterKey: masterKey, publicKeyHex: publicKey)
        XCTAssertEqual(key.map { $0.withUnsafeBytes { Data($0) } }, contactKey)
        XCTAssertNil(PushPreviewCrypto.contactKey(masterKey: Data(count: 16), publicKeyHex: publicKey))
        XCTAssertNil(PushPreviewCrypto.contactKey(masterKey: masterKey, publicKeyHex: "7E5A"))
    }

    func testKeyPacketIsReadBack() {
        XCTAssertEqual(OCTPushPreview.peerKey(fromPacket: keyPacket), contactKey)
        XCTAssertNil(OCTPushPreview.peerKey(fromPacket: keyPacket.prefix(34)))
        var otherVersion = keyPacket
        otherVersion[2] = 0x02
        XCTAssertNil(OCTPushPreview.peerKey(fromPacket: otherVersion))
    }

    // MARK: Sealing (objcTox) and opening (push extension)

    func testPlaintextIsSerialisedAsTheSpecShowsIt() {
        let bytes = OCTPushPreview.plaintext(withFields: textFields)
        XCTAssertEqual(bytes.flatMap { String(data: $0, encoding: .utf8) }, plaintext)
    }

    func testSealWithTheVectorNonceGivesTheVectorPreview() {
        let bytes = Data(plaintext.utf8)
        XCTAssertEqual(OCTPushPreview.sealPlaintext(bytes, key: contactKey, nonce: nonce), sealed)
    }

    func testPidMatchesTheVector() {
        XCTAssertEqual(OCTPushPreview.pid(forEventKey: eventKey, key: contactKey), "f7c7d2c8db7e2f187cad5b14889dfd07")
        let random = OCTPushPreview.pid(forEventKey: nil, key: contactKey)
        XCTAssertEqual(random.count, 32)
        XCTAssertNotEqual(random, OCTPushPreview.pid(forEventKey: nil, key: contactKey))
    }

    func testTheExtensionOpensTheVectorPreview() {
        guard let key = PushPreviewCrypto.contactKey(masterKey: masterKey, publicKeyHex: publicKey),
              let opened = PushPreviewCrypto.open(sealed, key: key),
              let preview = PushPreviewCrypto.decode(opened) else {
            return XCTFail("the vector preview did not open")
        }
        XCTAssertEqual(preview.kind, .text)
        XCTAssertEqual(preview.body, "Привет 👋")
        XCTAssertEqual(preview.senderName, "Isa")
        XCTAssertEqual(preview.sentAt, sentAt)
        XCTAssertEqual(preview.eventKey, eventKey)
        XCTAssertNil(preview.groupId)
    }

    func testAnAlteredPreviewDoesNotOpen() {
        guard let key = PushPreviewCrypto.contactKey(masterKey: masterKey, publicKeyHex: publicKey) else {
            return XCTFail("no key")
        }
        var characters = Array(sealed)
        characters[40] = characters[40] == "A" ? "B" : "A"
        XCTAssertNil(PushPreviewCrypto.open(String(characters), key: key))

        let otherKey = PushPreviewCrypto.contactKey(masterKey: Data(repeating: 7, count: 32), publicKeyHex: publicKey)!
        XCTAssertNil(PushPreviewCrypto.open(sealed, key: otherKey))
        XCTAssertNil(PushPreviewCrypto.open("AgABAgMEBQYHCAkKC0cuZhE", key: key))
    }

    func testRandomNonceRoundTripWithReactionFields() {
        let fields: [String: Any] = ["k": "reaction", "e": "❤️", "rk": "voice", "n": "Amina", "g": "Book club",
                                     "gid": String(repeating: "0A", count: 32), "ts": NSNumber(value: Int64(sentAt)),
                                     "h": "r:" + String(repeating: "CD", count: 32) + ":❤️"]
        guard let bytes = OCTPushPreview.plaintext(withFields: fields),
              let seal = OCTPushPreview.sealPlaintext(bytes, key: contactKey, nonce: nil),
              let key = PushPreviewCrypto.contactKey(masterKey: masterKey, publicKeyHex: publicKey),
              let opened = PushPreviewCrypto.open(seal, key: key),
              let preview = PushPreviewCrypto.decode(opened) else {
            return XCTFail("the reaction preview did not round-trip")
        }
        XCTAssertNotEqual(seal, OCTPushPreview.sealPlaintext(bytes, key: contactKey, nonce: nil))
        XCTAssertEqual(preview.kind, .reaction)
        XCTAssertEqual(preview.reactedKind, .voice)
        XCTAssertEqual(preview.emoji, "❤️")
        XCTAssertEqual(preview.groupName, "Book club")
        XCTAssertEqual(preview.groupId, String(repeating: "0A", count: 32))

        let shown = PushPreviewText.content(for: preview, showsContent: true, language: "en")
        XCTAssertEqual(shown.title, "Book club")
        XCTAssertEqual(shown.body, "Amina: Reacted ❤️ to your voice message")
    }

    func testALongTextIsShortenedToFit() {
        let long = String(repeating: "Ж", count: 2000)
        var fields = textFields
        fields["b"] = long
        fields["n"] = String(repeating: "N", count: 200)
        guard let bytes = OCTPushPreview.plaintext(withFields: fields),
              let seal = OCTPushPreview.sealPlaintext(bytes, key: contactKey, nonce: nil) else {
            return XCTFail("no plaintext")
        }
        XCTAssertLessThanOrEqual(bytes.count, 900)
        XCTAssertLessThanOrEqual(seal.count, 1400)
        let preview = PushPreviewCrypto.decode(bytes)
        XCTAssertEqual(preview?.body?.count, 140)
        XCTAssertEqual(preview?.senderName?.count, 48)
    }

    func testPrefixNeverSplitsACharacter() {
        XCTAssertEqual(OCTPushPreview.prefix(of: "👨‍👩‍👧‍👦abc", characters: 1), "👨‍👩‍👧‍👦")
        XCTAssertEqual(OCTPushPreview.prefix(of: "ab", characters: 5), "ab")
    }

    // MARK: Group presence

    func testGroupPresenceMatchesTheVector() {
        let hash = OCTPushPreview.groupPresenceHash(forChatIdHex: "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20",
                                                    peerPublicKeyHex: "A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0")
        XCTAssertEqual(hash, "9f033743ff83e9574563245fe61fac74")

        let packet = OCTPushPreview.groupPresencePacket(withHashes: ["9f033743ff83e9574563245fe61fac74"])
        XCTAssertEqual(packet, data("BE0201019F033743FF83E9574563245FE61FAC74"))
        XCTAssertEqual(OCTPushPreview.groupPresenceHashes(fromPacket: packet), ["9f033743ff83e9574563245fe61fac74"])

        let empty = OCTPushPreview.groupPresencePacket(withHashes: [])
        XCTAssertEqual(empty, data("BE020100"))
        XCTAssertEqual(OCTPushPreview.groupPresenceHashes(fromPacket: empty), [])
    }

    // MARK: What the notification says

    func testTheExtensionShowsTheTextUnderTheSendersName() {
        let content = UNMutableNotificationContent()
        content.title = "Khandaq"
        content.body = "New message"
        let applied = PushPreviewPresenter.apply(to: content,
                                                 userInfo: ["khq_pv": sealed, "sender_pubkey": publicKey.lowercased()],
                                                 masterKey: masterKey,
                                                 allowedSenders: data(publicKey),
                                                 showsContent: true,
                                                 language: "ru",
                                                 now: Date(timeIntervalSince1970: sentAt + 60))
        XCTAssertTrue(applied)
        XCTAssertEqual(content.title, "Isa")
        XCTAssertEqual(content.body, "Привет 👋")
        XCTAssertEqual(content.threadIdentifier, publicKey)
        XCTAssertEqual(content.userInfo["khq_ev"] as? String, eventKey)
        XCTAssertNil(content.userInfo["khq_gid"])
    }

    func testWithPreviewsOffOnlyTheNameShows() {
        let content = UNMutableNotificationContent()
        PushPreviewPresenter.apply(to: content,
                                   userInfo: ["khq_pv": sealed, "sender_pubkey": publicKey],
                                   masterKey: masterKey,
                                   allowedSenders: data(publicKey),
                                   showsContent: false,
                                   language: "en",
                                   now: Date(timeIntervalSince1970: sentAt + 60))
        XCTAssertEqual(content.title, "Isa")
        XCTAssertEqual(content.body, "New message")
    }

    func testAnOldOrForeignPreviewLeavesTheRelayText() {
        let stale = UNMutableNotificationContent()
        stale.title = "Khandaq"
        stale.body = "New message"
        XCTAssertFalse(PushPreviewPresenter.apply(to: stale,
                                                  userInfo: ["khq_pv": sealed, "sender_pubkey": publicKey],
                                                  masterKey: masterKey,
                                                  allowedSenders: data(publicKey),
                                                  showsContent: true,
                                                  language: "en",
                                                  now: Date(timeIntervalSince1970: sentAt + 49 * 3600)))
        XCTAssertEqual(stale.body, "New message")

        let foreign = UNMutableNotificationContent()
        foreign.title = "Khandaq"
        // An allowed sender whose key the preview was not sealed with.
        XCTAssertFalse(PushPreviewPresenter.apply(to: foreign,
                                                  userInfo: ["khq_pv": sealed, "sender_pubkey": String(repeating: "11", count: 32)],
                                                  masterKey: masterKey,
                                                  allowedSenders: data(publicKey + String(repeating: "11", count: 32)),
                                                  showsContent: true,
                                                  language: "en",
                                                  now: Date(timeIntervalSince1970: sentAt + 60)))
        XCTAssertEqual(foreign.title, "Khandaq")

        let none = UNMutableNotificationContent()
        XCTAssertFalse(PushPreviewPresenter.apply(to: none, userInfo: ["sender_pubkey": publicKey], masterKey: masterKey,
                                                  allowedSenders: data(publicKey)))
    }

    func testASenderOffTheAllowedListLeavesTheRelayText() {
        // A contact removed or blocked keeps the key it was given: the preview opens, and still is not shown.
        let other = String(repeating: "22", count: 32)
        for allowed in [nil, data(other), data(other).prefix(31), data(other) + data(publicKey).prefix(31)] as [Data?] {
            let content = UNMutableNotificationContent()
            content.title = "Khandaq"
            content.body = "New message"
            XCTAssertFalse(PushPreviewPresenter.apply(to: content,
                                                      userInfo: ["khq_pv": sealed, "sender_pubkey": publicKey],
                                                      masterKey: masterKey,
                                                      allowedSenders: allowed,
                                                      showsContent: true,
                                                      language: "en",
                                                      now: Date(timeIntervalSince1970: sentAt + 60)))
            XCTAssertEqual(content.title, "Khandaq")
            XCTAssertEqual(content.body, "New message")
        }
        // Anywhere on the list, in either case, and from a slice that does not start at 0.
        let content = UNMutableNotificationContent()
        let slice = (data("00") + data(other) + data(publicKey)).dropFirst(1)
        XCTAssertEqual(slice.startIndex, 1)
        XCTAssertTrue(PushPreviewPresenter.apply(to: content,
                                                 userInfo: ["khq_pv": sealed, "sender_pubkey": publicKey.lowercased()],
                                                 masterKey: masterKey,
                                                 allowedSenders: slice,
                                                 showsContent: true,
                                                 language: "en",
                                                 now: Date(timeIntervalSince1970: sentAt + 60)))
        XCTAssertEqual(content.body, "Привет 👋")
        XCTAssertFalse(PushPreviewStore.allows(data(publicKey), senderHex: "7E5A"))
        XCTAssertFalse(PushPreviewStore.allows(data(publicKey), senderHex: String(repeating: "zz", count: 32)))
    }

    func testTheAllowedListFollowsTheContactsAndADeletedProfileTakesItAll() {
        // The real Keychain of the test host: the list objcTox writes is the one the extension reads.
        OCTPushPreview.resetAll()
        defer { OCTPushPreview.resetAll() }
        let other = String(repeating: "22", count: 32)
        XCTAssertFalse(OCTPushPreview.isAllowedSenderPublicKey(publicKey))
        XCTAssertNotNil(OCTPushPreview.keyPacket(forFriendPublicKey: publicKey))
        XCTAssertTrue(OCTPushPreview.isAllowedSenderPublicKey(publicKey.lowercased()))
        XCTAssertTrue(OCTPushPreview.allowSenderPublicKey(other))
        XCTAssertTrue(OCTPushPreview.allowSenderPublicKey(other), "a contact already on the list")
        XCTAssertEqual(PushPreviewStore.allowedSenders()?.count, 64)
        XCTAssertTrue(PushPreviewStore.allows(PushPreviewStore.allowedSenders(), senderHex: publicKey))
        XCTAssertFalse(OCTPushPreview.allowSenderPublicKey("7E5A"))

        // Removed or blocked.
        OCTPushPreview.forgetFriendPublicKey(publicKey)
        XCTAssertFalse(OCTPushPreview.isAllowedSenderPublicKey(publicKey))
        XCTAssertFalse(PushPreviewStore.allows(PushPreviewStore.allowedSenders(), senderHex: publicKey))
        XCTAssertTrue(OCTPushPreview.isAllowedSenderPublicKey(other))

        // The profile is deleted: a new master key, and no one on the list.
        OCTPushPreview.storePeerKey(contactKey, forFriendPublicKey: other)
        let before = OCTPushPreview.masterKey()
        XCTAssertNotNil(before)
        XCTAssertEqual(PushPreviewStore.masterKey(), before)
        OCTPushPreview.resetAll()
        XCTAssertNil(PushPreviewStore.masterKey())
        XCTAssertNil(PushPreviewStore.allowedSenders())
        XCTAssertFalse(OCTPushPreview.isAllowedSenderPublicKey(other))
        XCTAssertNil(OCTPushPreview.peerKey(forFriendPublicKey: other))
        let after = OCTPushPreview.masterKey()
        XCTAssertNotNil(after)
        XCTAssertNotEqual(after, before)
    }

    func testFileKindsAndTheirLines() {
        XCTAssertEqual(OCTPushPreview.kind(forFileName: "1763571600.file.m4a"), "voice")
        XCTAssertEqual(OCTPushPreview.kind(forFileName: "song.m4a"), "file")
        XCTAssertEqual(OCTPushPreview.kind(forFileName: "clip.MP4"), "video")
        XCTAssertEqual(OCTPushPreview.kind(forFileName: "IMG_0001.HEIC"), "photo")
        XCTAssertEqual(OCTPushPreview.kind(forFileName: "report.pdf"), "file")
        XCTAssertEqual(OCTPushPreview.kind(forFileName: nil), "file")

        XCTAssertEqual(PushPreviewText.messageBody(kind: .voice, text: "voice.m4a", language: "en"), "🎤 Voice message")
        XCTAssertEqual(PushPreviewText.messageBody(kind: .video, text: nil, language: "ru"), "🎬 Видео")
        XCTAssertEqual(PushPreviewText.messageBody(kind: .file, text: "report.pdf", language: "en"), "📎 report.pdf")
        XCTAssertEqual(PushPreviewText.reactionBody(emoji: "👍", reactedKind: .text, text: "50% off", language: "en"),
                       "Reacted 👍 to \"50% off\"")
        XCTAssertEqual(PushPreviewText.reactionBody(emoji: "👍", reactedKind: .photo, text: nil, language: "zh"), "对你的照片回应了 👍")
    }

    func testEveryLanguageHasEveryLine() {
        for language in PushPreviewText.languages {
            let s = PushPreviewText.strings(language)
            for line in [s.newMessage, s.newReaction, s.voice, s.video, s.photo, s.file, s.reactedVoice,
                         s.reactedVideo, s.reactedPhoto, s.reactedFile, s.reactedMessage] {
                XCTAssertFalse(line.isEmpty, language)
            }
            XCTAssertTrue(s.reactedText.contains("{e}") && s.reactedText.contains("{b}"), language)
            XCTAssertTrue(s.namedFile.contains("{b}"), language)
        }
    }

    // MARK: The wake banners a local notification takes the place of

    /// KHANDAQ (notification parity, 2026-11-20): every local notification took away every generic wake banner, so a
    /// Community message removed the only trace of a 1:1 message that had not come in yet. A local notification now
    /// takes the place of the generic banners of its own conversation only; a banner that names nobody (our own wake)
    /// cannot be told apart, and any local notification takes its place, as before.
    func testALocalNotificationReplacesTheGenericBannersOfItsOwnConversationOnly() {
        let other = String(repeating: "22", count: 32)
        let group = String(repeating: "C3", count: 32)
        func banner(title: String = "Khandaq", body: String = "New message", _ userInfo: [AnyHashable: Any]) -> UNMutableNotificationContent {
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.userInfo = userInfo
            return content
        }

        // The relay's wake, as the extension shows it when no preview opens.
        let wake = banner(["wake": "1", "sender_pubkey": publicKey])
        XCTAssertTrue(WakeBanners.isReplaced(wake, byNotificationAbout: publicKey), "the contact's own message")
        XCTAssertTrue(WakeBanners.isReplaced(wake, byNotificationAbout: publicKey.lowercased()), "in either case")
        XCTAssertFalse(WakeBanners.isReplaced(wake, byNotificationAbout: group), "a Community message leaves it")
        XCTAssertFalse(WakeBanners.isReplaced(wake, byNotificationAbout: other), "and so does another contact's")
        XCTAssertFalse(WakeBanners.isReplaced(wake, byNotificationAbout: nil))
        XCTAssertTrue(WakeBanners.isReplaced(banner(body: "New activity", ["sender_pubkey": publicKey]), byNotificationAbout: publicKey))
        XCTAssertTrue(WakeBanners.isReplaced(banner(["from": publicKey.lowercased()]), byNotificationAbout: publicKey),
                      "the legacy FCM API names the sender in from")

        // Our own wake (sendOwnPush) names nobody: any local notification takes its place, or it would stay until
        // swiped.
        let own = banner(["wake": "1"])
        XCTAssertNil(WakeBanners.owner(of: own))
        XCTAssertTrue(WakeBanners.isReplaced(own, byNotificationAbout: publicKey))
        XCTAssertTrue(WakeBanners.isReplaced(own, byNotificationAbout: group))
        XCTAssertTrue(WakeBanners.isReplaced(own, byNotificationAbout: nil))
        XCTAssertFalse(WakeBanners.isReplaced(banner(title: "Isa", body: "See you at six", [:]), byNotificationAbout: nil),
                       "one that says what came stays, whoever it is from")

        // A preview that opened with the previews off says "New message" under the sender's name, and is the
        // contact's.
        let opened = UNMutableNotificationContent()
        opened.userInfo = ["khq_pv": sealed, "sender_pubkey": publicKey.lowercased()]
        XCTAssertTrue(PushPreviewPresenter.apply(to: opened,
                                                 userInfo: opened.userInfo,
                                                 masterKey: masterKey,
                                                 allowedSenders: data(publicKey),
                                                 showsContent: false,
                                                 language: "en",
                                                 now: Date(timeIntervalSince1970: sentAt + 60)))
        XCTAssertEqual(WakeBanners.owner(of: opened), publicKey)
        XCTAssertTrue(WakeBanners.isReplaced(opened, byNotificationAbout: publicKey))
        XCTAssertFalse(WakeBanners.isReplaced(opened, byNotificationAbout: group))

        // One about a group is the group's.
        let inGroup = banner(title: "Book club", ["sender_pubkey": publicKey, "khq_gid": group])
        XCTAssertTrue(WakeBanners.isReplaced(inGroup, byNotificationAbout: group))
        XCTAssertFalse(WakeBanners.isReplaced(inGroup, byNotificationAbout: publicKey))

        // A banner that says what came is never taken away.
        XCTAssertFalse(WakeBanners.isReplaced(banner(title: "Isa", body: "See you at six", ["sender_pubkey": publicKey]),
                                              byNotificationAbout: publicKey))
    }
}

private func data(_ hex: String) -> Data {
    return PushPreviewCrypto.data(fromHex: hex)!
}
