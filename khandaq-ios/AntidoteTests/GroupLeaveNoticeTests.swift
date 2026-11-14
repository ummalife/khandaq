// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 A member who leaves a group and a member whose app closes (QA-Live 14.11, F11). toxcore sends the same
 QUIT in both cases: tox_kill sends it for every group, with no part message. So iOS said "left the group"
 of everyone who closed the app, and Android dropped them from the members. The member that leaves now
 says so first, with the leave notice (magic | 0x01 | 0x44, the same bytes on Android and the desktop), and
 only an exit after a notice, or a kick, ends the membership.
 */
final class GroupLeaveNoticeTests: XCTestCase {
    private let groups = OCTSubmanagerGroupsImpl()

    func testTheNoticeIsTheNgcHeaderWithId0x44() {
        let packet: Data = OCTSubmanagerGroupsImpl.groupLeaveNoticePacket()
        XCTAssertEqual([UInt8](packet), [0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x44])
        XCTAssertTrue(OCTSubmanagerGroupsImpl.isGroupLeaveNoticePacket(packet))
        // A later version may add bytes after the header.
        XCTAssertTrue(OCTSubmanagerGroupsImpl.isGroupLeaveNoticePacket(packet + Data([0x00, 0x01])))
    }

    func testOtherNgcPacketsAreNotANotice() {
        let notice: [UInt8] = [UInt8](OCTSubmanagerGroupsImpl.groupLeaveNoticePacket() as Data)
        XCTAssertFalse(OCTSubmanagerGroupsImpl.isGroupLeaveNoticePacket(Data(notice.prefix(7))))
        for id: UInt8 in [0x01, 0x11, 0x21, 0x31, 0x41, 0x42, 0x43, 0x45, 0x50] {
            var other = notice
            other[7] = id
            XCTAssertFalse(OCTSubmanagerGroupsImpl.isGroupLeaveNoticePacket(Data(other)), "id \(id)")
        }
        var version2 = notice
        version2[6] = 0x02
        XCTAssertFalse(OCTSubmanagerGroupsImpl.isGroupLeaveNoticePacket(Data(version2)))
    }

    /// The live case: Windows closed its window, and iOS said QA Windows had left the group.
    func testAQuitAloneIsAnAppThatClosed() {
        XCTAssertFalse(OCTSubmanagerGroupsImpl.groupExitEndsMembership(.quit, saidItWasLeaving: false))
        XCTAssertFalse(OCTSubmanagerGroupsImpl.groupExitEndsMembership(.timeout, saidItWasLeaving: false))
        XCTAssertFalse(OCTSubmanagerGroupsImpl.groupExitEndsMembership(.disconnected, saidItWasLeaving: false))
        XCTAssertFalse(OCTSubmanagerGroupsImpl.groupExitEndsMembership(.selfDisconnected, saidItWasLeaving: false))
        XCTAssertFalse(OCTSubmanagerGroupsImpl.groupExitEndsMembership(.syncError, saidItWasLeaving: false))
    }

    func testAKickOrAnExitAfterANoticeEndsTheMembership() {
        XCTAssertTrue(OCTSubmanagerGroupsImpl.groupExitEndsMembership(.kick, saidItWasLeaving: false))
        XCTAssertTrue(OCTSubmanagerGroupsImpl.groupExitEndsMembership(.quit, saidItWasLeaving: true))
        // The QUIT itself can be lost; the member then times out, and it still left.
        XCTAssertTrue(OCTSubmanagerGroupsImpl.groupExitEndsMembership(.timeout, saidItWasLeaving: true))
    }

    func testANoticeCountsOnceForTheExitThatFollowsIt() {
        // A member's public key, 64 hex digits (named so that gitleaks does not take it for a secret).
        let member = String(repeating: "B3A1C9D2E4F50617", count: 4)
        groups.noteGroupLeaveNotice(forChatKey: "chat-1", peerId: 4, publicKeyHex: member, at: 1_000)

        var pubkey: NSString?
        XCTAssertTrue(groups.takeGroupLeaveNotice(forChatKey: "chat-1", peerId: 4, at: 1_002, publicKeyHex: &pubkey))
        XCTAssertEqual(pubkey as String?, member)
        // Used up: the same member closing the app later is not a leave.
        XCTAssertFalse(groups.takeGroupLeaveNotice(forChatKey: "chat-1", peerId: 4, at: 1_003, publicKeyHex: nil))
    }

    func testANoticeIsForItsChatAndItsPeerOnly() {
        groups.noteGroupLeaveNotice(forChatKey: "chat-2", peerId: 1, publicKeyHex: nil, at: 50)
        XCTAssertFalse(groups.takeGroupLeaveNotice(forChatKey: "chat-2", peerId: 2, at: 51, publicKeyHex: nil))
        XCTAssertFalse(groups.takeGroupLeaveNotice(forChatKey: "chat-3", peerId: 1, at: 51, publicKeyHex: nil))
        XCTAssertTrue(groups.takeGroupLeaveNotice(forChatKey: "chat-2", peerId: 1, at: 51, publicKeyHex: nil))
    }

    func testAnOldNoticeOrOneDroppedByAJoinDoesNotCount() {
        groups.noteGroupLeaveNotice(forChatKey: "chat-4", peerId: 7, publicKeyHex: nil, at: 100)
        XCTAssertFalse(groups.takeGroupLeaveNotice(forChatKey: "chat-4", peerId: 7, at: 100 + 301, publicKeyHex: nil))

        groups.noteGroupLeaveNotice(forChatKey: "chat-4", peerId: 7, publicKeyHex: nil, at: 500)
        // Another peer joined on the id before any exit used the notice.
        groups.forgetGroupLeaveNotice(forChatKey: "chat-4", peerId: 7)
        XCTAssertFalse(groups.takeGroupLeaveNotice(forChatKey: "chat-4", peerId: 7, at: 501, publicKeyHex: nil))
    }
}
