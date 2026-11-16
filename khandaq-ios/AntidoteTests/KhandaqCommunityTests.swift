// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Khandaq Community (SPEC-community, part B): every profile joins the official group once by itself, muted,
 and never again once the person left it, across restarts.
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

    // MARK: Item 2, muted only on the auto-join

    func testTheJoinItMakesMutesTheGroupOnceAndMarksItDone() {
        let marks = FakeMarks()
        var joins = 0
        var muted: [String] = []

        let joined = KhandaqCommunity.autoJoin(marks: marks, connected: true, alreadyInGroup: false,
                                               join: { () -> String? in
                                                   joins += 1
                                                   return "community"
                                               },
                                               mute: { muted.append($0) })

        XCTAssertEqual(joined, "community")
        XCTAssertEqual(joins, 1)
        XCTAssertEqual(muted, ["community"])
        XCTAssertTrue(marks.communityAutoJoinDone)
        XCTAssertFalse(marks.communityLeft)

        // The next start or connection: done, nothing more.
        let again = KhandaqCommunity.autoJoin(marks: marks, connected: true, alreadyInGroup: false,
                                              join: { () -> String? in
                                                  joins += 1
                                                  return "community"
                                              },
                                              mute: { muted.append($0) })
        XCTAssertNil(again)
        XCTAssertEqual(joins, 1)
        XCTAssertEqual(muted, ["community"])
    }

    func testAGroupJoinedByHandIsNeitherJoinedAgainNorMuted() {
        let marks = FakeMarks()
        var calls = 0

        let joined = KhandaqCommunity.autoJoin(marks: marks, connected: true, alreadyInGroup: true,
                                               join: { () -> String? in
                                                   calls += 1
                                                   return "community"
                                               },
                                               mute: { _ in calls += 1 })

        XCTAssertNil(joined)
        XCTAssertEqual(calls, 0)
        XCTAssertFalse(marks.communityAutoJoinDone)
    }

    func testAJoinToxcoreDidNotTakeMarksNothingAndIsTriedAgain() {
        let marks = FakeMarks()
        var muted = 0

        let failed = KhandaqCommunity.autoJoin(marks: marks, connected: true, alreadyInGroup: false,
                                               join: { () -> String? in nil },
                                               mute: { _ in muted += 1 })
        XCTAssertNil(failed)
        XCTAssertEqual(muted, 0)
        XCTAssertFalse(marks.communityAutoJoinDone)

        // Offline: not even tried.
        var tried = false
        KhandaqCommunity.autoJoin(marks: marks, connected: false, alreadyInGroup: false,
                                  join: { () -> String? in
                                      tried = true
                                      return "community"
                                  },
                                  mute: { _ in muted += 1 })
        XCTAssertFalse(tried)

        // Back online: the retry joins and mutes.
        let joined = KhandaqCommunity.autoJoin(marks: marks, connected: true, alreadyInGroup: false,
                                               join: { () -> String? in "community" },
                                               mute: { _ in muted += 1 })
        XCTAssertEqual(joined, "community")
        XCTAssertEqual(muted, 1)
        XCTAssertTrue(marks.communityAutoJoinDone)
    }

    /// The join mutes the group: a muted group, this one or any other, adds nothing to the badge of the Chats tab
    /// nor to the app icon's, and its row keeps its own counter.
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
        let report = GroupReport(chat: community, message: message)
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
        XCTAssertEqual(GroupReport(chat: other, message: message).subject, "Report: Book club")
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
                                               },
                                               mute: { _ in })
        XCTAssertNil(joined)
        XCTAssertFalse(tried)
    }

    func testTheJoinMadeOnceIsKeptAcrossARestart() {
        let directory = makeDirectory()
        let first = AirLocalStore(sealing: PlainSealing())
        first.attach(directory: directory)
        KhandaqCommunity.autoJoin(marks: first, connected: true, alreadyInGroup: false,
                                  join: { () -> String? in "community" },
                                  mute: { _ in })
        first.flush()

        let second = AirLocalStore(sealing: PlainSealing())
        second.attach(directory: directory)
        XCTAssertTrue(second.communityAutoJoinDone)
        XCTAssertFalse(second.communityLeft)
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

    func setCommunityAutoJoinDone() {
        communityAutoJoinDone = true
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
