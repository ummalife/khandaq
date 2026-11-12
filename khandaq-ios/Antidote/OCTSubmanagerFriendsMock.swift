// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

class OCTSubmanagerFriendsMock: NSObject, OCTSubmanagerFriends {
    /// The demo world's store: with it, accepting, declining and removing change the demo as they
    /// change a real profile. Without it every call does nothing, as before.
    var realm: RLMRealm?

    func sendFriendRequest(toAddress address: String!, message: String!) throws {
        // nop
    }
    
    func addFriend(byPublicKey publicKey: String!) throws {
        // nop
    }

    func approve(_ friendRequest: OCTFriendRequest!) throws {
        guard let realm = realm, let request = friendRequest, !request.isInvalidated else {
            return
        }
        realm.beginWriteTransaction()
        let friend = OCTFriend()
        friend.publicKey = request.publicKey
        // A request reaches you only while both of you are online, so the person is usually still
        // there when it is accepted: the demo connects them and their name arrives.
        let name = request.publicKey == OCTManagerMock.demoRequestKey ? OCTManagerMock.storeText("demo_s3_timur") : ""
        friend.nickname = name
        friend.name = name
        friend.connectionStatus = .UDP
        friend.isConnected = true
        friend.lastSeenOnlineInterval = Date().timeIntervalSince1970
        friend.status = .none
        realm.add(friend)
        realm.delete(request)
        try? realm.commitWriteTransaction()
    }
    
    func remove(_ friendRequest: OCTFriendRequest!) {
        guard let realm = realm, let request = friendRequest, !request.isInvalidated else {
            return
        }
        realm.beginWriteTransaction()
        realm.delete(request)
        try? realm.commitWriteTransaction()
    }
    
    func remove(_ friend: OCTFriend!) throws {
        guard let realm = realm, let friend = friend, !friend.isInvalidated else {
            return
        }
        realm.beginWriteTransaction()
        realm.delete(friend)
        try? realm.commitWriteTransaction()
    }

    func refreshConnectionStatuses() {
        // nop
    }

    func ensureFriend(forFriendNumber friendNumber: OCTToxFriendNumber) -> OCTFriend! {
        return nil
    }
}
