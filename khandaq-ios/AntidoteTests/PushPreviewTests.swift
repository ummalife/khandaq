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

    // MARK: Group message wakes (0.2.69)

    private let groupChatId = "0102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F20"
    private let groupPeerKey = "A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0"

    func testGroupWakePacketMatchesTheVector() {
        let hash = OCTPushPreview.groupPresenceHash(forChatIdHex: groupChatId, peerPublicKeyHex: groupPeerKey)
        XCTAssertEqual(hash, "9f033743ff83e9574563245fe61fac74")

        let packet = OCTPushPreview.groupWakePacket(withHashes: [hash!])
        XCTAssertEqual(packet, data("BE0301019F033743FF83E9574563245FE61FAC74"))
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(fromPacket: packet), ["9f033743ff83e9574563245fe61fac74"])
        XCTAssertNil(OCTPushPreview.groupPresenceHashes(fromPacket: packet), "a 190/03 is no 190/02")
        XCTAssertNil(OCTPushPreview.groupWakeHashes(fromPacket: data("BE0201019F033743FF83E9574563245FE61FAC74")),
                     "nor the other way round")

        let empty = OCTPushPreview.groupWakePacket(withHashes: [])
        XCTAssertEqual(empty, data("BE030100"))
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(fromPacket: empty), [])

        // The first 64 well-formed hashes.
        let many = (0..<70).map { String(format: "%032x", $0) }
        let capped = OCTPushPreview.groupWakePacket(withHashes: ["not a hash"] + many)
        XCTAssertEqual(capped.count, 4 + 64 * 16)
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(fromPacket: capped), Array(many.prefix(64)))
    }

    func testAMalformedGroupWakePacketIsDropped() {
        let packet = data("BE0301019F033743FF83E9574563245FE61FAC74")
        XCTAssertNil(OCTPushPreview.groupWakeHashes(fromPacket: packet.prefix(19)), "a byte short")
        XCTAssertNil(OCTPushPreview.groupWakeHashes(fromPacket: packet + data("00")), "a byte over")
        XCTAssertNil(OCTPushPreview.groupWakeHashes(fromPacket: data("BE0301")), "no count")
        XCTAssertNil(OCTPushPreview.groupWakeHashes(fromPacket: Data()))
        var otherVersion = packet
        otherVersion[2] = 0x02
        XCTAssertNil(OCTPushPreview.groupWakeHashes(fromPacket: otherVersion))
        var otherPacket = packet
        otherPacket[0] = 0xB5
        XCTAssertNil(OCTPushPreview.groupWakeHashes(fromPacket: otherPacket))
        // 65 hashes, more than a set holds, though the length matches the count.
        XCTAssertNil(OCTPushPreview.groupWakeHashes(fromPacket: data("BE030141") + Data(count: 65 * 16)))
        XCTAssertNil(OCTPushPreview.peerKey(fromPacket: packet))
    }

    func testGroupEventKeysMatchTheVector() {
        XCTAssertEqual(OCTPushPreview.groupTextEventKey(withMessageId: 0x0000ABCD, senderPeerKeyHex: groupPeerKey.lowercased()),
                       "gt:0000ABCD:A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0")
        // toxcore's ids are unsigned; iOS keeps them in an Int32.
        XCTAssertEqual(OCTPushPreview.groupTextEventKey(withMessageId: UInt32(bitPattern: -2), senderPeerKeyHex: groupPeerKey),
                       "gt:FFFFFFFE:" + groupPeerKey)
        XCTAssertNil(OCTPushPreview.groupTextEventKey(withMessageId: 1, senderPeerKeyHex: ""))
        XCTAssertNil(OCTPushPreview.groupTextEventKey(withMessageId: 1, senderPeerKeyHex: nil))
        XCTAssertNil(OCTPushPreview.groupTextEventKey(withMessageId: 1, senderPeerKeyHex: String(groupPeerKey.dropLast(2))))

        let fileHash = String(repeating: "ab", count: 32)
        XCTAssertEqual(OCTPushPreview.groupFileEventKey(withMsgIdHex: fileHash), "gf:" + fileHash.uppercased())
        XCTAssertNil(OCTPushPreview.groupFileEventKey(withMsgIdHex: String(repeating: "ab", count: 16)))
        XCTAssertNil(OCTPushPreview.groupFileEventKey(withMsgIdHex: nil))
    }

    func testTheWakeSetLeavesOutAMutedGroupAndAGroupWhereTheFriendIsMuted() {
        let ownKey = String(repeating: "1a", count: 32)
        let friendMember = String(repeating: "2B", count: 32)
        let otherMember = String(repeating: "3c", count: 32)
        let chatId = { (n: Int) in String(format: "%064X", n) }
        let own = { (n: Int) in OCTPushPreview.groupPresenceHash(forChatIdHex: chatId(n), peerPublicKeyHex: ownKey)! }
        let friendPresence = (1...4).map { OCTPushPreview.groupPresenceHash(forChatIdHex: chatId($0), peerPublicKeyHex: friendMember)! }
        let groups = [
            OCTPushPreviewGroup(chatIdHex: chatId(1), ownPeerKeyHex: ownKey, muted: false, mutedMemberKeyHexes: []),
            OCTPushPreviewGroup(chatIdHex: chatId(2), ownPeerKeyHex: ownKey, muted: true, mutedMemberKeyHexes: []),
            OCTPushPreviewGroup(chatIdHex: chatId(3), ownPeerKeyHex: ownKey, muted: false,
                                mutedMemberKeyHexes: [otherMember, friendMember.lowercased()]),
            OCTPushPreviewGroup(chatIdHex: chatId(4), ownPeerKeyHex: ownKey, muted: false, mutedMemberKeyHexes: [otherMember]),
            OCTPushPreviewGroup(chatIdHex: "0102", ownPeerKeyHex: ownKey, muted: false, mutedMemberKeyHexes: []),
            OCTPushPreviewGroup(chatIdHex: chatId(1), ownPeerKeyHex: ownKey, muted: false, mutedMemberKeyHexes: []),
        ]
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(for: groups, friendPresenceHashes: friendPresence), [own(1), own(4)],
                       "group 2 is muted, and in group 3 the friend is a muted member")
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(for: groups, friendPresenceHashes: friendPresence.map { $0.uppercased() }),
                       [own(1), own(4)])
        // Without the friend's 190/02 set, nothing tells which member the friend is.
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(for: groups, friendPresenceHashes: nil), [own(1), own(3), own(4)])

        // More than 64 groups: the first 64 that may wake us.
        let many = [OCTPushPreviewGroup(chatIdHex: chatId(0), ownPeerKeyHex: ownKey, muted: true, mutedMemberKeyHexes: [])]
            + (1...70).map { OCTPushPreviewGroup(chatIdHex: chatId($0), ownPeerKeyHex: ownKey, muted: false, mutedMemberKeyHexes: []) }
        let set = OCTPushPreview.groupWakeHashes(for: many, friendPresenceHashes: [])
        XCTAssertEqual(set, (1...64).map(own))
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(fromPacket: OCTPushPreview.groupWakePacket(withHashes: set)), set)
    }

    func testAGroupMessageWakesTheOneContactThatClaimsAnAwayMember() {
        let ownKey = String(repeating: "a0", count: 32)
        let away = String(repeating: "a1", count: 32)
        let replayed = String(repeating: "a2", count: 32)
        let online = String(repeating: "a3", count: 32)
        let gone = String(repeating: "a4", count: 32)
        let alsoAway = String(repeating: "a5", count: 32)
        let unclaimed = String(repeating: "a6", count: 32)
        let hash = { (key: String) in OCTPushPreview.groupPresenceHash(forChatIdHex: self.groupChatId, peerPublicKeyHex: key)! }
        let contact = (1...6).map { String(repeating: "C\($0)", count: 32) }
        var wakeSets: [String: [String]] = [
            contact[0]: [hash(away), hash(alsoAway)],
            contact[1]: [hash(replayed)],
            contact[2]: ["0" + String(repeating: "f", count: 31), hash(replayed).uppercased()],
            contact[3]: [hash(online)],
            contact[4]: [hash(gone)],
            contact[5]: [hash(ownKey)],
        ]
        let members = [away, replayed, online, gone, alsoAway, unclaimed, ownKey].map { $0.uppercased() }
        func woken() -> [String: String] {
            return OCTPushPreview.groupWakeMembers(forChatIdHex: groupChatId,
                                                   memberKeys: members,
                                                   connectedKeys: [online],
                                                   ownKey: ownKey.uppercased(),
                                                   leftKeys: [gone.uppercased()],
                                                   contactWakeSets: wakeSets)
        }
        // Two claimants of one member wake neither; a connected member, one that left and our own key wake nobody; a
        // contact claiming two away members is woken once, for the first.
        XCTAssertEqual(woken(), [contact[0]: away])

        // Without the replay, the member's own contact is woken.
        wakeSets[contact[2]] = nil
        XCTAssertEqual(woken(), [contact[0]: away, contact[1]: replayed])

        // A contact whose set the group is not in wakes for nothing.
        XCTAssertEqual(OCTPushPreview.groupWakeMembers(forChatIdHex: String(repeating: "00", count: 32),
                                                       memberKeys: members,
                                                       connectedKeys: [],
                                                       ownKey: nil,
                                                       leftKeys: [],
                                                       contactWakeSets: wakeSets), [:])
        XCTAssertEqual(OCTPushPreview.groupWakeMembers(forChatIdHex: groupChatId, memberKeys: members, connectedKeys: [],
                                                       ownKey: nil, leftKeys: [], contactWakeSets: [:]), [:])
    }

    func testAContactsWakeSetIsKeptUntilTheContactGoes() {
        let contact = String(repeating: "4D", count: 32)
        let hashes = ["9f033743ff83e9574563245fe61fac74"]
        OCTPushPreview.forgetFriendPublicKey(contact)
        defer { OCTPushPreview.forgetFriendPublicKey(contact) }
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(forFriendPublicKey: contact), [], "a client before 0.2.69 sent none")

        OCTPushPreview.storeGroupWake(hashes, forFriendPublicKey: contact.lowercased())
        XCTAssertEqual(UserDefaults.standard.array(forKey: "khandaq.groupWake." + contact) as? [String], hashes)
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(forFriendPublicKey: contact.lowercased()), hashes)
        OCTPushPreview.storeGroupWake([], forFriendPublicKey: contact)
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(forFriendPublicKey: contact), [], "a set replaces the one before")
        XCTAssertNil(UserDefaults.standard.object(forKey: "khandaq.groupWake." + contact))

        // Removed or blocked: the wake set goes with the presence set.
        OCTPushPreview.storeGroupWake(hashes, forFriendPublicKey: contact)
        OCTPushPreview.storeGroupPresence(hashes, forFriendPublicKey: contact)
        XCTAssertEqual(OCTPushPreview.groupPresenceHashes(forFriendPublicKey: contact), hashes)
        OCTPushPreview.forgetFriendPublicKey(contact)
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(forFriendPublicKey: contact), [])
        XCTAssertEqual(OCTPushPreview.groupPresenceHashes(forFriendPublicKey: contact), [])
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(forFriendPublicKey: "4D4D"), [])
    }

    func testADeletedProfileTakesTheWakeSetsToo() {
        OCTPushPreview.resetAll()
        defer { OCTPushPreview.resetAll() }
        let contact = String(repeating: "5E", count: 32)
        OCTPushPreview.storeGroupWake(["9f033743ff83e9574563245fe61fac74"], forFriendPublicKey: contact)
        OCTPushPreview.storeGroupPresence(["9f033743ff83e9574563245fe61fac74"], forFriendPublicKey: contact)
        OCTPushPreview.resetAll()
        XCTAssertEqual(OCTPushPreview.groupWakeHashes(forFriendPublicKey: contact), [])
        XCTAssertEqual(OCTPushPreview.groupPresenceHashes(forFriendPublicKey: contact), [])
    }

    /// The app's own alert for a group message that came over Tox is skipped when a delivered notification carries
    /// its event key in `khq_ev` (showLocalNotificationObject): the key the push extension copied from the wake.
    func testALiveGroupMessageNamesTheEventItsWakeNamed() throws {
        let eventKey = "gt:0000ABCD:" + groupPeerKey
        let live = try groupEntry(messageId: 0x0000ABCD)
        XCTAssertEqual(NotificationCoordinator.groupEventKey(of: live), eventKey)

        let fields: [String: Any] = ["k": "text", "b": "Salam", "n": "Isa", "g": "Book club", "gid": groupChatId,
                                     "ts": NSNumber(value: Int64(sentAt)), "h": eventKey]
        guard let plaintext = OCTPushPreview.plaintext(withFields: fields),
              let groupSealed = OCTPushPreview.sealPlaintext(plaintext, key: contactKey, nonce: nil) else {
            return XCTFail("no group preview")
        }
        let content = UNMutableNotificationContent()
        XCTAssertTrue(PushPreviewPresenter.apply(to: content,
                                                 userInfo: ["khq_pv": groupSealed, "sender_pubkey": publicKey],
                                                 masterKey: masterKey,
                                                 allowedSenders: data(publicKey),
                                                 showsContent: true,
                                                 language: "en",
                                                 now: Date(timeIntervalSince1970: sentAt + 60)))
        XCTAssertEqual(content.title, "Book club")
        XCTAssertEqual(content.body, "Isa: Salam")
        XCTAssertEqual(content.userInfo["khq_gid"] as? String, groupChatId)
        XCTAssertEqual(content.userInfo["khq_ev"] as? String, NotificationCoordinator.groupEventKey(of: live))

        XCTAssertEqual(NotificationCoordinator.groupEventKey(of: try groupEntry(messageId: Int32(bitPattern: 0xFFFFFFFE))),
                       "gt:FFFFFFFE:" + groupPeerKey)

        let file = try appClass("OCTMessageFile", as: OCTMessageFile.Type.self).init()
        file.groupMsgIdHashHex = String(repeating: "ab", count: 32)
        let liveFile = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        liveFile.groupSenderPeerId = 5
        liveFile.messageFile = file
        XCTAssertEqual(NotificationCoordinator.groupEventKey(of: liveFile), "gf:" + String(repeating: "AB", count: 32))

        // No wake names these.
        let synced = try groupEntry(messageId: 0x0000ABCD)
        synced.groupHistorySync = true
        XCTAssertNil(NotificationCoordinator.groupEventKey(of: synced))
        let privateMessage = try groupEntry(messageId: 0x0000ABCD)
        privateMessage.groupPrivateMessage = true
        XCTAssertNil(NotificationCoordinator.groupEventKey(of: privateMessage))
        let noAuthor = try groupEntry(messageId: 0x0000ABCD)
        noAuthor.groupPrivatePeerPubkey = nil
        XCTAssertNil(NotificationCoordinator.groupEventKey(of: noAuthor))
    }

    // MARK: A group text that waits (0.2.69)

    private let waitedMark = "dda36f64ff2a17499a893ea2042bacad"

    func testTheMarkOfATextThatWaitedMatchesTheVector() {
        XCTAssertEqual(PushPreviewCrypto.textMark(senderKey: groupPeerKey, text: "Wake  test\none "), waitedMark)
        XCTAssertEqual(PushPreviewCrypto.textMark(senderKey: groupPeerKey.lowercased(), text: "Wake  test one"), waitedMark)
        // CR LF, LF and CR are a space each, and the line is trimmed; nothing else changes.
        XCTAssertEqual(PushPreviewCrypto.textMark(senderKey: groupPeerKey, text: " Wake  test\r\none\n"), waitedMark)
        XCTAssertEqual(PushPreviewCrypto.textMark(senderKey: groupPeerKey, text: "Wake  test\rone"), waitedMark)
        XCTAssertNotEqual(PushPreviewCrypto.textMark(senderKey: groupPeerKey, text: "Wake test one"), waitedMark)
        XCTAssertEqual(PushPreviewCrypto.oneLine("a\r\n\nb\r\rc \r\n"), "a  b  c")
        // The sender's key is part of it; a key that is no key, or a text with nothing on its line, has no mark.
        XCTAssertNotEqual(PushPreviewCrypto.textMark(senderKey: String(repeating: "B1", count: 32), text: "Wake  test one"),
                          waitedMark)
        XCTAssertNil(PushPreviewCrypto.textMark(senderKey: String(groupPeerKey.dropLast(2)), text: "Wake  test one"))
        XCTAssertNil(PushPreviewCrypto.textMark(senderKey: "", text: "Wake  test one"))
        XCTAssertNil(PushPreviewCrypto.textMark(senderKey: groupPeerKey, text: " \r\n "))
    }

    func testOnlyAKeyOfSixtyFourHexDigitsIsReadAsGp() throws {
        func decoded(_ gp: Any?) throws -> String? {
            var fields: [String: Any] = ["v": 1, "k": "text", "b": "Wake", "gid": groupChatId,
                                         "ts": NSNumber(value: Int64(sentAt))]
            fields["gp"] = gp
            let preview = try XCTUnwrap(PushPreviewCrypto.decode(try JSONSerialization.data(withJSONObject: fields)))
            return preview.groupPeerKey
        }
        XCTAssertEqual(try decoded(groupPeerKey), groupPeerKey)
        XCTAssertEqual(try decoded(groupPeerKey.lowercased()), groupPeerKey, "kept in upper case")
        XCTAssertNil(try decoded(nil))
        XCTAssertNil(try decoded(String(groupPeerKey.dropLast(2))), "short")
        XCTAssertNil(try decoded(groupPeerKey + "C1"), "long, and not cut to 64")
        XCTAssertNil(try decoded(" " + String(groupPeerKey.dropFirst())), "padded")
        XCTAssertNil(try decoded(String(repeating: "+1", count: 32)), "a sign is no hex digit")
        XCTAssertNil(try decoded(String(repeating: "G1", count: 32)), "not hex")
        XCTAssertNil(try decoded(String(repeating: "\u{00FC}", count: 64)), "64 characters, not 64 hex digits")
        XCTAssertNil(try decoded(NSNumber(value: 12345)), "not a string")
    }

    /// The sender's half: a text sent at once names its event, a text that waits names its sender's key in the group.
    func testAGroupWakeNamesItsEventOrElseItsSendersKey() throws {
        let base: [String: Any] = ["k": "text", "n": "Isa", "ts": NSNumber(value: Int64(sentAt))]
        let eventKey = "gt:0000ABCD:" + groupPeerKey

        let sent = try XCTUnwrap(OCTPushPreview.groupWakeFields(withFields: base, body: "Salam", groupName: "Book club",
                                                                chatIdHex: groupChatId, eventKey: eventKey,
                                                                senderPeerKeyHex: groupPeerKey.lowercased()))
        XCTAssertEqual(sent["h"] as? String, eventKey)
        XCTAssertNil(sent["gp"], "gp goes only without h")
        XCTAssertEqual(sent["b"] as? String, "Salam")
        XCTAssertEqual(sent["g"] as? String, "Book club")
        XCTAssertEqual(sent["gid"] as? String, groupChatId)
        XCTAssertEqual(sent["n"] as? String, "Isa")
        XCTAssertEqual(sent["k"] as? String, "text")

        var stale = base
        stale["h"] = "t:" + String(repeating: "AB", count: 32)
        let waiting = try XCTUnwrap(OCTPushPreview.groupWakeFields(withFields: stale, body: nil, groupName: "",
                                                                   chatIdHex: groupChatId.lowercased(), eventKey: nil,
                                                                   senderPeerKeyHex: groupPeerKey.lowercased()))
        XCTAssertNil(waiting["h"], "toxcore has given the text no id yet")
        XCTAssertEqual(waiting["gp"] as? String, groupPeerKey)
        XCTAssertEqual(waiting["gid"] as? String, groupChatId)
        XCTAssertNil(waiting["b"])
        XCTAssertNil(waiting["g"])

        // Only a text waits; a sender key or a chat id that is none wakes nobody.
        XCTAssertNil(OCTPushPreview.groupWakeFields(withFields: ["k": "file"], body: "report.pdf", groupName: nil,
                                                    chatIdHex: groupChatId, eventKey: nil, senderPeerKeyHex: groupPeerKey))
        XCTAssertNil(OCTPushPreview.groupWakeFields(withFields: base, body: "Salam", groupName: nil, chatIdHex: groupChatId,
                                                    eventKey: nil, senderPeerKeyHex: String(groupPeerKey.dropLast(2))))
        XCTAssertNil(OCTPushPreview.groupWakeFields(withFields: base, body: "Salam", groupName: nil, chatIdHex: groupChatId,
                                                    eventKey: nil, senderPeerKeyHex: nil))
        XCTAssertNil(OCTPushPreview.groupWakeFields(withFields: base, body: "Salam", groupName: nil, chatIdHex: "0102",
                                                    eventKey: eventKey, senderPeerKeyHex: groupPeerKey))
    }

    /// From the sender's fields to the push extension's `khq_tm` to the mark of the text that comes over Tox.
    func testAGroupTextThatWaitedIsKnownByItsSendersKeyAndWhatItSays() throws {
        let base: [String: Any] = ["k": "text", "n": "Isa", "ts": NSNumber(value: Int64(sentAt))]
        let fields = try XCTUnwrap(OCTPushPreview.groupWakeFields(withFields: base, body: "Wake  test\none ",
                                                                  groupName: "Book club", chatIdHex: groupChatId,
                                                                  eventKey: nil, senderPeerKeyHex: groupPeerKey.lowercased()))
        let plaintext = try XCTUnwrap(OCTPushPreview.plaintext(withFields: fields))
        XCTAssertEqual(String(data: plaintext, encoding: .utf8),
                       "{\"v\":1,\"k\":\"text\",\"b\":\"Wake  test\\none \",\"n\":\"Isa\",\"g\":\"Book club\","
                       + "\"gid\":\"\(groupChatId)\",\"gp\":\"\(groupPeerKey)\",\"ts\":1763571600}")

        let content = UNMutableNotificationContent()
        XCTAssertTrue(PushPreviewPresenter.apply(to: content,
                                                 userInfo: ["khq_pv": try seal(fields), "sender_pubkey": publicKey],
                                                 masterKey: masterKey,
                                                 allowedSenders: data(publicKey),
                                                 showsContent: true,
                                                 language: "en",
                                                 now: Date(timeIntervalSince1970: sentAt + 60)))
        XCTAssertEqual(content.title, "Book club")
        XCTAssertEqual(content.body, "Isa: Wake  test\none")
        XCTAssertEqual(content.userInfo["khq_gid"] as? String, groupChatId)
        XCTAssertNil(content.userInfo["khq_ev"])
        XCTAssertEqual(content.userInfo["khq_tm"] as? String, waitedMark)

        XCTAssertEqual(NotificationCoordinator.groupTextMarks(of: try groupEntry(messageId: 0x0000ABCD, text: "Wake  test\none ")),
                       [waitedMark])
    }

    func testOnlyAGroupTextWithoutAnEventKeyIsMarked() throws {
        let group: [String: Any] = ["k": "text", "b": "Wake  test\none ", "n": "Isa", "g": "Book club", "gid": groupChatId,
                                    "gp": groupPeerKey, "ts": NSNumber(value: Int64(sentAt))]
        XCTAssertEqual(try appliedUserInfo(group)["khq_tm"] as? String, waitedMark)
        XCTAssertEqual(try appliedUserInfo(group, showsContent: false)["khq_tm"] as? String, waitedMark,
                       "the event is the same with the previews off")

        var withEventKey = group
        withEventKey["h"] = "gt:0000ABCD:" + groupPeerKey
        let named = try appliedUserInfo(withEventKey)
        XCTAssertEqual(named["khq_ev"] as? String, "gt:0000ABCD:" + groupPeerKey)
        XCTAssertNil(named["khq_tm"], "an event key names the event")

        var direct = group
        direct["gid"] = nil
        direct["g"] = nil
        XCTAssertNil(try appliedUserInfo(direct)["khq_tm"], "not a group event")

        var file = group
        file["k"] = "file"
        XCTAssertNil(try appliedUserInfo(file)["khq_tm"])

        var noKey = group
        noKey["gp"] = nil
        XCTAssertNil(try appliedUserInfo(noKey)["khq_tm"])

        var malformedKey = group
        malformedKey["gp"] = String(groupPeerKey.dropLast(2))
        XCTAssertNil(try appliedUserInfo(malformedKey)["khq_tm"])

        var noBody = group
        noBody["b"] = " \n "
        XCTAssertNil(try appliedUserInfo(noBody)["khq_tm"])
    }

    /// What the app computes of a group text that came over Tox: its first 140 characters as the chat shows them and
    /// as they came, from its author's key, as a sender may have put either into its preview.
    func testAGroupTextFromToxIsMarkedAsShownAndAsItCame() throws {
        let sanitizer = OCTPushPreview.previewTextSanitizer
        OCTPushPreview.previewTextSanitizer = { GroupMentionHelper.notificationPreviewText($0) }
        defer { OCTPushPreview.previewTextSanitizer = sanitizer }

        // A mention block is taken out of what the chat shows.
        let raw = GroupMentionHelper.mentionBlockPrefix + "A1:isa" + GroupMentionHelper.mentionBlockSuffix + "Wake  test\none "
        let marks = NotificationCoordinator.groupTextMarks(of: try groupEntry(messageId: 0x0000ABCD, text: raw))
        XCTAssertEqual(marks, [waitedMark, PushPreviewCrypto.textMark(senderKey: groupPeerKey, text: raw)!])

        // The first 140 characters, as the sender cuts them, never inside one.
        let long = "👨‍👩‍👧‍👦" + String(repeating: "\u{00FC}", count: 199)
        let body = OCTPushPreview.prefix(of: long, characters: 140)
        XCTAssertEqual(body.count, 140)
        let plaintext = try XCTUnwrap(OCTPushPreview.plaintext(withFields: ["k": "text", "b": long, "gid": groupChatId,
                                                                            "gp": groupPeerKey, "ts": NSNumber(value: Int64(sentAt))]))
        XCTAssertEqual(PushPreviewCrypto.decode(plaintext)?.body, body)
        XCTAssertEqual(NotificationCoordinator.groupTextMarks(of: try groupEntry(messageId: 1, text: long)),
                       [PushPreviewCrypto.textMark(senderKey: groupPeerKey, text: body)!])

        // As for the event key: none for what no wake names, nor for a row that kept no author key.
        let synced = try groupEntry(messageId: 1, text: "Wake  test one")
        synced.groupHistorySync = true
        XCTAssertEqual(NotificationCoordinator.groupTextMarks(of: synced), [])
        let privateMessage = try groupEntry(messageId: 1, text: "Wake  test one")
        privateMessage.groupPrivateMessage = true
        XCTAssertEqual(NotificationCoordinator.groupTextMarks(of: privateMessage), [])
        let system = try groupEntry(messageId: 1, text: "Wake  test one")
        system.groupSystemMessage = true
        XCTAssertEqual(NotificationCoordinator.groupTextMarks(of: system), [])
        let noAuthor = try groupEntry(messageId: 1, text: "Wake  test one")
        noAuthor.groupPrivatePeerPubkey = nil
        XCTAssertEqual(NotificationCoordinator.groupTextMarks(of: noAuthor), [])
    }

    /// A push of a text that waited stands for one text: the next one saying the same alerts.
    func testAMarkTakesADeliveredNotificationOnce() {
        let other = "0123456789abcdef0123456789abcdef"
        var matches = PushTextMarkMatches()
        let delivered: [(identifier: String, mark: String?)] = [("direct", nil), ("push-1", other), ("push-2", waitedMark)]
        XCTAssertFalse(matches.match([], in: delivered))
        XCTAssertFalse(matches.match([String(repeating: "f", count: 32)], in: delivered))
        XCTAssertTrue(matches.match([String(repeating: "f", count: 32), waitedMark], in: delivered),
                      "the text the push showed: its own alert is skipped")
        XCTAssertFalse(matches.match([waitedMark], in: delivered), "the next text saying the same alerts")

        // Two texts that said the same while they waited, each with its own push.
        let twice = delivered + [(identifier: "push-3", mark: waitedMark)]
        XCTAssertTrue(matches.match([waitedMark], in: twice))
        XCTAssertFalse(matches.match([waitedMark], in: twice))
        XCTAssertTrue(matches.match([other], in: twice))
        XCTAssertEqual(matches.matchedIdentifiers, ["push-2", "push-3", "push-1"])

        // Beyond the capacity the oldest match goes first.
        for n in 0..<PushTextMarkMatches.capacity {
            XCTAssertTrue(matches.match([waitedMark], in: [(identifier: "bulk-\(n)", mark: waitedMark)]))
        }
        XCTAssertEqual(matches.matchedIdentifiers.count, PushTextMarkMatches.capacity)
        XCTAssertEqual(matches.matchedIdentifiers.first, "bulk-0")
        XCTAssertEqual(matches.matchedIdentifiers.last, "bulk-\(PushTextMarkMatches.capacity - 1)")
    }

    private func seal(_ fields: [String: Any]) throws -> String {
        let plaintext = try XCTUnwrap(OCTPushPreview.plaintext(withFields: fields))
        return try XCTUnwrap(OCTPushPreview.sealPlaintext(plaintext, key: contactKey, nonce: nil))
    }

    /// The `userInfo` the push extension leaves on a notification of a preview with these fields.
    private func appliedUserInfo(_ fields: [String: Any], showsContent: Bool = true) throws -> [AnyHashable: Any] {
        let content = UNMutableNotificationContent()
        XCTAssertTrue(PushPreviewPresenter.apply(to: content,
                                                 userInfo: ["khq_pv": try seal(fields), "sender_pubkey": publicKey],
                                                 masterKey: masterKey,
                                                 allowedSenders: data(publicKey),
                                                 showsContent: showsContent,
                                                 language: "en",
                                                 now: Date(timeIntervalSince1970: sentAt + 60)))
        return content.userInfo
    }

    /// A live group text as the receive path stores it: the author's key frozen, in lower case, on the row.
    private func groupEntry(messageId: Int32, text body: String = "Salam") throws -> OCTMessageAbstract {
        let text = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        text.text = body
        text.messageId = messageId
        let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        message.groupSenderPeerId = 5
        message.groupPrivatePeerPubkey = groupPeerKey.lowercased()
        message.messageText = text
        return message
    }

    /// The app's class: objcTox is in the app and in this bundle.
    private func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
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

    /// KHANDAQ (owner, 28.11): a place is named in a push and a banner, not shown as its wire text
    /// "khandaq-location:…", and so is a reaction to one.
    func testAPlaceIsNamedNotShownAsItsWireText() {
        let place = LocationMessage.payload(latitude: 41.0082, longitude: 28.9784)

        XCTAssertTrue(PushPreviewText.isLocation(place))
        XCTAssertTrue(PushPreviewText.isLocation("  KHANDAQ-LOCATION:1.00000,2.00000"))
        XCTAssertFalse(PushPreviewText.isLocation("Meet me at the khandaq-location: gate"))
        XCTAssertFalse(PushPreviewText.isLocation(nil))
        XCTAssertEqual(PushPreviewText.messageBody(kind: .text, text: place, language: "en"), "📍 Location")
        XCTAssertEqual(PushPreviewText.messageBody(kind: .text, text: place, language: "ru"), "📍 Геопозиция")
        XCTAssertEqual(PushPreviewText.messageBody(kind: .text, text: "See you there", language: "en"), "See you there")
        XCTAssertEqual(PushPreviewText.reactionBody(emoji: "👍", reactedKind: .text, text: place, language: "en"),
                       "Reacted 👍 to your location")
        XCTAssertEqual(PushPreviewText.reactionBody(emoji: "❤️", reactedKind: .text, text: place, language: "ru"),
                       "Реакция ❤️ на вашу геопозицию")
    }

    func testEveryLanguageHasEveryLine() {
        for language in PushPreviewText.languages {
            let s = PushPreviewText.strings(language)
            for line in [s.newMessage, s.newReaction, s.voice, s.video, s.photo, s.file, s.reactedVoice,
                         s.reactedVideo, s.reactedPhoto, s.reactedFile, s.reactedMessage, s.location] {
                XCTAssertFalse(line.isEmpty, language)
            }
            XCTAssertTrue(s.reactedLocation.contains("{e}"), language)
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
