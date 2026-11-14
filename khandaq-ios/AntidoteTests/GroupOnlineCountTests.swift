// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 How many members of a group are online (QA-Live 14.11). The chat list, the header, the hint under the
 field and the members drawer all show the members online besides you, as the desktop and Android do.
 toxcore lists you among the confirmed peers of a group and never reports your own connection as NONE,
 so objcTox counted you too: with the only other member offline, the chat list read "1 online" while
 the header said nobody was.

 The count runs in objcTox's own groups submanager, against a stand-in for Tox that lists the peers of
 the group as toxcore does, you among them.
 */
final class GroupOnlineCountTests: XCTestCase {
    private let groups = OCTSubmanagerGroupsImpl()
    /// The submanager holds its data source weakly.
    private var dataSource: GroupPeersDataSource?

    override func tearDown() {
        dataSource = nil
        super.tearDown()
    }

    /// The live case: a member who is offline is not among the peers toxcore lists, so only you are.
    func testWithEveryoneElseOfflineNobodyIsOnline() throws {
        XCTAssertEqual(try onlineCount(selfPeerId: 0, peers: [(0, .UDP)]), 0, "you were counted as online")
    }

    func testTheOthersOnlineAreCountedAndYouAreNot() throws {
        // Peer 3 is no longer there when its connection is asked for.
        XCTAssertEqual(try onlineCount(selfPeerId: 0, peers: [(0, .TCP), (1, .UDP), (2, .TCP), (3, OCTToxConnectionStatus.none)]), 2)
    }

    /// You are left out by the peer id toxcore gives you, not by a place in the list.
    func testYouAreLeftOutByYourOwnPeerId() throws {
        XCTAssertEqual(try onlineCount(selfPeerId: 4, peers: [(1, .UDP), (4, .UDP), (6, .TCP)]), 2)
    }

    // MARK: - Helpers

    private func onlineCount(selfPeerId: UInt32, peers: [(peerId: UInt32, status: OCTToxConnectionStatus)]) throws -> Int32 {
        let dataSource = GroupPeersDataSource(tox: GroupPeersTox(selfPeerId: selfPeerId, peers: peers))
        self.dataSource = dataSource
        groups.setValue(dataSource, forKey: "dataSource")

        // objcTox and Realm are linked into this test bundle as well as into the app, and Realm cannot
        // make a chat of the bundle's copy, the class Swift names here: it looks up the class of the
        // chat's friends by name and finds the app's. So the chat is made of the app's class too.
        let chatClass: AnyClass = try XCTUnwrap(NSClassFromString("OCTChat"), "no OCTChat")
        let chat = unsafeBitCast(chatClass, to: OCTChat.Type.self).init()
        chat.isGroup = true
        chat.groupNumber = 3
        // The bridging header declares OCTSubmanagerGroupsImpl without the protocol it adopts.
        let counter = try XCTUnwrap(groups as? OCTSubmanagerGroups, "OCTSubmanagerGroupsImpl counts no members")
        return counter.onlineGroupPeerCount(for: chat)
    }
}

/// Stands in for OCTTox: the peers of the group, as toxcore lists them, and their connections.
private final class GroupPeersTox: NSObject {
    private let selfPeerId: UInt32
    private let peers: [(peerId: UInt32, status: OCTToxConnectionStatus)]

    init(selfPeerId: UInt32, peers: [(peerId: UInt32, status: OCTToxConnectionStatus)]) {
        self.selfPeerId = selfPeerId
        self.peers = peers
        super.init()
    }

    @objc(groupSelfPeerIdForGroupNumber:error:)
    func groupSelfPeerId(groupNumber: OCTToxGroupNumber, error: NSErrorPointer) -> UInt32 {
        return selfPeerId
    }

    @objc(groupPeersForGroupNumber:error:)
    func groupPeers(groupNumber: OCTToxGroupNumber, error: NSErrorPointer) -> [NSDictionary] {
        return peers.map { ["peerId": NSNumber(value: $0.peerId)] as NSDictionary }
    }

    @objc(groupPeerConnectionStatusForGroupNumber:peerId:error:)
    func groupPeerConnectionStatus(groupNumber: OCTToxGroupNumber, peerId: UInt32, error: NSErrorPointer) -> OCTToxConnectionStatus {
        return peers.first { $0.peerId == peerId }?.status ?? OCTToxConnectionStatus.none
    }
}

/// What OCTSubmanagerGroupsImpl asks of OCTManager to count the members online.
private final class GroupPeersDataSource: NSObject {
    private let tox: GroupPeersTox

    init(tox: GroupPeersTox) {
        self.tox = tox
        super.init()
    }

    @objc func managerGetTox() -> NSObject {
        return tox
    }
}
