// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 DESIGN-group-contacts-private-mentions.md (26.11.2026), the parts every client must do byte for byte alike: the
 packets of the member-to-contact exchange (magic, version, 0x61 offer, 0x62 accept, a 38-byte address whose
 checksum holds and an 8-byte nonce, 54 bytes in all), the marker of the contact request that accepts an offer, the
 handles of @mentions in any script and the block they travel in, the word "@" opens in the field, and the private
 messages of a group, which the history of the group never serves.
 */
class GroupContactsMentionsTests: XCTestCase {
    private var directories: [URL] = []

    override func tearDown() {
        directories.forEach { try? FileManager.default.removeItem(at: $0) }
        directories = []
        super.tearDown()
    }

    // MARK: - The packets of the exchange

    func testAnOfferIsTheHeaderTheAddressAndTheNonceIn54Bytes() throws {
        let address = toxAddress(key: String(repeating: "3C", count: 32), nospam: "0A0B0C0D")
        let packet = try XCTUnwrap(OCTGroupContactExchange.packet(with: .offer, addressHex: address, nonceHex: "0123456789ABCDEF"))

        XCTAssertEqual(packet.count, 54)
        XCTAssertEqual(Int(kOCTGroupContactPacketLength), 54)
        let bytes = [UInt8](packet)
        XCTAssertEqual(Array(bytes[0..<8]), [0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x61], "magic, version and the offer's id")
        XCTAssertEqual(hex(Array(bytes[8..<46])), address, "the sender's own address, checksum included")
        XCTAssertEqual(Array(bytes[46..<54]), [0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF])

        let parsed = try XCTUnwrap(OCTGroupContactExchange.parsePacket(packet))
        XCTAssertEqual(parsed.kind, .offer)
        XCTAssertEqual(parsed.addressHex, address)
        XCTAssertEqual(parsed.publicKeyHex, String(address.prefix(64)), "the key of the contact is the address's first 32 bytes")
        XCTAssertEqual(parsed.nonceHex, "0123456789abcdef", "the nonce reads in lower case, as the request marker carries it")
    }

    func testAnAcceptRepeatsTheNonceUnderItsOwnId() throws {
        let address = toxAddress(key: String(repeating: "A1", count: 32), nospam: "FFFFFFFF")
        let packet = try XCTUnwrap(OCTGroupContactExchange.packet(with: .accept, addressHex: address.lowercased(), nonceHex: "fedcba9876543210"))
        XCTAssertEqual([UInt8](packet)[7], 0x62)
        let parsed = try XCTUnwrap(OCTGroupContactExchange.parsePacket(packet))
        XCTAssertEqual(parsed.kind, .accept)
        XCTAssertEqual(parsed.addressHex, address, "an address in lower case goes out as the same bytes")
        XCTAssertEqual(parsed.nonceHex, "fedcba9876543210")
    }

    func testAPacketThatDoesNotHoldIsDropped() throws {
        let address = toxAddress(key: String(repeating: "5D", count: 32), nospam: "12345678")
        let packet = try XCTUnwrap(OCTGroupContactExchange.packet(with: .offer, addressHex: address, nonceHex: "00112233aabbccdd"))

        // The checksum of the address does not hold.
        var broken = [UInt8](packet)
        broken[45] ^= 0x01
        XCTAssertTrue(OCTGroupContactExchange.isContactPacket(Data(broken)), "the dispatcher still hands it to the exchange")
        XCTAssertNil(OCTGroupContactExchange.parsePacket(Data(broken)), "a wrong checksum was taken")
        broken = [UInt8](packet)
        broken[10] ^= 0x80
        XCTAssertNil(OCTGroupContactExchange.parsePacket(Data(broken)), "a changed key with the old checksum was taken")

        // Any other length.
        XCTAssertNil(OCTGroupContactExchange.parsePacket(packet.subdata(in: 0..<53)))
        XCTAssertNil(OCTGroupContactExchange.parsePacket(packet + Data([0x00])))
        XCTAssertNil(OCTGroupContactExchange.parsePacket(Data()))

        // Another id or another version is not the exchange's: 0x41 to 0x43 are the edits, deletions and reactions.
        for (index, value) in [(7, UInt8(0x41)), (7, UInt8(0x42)), (6, UInt8(0x02)), (0, UInt8(0x67))] {
            var other = [UInt8](packet)
            other[index] = value
            XCTAssertFalse(OCTGroupContactExchange.isContactPacket(Data(other)), "byte \(index) = \(value)")
            XCTAssertNil(OCTGroupContactExchange.parsePacket(Data(other)))
        }

        // Nothing is built from an address or a nonce that does not hold.
        var wrongCheck = Array(address)
        wrongCheck[75] = wrongCheck[75] == "0" ? "1" : "0"
        XCTAssertNil(OCTGroupContactExchange.packet(with: .offer, addressHex: String(wrongCheck), nonceHex: "0011223344556677"))
        XCTAssertNil(OCTGroupContactExchange.packet(with: .offer, addressHex: String(address.prefix(64)), nonceHex: "0011223344556677"),
                     "a member's key in a group is no address")
        XCTAssertNil(OCTGroupContactExchange.packet(with: .offer, addressHex: address, nonceHex: "0011"))
        XCTAssertNil(OCTGroupContactExchange.packet(with: .offer, addressHex: address, nonceHex: "00112233445566zz"))
    }

    func testAddressesHoldByTheirChecksumInEitherCase() {
        let address = toxAddress(key: String(repeating: "7E", count: 32), nospam: "DEADBEEF")
        XCTAssertTrue(OCTGroupContactExchange.isValidAddressHex(address))
        XCTAssertTrue(OCTGroupContactExchange.isValidAddressHex(address.lowercased()))
        XCTAssertFalse(OCTGroupContactExchange.isValidAddressHex(String(address.dropLast(2)) + "00"))
        XCTAssertFalse(OCTGroupContactExchange.isValidAddressHex(String(address.prefix(64))))
        XCTAssertFalse(OCTGroupContactExchange.isValidAddressHex(String(address.dropLast()) + "G"))
    }

    func testEveryOfferHasANonceOfItsOwn() {
        let first = OCTGroupContactExchange.randomNonceHex()
        let second = OCTGroupContactExchange.randomNonceHex()
        for nonce in [first, second] {
            XCTAssertEqual(nonce.count, 16)
            XCTAssertTrue(nonce.allSatisfy { "0123456789abcdef".contains($0) }, nonce)
        }
        XCTAssertNotEqual(first, second)
    }

    // MARK: - The marker of the request that accepts an offer

    func testTheRequestThatAcceptsAnOfferStartsWithItsNonce() {
        let message = OCTGroupContactExchange.requestMessage(withNonceHex: "0123456789ABCDEF",
                                                             text: "I accepted your request from the group «Book club»")
        XCTAssertEqual(message, "[KQ|c|0123456789abcdef]\nI accepted your request from the group «Book club»")
        XCTAssertTrue(message.hasPrefix(kOCTGroupContactRequestMarkerPrefix))
        XCTAssertEqual(OCTGroupContactExchange.nonceHex(inRequestMessage: message), "0123456789abcdef")
        XCTAssertEqual(OCTGroupContactExchange.requestMessageWithoutMarker(message), "I accepted your request from the group «Book club»")
    }

    func testAMarkerThatDoesNotHoldIsNoNonceAndANoteIsShownWithoutIt() {
        XCTAssertNil(OCTGroupContactExchange.nonceHex(inRequestMessage: nil))
        XCTAssertNil(OCTGroupContactExchange.nonceHex(inRequestMessage: "Salam, add me please"))
        XCTAssertNil(OCTGroupContactExchange.nonceHex(inRequestMessage: "[KQ|c|0123456789abcdeg]\nHi"), "not hex")
        XCTAssertNil(OCTGroupContactExchange.nonceHex(inRequestMessage: "[KQ|c|0123456789abcde]\nHi"), "15 characters")
        XCTAssertNil(OCTGroupContactExchange.nonceHex(inRequestMessage: "[KQ|c|0123456789abcdef0]\nHi"), "17 characters")
        XCTAssertNil(OCTGroupContactExchange.nonceHex(inRequestMessage: " [KQ|c|0123456789abcdef]\nHi"), "not at the start")
        XCTAssertEqual(OCTGroupContactExchange.nonceHex(inRequestMessage: "[KQ|c|0123456789abcdef]"), "0123456789abcdef")

        // A request whose nonce is unknown is an ordinary request, shown without the marker.
        XCTAssertEqual(OCTGroupContactExchange.requestMessageWithoutMarker("[KQ|c|ffffffffffffffff]\r\nSalam"), "Salam")
        XCTAssertEqual(OCTGroupContactExchange.requestMessageWithoutMarker("[KQ|c|ffffffffffffffff]"), "")
        XCTAssertEqual(OCTGroupContactExchange.requestMessageWithoutMarker("Salam\n[KQ|c|ffffffffffffffff]"),
                       "Salam\n[KQ|c|ffffffffffffffff]", "a marker that is not first is the person's words")
        XCTAssertNil(OCTGroupContactExchange.requestMessageWithoutMarker(nil))
    }

    // MARK: - The offers we sent

    func testAnAcceptCountsOnlyForTheOfferSentToThatMemberInThatGroup() throws {
        let store = try offerStore()
        let group = String(repeating: "ab", count: 32)
        let member = String(repeating: "cd", count: 32)
        let nonce = "0123456789abcdef"
        let now = Date().timeIntervalSince1970
        store.save(try pendingOffer(group: group, member: member, nonce: nonce, sentAt: now))

        let otherNonce: OCTGroupContactPendingOffer? = store.takePendingOffer(toMember: member, inGroup: group, nonceHex: "fedcba9876543210", at: now)
        let otherMember: OCTGroupContactPendingOffer? = store.takePendingOffer(toMember: String(repeating: "ef", count: 32), inGroup: group, nonceHex: nonce, at: now)
        let otherGroup: OCTGroupContactPendingOffer? = store.takePendingOffer(toMember: member, inGroup: String(repeating: "12", count: 32), nonceHex: nonce, at: now)
        XCTAssertNil(otherNonce, "an accept with another nonce")
        XCTAssertNil(otherMember, "an accept from another member")
        XCTAssertNil(otherGroup, "an accept in another group")

        // Their answer takes the offer, whatever the case of the keys, and only once.
        let taken: OCTGroupContactPendingOffer? = store.takePendingOffer(toMember: member.uppercased(), inGroup: group.uppercased(), nonceHex: nonce.uppercased(), at: now)
        XCTAssertEqual(taken?.nonceHex, nonce, "the answer to the offer did not take it")
        XCTAssertEqual(taken?.memberPublicKeyHex, member)
        let again: OCTGroupContactPendingOffer? = store.takePendingOffer(toMember: member, inGroup: group, nonceHex: nonce, at: now)
        let byRequest: OCTGroupContactPendingOffer? = store.takePendingOffer(withNonceHex: nonce, at: now)
        XCTAssertNil(again, "the offer was taken twice")
        XCTAssertNil(byRequest, "the contact request took the offer the accept had taken")

        // An offer 30 days old is answered by nobody.
        store.save(try pendingOffer(group: group, member: member, nonce: "00112233445566ff", sentAt: now - 31 * 24 * 3600))
        let late: OCTGroupContactPendingOffer? = store.takePendingOffer(withNonceHex: "00112233445566ff", at: now)
        XCTAssertNil(late, "an offer past its 30 days")
    }

    func testOfTwoAnswersThatComeTogetherOnlyOneTakesTheOffer() throws {
        let store = try offerStore()
        let group = String(repeating: "ab", count: 32)
        let member = String(repeating: "cd", count: 32)
        let now = Date().timeIntervalSince1970
        // objcTox gets both answers as toxcore's callbacks hand them over, queued on the main queue (the thread of the
        // profile's Realm), here in both orders: the accept over the group and the contact request with the nonce.
        for acceptFirst in [true, false] {
            let nonce: String = OCTGroupContactExchange.randomNonceHex()
            store.save(try pendingOffer(group: group, member: member, nonce: nonce, sentAt: now))
            let accept = { () -> OCTGroupContactPendingOffer? in
                store.takePendingOffer(toMember: member, inGroup: group, nonceHex: nonce, at: now)
            }
            let request = { () -> OCTGroupContactPendingOffer? in
                store.takePendingOffer(withNonceHex: nonce, at: now)
            }
            var takers: [String] = []
            let answered = expectation(description: "both answers handled")
            answered.expectedFulfillmentCount = 2
            for (name, answer) in acceptFirst ? [("accept", accept), ("request", request)] : [("request", request), ("accept", accept)] {
                DispatchQueue.main.async {
                    if answer() != nil {
                        takers.append(name)
                    }
                    answered.fulfill()
                }
            }
            wait(for: [answered], timeout: 5)
            XCTAssertEqual(takers, [acceptFirst ? "accept" : "request"], "the offer was not taken by the first answer alone")
        }
    }

    // MARK: - Handles

    func testAHandleKeepsTheLettersOfAnyScript() {
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "Иса Дагестани"), "Иса_Дагестани")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "محمد علي"), "محمد_علي")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "李小龙"), "李小龙")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "Agent 007"), "Agent_007")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "Jean-Luc  Picard"), "Jean_Luc_Picard", "a run of separators is one _")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "a.-. b"), "a_b")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "Bilal!"), "Bilal", "anything else is dropped")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "Amina 🌙"), "Amina", "and _ at the end is trimmed")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "  Omar"), "Omar", "no _ before the first letter")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "Yusuf (iPhone)"), "Yusuf", "what follows \" (\" is left out")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "(iPhone) Yusuf"), "iPhone_Yusuf", "but not from the start")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "Ali_Hasan"), "AliHasan", "_ typed in a name is not a separator")
    }

    func testAHandleKeepsTheMarksOfTheLetterBeforeThem() {
        // Arabic with its vowels, a decomposed é and a Cyrillic stress: the marks stay with their letters.
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "مُحَمَّد"), "مُحَمَّد")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "Jose\u{301} Mari\u{301}a"), "Jose\u{301}_Mari\u{301}a")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "Иса\u{301}"), "Иса\u{301}")
        // A mark with no kept letter before it goes with what it followed.
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "\u{301}Amina"), "Amina")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "Amina !\u{301}"), "Amina")
        XCTAssertEqual(GroupMentionHelper.baseHandle(fromName: "a \u{301}b"), "a_b")
    }

    func testAnEmptyHandleIsTheKeyAndASharedOneGetsTheKeyToo() {
        let members = [
            member("Иса Дагестани", key: "A1" + String(repeating: "0", count: 62)),
            member("محمد علي", key: "B2" + String(repeating: "0", count: 62)),
            member("Amina", key: "C3D4" + String(repeating: "0", count: 60)),
            member("amina (laptop)", key: "e5f6" + String(repeating: "0", count: 60)),
            member("!!!", key: "0123456789" + String(repeating: "F", count: 54)),
            member("No key", key: ""),
        ]
        let handles = GroupMentionHelper.handles(for: members)
        XCTAssertEqual(handles.count, 5, "a member with no key cannot be mentioned")
        XCTAssertEqual(handles["A1" + String(repeating: "0", count: 62)], "Иса_Дагестани")
        XCTAssertEqual(handles["B2" + String(repeating: "0", count: 62)], "محمد_علي")
        XCTAssertEqual(handles["C3D4" + String(repeating: "0", count: 60)], "Amina_C3D4", "both who share a handle get the key")
        XCTAssertEqual(handles["E5F6" + String(repeating: "0", count: 60)], "amina_E5F6", "in any case of its letters")
        XCTAssertEqual(handles["0123456789" + String(repeating: "F", count: 54)], "u01234567")
        XCTAssertEqual(GroupMentionHelper.handles(for: [member("Amina", key: "C3D4" + String(repeating: "0", count: 60))]).values.first,
                       "Amina", "a handle of one member stays as it is")
    }

    func testTwinsWhoseKeysStartAlikeTakeEightCharacters() {
        let first = "ABCD1111" + String(repeating: "0", count: 56)
        let second = "ABCD2222" + String(repeating: "0", count: 56)
        let handles = GroupMentionHelper.handles(for: [member("Ali", key: first), member("Ali", key: second)])
        XCTAssertEqual(handles[first], "Ali_ABCD1111", "as Android and desktop name them")
        XCTAssertEqual(handles[second], "Ali_ABCD2222")
    }

    // MARK: - Mentions in a message

    func testTheBlockListsEveryMemberTheTextNamesOnceAsTyped() throws {
        let isa = "A1" + String(repeating: "0", count: 62)
        let muhammad = "B2" + String(repeating: "0", count: 62)
        let amina = "C3D4" + String(repeating: "0", count: 60)
        let handles = [isa: "Иса_Дагестани", muhammad: "محمد_علي", amina: "Amina"]
        let body = "Салам @иса_дагестани, @Amina и @محمد_علي! @Amina ещё раз, @nobody, @Aminah и mail@Amina."

        let mentions = GroupMentionHelper.mentions(in: body, handles: handles)
        XCTAssertEqual(mentions.map { $0.pubkey }, [isa, amina, muhammad], "in order, once each, and only members")
        XCTAssertEqual(mentions.map { $0.handle }, ["иса_дагестани", "Amina", "محمد_علي"], "the token as it was typed")
        XCTAssertTrue(GroupMentionHelper.mentions(in: "no mention here", handles: handles).isEmpty)
        XCTAssertTrue(GroupMentionHelper.mentions(in: "@Amina", handles: [:]).isEmpty)

        let block = GroupMentionHelper.block(for: mentions)
        XCTAssertEqual(block, "[KQ|m|\(isa):иса_дагестани;\(amina):Amina;\(muhammad):محمد_علي][KQ/m]\n")
        XCTAssertEqual(GroupMentionHelper.block(for: []), "", "no block for a text that names nobody")

        // What the other clients read: the members and the body without the block.
        let parsed = GroupMentionHelper.parse(block + body)
        XCTAssertEqual(parsed.mentions.map { $0.pubkey }, [isa, amina, muhammad])
        XCTAssertEqual(parsed.mentions.map { $0.handle }, ["иса_дагестани", "Amina", "محمد_علي"])
        XCTAssertEqual(parsed.bodyText, body)
        XCTAssertEqual(GroupMentionHelper.notificationPreviewText(block + body), body, "the previews leave the block out")

        // After the header of a reply, as every client writes it.
        var meta = MessageReplyHelper.ReplyMeta()
        meta.localMessageId = 42
        meta.senderPubkeyTail = "ABCDEF12"
        meta.sortTimestampMs = 1_800_000_000_000
        meta.senderName = "Bilal"
        meta.previewText = "Where do we meet?"
        let replied = GroupMentionHelper.parse(MessageReplyHelper.encode(meta, bodyText: block + body))
        XCTAssertEqual(replied.reply?.senderName, "Bilal")
        XCTAssertEqual(replied.mentions.map { $0.pubkey }, [isa, amina, muhammad])
        XCTAssertEqual(replied.bodyText, body)
    }

    func testAMentionIsDrawnWhereItsHandleStandsWhole() {
        let amina = "C3D4" + String(repeating: "0", count: 60)
        let isa = "A1" + String(repeating: "0", count: 62)
        let text = "@AMINA, @Aminah @amina_x @Иса_Дагестани\u{301} @Иса_Дагестани."
        let mentions = [GroupMentionHelper.MentionEntry(pubkey: amina, handle: "Amina"),
                        GroupMentionHelper.MentionEntry(pubkey: isa, handle: "Иса_Дагестани")]
        let ranges = GroupMentionHelper.mentionRanges(in: text, mentions: mentions)
        let string = text as NSString
        XCTAssertEqual(ranges.map { string.substring(with: $0.range) }, ["@AMINA", "@Иса_Дагестани"],
                       "in any case, and never inside a longer word")
        XCTAssertEqual(ranges.map { $0.key }, [amina, isa])
        XCTAssertEqual(ranges.last?.range.location, string.range(of: "@Иса_Дагестани.").location)
    }

    func testTheFieldOpensTheMembersOnlyForAWordThatStartsWithAt() {
        func typed(_ text: String, _ caret: Int? = nil) -> String? {
            let at = caret ?? (text as NSString).length
            guard let found = GroupMentionHelper.typedMention(in: text, caret: at) else {
                return nil
            }
            return (text as NSString).substring(with: found.range) + "|" + found.query
        }
        XCTAssertEqual(typed("@"), "@|")
        XCTAssertEqual(typed("Salam @Ам"), "@Ам|Ам")
        XCTAssertEqual(typed("first line\n@mu"), "@mu|mu", "after a line break")
        XCTAssertEqual(typed("@محم"), "@محم|محم")
        XCTAssertEqual(typed("@Amina_C3 and more", 3), "@Amina_C3|Am", "the range runs to the end of the word")
        XCTAssertNil(typed("mail@Amina"), "not inside a word")
        XCTAssertNil(typed("@Amina "), "a space closes it")
        XCTAssertNil(typed("@Amina, hi", 7), "the caret left the word")
        XCTAssertNil(typed("Salam"))
        XCTAssertNil(typed("@Amina", 0), "the caret before the @")
        XCTAssertNil(GroupMentionHelper.typedMention(in: "@Amina", caret: 99))
    }

    func testTheFieldOffersOnlineMembersFirstThenByNameWithoutYou() {
        let me = AirGroupMember(peerId: 0, name: "Isa", publicKey: "F0" + String(repeating: "0", count: 62),
                                role: .user, isOnline: true, lastSeen: 0, isSelf: true)
        let jose = member("José", key: "A1" + String(repeating: "0", count: 62), online: false)
        let isabel = member("Isabel", key: "B2" + String(repeating: "0", count: 62), online: true)
        let bilal = member("Bilal", key: "C3" + String(repeating: "0", count: 62), online: true)
        let keyless = member("Isaac", key: "", online: true)
        let members = [me, jose, isabel, bilal, keyless]
        let handles = GroupMentionHelper.handles(for: members)

        func offered(_ query: String) -> [String] {
            return GroupMentionHelper.candidates(from: members, handles: handles, query: query).map { $0.member.name }
        }
        XCTAssertEqual(offered(""), ["Bilal", "Isabel", "José"], "online first, then by name; not you and not a member with no key")
        XCTAssertEqual(offered("is"), ["Isabel"], "the start of a handle")
        XCTAssertEqual(offered("lal"), ["Bilal"], "or anywhere in the name")
        XCTAssertEqual(offered("JOSE"), ["José"], "case and marks ignored")
        XCTAssertEqual(offered("x"), [])
        XCTAssertEqual(GroupMentionHelper.candidates(from: members, handles: handles, query: "bi").first?.handle, "Bilal")
    }

    // MARK: - The feed

    func testAPrivateEntryShowsItsNoteAndItsMentionsInInkAndBold() throws {
        let key = "C3D4" + String(repeating: "0", count: 60)
        let model = AirEntryTextCellModel()
        model.message = "@Amina only you see this"
        model.timeText = "9:41"
        model.mentions = [GroupMentionHelper.MentionEntry(pubkey: key, handle: "Amina")]
        model.privateNote = "Privately to Amina: other members don't see this"
        var opened: String?
        model.onMentionTap = { key, _ in opened = key }
        let cell = AirEntryTextCell()
        cell.setupWithTheme(ThemeAppearance.resolvedTheme(), model: model)

        let note = try XCTUnwrap(subviews(of: cell).first { $0.accessibilityIdentifier == "air.entry.privateNote" })
        XCTAssertFalse(note.isHidden)
        XCTAssertTrue(subviews(of: note).contains { ($0 as? UILabel)?.text == model.privateNote })
        XCTAssertTrue(subviews(of: note).contains { ($0 as? UIImageView)?.image != nil }, "no lock")

        let text = try XCTUnwrap(subviews(of: cell).compactMap { $0 as? UITextView }.first?.attributedText)
        XCTAssertEqual(text.string, model.message)
        let mention = text.attributes(at: 1, effectiveRange: nil)
        XCTAssertEqual(mention[BubbleView.mentionKeyAttribute] as? String, key)
        XCTAssertEqual(mention[.font] as? UIFont, KQFont.font(.body, weight: 600))
        XCTAssertEqual(mention[.foregroundColor] as? UIColor, KQColor.ink)
        let plain = text.attributes(at: 8, effectiveRange: nil)
        XCTAssertNil(plain[BubbleView.mentionKeyAttribute])
        XCTAssertEqual(plain[.font] as? UIFont, KQFont.font(.body))
        XCTAssertNil(opened)

        // The same cell for an entry to the group: no note.
        let groupEntry = AirEntryTextCellModel()
        groupEntry.message = "Salam"
        groupEntry.timeText = "9:42"
        cell.setupWithTheme(ThemeAppearance.resolvedTheme(), model: groupEntry)
        XCTAssertTrue(note.isHidden)
    }

    /// A private message reaches only the member it went to; asking a member for the group's history must never
    /// hand it out, ours or one we received, through either query the history sync serves from or its packet
    /// builder.
    func testAPrivateMessageIsNeverServedByTheHistorySync() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("group-private-history-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        directories.append(directory)
        let realmManager = try XCTUnwrap(appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
            .init(databaseFileURL: directory.appendingPathComponent("profile.realm"), encryptionKey: nil))
        let groupNumber: Int32 = 7
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        chat.isGroup = true
        chat.groupNumber = groupNumber
        chat.groupName = "Book club"
        chat.groupChatIdHex = String(repeating: "AB", count: 32)
        realmManager.add(chat)

        let member = String(repeating: "C3", count: 32)
        let now = Date().timeIntervalSince1970
        func row(_ text: String, privately: Bool, outgoing: Bool, at offset: TimeInterval) throws -> OCTMessageAbstract {
            let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
            message.chatUniqueIdentifier = chat.uniqueIdentifier
            message.dateInterval = now - offset
            message.groupPrivateMessage = privately
            message.groupSenderPeerId = outgoing ? 0 : 3
            message.groupPrivatePeerPubkey = privately ? member.lowercased() : nil
            if !outgoing {
                message.senderUniqueIdentifier = "member"
            }
            let messageText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
            messageText.text = text
            messageText.messageId = privately ? 0 : 4242
            messageText.groupPeerName = outgoing ? "Isa" : "Amina"
            message.messageText = messageText
            realmManager.add(message)
            return message
        }
        let shared = try row("To the whole group", privately: false, outgoing: true, at: 300)
        let ours = try row("Privately to Amina", privately: true, outgoing: true, at: 200)
        let theirs = try row("Privately to you", privately: true, outgoing: false, at: 100)

        let servedRows: [OCTMessageAbstract] = realmManager.groupMessagesForHistorySync(in: chat)
        XCTAssertEqual(servedRows.map { $0.messageText?.text ?? "" }, ["To the whole group"],
                       "a private message is in what the history sync serves")
        var packed: [String] = []
        let packets: [Data] = realmManager.groupHistorySyncPackets(forGroupNumber: UInt32(groupNumber)) { message in
            packed.append(message?.messageText?.text ?? "")
            return Data([0x01])
        }
        XCTAssertEqual(packed, ["To the whole group"], "a private message went to the packets of the history")
        XCTAssertEqual(packets.count, 1)

        // The builder refuses one too, so neither layer alone can let it out.
        let selfKey = String(repeating: "F0", count: 32)
        let historySync = try XCTUnwrap(appClass("OCTNgcGroupHistSync", as: OCTNgcGroupHistSync.Type.self).init(
            sendPrivatePacketBlock: { _, _, _, _ in true },
            peerPublicKeyBlock: { _, _ in member },
            peerNameForPeerIdBlock: { _, _ in "Amina" },
            defaultPeerNameBlock: { "Member" },
            selfPublicKeyBlock: { _ in selfKey },
            peerIdForPublicKeyBlock: { _, _ in 3 },
            isPublicGroupBlock: { _ in true },
            selfPeerIdBlock: { _ in 0 },
            peerIdsBlock: { _ in [3] },
            chatForGroupBlock: { _, _ in chat },
            messagesToSyncBlock: { _ in [] },
            messageExistsBlock: { _, _, _, _, _, _, _ in false },
            fileExistsBlock: { _, _ in false },
            insertSyncedMessageBlock: { _, _, _, _, _, _, _, _ in nil },
            insertSyncedFileBlock: { _, _, _, _, _, _, _, _, _, _ in nil },
            incomingFilesDirectoryBlock: { _ in directory.path },
            fileUTIBlock: { _ in "public.data" },
            isBlockedPeerBlock: { _, _ in false },
            deliveryReceiptBlock: { _, _, _ in },
            fileSyncConfirmationBlock: { _, _, _ in }))
        XCTAssertNotNil(historySync.syncPacket(for: shared, groupNumber: UInt32(groupNumber)), "the group's own text is served")
        XCTAssertNil(historySync.syncPacket(for: ours, groupNumber: UInt32(groupNumber)), "our private message was built")
        XCTAssertNil(historySync.syncPacket(for: theirs, groupNumber: UInt32(groupNumber)), "a private message to us was built")
    }
}

private extension GroupContactsMentionsTests {
    /// A Tox address of `key` (64 hex) and `nospam` (8 hex) with its checksum: the first 36 bytes XORed into two,
    /// byte by byte in turn, as toxcore computes it.
    func toxAddress(key: String, nospam: String) -> String {
        let bytes = bytesOf(key + nospam)
        var check: [UInt8] = [0, 0]
        for (index, byte) in bytes.enumerated() {
            check[index % 2] ^= byte
        }
        return hex(bytes + check)
    }

    func bytesOf(_ hex: String) -> [UInt8] {
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            bytes.append(UInt8(hex[index..<next], radix: 16) ?? 0)
            index = next
        }
        return bytes
    }

    func hex(_ bytes: [UInt8]) -> String {
        return bytes.map { String(format: "%02X", $0) }.joined()
    }

    func member(_ name: String, key: String, online: Bool = false) -> AirGroupMember {
        return AirGroupMember(peerId: 1, name: name, publicKey: key.uppercased(), role: .user, isOnline: online, lastSeen: 0, isSelf: false)
    }

    /// The store of the exchange on a profile of the test's own.
    func offerStore() throws -> OCTGroupContactStore {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("group-contact-offers-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        directories.append(directory)
        let realmManager = try XCTUnwrap(appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
            .init(databaseFileURL: directory.appendingPathComponent("profile.realm"), encryptionKey: nil))
        return try XCTUnwrap(appClass("OCTGroupContactStore", as: OCTGroupContactStore.Type.self).init(realmManager: realmManager))
    }

    func pendingOffer(group: String, member: String, nonce: String, sentAt: TimeInterval) throws -> OCTGroupContactPendingOffer {
        return try XCTUnwrap(appClass("OCTGroupContactPendingOffer", as: OCTGroupContactPendingOffer.Type.self)
            .init(groupChatIdHex: group, memberPublicKeyHex: member, memberName: "Борис", nonceHex: nonce, sentAt: sentAt))
    }

    /// The app's copy of an objcTox class: the rows of a Realm the app opened are its classes, and this bundle has
    /// copies of its own that do not mix with them (AirGroupModelTests).
    func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }

    func subviews(of view: UIView) -> [UIView] {
        return view.subviews + view.subviews.flatMap { subviews(of: $0) }
    }
}
