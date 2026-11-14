// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CryptoKit
import XCTest
@testable import Antidote

/**
 air-local.json since stage 3: refused messages survive a restart until they are retried or dropped,
 the block list stays when the contact goes, and both sit in a sealed part of the file that never
 shows a message or a key in the clear.
 */
class AirLocalStoreTests: XCTestCase {
    private let aminaKey = "7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71"
    private let yusufKey = "0B1C2D3E4F5061728394A5B6C7D8E9F00112233445566778899AABBCCDDEEFF0"
    private var directories: [URL] = []

    override func tearDown() {
        directories.forEach { try? FileManager.default.removeItem(at: $0) }
        directories = []
        super.tearDown()
    }

    func testRefusedMessagesComeBackAfterARestartSealed() {
        let directory = makeDirectory()
        let sealing = FixedKeySealing()
        let first = AirLocalStore(sealing: sealing)
        first.attach(directory: directory)
        let sends = AirFailedSends(store: first)
        let date = Date(timeIntervalSince1970: 1_762_850_000)
        let kept = sends.add(text: "Meet at the station at 5:45", date: date, toChat: "chat-amina")
        sends.add(text: "Second one", date: date.addingTimeInterval(60), toChat: "chat-amina")
        sends.add(text: "Somewhere else", date: date, toChat: "chat-yusuf")
        first.flush()

        let json = fileText(directory)
        XCTAssertFalse(json.contains("station"), "a refused message is never in the clear")
        XCTAssertTrue(json.contains("\"sealed\""))

        // A new launch: the same profile directory, a new store.
        let second = AirLocalStore(sealing: sealing)
        second.attach(directory: directory)
        let again = AirFailedSends(store: second)
        let items = again.items(forChat: "chat-amina")
        XCTAssertEqual(items.map { $0.text }, ["Meet at the station at 5:45", "Second one"])
        XCTAssertEqual(items.first?.id, kept.id)
        XCTAssertEqual(items.first?.date, date)
        XCTAssertEqual(again.items(forChat: "chat-yusuf").count, 1)

        // "Retry" took the first one, the other was dropped: nothing is left after the next launch.
        again.remove(id: kept.id, fromChat: "chat-amina")
        again.remove(id: items[1].id, fromChat: "chat-amina")
        second.flush()
        let third = AirLocalStore(sealing: sealing)
        third.attach(directory: directory)
        XCTAssertTrue(third.failedSends(chatId: "chat-amina").isEmpty)
        XCTAssertEqual(third.failedSends(chatId: "chat-yusuf").count, 1)
    }

    func testRemovingAContactDropsItsRefusedMessagesAndAKeyStaysBlocked() {
        let directory = makeDirectory()
        let sealing = FixedKeySealing()
        let store = AirLocalStore(sealing: sealing)
        store.attach(directory: directory)
        let sends = AirFailedSends(store: store)
        sends.add(text: "Are you there?", toChat: "chat-yusuf")
        store.setMuted(true, publicKey: yusufKey)

        // What "Block" does to the store: the key goes on the list, then the contact is removed the way
        // "Remove" removes it (AirContactRemoval forgets the marks and drops the refused messages).
        store.block(publicKey: yusufKey)
        store.forget(publicKey: yusufKey)
        sends.removeAll(fromChat: "chat-yusuf")
        store.flush()

        let again = AirLocalStore(sealing: sealing)
        again.attach(directory: directory)
        XCTAssertTrue(again.failedSends(chatId: "chat-yusuf").isEmpty)
        XCTAssertFalse(again.isMuted(publicKey: yusufKey))
        XCTAssertTrue(again.isBlocked(publicKey: yusufKey))
    }

    func testTheBlockListTakesKeysOnlyAndKeepsThemSealed() {
        let directory = makeDirectory()
        let sealing = FixedKeySealing()
        let store = AirLocalStore(sealing: sealing)
        store.attach(directory: directory)
        store.block(publicKey: "  " + aminaKey.lowercased() + "\n")
        store.block(publicKey: aminaKey)
        // A whole ID (76 characters) or a short string is not a key.
        store.block(publicKey: aminaKey + "01234567ABCD")
        store.block(publicKey: "ABCD")
        store.block(publicKey: yusufKey)
        XCTAssertEqual(store.blockedKeys, [aminaKey, yusufKey])
        XCTAssertTrue(store.isBlocked(publicKey: aminaKey.lowercased()))
        store.flush()
        XCTAssertFalse(fileText(directory).contains(aminaKey), "a blocked key is never in the clear")

        store.unblock(publicKey: aminaKey.lowercased())
        store.flush()
        let again = AirLocalStore(sealing: sealing)
        again.attach(directory: directory)
        XCTAssertEqual(again.blockedKeys, [yusufKey])
        XCTAssertFalse(again.isBlocked(publicKey: aminaKey))
    }

    func testASealedPartThatCannotBeOpenedIsKeptAsItIs() {
        let directory = makeDirectory()
        let store = AirLocalStore(sealing: FixedKeySealing())
        store.attach(directory: directory)
        store.block(publicKey: aminaKey)
        store.flush()

        // The keychain is locked this time: the marks still work and a write must not throw the sealed
        // part away.
        let locked = AirLocalStore(sealing: LockedSealing())
        locked.attach(directory: directory)
        XCTAssertTrue(locked.blockedKeys.isEmpty)
        locked.markOnline(publicKey: yusufKey, at: Date())
        locked.flush()

        let unlocked = AirLocalStore(sealing: FixedKeySealing())
        unlocked.attach(directory: directory)
        XCTAssertEqual(unlocked.blockedKeys, [aminaKey])
        XCTAssertEqual(unlocked.hours(publicKey: yusufKey).count, 1)
    }

    func testASealedPartWhoseKeyIsGoneMakesWayForANewOne() {
        let directory = makeDirectory()
        let store = AirLocalStore(sealing: FixedKeySealing())
        store.attach(directory: directory)
        store.block(publicKey: aminaKey)
        store.setMuted(true, publicKey: aminaKey)
        store.flush()

        // A backup restored on another phone: the file came along, its key did not. The old part is
        // lost, and a block made on the new phone must survive the next launch.
        let restored = AirLocalStore(sealing: FixedKeySealing(byte: 9))
        restored.attach(directory: directory)
        XCTAssertTrue(restored.blockedKeys.isEmpty)
        XCTAssertTrue(restored.isMuted(publicKey: aminaKey), "the marks do not depend on the key")
        restored.block(publicKey: yusufKey)
        restored.flush()

        let again = AirLocalStore(sealing: FixedKeySealing(byte: 9))
        again.attach(directory: directory)
        XCTAssertEqual(again.blockedKeys, [yusufKey])
        XCTAssertTrue(again.isMuted(publicKey: aminaKey))
    }

    func testAFileOfTheSecondStageStillLoads() throws {
        let directory = makeDirectory()
        let hour = Int(Calendar.current.dateInterval(of: .hour, for: Date())!.start.timeIntervalSince1970)
        let legacy = "{\"\(AirLocalStore.hashedKey(aminaKey))\":{\"hours\":[\(hour)],\"muted\":true}}"
        try Data(legacy.utf8).write(to: directory.appendingPathComponent(AirLocalStore.fileName))
        let store = AirLocalStore(sealing: FixedKeySealing())
        store.attach(directory: directory)
        XCTAssertEqual(store.hours(publicKey: aminaKey), [hour])
        XCTAssertTrue(store.isMuted(publicKey: aminaKey))
        XCTAssertTrue(store.blockedKeys.isEmpty)
    }

    func testTheKeychainSealOpensWhatItSealed() {
        let sealing = AirKeychainSealing()
        let keyId = "test-" + UUID().uuidString
        defer {
            let query: [String: AnyObject] = [
                kSecClass as String: kSecClassGenericPassword,
                kSecAttrService as String: AirKeychainSealing.service as AnyObject,
                kSecAttrAccount as String: keyId as AnyObject,
            ]
            SecItemDelete(query as CFDictionary)
        }
        let plain = Data("Встречаемся у станции".utf8)
        XCTAssertEqual(sealing.open(Data(count: 40), keyId: keyId), .lost, "no key yet, nothing opens")
        guard let box = sealing.seal(plain, keyId: keyId) else {
            return XCTFail("the keychain refused the sealing key")
        }
        XCTAssertNotEqual(box, plain)
        XCTAssertEqual(sealing.open(box, keyId: keyId), .opened(plain))
        XCTAssertEqual(sealing.open(Data(box.reversed()), keyId: keyId), .lost, "a damaged box does not open")
    }

    func testRequestsFromABlockedKeyAreDroppedAndOthersStay() {
        let store = AirLocalStore(sealing: FixedKeySealing())
        let waiting = [aminaKey, yusufKey.lowercased(), aminaKey, yusufKey]
        XCTAssertTrue(AirBlockGuard.blockedPositions(publicKeys: waiting, store: store).isEmpty)

        store.block(publicKey: yusufKey)
        XCTAssertEqual(AirBlockGuard.blockedPositions(publicKeys: waiting, store: store), [1, 3])

        store.unblock(publicKey: yusufKey)
        XCTAssertTrue(AirBlockGuard.blockedPositions(publicKeys: waiting, store: store).isEmpty)
    }

    /// KHANDAQ (QA 2026-11-14, F2): accepting a request keeps the time it came with the new contact,
    /// across launches and the weekly pruning and without naming the contact, until the contact is
    /// removed. A contact made by our own request has no such time, and a refused Accept leaves none.
    func testAnAcceptedRequestKeepsItsTimeAcrossLaunchesUntilTheContactGoes() throws {
        let directory = makeDirectory()
        let sealing = FixedKeySealing()
        let store = AirLocalStore(sealing: sealing)
        store.attach(directory: directory)
        // More than a week ago: a launch prunes every hour mark that old.
        let came = Date(timeIntervalSince1970: 1_762_850_000)

        try OCTSubmanagerFriendsMock().approveKeepingItsTime(friendRequest(aminaKey.lowercased(), came: came), store: store)
        XCTAssertEqual(store.askedAt(publicKey: aminaKey), came)
        XCTAssertNil(store.askedAt(publicKey: yusufKey), "a contact made by our own request has no such time")

        XCTAssertThrowsError(try RefusingFriends().approveKeepingItsTime(friendRequest(yusufKey, came: came), store: store))
        XCTAssertNil(store.askedAt(publicKey: yusufKey), "a refused Accept makes no contact")
        store.flush()
        XCTAssertFalse(fileText(directory).uppercased().contains(aminaKey), "the file never names a contact, in any case")

        let again = AirLocalStore(sealing: sealing)
        again.attach(directory: directory)
        XCTAssertEqual(again.askedAt(publicKey: aminaKey), came)

        again.forget(publicKey: aminaKey)
        XCTAssertNil(again.askedAt(publicKey: aminaKey))
        again.flush()
        let third = AirLocalStore(sealing: sealing)
        third.attach(directory: directory)
        XCTAssertNil(third.askedAt(publicKey: aminaKey))
    }

    func testGroupInvitationsWaitSealedUntilAnswered() {
        let directory = makeDirectory()
        let sealing = FixedKeySealing()
        let store = AirLocalStore(sealing: sealing)
        store.attach(directory: directory)
        let invites = AirGroupInvites(store: store)
        let date = Date(timeIntervalSince1970: 1_762_850_000)
        invites.add(inviterKey: aminaKey.lowercased(), groupName: "Book club", data: Data([1, 2, 3]), date: date)
        invites.add(inviterKey: yusufKey, groupName: "Family", data: Data([4]), date: date.addingTimeInterval(60))
        // The same group from the same person again: the newer invitation takes the place of the old.
        let newer = invites.add(inviterKey: aminaKey, groupName: "Book club", data: Data([5, 6]), date: date.addingTimeInterval(120))
        XCTAssertEqual(invites.items.count, 2)
        store.flush()
        let json = fileText(directory)
        XCTAssertFalse(json.contains("Book club"), "an invitation is never in the clear")
        XCTAssertFalse(json.contains(aminaKey))

        // A new launch: the invitation is still there, newest first, as toxcore handed it over.
        let second = AirLocalStore(sealing: sealing)
        second.attach(directory: directory)
        let again = AirGroupInvites(store: second)
        XCTAssertEqual(again.items.map { $0.groupName }, ["Book club", "Family"])
        XCTAssertEqual(again.item(id: newer.id)?.data, Data([5, 6]))
        XCTAssertEqual(again.item(id: newer.id)?.inviterKey, aminaKey)
        XCTAssertEqual(again.item(id: newer.id)?.date, date.addingTimeInterval(120))

        // Removing Amina drops what she sent; joining or declining drops the other one.
        again.removeAll(from: aminaKey.lowercased())
        XCTAssertEqual(again.items.map { $0.groupName }, ["Family"])
        again.remove(id: again.items[0].id)
        second.flush()
        let third = AirLocalStore(sealing: sealing)
        third.attach(directory: directory)
        XCTAssertTrue(AirGroupInvites(store: third).items.isEmpty)
    }

    func testASealedPartWrittenBeforeInvitationsStillOpens() throws {
        let directory = makeDirectory()
        let sealing = FixedKeySealing()
        let early = "{\"failed\":{\"chat-amina\":[{\"id\":\"one\",\"text\":\"Hello\",\"date\":1762850000}]},\"blocked\":[\"\(yusufKey)\"]}"
        guard let box = sealing.seal(Data(early.utf8), keyId: "early") else {
            return XCTFail("no seal")
        }
        let file = "{\"version\":2,\"contacts\":{},\"keyId\":\"early\",\"sealed\":\"\(box.base64EncodedString())\"}"
        try Data(file.utf8).write(to: directory.appendingPathComponent(AirLocalStore.fileName))

        let store = AirLocalStore(sealing: sealing)
        store.attach(directory: directory)
        XCTAssertEqual(store.failedSends(chatId: "chat-amina").map { $0.text }, ["Hello"])
        XCTAssertEqual(store.blockedKeys, [yusufKey])
        XCTAssertTrue(AirGroupInvites(store: store).items.isEmpty)
    }

    // MARK: - Helpers

    private func makeDirectory() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("air-local-" + UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        directories.append(url)
        return url
    }

    private func fileText(_ directory: URL) -> String {
        let data = (try? Data(contentsOf: directory.appendingPathComponent(AirLocalStore.fileName))) ?? Data()
        return String(decoding: data, as: UTF8.self)
    }

    /// A request as objcTox keeps it, outside any Realm.
    private func friendRequest(_ publicKey: String, came: Date) -> OCTFriendRequest {
        let request = OCTFriendRequest()
        request.publicKey = publicKey
        request.dateInterval = came.timeIntervalSince1970
        return request
    }
}

/// A friends submanager whose Accept toxcore refuses.
private final class RefusingFriends: OCTSubmanagerFriendsMock {
    override func approve(_ friendRequest: OCTFriendRequest!) throws {
        throw NSError(domain: "AirLocalStoreTests", code: 1)
    }
}

/// AES-GCM under a fixed key, so a test can read back what it wrote without the keychain.
private struct FixedKeySealing: AirSealing {
    private let key: SymmetricKey

    init(byte: UInt8 = 7) {
        key = SymmetricKey(data: Data(repeating: byte, count: 32))
    }

    func seal(_ data: Data, keyId: String) -> Data? {
        guard let box = try? AES.GCM.seal(data, using: key) else {
            return nil
        }
        return box.combined
    }

    func open(_ data: Data, keyId: String) -> AirUnsealed {
        guard let box = try? AES.GCM.SealedBox(combined: data), let plain = try? AES.GCM.open(box, using: key) else {
            return .lost
        }
        return .opened(plain)
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
