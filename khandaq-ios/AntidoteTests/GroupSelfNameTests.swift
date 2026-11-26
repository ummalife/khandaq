// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Your own name in a group (tester 21.11, 8257). The tester renamed themselves in a group from "AZ iOS" to "AZ iOS1",
 and after a restart of the app the group had "AZ iOS" again. toxcore keeps the name you use in each group in the
 profile's save, and nothing saved the profile after a rename: the next start loaded the save of before, and the
 members got the old name from us again. A rename now saves the profile, and the name comes back from that save.
 */
final class GroupSelfNameTests: XCTestCase {
    private var bed: GroupTestBed?

    override func tearDown() {
        bed?.remove()
        bed = nil
        super.tearDown()
    }

    func testARenameSavesTheProfileWithTheNewName() throws {
        let bed = try makeBed()
        var namesAtSave = [String]()
        bed.profile.onSave = {
            namesAtSave.append((try? bed.tox.groupSelfName(forGroupNumber: bed.group)) ?? "?")
        }

        try bed.groups.setGroupSelfPeerName("AZ iOS1", for: bed.chat)

        XCTAssertEqual(namesAtSave, ["AZ iOS1"], "the profile was not saved after the rename")
        XCTAssertEqual(try bed.groups.groupSelfPeerName(for: bed.chat), "AZ iOS1")
    }

    /// The start after it: a Tox made from that save has the new name in the group.
    func testTheNewNameComesBackFromTheSave() throws {
        let bed = try makeBed()
        var saved: Data?
        bed.profile.onSave = {
            saved = bed.tox.save()
        }

        try bed.groups.setGroupSelfPeerName("AZ iOS1", for: bed.chat)

        let restarted = try GroupTestBed.makeTox(savedData: XCTUnwrap(saved, "nothing was saved"))
        XCTAssertEqual(try restarted.groupSelfName(forGroupNumber: bed.group), "AZ iOS1")
    }

    /// A name toxcore refuses changes nothing, and there is nothing to save.
    func testARefusedNameSavesNothing() throws {
        let bed = try makeBed()
        var saves = 0
        bed.profile.onSave = {
            saves += 1
        }

        XCTAssertThrowsError(try bed.groups.setGroupSelfPeerName(String(repeating: "A", count: 200), for: bed.chat))

        XCTAssertEqual(saves, 0)
        XCTAssertEqual(try bed.tox.groupSelfName(forGroupNumber: bed.group), "AZ iOS")
    }

    private func makeBed() throws -> GroupTestBed {
        let bed = try GroupTestBed(peerName: "AZ iOS")
        self.bed = bed
        return bed
    }
}

/**
 The groups submanager of objcTox with what it asks its manager for: a Tox of the test's own, without the network,
 with one private group (nothing is announced); the group's chat in a Realm of the test's own; and the save of the
 profile, which the test watches. Every objcTox object is of the app's copy of its class, which Realm takes its
 classes from (OwnCaptionOrderTests says why); an object the bed hands out is read by key or through the declared
 interfaces.
 */
final class GroupTestBed {
    let directory: URL
    let tox: OCTTox
    let group: OCTToxGroupNumber
    let realmManager: OCTRealmManager
    let chat: OCTChat
    let profile: GroupTestProfile
    /// The submanager; a restart makes a new one.
    private(set) var impl: OCTSubmanagerGroupsImpl

    var groups: OCTSubmanagerGroups {
        // The app's copy of the class conforms to the protocol, which the runtime knows by its name.
        return impl as AnyObject as! OCTSubmanagerGroups
    }

    init(peerName: String, groupName: String = "Tester 21.11") throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("group-test-bed-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)

        let tox = try GroupTestBed.makeTox(savedData: nil)
        var error: NSError?
        let group = tox.groupNew(with: .private, groupName: groupName, peerName: peerName, error: &error)
        if let error = error {
            throw error
        }
        guard group != kOCTToxGroupNumberFailure else {
            throw NSError(domain: "GroupTestBed", code: 2, userInfo: [NSLocalizedDescriptionKey: "no group"])
        }

        let realmManagerClass = try GroupTestBed.appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
        let realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("profile.realm"),
                                                                encryptionKey: nil))
        let chatIdHex = try tox.groupChatIdHex(forGroupNumber: group).uppercased()
        let profile = GroupTestProfile(tox: tox, realmManager: realmManager)

        self.directory = directory
        self.tox = tox
        self.group = group
        self.realmManager = realmManager
        self.chat = realmManager.getOrCreateGroupChat(withGroupNumber: group,
                                                      chatIdHex: chatIdHex,
                                                      groupName: groupName,
                                                      privacyState: .private)
        self.profile = profile
        self.impl = try GroupTestBed.makeGroups(profile: profile)
    }

    /// The submanager made again, as at a start of the app: what it kept in memory is gone, the profile's rows stay.
    func restart() throws {
        impl = try GroupTestBed.makeGroups(profile: profile)
    }

    func remove() {
        try? FileManager.default.removeItem(at: directory)
    }

    static func makeTox(savedData: Data?) throws -> OCTTox {
        let options = try appClass("OCTToxOptions", as: OCTToxOptions.Type.self).init()
        options.udpEnabled = false
        options.ipv6Enabled = false
        options.localDiscoveryEnabled = false
        return try appClass("OCTTox", as: OCTTox.Type.self).init(options: options, savedData: savedData)
    }

    static func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        guard let cls = NSClassFromString(name) else {
            throw NSError(domain: "GroupTestBed", code: 1, userInfo: [NSLocalizedDescriptionKey: "no class \(name)"])
        }
        return unsafeBitCast(cls, to: type)
    }

    private static func makeGroups(profile: GroupTestProfile) throws -> OCTSubmanagerGroupsImpl {
        let groups = try appClass("OCTSubmanagerGroupsImpl", as: OCTSubmanagerGroupsImpl.Type.self).init()
        // The data source is weak: the bed keeps it.
        groups.setValue(profile, forKey: "dataSource")
        return groups
    }
}

/// What the groups submanager asks its manager for (OCTSubmanagerDataSource).
final class GroupTestProfile: NSObject {
    let realmManager: OCTRealmManager
    var onSave: (() -> Void)?
    private let tox: OCTTox
    private let notificationCenter = NotificationCenter()

    init(tox: OCTTox, realmManager: OCTRealmManager) {
        self.tox = tox
        self.realmManager = realmManager
        super.init()
    }

    @objc func managerGetTox() -> OCTTox {
        return tox
    }

    @objc func managerIsToxConnected() -> Bool {
        return false
    }

    @objc func managerSaveTox() {
        onSave?()
    }

    @objc func managerGetRealmManager() -> OCTRealmManager {
        return realmManager
    }

    @objc func managerGetNotificationCenter() -> NotificationCenter {
        return notificationCenter
    }

    @objc func managerUseFauxOfflineMessaging() -> Bool {
        return false
    }
}
