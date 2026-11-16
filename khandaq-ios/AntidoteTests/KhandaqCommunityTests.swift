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
        XCTAssertNotNil(store.communityHoldSince, "the files that came in 0.2.56 stay shown")
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

    // MARK: Item 6, from when files wait

    func testFilesWaitFromTheFirstStartOf057AndThatMomentIsKept() throws {
        let directory = makeDirectory()
        let first = AirLocalStore(sealing: PlainSealing())
        XCTAssertNil(first.communityHoldSince, "no profile yet")
        let before = Date().timeIntervalSince1970
        first.attach(directory: directory)
        let since = try XCTUnwrap(first.communityHoldSince)
        XCTAssertGreaterThanOrEqual(since, before)
        first.flush()

        // A later start keeps it: what waited still waits, what was shown is still shown.
        let second = AirLocalStore(sealing: PlainSealing())
        second.attach(directory: directory)
        XCTAssertEqual(second.communityHoldSince, since)
        second.detach()
        XCTAssertNil(second.communityHoldSince)
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

/// A keychain that cannot be read right now.
private struct LockedSealing: AirSealing {
    func seal(_ data: Data, keyId: String) -> Data? {
        return nil
    }

    func open(_ data: Data, keyId: String) -> AirUnsealed {
        return .unavailable
    }
}
