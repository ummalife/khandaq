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

    // MARK: Gone at once (QA live 14.11: on a TCP relay the QUIT was lost, and the member stayed for 54 s)

    /// Public keys of two members, 64 hex digits each (named so that gitleaks does not take them for secrets).
    private let leaver = String(repeating: "B3A1C9D2E4F50617", count: 4)
    private let newcomer = String(repeating: "0A1B2C3D4E5F6071", count: 4)

    func testTheNoticeTakesTheMemberOutAtOnce() {
        XCTAssertFalse(groups.hasGroupLeaveNotices(forChatKey: "chat-5"))
        XCTAssertTrue(groups.noteGroupLeaveNotice(forChatKey: "chat-5", peerId: 3, publicKeyHex: leaver, at: 1_000))

        XCTAssertTrue(groups.hasGroupLeaveNotices(forChatKey: "chat-5"))
        // toxcore still lists the member until its exit; here it is out already, whatever the case of its key.
        XCTAssertTrue(groups.isGroupPeerLeaving(forChatKey: "chat-5", peerId: 3, publicKeyHex: leaver, at: 1_001))
        XCTAssertTrue(groups.isGroupPeerLeaving(forChatKey: "chat-5", peerId: 3,
                                                publicKeyHex: leaver.lowercased(), at: 1_054))
        // A key toxcore could not give is taken for the member on the id.
        XCTAssertTrue(groups.isGroupPeerLeaving(forChatKey: "chat-5", peerId: 3, publicKeyHex: nil, at: 1_001))
        // The other members stay, and so does the member in its other groups.
        XCTAssertFalse(groups.isGroupPeerLeaving(forChatKey: "chat-5", peerId: 4, publicKeyHex: newcomer, at: 1_001))
        XCTAssertFalse(groups.isGroupPeerLeaving(forChatKey: "chat-6", peerId: 3, publicKeyHex: leaver, at: 1_001))
        XCTAssertFalse(groups.hasGroupLeaveNotices(forChatKey: "chat-6"))
    }

    /// toxcore gives a freed id to the next peer: that one has another key and is a member.
    func testAnotherMemberOnTheIdIsNotLeaving() {
        groups.noteGroupLeaveNotice(forChatKey: "chat-7", peerId: 3, publicKeyHex: leaver, at: 1_000)
        XCTAssertFalse(groups.isGroupPeerLeaving(forChatKey: "chat-7", peerId: 3, publicKeyHex: newcomer, at: 1_001))
        // Its own notice on the id is news again.
        XCTAssertTrue(groups.noteGroupLeaveNotice(forChatKey: "chat-7", peerId: 3, publicKeyHex: newcomer, at: 1_002))
        XCTAssertTrue(groups.isGroupPeerLeaving(forChatKey: "chat-7", peerId: 3, publicKeyHex: newcomer, at: 1_003))
    }

    /// The group is told "left" once: a repeated notice is not news, and keeps the first time, so it cannot
    /// keep a member hidden past the window.
    func testARepeatedNoticeIsNotNewsAndKeepsItsTime() {
        XCTAssertTrue(groups.noteGroupLeaveNotice(forChatKey: "chat-8", peerId: 2, publicKeyHex: leaver, at: 100))
        XCTAssertFalse(groups.noteGroupLeaveNotice(forChatKey: "chat-8", peerId: 2, publicKeyHex: leaver, at: 200))
        XCTAssertTrue(groups.isGroupPeerLeaving(forChatKey: "chat-8", peerId: 2, publicKeyHex: leaver, at: 100 + 300))
        XCTAssertFalse(groups.isGroupPeerLeaving(forChatKey: "chat-8", peerId: 2, publicKeyHex: leaver, at: 100 + 301))
    }

    /// No exit in five minutes: the member said it was leaving and stayed, and is shown again.
    func testANoticeWithNoExitLapses() {
        XCTAssertTrue(groups.noteGroupLeaveNotice(forChatKey: "chat-9", peerId: 5, publicKeyHex: leaver, at: 100))
        XCTAssertFalse(groups.isGroupPeerLeaving(forChatKey: "chat-9", peerId: 5, publicKeyHex: leaver, at: 401))
        // The time went back: the notice is not taken as pending.
        XCTAssertFalse(groups.isGroupPeerLeaving(forChatKey: "chat-9", peerId: 5, publicKeyHex: leaver, at: 99))
        // A notice after the lapse is news, and the lapsed one is let go.
        XCTAssertTrue(groups.noteGroupLeaveNotice(forChatKey: "chat-9", peerId: 5, publicKeyHex: leaver, at: 500))
        XCTAssertTrue(groups.isGroupPeerLeaving(forChatKey: "chat-9", peerId: 5, publicKeyHex: leaver, at: 501))
    }

    /// A member that said it was leaving and joins again is a member again, on whatever id it has now.
    func testAJoinOfTheKeyIsAMemberAgain() {
        groups.noteGroupLeaveNotice(forChatKey: "chat-10", peerId: 3, publicKeyHex: leaver, at: 1_000)
        groups.noteGroupLeaveNotice(forChatKey: "chat-10", peerId: 4, publicKeyHex: newcomer, at: 1_000)
        groups.noteGroupLeaveNotice(forChatKey: "chat-11", peerId: 3, publicKeyHex: leaver, at: 1_000)

        groups.forgetGroupLeaveNotices(forChatKey: "chat-10", publicKeyHex: leaver.lowercased())

        XCTAssertFalse(groups.isGroupPeerLeaving(forChatKey: "chat-10", peerId: 3, publicKeyHex: leaver, at: 1_001))
        XCTAssertFalse(groups.takeGroupLeaveNotice(forChatKey: "chat-10", peerId: 3, at: 1_002, publicKeyHex: nil))
        // The other member's notice and the member's notice in another group stay.
        XCTAssertTrue(groups.isGroupPeerLeaving(forChatKey: "chat-10", peerId: 4, publicKeyHex: newcomer, at: 1_001))
        XCTAssertTrue(groups.isGroupPeerLeaving(forChatKey: "chat-11", peerId: 3, publicKeyHex: leaver, at: 1_001))
        // A join whose key toxcore could not give drops nothing.
        groups.forgetGroupLeaveNotices(forChatKey: "chat-10", publicKeyHex: nil)
        XCTAssertTrue(groups.isGroupPeerLeaving(forChatKey: "chat-10", peerId: 4, publicKeyHex: newcomer, at: 1_001))
    }

    /// The exit within the window, the QUIT or the timeout of a lost one, uses the notice up: the member is gone
    /// from toxcore's list by then, and nothing is left on record.
    func testTheExitUsesTheNoticeUp() {
        groups.noteGroupLeaveNotice(forChatKey: "chat-12", peerId: 6, publicKeyHex: leaver, at: 1_000)
        XCTAssertTrue(groups.takeGroupLeaveNotice(forChatKey: "chat-12", peerId: 6, at: 1_054, publicKeyHex: nil))
        XCTAssertFalse(groups.isGroupPeerLeaving(forChatKey: "chat-12", peerId: 6, publicKeyHex: leaver, at: 1_055))
        XCTAssertFalse(groups.hasGroupLeaveNotices(forChatKey: "chat-12"))
    }

    /// "Left" is told at the notice. The exit after it adds nothing; without a notice only a kick is told.
    func testTheExitAfterANoticeTellsNothingMore() {
        for exit: OCTToxGroupExitType in [.quit, .timeout, .disconnected, .kick] {
            XCTAssertNil(OCTSubmanagerGroupsImpl.groupExitSystemMessageFormatKey(exit, saidItWasLeaving: true),
                         "exit \(exit.rawValue)")
        }
        XCTAssertEqual(OCTSubmanagerGroupsImpl.groupExitSystemMessageFormatKey(.kick, saidItWasLeaving: false),
                       "group_system_peer_kicked")
        // A bare QUIT is an app that closed, a timeout a lost connection: no message, as before.
        XCTAssertNil(OCTSubmanagerGroupsImpl.groupExitSystemMessageFormatKey(.quit, saidItWasLeaving: false))
        XCTAssertNil(OCTSubmanagerGroupsImpl.groupExitSystemMessageFormatKey(.timeout, saidItWasLeaving: false))
    }
}
