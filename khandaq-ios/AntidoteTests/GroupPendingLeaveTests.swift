// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The leave that waits for its notice (QA-Live 14.11, F11 follow-up). toxcore deletes a group, with its connections
 and its send queue, on the iterate after tox_group_leave, so the leave notice sent right before it had a single try,
 and over a TCP relay it was sometimes lost. A leave whose notice was queued to another member now keeps the group for
 12 s, the time of toxcore's resends at about +3, +5 and +9 s; for the app the group is gone at once all the same.
 */
/// Where the profile keeps the chat ids of the pending leaves (kOCTGroupPendingLeavesStoreKey in objcTox).
private let pendingLeavesStoreKey = "kqpendingleaves"

final class GroupPendingLeaveTests: XCTestCase {
    // Chat ids, 64 hex digits each (named so that gitleaks does not take them for secrets).
    private let chatA = String(repeating: "A1B2C3D4E5F60718", count: 4)
    private let chatB = String(repeating: "0F1E2D3C4B5A6978", count: 4)

    private var directory: URL?
    private var profile: PendingLeaveProfile?

    override func tearDown() {
        profile = nil
        if let directory = directory {
            try? FileManager.default.removeItem(at: directory)
        }
        super.tearDown()
    }

    // MARK: The bookkeeping

    func testTheLeaveWaitsPastTheThirdResend() {
        XCTAssertEqual(OCTGroupPendingLeaves.delay(), 12)
        // toxcore resends at about +3, +5 and +9 s, and next only at about +17 s.
        XCTAssertGreaterThan(OCTGroupPendingLeaves.delay(), 9)
        XCTAssertLessThan(OCTGroupPendingLeaves.delay(), 17)
    }

    /// A leave waits only for a notice that toxcore queued to another member: toxcore counts the user among the
    /// confirmed peers, and reports a lossless packet as sent when no one else is there to get it.
    func testALeaveWaitsOnlyForANoticeQueuedToAnotherMember() {
        XCTAssertTrue(OCTGroupPendingLeaves.leaveWaits(forNoticeSent: true, confirmedPeerCount: 2))
        XCTAssertFalse(OCTGroupPendingLeaves.leaveWaits(forNoticeSent: true, confirmedPeerCount: 1))
        XCTAssertFalse(OCTGroupPendingLeaves.leaveWaits(forNoticeSent: true, confirmedPeerCount: 0))
        XCTAssertFalse(OCTGroupPendingLeaves.leaveWaits(forNoticeSent: false, confirmedPeerCount: 5))
    }

    func testALeaveIsDueTwelveSecondsAfterItWasRecorded() {
        let leaves = OCTGroupPendingLeaves()
        let entry = leaves.recordLeave(ofGroupNumber: 3, chatIdHex: chatA.lowercased(), partMessage: "bye", at: 100)

        XCTAssertEqual(entry?.groupNumber, 3)
        XCTAssertEqual(entry?.chatIdHex, chatA)
        XCTAssertEqual(entry?.partMessage, "bye")
        XCTAssertEqual(entry?.dueAt, 112)
        XCTAssertEqual(leaves.nextDueAt(), 112)
        XCTAssertTrue(leaves.dueEntries(at: 111.9).isEmpty)
        XCTAssertEqual(groupNumbers(of: leaves.dueEntries(at: 112)), [3])
        // Due is not done: the leave stays until it is removed.
        XCTAssertEqual(leaves.count, 1)
    }

    func testALeaveIsFoundByItsNumberAndItsChatIdOnly() {
        let leaves = OCTGroupPendingLeaves()
        leaves.recordLeave(ofGroupNumber: 3, chatIdHex: chatA, partMessage: nil, at: 0)

        XCTAssertTrue(leaves.containsGroupNumber(3))
        XCTAssertFalse(leaves.containsGroupNumber(4))
        XCTAssertEqual(groupNumbers(of: leaves.entries(forChatIdHex: chatA.lowercased())), [3])
        XCTAssertTrue(leaves.entries(forChatIdHex: chatB).isEmpty)
        XCTAssertTrue(leaves.entries(forChatIdHex: "not a chat id").isEmpty)
        XCTAssertTrue(leaves.entries(forChatIdHex: nil).isEmpty)
    }

    func testWhatIsNotAChatIdIsNotRecorded() {
        let leaves = OCTGroupPendingLeaves()

        XCTAssertNil(leaves.recordLeave(ofGroupNumber: 1, chatIdHex: String(chatA.dropLast()), partMessage: nil, at: 0))
        XCTAssertNil(leaves.recordLeave(ofGroupNumber: 1, chatIdHex: String(chatA.dropLast()) + "G", partMessage: nil, at: 0))
        XCTAssertEqual(leaves.count, 0)
    }

    /// A rejoin cancels the leave: it is never due, so the new instance is not left.
    func testACancelledLeaveIsNeverDue() {
        let leaves = OCTGroupPendingLeaves()
        let entry: OCTGroupPendingLeave = leaves.recordLeave(ofGroupNumber: 3, chatIdHex: chatA, partMessage: nil, at: 0)

        XCTAssertTrue(leaves.removeEntry(entry))
        XCTAssertFalse(leaves.removeEntry(entry))
        XCTAssertFalse(leaves.containsGroupNumber(3))
        XCTAssertTrue(leaves.dueEntries(at: 1_000).isEmpty)
        XCTAssertEqual(leaves.nextDueAt(), 0)
        XCTAssertEqual(leaves.serializedChatIds(), "")
    }

    /// One instance holds a group number at a time: a second leave of the number is the one that counts, and the
    /// first one, removed late, does not take it away.
    func testASecondLeaveOfANumberReplacesTheFirst() {
        let leaves = OCTGroupPendingLeaves()
        let first: OCTGroupPendingLeave = leaves.recordLeave(ofGroupNumber: 5, chatIdHex: chatA, partMessage: nil, at: 0)
        let second: OCTGroupPendingLeave = leaves.recordLeave(ofGroupNumber: 5, chatIdHex: chatB, partMessage: nil, at: 4)

        XCTAssertEqual(leaves.count, 1)
        XCTAssertFalse(leaves.removeEntry(first))
        XCTAssertTrue(leaves.containsGroupNumber(5))
        XCTAssertEqual(chatIds(of: leaves.allEntries()), [chatB])
        XCTAssertEqual(leaves.nextDueAt(), second.dueAt)
    }

    func testLeavesAreDueEarliestFirst() {
        let leaves = OCTGroupPendingLeaves()
        leaves.recordLeave(ofGroupNumber: 1, chatIdHex: chatA, partMessage: nil, at: 10)
        leaves.recordLeave(ofGroupNumber: 2, chatIdHex: chatB, partMessage: nil, at: 5)

        XCTAssertEqual(leaves.nextDueAt(), 17)
        XCTAssertEqual(groupNumbers(of: leaves.dueEntries(at: 20)), [2])
        XCTAssertEqual(groupNumbers(of: leaves.dueEntries(at: 30)), [2, 1])
        XCTAssertEqual(groupNumbers(of: leaves.allEntries()), [1, 2])
    }

    /// What the profile keeps: the chat ids alone, read back as they were written.
    func testTheStoredListReadsBackAsItWasWritten() {
        let leaves = OCTGroupPendingLeaves()
        leaves.recordLeave(ofGroupNumber: 1, chatIdHex: chatA.lowercased(), partMessage: "part", at: 0)
        leaves.recordLeave(ofGroupNumber: 2, chatIdHex: chatB, partMessage: nil, at: 0)

        let stored: String = leaves.serializedChatIds()
        XCTAssertEqual(stored, chatA + "," + chatB)
        XCTAssertEqual(OCTGroupPendingLeaves.chatIdHexes(fromSerialized: stored), [chatA, chatB])
    }

    func testADamagedStoredListKeepsWhatIsAChatId() {
        XCTAssertEqual(OCTGroupPendingLeaves.chatIdHexes(fromSerialized: nil), [])
        XCTAssertEqual(OCTGroupPendingLeaves.chatIdHexes(fromSerialized: ""), [])

        let damaged = " \(chatA.lowercased()) ,,xyz,\(chatA),\(chatB.dropLast())"
        XCTAssertEqual(OCTGroupPendingLeaves.chatIdHexes(fromSerialized: damaged), [chatA])
    }

    // MARK: The glue, on a Tox made without the network

    /// Why the leave can wait, and why the due-path runs an iterate: toxcore keeps a group it was asked to leave, and
    /// saves it, until its next iterate, which deletes it.
    func testToxcoreDeletesALeftGroupOnlyOnTheNextIterate() throws {
        let tox = try makeTox()
        let group = try makeGroup(on: tox)

        try tox.groupLeave(withGroupNumber: group, partMessage: nil)
        XCTAssertEqual(tox.groupNumbers().map { $0.uint32Value }, [group])

        tox.iterateOnce()
        XCTAssertEqual(tox.groupNumbers().map { $0.uint32Value }, [])
    }

    /// Why the peer count decides: in a group with no other member toxcore reports the notice as sent, though it
    /// queued it to no one, so there is nothing to resend and the group is left at once.
    func testANoticeToAGroupWithNoOtherMemberIsSentToNoOne() throws {
        let tox = try makeTox()
        let group = try makeGroup(on: tox)

        try tox.groupSendCustomPacket(OCTSubmanagerGroupsImpl.groupLeaveNoticePacket(), groupNumber: group, lossless: true)
        let peers = tox.groupPeerCount(forGroupNumber: group, error: nil)

        XCTAssertEqual(peers, 1, "toxcore counts the user alone")
        XCTAssertFalse(OCTGroupPendingLeaves.leaveWaits(forNoticeSent: true, confirmedPeerCount: peers))
    }

    /// The due-path leaves a group only while its number still holds the chat id it recorded, and toxcore has deleted
    /// the group when the path returns, so the save that follows has it no more.
    func testTheDuePathLeavesTheRecordedGroupAndNoOther() throws {
        let tox = try makeTox()
        let kept = try makeGroup(on: tox)
        let left = try makeGroup(on: tox)
        let leftChatId = try tox.groupChatIdHex(forGroupNumber: left)
        let groups = OCTSubmanagerGroupsImpl()

        // The chat id under another number: it is not looked for there.
        let stale: OCTGroupPendingLeave = OCTGroupPendingLeave(groupNumber: kept, chatIdHex: leftChatId, partMessage: nil, dueAt: 0)
        XCTAssertEqual(groups.leaveGroups(of: [stale], event: "test", on: tox), 0)
        XCTAssertEqual(Set(tox.groupNumbers().map { $0.uint32Value }), [kept, left])

        let due: OCTGroupPendingLeave = OCTGroupPendingLeave(groupNumber: left, chatIdHex: leftChatId, partMessage: nil, dueAt: 0)
        XCTAssertEqual(groups.leaveGroups(of: [due], event: "test", on: tox), 1)
        XCTAssertEqual(tox.groupNumbers().map { $0.uint32Value }, [kept])
    }

    /// What toxcore reports of a group whose leave is pending is dropped, and of a group just left until the main
    /// queue has run what toxcore queued before deleting it; after that the number belongs to whatever gets it next.
    func testAGroupIsBeingLeftUntilTheMainQueueHasRunItsLastCallbacks() throws {
        let tox = try makeTox()
        let group = try makeGroup(on: tox)
        let chatId = try tox.groupChatIdHex(forGroupNumber: group)
        let groups = OCTSubmanagerGroupsImpl()

        XCTAssertFalse(groups.isGroupNumberBeingLeft(group))
        let entry: OCTGroupPendingLeave = groups.pendingGroupLeaves.recordLeave(ofGroupNumber: group, chatIdHex: chatId, partMessage: nil, at: 0)
        XCTAssertTrue(groups.isGroupNumberBeingLeft(group))

        XCTAssertEqual(groups.leaveGroups(of: [entry], event: "test", on: tox), 1)
        XCTAssertTrue(groups.pendingGroupLeaves.removeEntry(entry))
        XCTAssertTrue(groups.isGroupNumberBeingLeft(group))

        let drained = expectation(description: "the main queue ran what was queued before")
        DispatchQueue.main.async {
            drained.fulfill()
        }
        wait(for: [drained], timeout: 5)
        XCTAssertFalse(groups.isGroupNumberBeingLeft(group))
    }

    /// A group whose leave is pending is not the group of its chat id any more, for every lookup by the chat id (the
    /// invite checks, a friend's request to be invited, the stuck-instance check).
    func testALookupByChatIdSkipsAGroupWhoseLeaveIsPending() throws {
        let tox = try makeTox()
        let group = try makeGroup(on: tox)
        let chatId = try tox.groupChatIdHex(forGroupNumber: group)
        let groups = OCTSubmanagerGroupsImpl()

        XCTAssertEqual(groups.groupNumberInTox(forChatIdHex: chatId, on: tox), group)
        groups.pendingGroupLeaves.recordLeave(ofGroupNumber: group, chatIdHex: chatId, partMessage: nil, at: 0)
        XCTAssertEqual(groups.groupNumberInTox(forChatIdHex: chatId, on: tox), kOCTToxGroupNumberFailure)
    }

    /// Joining the group again while its leave is pending: toxcore refuses a join by the ID while the old instance
    /// exists. The rejoin drops the pending leave and leaves the old instance at once, so the join that follows works.
    func testARejoinLeavesTheOldInstanceFirstSoTheJoinWorks() throws {
        let tox = try makeTox()
        let group = try makeGroup(on: tox)
        let chatId = try tox.groupChatIdHex(forGroupNumber: group)
        let groups = OCTSubmanagerGroupsImpl()
        groups.pendingGroupLeaves.recordLeave(ofGroupNumber: group, chatIdHex: chatId, partMessage: nil, at: 0)

        var error: NSError?
        XCTAssertEqual(tox.groupJoin(withChatIdHex: chatId, peerName: "Khandaq", password: nil, error: &error), kOCTToxGroupNumberFailure)

        let cancelled = groupNumbers(of: groups.leavePendingGroups(beforeRejoinOfChatIdHex: chatId, on: tox))
        XCTAssertEqual(cancelled, [group])
        XCTAssertEqual(groups.pendingGroupLeaves.count, 0)

        error = nil
        XCTAssertNotEqual(tox.groupJoin(withChatIdHex: chatId, peerName: "Khandaq", password: nil, error: &error), kOCTToxGroupNumberFailure)
        XCTAssertNil(error)
    }

    /// The leave itself, in a group with no other member: the notice goes to no one, so the group is left at once, as
    /// before, and toxcore has deleted it when the profile is saved; nothing waits and nothing is stored.
    func testALeaveWithNoOtherMemberIsDoneAtOnceAndSavedWithoutTheGroup() throws {
        let tox = try makeTox()
        let group = try makeGroup(on: tox)
        let (groups, profile) = try makeGroupsWithProfile(tox: tox)

        var groupsAtSave: [OCTToxGroupNumber]?
        profile.onSave = {
            groupsAtSave = tox.groupNumbers().map { $0.uint32Value }
        }
        try groups.leaveGroup(withNumber: group, partMessage: nil)

        XCTAssertEqual(groupsAtSave, [])
        XCTAssertEqual(groups.pendingGroupLeaves.count, 0)
        XCTAssertNil(profile.storedPendingLeaves(), "nothing was stored")
    }

    /// The flush at quit, logout or a profile switch leaves nothing pending (here without a manager, so with no Tox).
    func testAFlushLeavesNothingPending() {
        let groups = OCTSubmanagerGroupsImpl()
        groups.pendingGroupLeaves.recordLeave(ofGroupNumber: 1, chatIdHex: chatA, partMessage: nil, at: 0)
        groups.pendingGroupLeaves.recordLeave(ofGroupNumber: 2, chatIdHex: chatB, partMessage: nil, at: 0)

        groups.leavePendingGroupsNow()

        XCTAssertEqual(groups.pendingGroupLeaves.count, 0)
        XCTAssertFalse(groups.isGroupNumberBeingLeft(1))
    }

    /// The flush, and the due-path, which runs the same code: the group is deleted before the profile is saved, and
    /// its chat id leaves the stored list only after that save, so that a stop between the two leaves it again at the
    /// next start and never brings it back.
    func testAFlushSavesTheProfileWithoutTheGroupBeforeItDropsTheChatId() throws {
        let tox = try makeTox()
        let group = try makeGroup(on: tox)
        let chatId = try tox.groupChatIdHex(forGroupNumber: group).uppercased()
        let (groups, profile) = try makeGroupsWithProfile(tox: tox)

        groups.recordPendingGroupLeave(ofGroupNumber: group, chatIdHex: chatId, partMessage: nil)
        XCTAssertEqual(profile.storedPendingLeaves(), chatId)
        XCTAssertEqual(tox.groupNumbers().map { $0.uint32Value }, [group], "the group waits in toxcore")

        var atSave: (groups: [UInt32], stored: String?)?
        profile.onSave = { [unowned profile] in
            atSave = (tox.groupNumbers().map { $0.uint32Value }, profile.storedPendingLeaves())
        }
        groups.leavePendingGroupsNow()

        XCTAssertEqual(atSave?.groups, [])
        XCTAssertEqual(atSave?.stored, chatId)
        XCTAssertEqual(profile.storedPendingLeaves(), "")
        XCTAssertEqual(groups.pendingGroupLeaves.count, 0)
    }

    /// The start after a stop with leaves pending: their groups, which the saved profile still has, are left and
    /// deleted, the profile is saved without them, and only then is the list cleared. A chat id whose group is not in
    /// the profile goes with it.
    func testTheStartLeavesTheStoredGroupsBeforeItClearsTheList() throws {
        let tox = try makeTox()
        let kept = try makeGroup(on: tox)
        let left = try makeGroup(on: tox)
        let leftChatId = try tox.groupChatIdHex(forGroupNumber: left)
        let stored = chatB + "," + leftChatId
        let (groups, profile) = try makeGroupsWithProfile(tox: tox)
        XCTAssertTrue(profile.realmManager.setNgcValue(stored, forKey: pendingLeavesStoreKey))

        var atSave: (groups: [UInt32], stored: String?)?
        profile.onSave = { [unowned profile] in
            atSave = (tox.groupNumbers().map { $0.uint32Value }, profile.storedPendingLeaves())
        }
        groups.leaveGroupsOfStoredPendingLeaves()

        XCTAssertEqual(atSave?.groups, [kept])
        XCTAssertEqual(atSave?.stored, stored)
        XCTAssertEqual(profile.storedPendingLeaves(), "")
    }

    // MARK: Helpers

    /// The group numbers of leaves objcTox returned, read by key: objcTox is in the app and in this bundle, and its
    /// leaves are not of this bundle's class (the bridging header says more).
    private func groupNumbers(of entries: [Any]?) -> [OCTToxGroupNumber] {
        return (entries ?? []).compactMap { ($0 as? NSObject)?.value(forKey: "groupNumber") as? OCTToxGroupNumber }
    }

    private func chatIds(of entries: [Any]?) -> [String] {
        return (entries ?? []).compactMap { ($0 as? NSObject)?.value(forKey: "chatIdHex") as? String }
    }

    /// A real Tox without the network, as AirProfileAccessTests makes one.
    private func makeTox() throws -> OCTTox {
        let options = OCTToxOptions()
        options.udpEnabled = false
        options.ipv6Enabled = false
        options.localDiscoveryEnabled = false
        return try OCTTox(options: options, savedData: nil)
    }

    /// A private group, so that nothing is announced.
    private func makeGroup(on tox: OCTTox) throws -> OCTToxGroupNumber {
        var error: NSError?
        let group = tox.groupNew(with: .private, groupName: "Leave test", peerName: "Khandaq", error: &error)

        if let error = error {
            throw error
        }
        XCTAssertNotEqual(group, kOCTToxGroupNumberFailure)
        return group
    }

    /// The groups submanager on a stand-in for its manager: the Tox, a Realm of the test's own for the profile's
    /// rows, and the save, which the test watches.
    private func makeGroupsWithProfile(tox: OCTTox) throws -> (OCTSubmanagerGroupsImpl, PendingLeaveProfile) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("pending-leave-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        self.directory = directory

        // The app's copy of the class, which Realm takes its classes from (OwnCaptionOrderTests says why).
        let realmManagerClass: AnyClass = try XCTUnwrap(NSClassFromString("OCTRealmManager"), "no OCTRealmManager")
        let realmManager = try XCTUnwrap(unsafeBitCast(realmManagerClass, to: OCTRealmManager.Type.self)
            .init(databaseFileURL: directory.appendingPathComponent("profile.realm"), encryptionKey: nil))
        let profile = PendingLeaveProfile(tox: tox, realmManager: realmManager)
        let groups = OCTSubmanagerGroupsImpl()
        // The data source is weak: the test keeps it.
        groups.setValue(profile, forKey: "dataSource")
        self.profile = profile
        return (groups, profile)
    }
}

/// What the groups submanager asks its manager for around the pending leaves.
private final class PendingLeaveProfile: NSObject {
    let realmManager: OCTRealmManager
    var onSave: (() -> Void)?
    private let tox: OCTTox

    init(tox: OCTTox, realmManager: OCTRealmManager) {
        self.tox = tox
        self.realmManager = realmManager
        super.init()
    }

    @objc func managerGetTox() -> OCTTox {
        return tox
    }

    @objc func managerGetRealmManager() -> OCTRealmManager {
        return realmManager
    }

    @objc func managerSaveTox() {
        onSave?()
    }

    func storedPendingLeaves() -> String? {
        return realmManager.ngcValue(forKey: pendingLeavesStoreKey)
    }
}
