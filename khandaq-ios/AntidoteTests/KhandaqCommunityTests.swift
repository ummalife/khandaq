// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Khandaq Community (SPEC-community, part B): every profile joins the official group once by itself, and never
 again once the person left it, across restarts. Since 0.2.60 the join keeps the group's sound, and the sound an
 earlier join turned off is turned on once.
 */
class KhandaqCommunityTests: XCTestCase {
    private var directories: [URL] = []

    override func tearDown() {
        directories.forEach { try? FileManager.default.removeItem(at: $0) }
        directories = []
        super.tearDown()
    }

    // MARK: Item 1, the decision

    func testItJoinsOnlyWhenConnectedNeverJoinedNeverLeftAndNotInItAlready() {
        for connected in [false, true] {
            for autoJoinDone in [false, true] {
                for left in [false, true] {
                    for alreadyInGroup in [false, true] {
                        let expected = connected && !autoJoinDone && !left && !alreadyInGroup
                        XCTAssertEqual(KhandaqCommunity.shouldAutoJoin(connected: connected,
                                                                       autoJoinDone: autoJoinDone,
                                                                       left: left,
                                                                       alreadyInGroup: alreadyInGroup),
                                       expected,
                                       "connected \(connected), done \(autoJoinDone), left \(left), in it \(alreadyInGroup)")
                    }
                }
            }
        }
    }

    func testTheChatIdIsTheLiveGroupsAndMatchesInEitherCase() throws {
        XCTAssertEqual(KhandaqCommunity.chatIdHex, "CFF1995F235303B88C4EF1CE903DCEDC77CEAC82DD1323DAB2CACD2EF840646F")
        XCTAssertTrue(KhandaqCommunity.isCommunity(chatIdHex: KhandaqCommunity.chatIdHex.lowercased()))
        XCTAssertFalse(KhandaqCommunity.isCommunity(chatIdHex: nil))
        // The TRIfA group of the old "Khandaq Community" is not ours, and never matches.
        XCTAssertFalse(KhandaqCommunity.isCommunity(chatIdHex: "154b3973bd0e66304fd6179a8a54759073649e09e6e368f0334fc6ed666ab762"))

        XCTAssertTrue(KhandaqCommunity.isCommunity(try group(KhandaqCommunity.chatIdHex)))
        XCTAssertFalse(KhandaqCommunity.isCommunity(try group(String(repeating: "A", count: 64))))
        let direct = try group(KhandaqCommunity.chatIdHex)
        direct.isGroup = false
        XCTAssertFalse(KhandaqCommunity.isCommunity(direct), "only a group is the Community")
    }

    // MARK: Item 2, the join, and the sound

    func testTheJoinItMakesHappensOnceAndMarksItDone() {
        let marks = FakeMarks()
        var joins = 0

        let joined = KhandaqCommunity.autoJoin(marks: marks, connected: true, alreadyInGroup: false,
                                               join: { () -> String? in
                                                   joins += 1
                                                   return "community"
                                               })

        XCTAssertEqual(joined, "community")
        XCTAssertEqual(joins, 1)
        XCTAssertTrue(marks.communityAutoJoinDone)
        XCTAssertFalse(marks.communityLeft)

        // The next start or connection: done, nothing more.
        let again = KhandaqCommunity.autoJoin(marks: marks, connected: true, alreadyInGroup: false,
                                              join: { () -> String? in
                                                  joins += 1
                                                  return "community"
                                              })
        XCTAssertNil(again)
        XCTAssertEqual(joins, 1)
    }

    func testAGroupJoinedByHandIsNotJoinedAgain() {
        let marks = FakeMarks()
        var calls = 0

        let joined = KhandaqCommunity.autoJoin(marks: marks, connected: true, alreadyInGroup: true,
                                               join: { () -> String? in
                                                   calls += 1
                                                   return "community"
                                               })

        XCTAssertNil(joined)
        XCTAssertEqual(calls, 0)
        XCTAssertFalse(marks.communityAutoJoinDone)
    }

    func testAJoinToxcoreDidNotTakeMarksNothingAndIsTriedAgain() {
        let marks = FakeMarks()

        let failed = KhandaqCommunity.autoJoin(marks: marks, connected: true, alreadyInGroup: false,
                                               join: { () -> String? in nil })
        XCTAssertNil(failed)
        XCTAssertFalse(marks.communityAutoJoinDone)

        // Offline: not even tried.
        var tried = false
        KhandaqCommunity.autoJoin(marks: marks, connected: false, alreadyInGroup: false,
                                  join: { () -> String? in
                                      tried = true
                                      return "community"
                                  })
        XCTAssertFalse(tried)

        // Back online: the retry joins.
        let joined = KhandaqCommunity.autoJoin(marks: marks, connected: true, alreadyInGroup: false,
                                               join: { () -> String? in "community" })
        XCTAssertEqual(joined, "community")
        XCTAssertTrue(marks.communityAutoJoinDone)
    }

    /// 0.2.60: the group an earlier auto-join muted is heard again, once; the person may mute it again for good.
    func testTheSoundAnEarlierJoinTurnedOffIsTurnedOnOnce() {
        let marks = FakeMarks()
        marks.setCommunityAutoJoinDone()
        var silent = true
        var unmutes = 0

        func restore() -> Bool {
            return KhandaqCommunity.restoreSound(marks: marks,
                                                 chat: { () -> String? in "community" },
                                                 isSilent: { _ in silent },
                                                 unmute: { _ in
                                                     unmutes += 1
                                                     silent = false
                                                 })
        }

        XCTAssertTrue(restore())
        XCTAssertFalse(silent)
        XCTAssertEqual(unmutes, 1)
        XCTAssertTrue(marks.communitySoundRestored)

        // The person mutes it again: it stays muted.
        silent = true
        XCTAssertFalse(restore())
        XCTAssertTrue(silent)
        XCTAssertEqual(unmutes, 1)
    }

    /// A group joined by hand was muted by hand, and a group left is not ours to touch: both are done at once,
    /// so a join of 0.2.60, which keeps the sound, never finds a restore due afterwards.
    func testTheSoundOfAGroupTheAppDidNotJoinOrThePersonLeftIsLeftAsItIs() {
        for (autoJoinDone, left) in [(false, false), (true, true), (false, true)] {
            let marks = FakeMarks()
            if autoJoinDone {
                marks.setCommunityAutoJoinDone()
            }
            if left {
                marks.setCommunityLeft()
            }
            var unmutes = 0
            let unmuted = KhandaqCommunity.restoreSound(marks: marks,
                                                        chat: { () -> String? in "community" },
                                                        isSilent: { _ in true },
                                                        unmute: { _ in unmutes += 1 })
            XCTAssertFalse(unmuted, "done \(autoJoinDone), left \(left)")
            XCTAssertEqual(unmutes, 0, "done \(autoJoinDone), left \(left)")
            XCTAssertTrue(marks.communitySoundRestored, "done \(autoJoinDone), left \(left)")
        }
    }

    /// The group not here yet at a start (its row not yet read): nothing is marked, the next start looks again.
    func testTheSoundIsTurnedOnOnceTheGroupIsHere() {
        let marks = FakeMarks()
        marks.setCommunityAutoJoinDone()
        var here = false
        var unmutes = 0

        func restore() -> Bool {
            return KhandaqCommunity.restoreSound(marks: marks,
                                                 chat: { () -> String? in here ? "community" : nil },
                                                 isSilent: { _ in true },
                                                 unmute: { _ in unmutes += 1 })
        }

        XCTAssertFalse(restore())
        XCTAssertFalse(marks.communitySoundRestored)
        here = true
        XCTAssertTrue(restore())
        XCTAssertEqual(unmutes, 1)
        XCTAssertTrue(marks.communitySoundRestored)
    }

    /// A muted group, this one or any other, adds nothing to the badge of the Chats tab nor to the app icon's,
    /// and its row keeps its own counter.
    func testAMutedGroupAddsNothingToTheBadgesAndItsRowKeepsItsCounter() throws {
        let directory = makeDirectory()
        let realmManagerClass = try appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
        let realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("profile.realm"),
                                                                encryptionKey: nil))
        let objects = OCTSubmanagerObjectsMock(realm: realmManager.realm)
        func unreadChat(group: Bool, muted: Bool) throws -> OCTChat {
            let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
            chat.uniqueIdentifier = UUID().uuidString
            chat.isGroup = group
            chat.groupNotificationsSilent = muted
            chat.lastReadDateInterval = 5
            let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
            message.uniqueIdentifier = UUID().uuidString
            message.chatUniqueIdentifier = chat.uniqueIdentifier
            message.dateInterval = 10
            if group {
                message.groupSenderPeerId = 4
            }
            else {
                message.senderUniqueIdentifier = UUID().uuidString
            }
            chat.lastMessage = message
            realmManager.add(chat)
            return chat
        }
        let friend = try unreadChat(group: false, muted: false)
        let group = try unreadChat(group: true, muted: false)
        let muted = try unreadChat(group: true, muted: true)

        let unread = objects.chats(predicate: NotificationCoordinator.unreadChatsPredicate)
        XCTAssertEqual(unread.count, 2)
        XCTAssertGreaterThanOrEqual(unread.indexOfObject(friend), 0)
        XCTAssertGreaterThanOrEqual(unread.indexOfObject(group), 0)
        XCTAssertEqual(unread.indexOfObject(muted), -1, "a muted group adds nothing")
        XCTAssertEqual(ChatListContent.unreadCount(for: muted, submanagerGroups: OCTSubmanagerGroupsMock(), submanagerObjects: objects), 1,
                       "its row keeps its counter")

        // Its notifications on again, it counts again.
        let realm: RLMRealm = realmManager.realm
        realm.beginWriteTransaction()
        muted.groupNotificationsSilent = false
        try realm.commitWriteTransaction()
        XCTAssertEqual(objects.chats(predicate: NotificationCoordinator.unreadChatsPredicate).count, 3)
    }

    /// KHANDAQ (notification parity, 2026-11-20): what the history sync brings from a member who is not in the group at
    /// that moment, gone or offline as the Community's members are all day, has peer id 0 and its author's key alone.
    /// A text or a file of theirs counts unread in its row and on the badges as a message of a member who is there
    /// does; a legacy synced row that names nobody, a notice and our own message still do not, and a muted group still
    /// adds nothing to the badges while its row keeps its counter.
    func testWhatTheHistoryBringsFromAMemberWhoIsGoneCountsUnread() throws {
        let directory = makeDirectory()
        let realmManagerClass = try appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
        let realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("profile.realm"),
                                                                encryptionKey: nil))
        let objects = OCTSubmanagerObjectsMock(realm: realmManager.realm)
        let groups = OCTSubmanagerGroupsMock()
        let author = String(repeating: "A1", count: 32)
        let lastRead = Date().timeIntervalSince1970 - 600
        var made = 0
        func group(muted: Bool = false) throws -> OCTChat {
            made += 1
            let chat = try groupChat("group-\(made)", chatIdHex: String(repeating: "C", count: 63) + "\(made)", name: "Group \(made)", topic: nil)
            chat.groupNotificationsSilent = muted
            chat.lastReadDateInterval = lastRead
            realmManager.add(chat)
            return chat
        }
        func syncedText(in chat: OCTChat, author: String?) throws -> OCTMessageAbstract {
            return try XCTUnwrap(realmManager.addGroupSyncedMessage(withText: "Salam", type: .normal, chat: chat, peerId: 0,
                                                                    peerName: "Amina", senderPubkeyHex: author, messageId: 7,
                                                                    dateInterval: lastRead + 60))
        }
        func unreadCount(_ chat: OCTChat) -> Int {
            return ChatListContent.unreadCount(for: chat, submanagerGroups: groups, submanagerObjects: objects)
        }
        func badged(_ chat: OCTChat) -> Bool {
            return objects.chats(predicate: NotificationCoordinator.unreadChatsPredicate).indexOfObject(chat) >= 0
        }

        let texted = try group()
        let text = try syncedText(in: texted, author: author)
        XCTAssertEqual(text.groupSenderPeerId, 0)
        XCTAssertFalse(text.isOutgoing())
        XCTAssertTrue(ChatListContent.countsAsUnread(text))
        XCTAssertEqual(unreadCount(texted), 1, "its row")
        XCTAssertTrue(badged(texted), "the Chats tab and the app icon")

        let filed = try group()
        let file = try XCTUnwrap(realmManager.addGroupSyncedMessage(withFileName: "photo.jpg", fileSize: 4,
                                                                    filePath: directory.appendingPathComponent("photo.jpg").path,
                                                                    fileType: .ready, chat: filed, peerId: 0, peerName: "Amina",
                                                                    senderPubkeyHex: author, fileUTI: nil,
                                                                    groupMsgIdHashHex: String(repeating: "B2", count: 32),
                                                                    dateInterval: lastRead + 60))
        XCTAssertTrue(ChatListContent.countsAsUnread(file))
        XCTAssertEqual(unreadCount(filed), 1)
        XCTAssertTrue(badged(filed))

        // A synced row stored before the author's key was kept names nobody, and stays out as before.
        let legacy = try group()
        XCTAssertFalse(ChatListContent.countsAsUnread(try syncedText(in: legacy, author: nil)))
        XCTAssertEqual(unreadCount(legacy), 0)
        XCTAssertFalse(badged(legacy))

        let muted = try group(muted: true)
        _ = try syncedText(in: muted, author: author)
        XCTAssertEqual(unreadCount(muted), 1, "its row keeps its counter")
        XCTAssertFalse(badged(muted), "a muted group adds nothing to the badges")

        let notice = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        notice.groupSystemMessage = true
        XCTAssertFalse(ChatListContent.countsAsUnread(notice), "Amina joined")
        let ours = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        ours.groupSenderPeerId = 0
        XCTAssertFalse(ChatListContent.countsAsUnread(ours), "our own message")
    }

    // MARK: Its name and its place in the list (owner, 2026-11-16)

    /// Its founder's topic was "Official Khandaq community · Be kind, no spam or ads · …", the list showed the
    /// topic, and a profile whose group gave no name stores the topic as the name. Wherever a group's name shows
    /// it is "Khandaq Community"; any other group keeps its topic, then its name.
    func testTheCommunityIsCalledKhandaqCommunityWhateverItsTopic() throws {
        let official = "Official Khandaq community \u{00B7} Be kind, no spam or ads \u{00B7} \u{2026}"
        let community = try groupChat("community", chatIdHex: KhandaqCommunity.chatIdHex.lowercased(), name: official, topic: official)
        XCTAssertEqual(ChatListContent.groupTitle(for: community), "Khandaq Community", "the list, the header, the dossier, search")
        XCTAssertEqual(MessageForwarder.displayName(for: community), "Khandaq Community", "forward, share, messages found")
        XCTAssertEqual(NotificationCoordinator.groupName(of: community), "Khandaq Community", "its notifications")

        let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        message.groupSenderPeerId = 3
        // A message without a date is not one the app keeps (OCTMessageAbstract date is then nil).
        message.dateInterval = 1_763_296_496
        let text = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        text.text = "spam"
        message.messageText = text
        let report = GroupReport(chat: community, message: message, groups: nil)
        XCTAssertEqual(report.subject, "Report: Khandaq Community")
        XCTAssertTrue(report.body.hasPrefix("Group: Khandaq Community\n"), report.body)

        // An invitation to it: toxcore's invite data starts with the group's chat id.
        var invite = Data()
        var hex = KhandaqCommunity.chatIdHex[...]
        while !hex.isEmpty {
            invite.append(try XCTUnwrap(UInt8(hex.prefix(2), radix: 16)))
            hex = hex.dropFirst(2)
        }
        invite.append(Data(repeating: 7, count: 32))
        XCTAssertEqual(KhandaqCommunity.displayName(forInviteData: invite), "Khandaq Community")
        XCTAssertNil(KhandaqCommunity.displayName(forInviteData: Data(repeating: 0xBB, count: 64)))
        XCTAssertNil(KhandaqCommunity.displayName(forInviteData: invite.prefix(31)))

        let other = try groupChat("book", chatIdHex: String(repeating: "B", count: 64), name: "Book club", topic: "Reading Tolstoy")
        XCTAssertEqual(ChatListContent.groupTitle(for: other), "Reading Tolstoy")
        XCTAssertEqual(MessageForwarder.displayName(for: other), "Book club")
        XCTAssertEqual(NotificationCoordinator.groupName(of: other), "Book club")
        XCTAssertEqual(GroupReport(chat: other, message: message, groups: nil).subject, "Report: Book club")
        let unnamed = try groupChat("unnamed", chatIdHex: String(repeating: "C", count: 64), name: nil, topic: nil)
        XCTAssertEqual(ChatListContent.groupTitle(for: unnamed), String(localized: "group_chat_default_title"))
    }

    /// The iPad list: Saved Messages is pinned to the top of every tab it is in, and Khandaq Community right
    /// below it, or first without it, the same way, joined by itself or by hand; once left, its chat is gone and
    /// so is its row.
    func testOnTheIPadKhandaqCommunityComesRightAfterSavedMessages() throws {
        let direct = try appClass("OCTChat", as: OCTChat.Type.self).init()
        direct.uniqueIdentifier = "direct"
        let saved = try appClass("OCTChat", as: OCTChat.Type.self).init()
        saved.uniqueIdentifier = "saved"
        saved.isSavedMessages = true
        let community = try groupChat("community", chatIdHex: KhandaqCommunity.chatIdHex, name: "Khandaq Community", topic: nil)
        let book = try groupChat("book", chatIdHex: String(repeating: "B", count: 64), name: "Book club", topic: nil)
        // Newest activity first, as the list sorts them.
        let chats = [direct, book, community, saved]
        let everything: (OCTChat) -> Bool = { _ in true }

        XCTAssertEqual(rows(chats, everything), ["saved", "community", "direct", "book"])
        XCTAssertEqual(rows(chats.filter { !$0.isSavedMessages }, everything), ["community", "direct", "book"])
        XCTAssertEqual(rows(chats, { $0.isGroup }), ["community", "book"], "the Groups tab")
        XCTAssertEqual(rows(chats, { !$0.isGroup }), ["saved", "direct"], "the Direct tab")
        XCTAssertEqual(rows(chats.filter { $0.uniqueIdentifier != "community" }, everything), ["saved", "direct", "book"], "left")
        XCTAssertEqual(ChatListTableManager.chat(atRow: 4, count: chats.count, chatAt: { chats[$0] }, matches: everything)?.uniqueIdentifier,
                       "saved", "past the end, as before")
        XCTAssertNil(ChatListTableManager.chat(atRow: 1, count: 1, chatAt: { _ in book }, matches: everything))
    }

    /// KHANDAQ (0.2.60): its tile is the logo of the app, not the letters "KC" (owner, 2026-11-18), in the list
    /// and on its banners; any other group keeps its letters.
    func testTheCommunityTileIsTheLogoOfTheApp() {
        XCTAssertNotNil(UIImage(named: "kq-community"), "the logo is in the app's assets")
        let tile = AirTileView(side: KQSize.tilePhone)
        tile.configure(style: .community, photo: nil)
        XCTAssertEqual(tile.subviews.compactMap { $0 as? UIImageView }.filter { !$0.isHidden && $0.image != nil }.count, 1)
        XCTAssertTrue(tile.subviews.compactMap { $0 as? UILabel }.allSatisfy { ($0.text ?? "").isEmpty }, "no letters over the logo")

        tile.configure(style: .group(initials: "BC", tone: 2), photo: nil)
        XCTAssertTrue(tile.subviews.compactMap { $0 as? UIImageView }.allSatisfy { $0.isHidden || $0.image == nil })
        XCTAssertEqual(tile.subviews.compactMap { $0 as? UILabel }.first?.text, "BC")

        let row = { (kind: AirEntry.Kind, pinned: Bool) -> AirEntry in
            var entry = AirEntry(id: "c", kind: kind, title: "Khandaq Community", reach: .local, preview: "", previewStyle: .message,
                                 lastDelivery: nil, timeText: "", activity: 1, transitCount: 0, unreadCount: 0,
                                 isFavorite: false, avatarData: nil, tone: 0)
            entry.isPinned = pinned
            return entry
        }
        XCTAssertTrue(row(.group, true).isCommunity, "the one group pinned above the others")
        XCTAssertFalse(row(.group, false).isCommunity)
        XCTAssertFalse(row(.direct, true).isCommunity)
    }

    // MARK: Item 3, left means never again, across a restart

    func testLeavingTheCommunityIsKeptAcrossARestartAndItIsNeverJoinedAgain() {
        let directory = makeDirectory()
        let first = AirLocalStore(sealing: PlainSealing())
        first.attach(directory: directory)
        XCTAssertFalse(first.communityLeft)

        XCTAssertFalse(KhandaqCommunity.noteGroupLeft(chatIdHex: String(repeating: "B", count: 64), marks: first),
                       "another group leaves no mark")
        XCTAssertFalse(first.communityLeft)
        XCTAssertTrue(KhandaqCommunity.noteGroupLeft(chatIdHex: KhandaqCommunity.chatIdHex.lowercased(), marks: first))
        XCTAssertFalse(KhandaqCommunity.noteGroupLeft(chatIdHex: KhandaqCommunity.chatIdHex, marks: first),
                       "already left")
        first.flush()

        // A new launch: the same profile directory, a new store.
        let second = AirLocalStore(sealing: PlainSealing())
        second.attach(directory: directory)
        XCTAssertTrue(second.communityLeft)
        XCTAssertFalse(second.communityAutoJoinDone)

        var tried = false
        let joined = KhandaqCommunity.autoJoin(marks: second, connected: true, alreadyInGroup: false,
                                               join: { () -> String? in
                                                   tried = true
                                                   return "community"
                                               })
        XCTAssertNil(joined)
        XCTAssertFalse(tried)
    }

    func testTheJoinMadeOnceIsKeptAcrossARestart() {
        let directory = makeDirectory()
        let first = AirLocalStore(sealing: PlainSealing())
        first.attach(directory: directory)
        KhandaqCommunity.autoJoin(marks: first, connected: true, alreadyInGroup: false,
                                  join: { () -> String? in "community" })
        KhandaqCommunity.restoreSound(marks: first,
                                      chat: { () -> String? in "community" },
                                      isSilent: { _ in false },
                                      unmute: { _ in })
        first.flush()

        let second = AirLocalStore(sealing: PlainSealing())
        second.attach(directory: directory)
        XCTAssertTrue(second.communityAutoJoinDone)
        XCTAssertFalse(second.communityLeft)
        XCTAssertTrue(second.communitySoundRestored, "the sound is turned on once, not at every start")
        // Each profile keeps its own: another profile's directory starts with nothing.
        let other = AirLocalStore(sealing: PlainSealing())
        other.attach(directory: makeDirectory())
        XCTAssertFalse(other.communityAutoJoinDone)
    }

    func testAFileOf056JoinsOnceAndTheMarksStayInTheClearWhenTheKeychainIsLocked() throws {
        let directory = makeDirectory()
        let url = directory.appendingPathComponent(AirLocalStore.fileName)
        // 0.2.56 wrote no community marks; its sealed part cannot be opened right now.
        try Data("{\"version\":2,\"contacts\":{},\"keyId\":\"k\",\"sealed\":\"AAAA\"}".utf8).write(to: url)

        let store = AirLocalStore(sealing: LockedSealing())
        store.attach(directory: directory)
        XCTAssertFalse(store.communityAutoJoinDone)
        XCTAssertFalse(store.communityLeft)
        store.setCommunityAutoJoinDone()
        store.setCommunityLeft()
        store.flush()

        let json = String(decoding: try Data(contentsOf: url), as: UTF8.self)
        XCTAssertTrue(json.contains("\"sealed\":\"AAAA\""), "the sealed part it could not open is kept")
        let again = AirLocalStore(sealing: LockedSealing())
        again.attach(directory: directory)
        XCTAssertTrue(again.communityAutoJoinDone)
        XCTAssertTrue(again.communityLeft)
    }

    /// The marks are no secret: a moment the sealed part cannot be sealed again (the keychain refuses) keeps them
    /// off the disk no more, and the sealed part stays as it was. Before, nothing was written: a leave was lost
    /// with the next stop of the app, and the group joined again.
    func testTheMarksAreWrittenWhenTheSealedPartCannotBeSealedAgain() throws {
        let directory = makeDirectory()
        let blocked = String(repeating: "A1", count: 32)
        let first = AirLocalStore(sealing: PlainSealing())
        first.attach(directory: directory)
        first.block(publicKey: blocked)
        first.flush()

        let second = AirLocalStore(sealing: OpenOnlySealing())
        second.attach(directory: directory)
        XCTAssertTrue(second.isBlocked(publicKey: blocked))
        second.setCommunityAutoJoinDone()
        second.setCommunityLeft()
        second.flush()

        let third = AirLocalStore(sealing: PlainSealing())
        third.attach(directory: directory)
        XCTAssertTrue(third.communityAutoJoinDone)
        XCTAssertTrue(third.communityLeft)
        XCTAssertTrue(third.isBlocked(publicKey: blocked), "the sealed part is the one that was there")
    }

    // MARK: Item 6, every file waits

    /// Files of others wait whenever they came (KhandaqCommunity.fileHold), so the moment a build before the
    /// release kept for it is let go, and the marks next to it still read.
    func testTheMomentAnEarlierBuildKeptForWaitingFilesIsLetGo() throws {
        let directory = makeDirectory()
        let url = directory.appendingPathComponent(AirLocalStore.fileName)
        try Data("{\"version\":2,\"contacts\":{},\"community\":{\"autoJoinDone\":true,\"left\":false,\"holdSince\":1794832496}}".utf8).write(to: url)

        let store = AirLocalStore(sealing: PlainSealing())
        store.attach(directory: directory)
        XCTAssertTrue(store.communityAutoJoinDone)
        XCTAssertFalse(store.communityLeft)
        store.flush()
        XCTAssertTrue(String(decoding: try Data(contentsOf: url), as: UTF8.self).contains("holdSince"), "opening a profile writes nothing")

        store.setCommunityLeft()
        store.flush()
        let json = String(decoding: try Data(contentsOf: url), as: UTF8.self)
        XCTAssertFalse(json.contains("holdSince"), json)
        XCTAssertTrue(json.contains("\"autoJoinDone\":true"), json)
        XCTAssertTrue(json.contains("\"left\":true"), json)
    }

    // MARK: A failed join of its own says nothing

    func testAFailedJoinOfTheCommunityItMadeItselfIsNotTold() throws {
        let marks = FakeMarks()
        let community = try group(KhandaqCommunity.chatIdHex)
        let other = try group(String(repeating: "C", count: 64))

        XCTAssertTrue(KhandaqCommunity.tellsJoinFailure(of: community, marks: marks), "a join by hand before any of its own")
        marks.setCommunityAutoJoinDone()
        XCTAssertFalse(KhandaqCommunity.tellsJoinFailure(of: community, marks: marks))
        XCTAssertTrue(KhandaqCommunity.tellsJoinFailure(of: other, marks: marks))
        marks.setCommunityLeft()
        XCTAssertTrue(KhandaqCommunity.tellsJoinFailure(of: community, marks: marks), "a join by hand after leaving")
    }

    func testTheNameInTheGroupIsTheProfilesOwn() {
        XCTAssertEqual(GroupJoinHelper.peerName(userName: "Amina", profileName: "Phone"), "Amina")
        XCTAssertEqual(GroupJoinHelper.peerName(userName: "", profileName: "Phone"), "Phone")
        XCTAssertEqual(GroupJoinHelper.peerName(userName: nil, profileName: nil), "Khandaq")
    }

    // MARK: Helpers

    /// A group chat outside any Realm. objcTox is in the app and in this bundle; the Realm schema knows the
    /// app's classes only, so the chat is made from the app's class.
    private func group(_ chatIdHex: String) throws -> OCTChat {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        chat.isGroup = true
        chat.groupChatIdHex = chatIdHex
        return chat
    }

    /// A group's chat outside any Realm, made from the app's class.
    private func groupChat(_ id: String, chatIdHex: String, name: String?, topic: String?) throws -> OCTChat {
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        chat.uniqueIdentifier = id
        chat.isGroup = true
        chat.groupChatIdHex = chatIdHex
        chat.groupName = name
        chat.groupTopic = topic
        return chat
    }

    /// The ids of the iPad list's rows, top to bottom, for `chats` in their order and a tab that keeps `matches`.
    private func rows(_ chats: [OCTChat], _ matches: (OCTChat) -> Bool) -> [String] {
        let count = chats.filter(matches).count
        return (0..<count).compactMap { row in
            ChatListTableManager.chat(atRow: row, count: chats.count, chatAt: { chats[$0] }, matches: matches)?.uniqueIdentifier
        }
    }

    private func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }

    private func makeDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("community-" + UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        directories.append(url)
        return url
    }
}

private final class FakeMarks: KhandaqCommunityMarks {
    private(set) var communityAutoJoinDone = false
    private(set) var communityLeft = false
    private(set) var communitySoundRestored = false

    func setCommunityAutoJoinDone() {
        communityAutoJoinDone = true
    }

    func setCommunitySoundRestored() {
        communitySoundRestored = true
    }

    @discardableResult
    func setCommunityLeft() -> Bool {
        defer {
            communityLeft = true
        }
        return !communityLeft
    }
}

/// Nothing is sealed in these tests; the marks never are.
private struct PlainSealing: AirSealing {
    func seal(_ data: Data, keyId: String) -> Data? {
        return data
    }

    func open(_ data: Data, keyId: String) -> AirUnsealed {
        return .opened(data)
    }
}

/// The sealed part opens, and sealing fails: the keychain refuses to read or to make the key at that moment.
private struct OpenOnlySealing: AirSealing {
    func seal(_ data: Data, keyId: String) -> Data? {
        return nil
    }

    func open(_ data: Data, keyId: String) -> AirUnsealed {
        return .opened(data)
    }
}

/// A keychain that cannot be read right now.
private struct LockedSealing: AirSealing {
    func seal(_ data: Data, keyId: String) -> Data? {
        return nil
    }

    func open(_ data: Data, keyId: String) -> AirUnsealed {
        return .unavailable
    }
}
