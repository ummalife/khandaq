// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CryptoKit
import Foundation
import UIKit

// What the conversation screen remembers that the Tox profile and its history do not: the hours a
// contact was seen online (the day strip), "mute" and "key verified" from the contact dossier, the
// messages the network refused and the keys the person blocked. All of it stays on this device and
// outside Realm, so the profile, the history and the queue of outgoing messages keep their formats, and
// nothing of it goes over the wire.

extension Notification.Name {
    static let airLocalStoreDidChange = Notification.Name("KhandaqAirLocalStoreDidChange")
    static let airFailedSendsDidChange = Notification.Name("KhandaqAirFailedSendsDidChange")
    static let airGroupInvitesDidChange = Notification.Name("KhandaqAirGroupInvitesDidChange")
}

/// A message the network refused outright (not one waiting for the person: those are in Realm and
/// in the tray). objcTox does not store such a message, so it is kept in air-local.json until it is
/// sent again or dropped, and survives a restart.
struct AirFailedSend: Equatable {
    let id: String
    /// The text as it went to the network, with the reply header if it was a reply.
    let text: String
    let date: Date
}

/// An invitation to a group, kept as a card in Requests until it is answered (E12). toxcore hands
/// it over once, so it is kept in the sealed part of air-local.json and survives a restart.
struct AirGroupInvite: Equatable {
    let id: String
    /// The contact who sent it; their friend number is looked up again when it is accepted.
    let inviterKey: String
    let groupName: String
    /// toxcore's invite, as it came.
    let data: Data
    let date: Date
}

// MARK: - Sealing

/// The history in Realm is encrypted with the profile, air-local.json is not. So the part of the file
/// that says something about people (the text of refused messages and the blocked keys) is sealed.
protocol AirSealing {
    func seal(_ data: Data, keyId: String) -> Data?
    func open(_ data: Data, keyId: String) -> AirUnsealed
}

enum AirUnsealed: Equatable {
    case opened(Data)
    /// The key cannot be read right now (the keychain is locked): the sealed part is kept for later.
    case unavailable
    /// The key is gone (a backup restored on another device) or does not open the box: what was sealed
    /// cannot come back, so the store starts a new sealed part instead of guarding a dead one.
    case lost
}

/// AES-GCM under a 256-bit key per file, created on first use and kept in this device's keychain only
/// (no iCloud, no backups). A backup restored on another device keeps the profile and loses the
/// refused messages and the block list; nothing else in the file depends on the key.
final class AirKeychainSealing: AirSealing {
    static let service = "org.khandaq.messenger.AirLocalStore"

    func seal(_ data: Data, keyId: String) -> Data? {
        guard let key = key(keyId, create: true), let box = try? AES.GCM.seal(data, using: key) else {
            return nil
        }
        return box.combined
    }

    func open(_ data: Data, keyId: String) -> AirUnsealed {
        var missing = false
        guard let key = key(keyId, create: false, missing: &missing) else {
            return missing ? .lost : .unavailable
        }
        guard let box = try? AES.GCM.SealedBox(combined: data), let plain = try? AES.GCM.open(box, using: key) else {
            return .lost
        }
        return .opened(plain)
    }

    private func key(_ keyId: String, create: Bool) -> SymmetricKey? {
        var missing = false
        return key(keyId, create: create, missing: &missing)
    }

    /// `missing` tells a key that is not in the keychain from a keychain that cannot be read now.
    private func key(_ keyId: String, create: Bool, missing: inout Bool) -> SymmetricKey? {
        var item: [String: AnyObject] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: AirKeychainSealing.service as AnyObject,
            kSecAttrAccount as String: keyId as AnyObject,
        ]
        var query = item
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecReturnData as String] = kCFBooleanTrue
        var result: AnyObject?
        let status = withUnsafeMutablePointer(to: &result) {
            SecItemCopyMatching(query as CFDictionary, UnsafeMutablePointer($0))
        }
        if status == errSecSuccess, let data = result as? Data, data.count == 32 {
            return SymmetricKey(data: data)
        }
        guard status == errSecItemNotFound else {
            log("AirKeychainSealing: keychain read failed, status \(status)")
            return nil
        }
        missing = true
        guard create else {
            return nil
        }
        let key = SymmetricKey(size: .bits256)
        item[kSecValueData as String] = key.withUnsafeBytes { Data($0) } as AnyObject
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let added = SecItemAdd(item as CFDictionary, nil)
        guard added == errSecSuccess else {
            log("AirKeychainSealing: keychain write failed, status \(added)")
            return nil
        }
        return key
    }
}

// MARK: - The store

/// Per-contact marks kept in a small JSON file next to the profile (air-local.json). Contacts are
/// filed under a hash of their key, hours are the starts of local clock hours in seconds since 1970 and
/// are kept for seven days. Refused messages (by chat) and blocked keys sit in one sealed part of the
/// same file. Used from the main thread only; the file is written in the background.
final class AirLocalStore {
    static let shared = AirLocalStore()
    static let fileName = "air-local.json"
    static let keepSeconds: TimeInterval = 7 * 86400

    private struct Contact: Codable {
        var hours: [Int] = []
        var muted: Bool = false
        var verifiedAt: Double?
        /// When the request of a contact made by accepting it came, in seconds since 1970 (askedAt).
        var askedAt: Double?
    }

    private struct FailedRecord: Codable {
        let id: String
        let text: String
        let date: Double
    }

    private struct InviteRecord: Codable {
        let id: String
        let inviterKey: String
        let groupName: String
        let data: String
        let date: Double
    }

    /// The sealed part. Blocked keys are upper-case, 64 hex characters, in the order they were blocked.
    private struct Secrets: Codable {
        var failed: [String: [FailedRecord]] = [:]
        var blocked: [String] = []
        var invites: [InviteRecord] = []

        init() {
        }

        /// A part sealed before a field existed still opens: what it lacks starts empty.
        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            failed = try container.decodeIfPresent([String: [FailedRecord]].self, forKey: .failed) ?? [:]
            blocked = try container.decodeIfPresent([String].self, forKey: .blocked) ?? []
            invites = try container.decodeIfPresent([InviteRecord].self, forKey: .invites) ?? []
        }

        var isEmpty: Bool {
            return failed.isEmpty && blocked.isEmpty && invites.isEmpty
        }
    }

    /// The file since the second stage of the redesign: version 1 was the bare contacts dictionary.
    private struct FileContents: Codable {
        var version: Int
        var contacts: [String: Contact]
        /// Names the keychain item of the sealing key; it is not a secret.
        var keyId: String?
        /// Base64 of the sealed Secrets.
        var sealed: String?
    }

    private var contacts: [String: Contact] = [:]
    private var secrets = Secrets()
    private var keyId: String?
    /// A sealed part this run could not open (the keychain was locked or the key is gone): written
    /// back unchanged, so a bad moment never destroys what is in it.
    private var unreadableSealed: String?
    /// When this device saw each contact come online; memory only, a restart starts it again.
    private var onlineSince: [String: Date] = [:]
    private var fileURL: URL?
    private var isDemo = false
    private let sealing: AirSealing
    private let writeQueue = DispatchQueue(label: "khandaq.air.local-store", qos: .utility)

    init(sealing: AirSealing = AirKeychainSealing()) {
        self.sealing = sealing
    }

    /// Reads the marks of the profile in `directory`; a different profile drops what was in memory.
    func attach(directory: URL) {
        guard !isDemo else {
            return
        }
        let url = directory.appendingPathComponent(AirLocalStore.fileName)
        guard url != fileURL else {
            return
        }
        fileURL = url
        onlineSince = [:]
        contacts = [:]
        secrets = Secrets()
        keyId = nil
        unreadableSealed = nil
        if let data = try? Data(contentsOf: url) {
            read(data)
        }
        pruneHours(now: Date())
        notify()
    }

    func detach() {
        guard !isDemo else {
            return
        }
        fileURL = nil
        contacts = [:]
        secrets = Secrets()
        keyId = nil
        unreadableSealed = nil
        onlineSince = [:]
    }

    /// Waits for the writes already queued; for tests and for a clean shutdown.
    func flush() {
        writeQueue.sync {}
    }

    /// Demo marks for the UI tests' mock profile: held in memory, never written, and from then on an
    /// attach is ignored, so the demo cannot touch a real profile's file.
    func seedDemo(hours: [String: [Date]], onlineSince since: [String: Date], muted: Set<String>, verified: [String: Date]) {
        isDemo = true
        fileURL = nil
        contacts = [:]
        secrets = Secrets()
        onlineSince = [:]
        let calendar = Calendar.current
        for (publicKey, dates) in hours {
            var contact = Contact()
            contact.hours = dates.compactMap { calendar.dateInterval(of: .hour, for: $0).map { Int($0.start.timeIntervalSince1970) } }
            contacts[AirLocalStore.hashedKey(publicKey)] = contact
        }
        for (publicKey, date) in since {
            onlineSince[AirLocalStore.hashedKey(publicKey)] = date
        }
        for publicKey in muted {
            contacts[AirLocalStore.hashedKey(publicKey), default: Contact()].muted = true
        }
        for (publicKey, date) in verified {
            contacts[AirLocalStore.hashedKey(publicKey), default: Contact()].verifiedAt = date.timeIntervalSince1970
        }
        notify()
    }

    /// The file never names a contact: 16 bytes of SHA-256 over the upper-cased key.
    static func hashedKey(_ publicKey: String) -> String {
        let digest = SHA256.hash(data: Data(publicKey.uppercased().utf8))
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    // MARK: Presence

    /// Marks the clock hour of `date` as an hour the contact was online. Writes only for a new hour.
    func markOnline(publicKey: String, at date: Date, calendar: Calendar = Calendar.current) {
        guard let start = calendar.dateInterval(of: .hour, for: date)?.start else {
            return
        }
        let key = AirLocalStore.hashedKey(publicKey)
        let value = Int(start.timeIntervalSince1970)
        var contact = contacts[key] ?? Contact()
        guard !contact.hours.contains(value) else {
            return
        }
        let oldest = Int(date.timeIntervalSince1970 - AirLocalStore.keepSeconds)
        contact.hours = contact.hours.filter { $0 >= oldest } + [value]
        contacts[key] = contact
        save()
        notify()
    }

    func hours(publicKey: String) -> Set<Int> {
        return Set(contacts[AirLocalStore.hashedKey(publicKey)]?.hours ?? [])
    }

    func noteConnected(publicKey: String, at date: Date) {
        let key = AirLocalStore.hashedKey(publicKey)
        guard onlineSince[key] == nil else {
            return
        }
        onlineSince[key] = date
        notify()
    }

    func noteDisconnected(publicKey: String) {
        if onlineSince.removeValue(forKey: AirLocalStore.hashedKey(publicKey)) != nil {
            notify()
        }
    }

    func onlineSince(publicKey: String) -> Date? {
        return onlineSince[AirLocalStore.hashedKey(publicKey)]
    }

    // MARK: Marks from the dossier

    func isMuted(publicKey: String) -> Bool {
        return contacts[AirLocalStore.hashedKey(publicKey)]?.muted ?? false
    }

    func setMuted(_ muted: Bool, publicKey: String) {
        let key = AirLocalStore.hashedKey(publicKey)
        var contact = contacts[key] ?? Contact()
        guard contact.muted != muted else {
            return
        }
        contact.muted = muted
        contacts[key] = contact
        save()
        notify()
    }

    func verifiedAt(publicKey: String) -> Date? {
        return contacts[AirLocalStore.hashedKey(publicKey)]?.verifiedAt.map { Date(timeIntervalSince1970: $0) }
    }

    func setVerified(_ date: Date?, publicKey: String) {
        let key = AirLocalStore.hashedKey(publicKey)
        var contact = contacts[key] ?? Contact()
        contact.verifiedAt = date?.timeIntervalSince1970
        contacts[key] = contact
        save()
        notify()
    }

    // MARK: Accepted requests

    /// When the request of a contact made by accepting it came: they were online then, and no request
    /// of ours waits for them. Nil for a contact made by our own request. Kept until the contact is
    /// removed.
    func askedAt(publicKey: String) -> Date? {
        return contacts[AirLocalStore.hashedKey(publicKey)]?.askedAt.map { Date(timeIntervalSince1970: $0) }
    }

    /// KHANDAQ (QA 2026-11-14, F2): the contact was made by accepting their request, which came at
    /// `date`. Until they first connect toxcore has no last-online time for them, which the list read
    /// as our own request waiting for a reply; that they asked is known only here.
    func noteAccepted(publicKey: String, askedAt date: Date) {
        let key = AirLocalStore.hashedKey(publicKey)
        var contact = contacts[key] ?? Contact()
        contact.askedAt = date.timeIntervalSince1970
        contacts[key] = contact
        save()
        notify()
    }

    /// Forgets the marks of a contact, when the contact itself is removed. A block stays: it is about
    /// the key, not the contact.
    func forget(publicKey: String) {
        let key = AirLocalStore.hashedKey(publicKey)
        onlineSince.removeValue(forKey: key)
        guard contacts.removeValue(forKey: key) != nil else {
            return
        }
        save()
        notify()
    }

    // MARK: Refused messages

    func failedSends(chatId: String) -> [AirFailedSend] {
        return (secrets.failed[chatId] ?? []).map {
            AirFailedSend(id: $0.id, text: $0.text, date: Date(timeIntervalSince1970: $0.date))
        }
    }

    func addFailedSend(_ item: AirFailedSend, chatId: String) {
        secrets.failed[chatId, default: []].append(FailedRecord(id: item.id, text: item.text, date: item.date.timeIntervalSince1970))
        save()
    }

    @discardableResult
    func removeFailedSend(id: String, chatId: String) -> Bool {
        guard var list = secrets.failed[chatId], let index = list.firstIndex(where: { $0.id == id }) else {
            return false
        }
        list.remove(at: index)
        secrets.failed[chatId] = list.isEmpty ? nil : list
        save()
        return true
    }

    @discardableResult
    func removeFailedSends(chatId: String) -> Bool {
        guard secrets.failed.removeValue(forKey: chatId) != nil else {
            return false
        }
        save()
        return true
    }

    // MARK: Group invitations

    var groupInvites: [AirGroupInvite] {
        return secrets.invites.compactMap { record in
            guard let data = Data(base64Encoded: record.data) else {
                return nil
            }
            return AirGroupInvite(id: record.id, inviterKey: record.inviterKey, groupName: record.groupName,
                                  data: data, date: Date(timeIntervalSince1970: record.date))
        }
    }

    /// A second invitation from the same person to a group of the same name replaces the first.
    func addGroupInvite(_ invite: AirGroupInvite) {
        let key = AirLocalStore.normalizedKey(invite.inviterKey)
        secrets.invites.removeAll { $0.inviterKey == key && $0.groupName == invite.groupName }
        secrets.invites.append(InviteRecord(id: invite.id, inviterKey: key, groupName: invite.groupName,
                                            data: invite.data.base64EncodedString(), date: invite.date.timeIntervalSince1970))
        save()
    }

    @discardableResult
    func removeGroupInvite(id: String) -> Bool {
        guard let index = secrets.invites.firstIndex(where: { $0.id == id }) else {
            return false
        }
        secrets.invites.remove(at: index)
        save()
        return true
    }

    /// Invitations from a contact who is gone cannot be accepted: toxcore joins through that contact.
    @discardableResult
    func removeGroupInvites(inviterKey: String) -> Bool {
        let key = AirLocalStore.normalizedKey(inviterKey)
        let before = secrets.invites.count
        secrets.invites.removeAll { $0.inviterKey == key }
        guard secrets.invites.count != before else {
            return false
        }
        save()
        return true
    }

    // MARK: Block list

    /// Blocked keys, 64 hex characters in upper case, oldest first.
    var blockedKeys: [String] {
        return secrets.blocked
    }

    func isBlocked(publicKey: String) -> Bool {
        return secrets.blocked.contains(AirLocalStore.normalizedKey(publicKey))
    }

    func block(publicKey: String) {
        let key = AirLocalStore.normalizedKey(publicKey)
        guard AirLocalStore.isPublicKey(key), !secrets.blocked.contains(key) else {
            return
        }
        secrets.blocked.append(key)
        save()
        notify()
    }

    func unblock(publicKey: String) {
        let key = AirLocalStore.normalizedKey(publicKey)
        guard let index = secrets.blocked.firstIndex(of: key) else {
            return
        }
        secrets.blocked.remove(at: index)
        save()
        notify()
    }

    static func normalizedKey(_ publicKey: String) -> String {
        return publicKey.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
    }

    static func isPublicKey(_ key: String) -> Bool {
        return key.count == 64 && key.unicodeScalars.allSatisfy { CharacterSet(charactersIn: "0123456789ABCDEF").contains($0) }
    }

    // MARK: Private

    private func read(_ data: Data) {
        let decoder = JSONDecoder()
        guard let file = try? decoder.decode(FileContents.self, from: data) else {
            // Version 1: the contacts alone.
            contacts = (try? decoder.decode([String: Contact].self, from: data)) ?? [:]
            return
        }
        contacts = file.contacts
        keyId = file.keyId
        guard let sealed = file.sealed else {
            return
        }
        let unsealed: AirUnsealed
        if let keyId = file.keyId, let box = Data(base64Encoded: sealed) {
            unsealed = sealing.open(box, keyId: keyId)
        }
        else {
            unsealed = .lost
        }
        switch unsealed {
        case .opened(let plain):
            if let opened = try? decoder.decode(Secrets.self, from: plain) {
                secrets = opened
            }
            else {
                dropSealed()
            }
        case .unavailable:
            unreadableSealed = sealed
            log("AirLocalStore: the sealed part of \(AirLocalStore.fileName) cannot be opened now; it is kept as it is")
        case .lost:
            dropSealed()
        }
    }

    /// The sealed part can never be opened again: the next write starts a new one under a new key, so
    /// what is blocked or refused from now on is kept.
    private func dropSealed() {
        keyId = nil
        log("AirLocalStore: the sealed part of \(AirLocalStore.fileName) cannot be opened with this device's key; refused messages and blocked keys from before are lost")
    }

    private func pruneHours(now: Date) {
        let oldest = Int(now.timeIntervalSince1970 - AirLocalStore.keepSeconds)
        for (key, contact) in contacts {
            var kept = contact
            kept.hours = contact.hours.filter { $0 >= oldest }
            if kept.hours.isEmpty && !kept.muted && kept.verifiedAt == nil && kept.askedAt == nil {
                contacts.removeValue(forKey: key)
            }
            else {
                contacts[key] = kept
            }
        }
    }

    private func save() {
        guard !isDemo, let url = fileURL else {
            return
        }
        if keyId == nil && !secrets.isEmpty {
            keyId = UUID().uuidString
        }
        var file = FileContents(version: 2, contacts: contacts, keyId: keyId, sealed: nil)
        let secretsSnapshot = secrets
        let keep = unreadableSealed
        let sealing = self.sealing
        writeQueue.async {
            if let keep = keep {
                file.sealed = keep
            }
            else if !secretsSnapshot.isEmpty {
                guard let keyId = file.keyId,
                      let plain = try? JSONEncoder().encode(secretsSnapshot),
                      let box = sealing.seal(plain, keyId: keyId) else {
                    // Writing without the sealed part would lose it: the file keeps its last version.
                    log("AirLocalStore: cannot seal, \(url.lastPathComponent) is not rewritten")
                    return
                }
                file.sealed = box.base64EncodedString()
            }
            guard let data = try? JSONEncoder().encode(file) else {
                return
            }
            do {
                try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            }
            catch {
                log("AirLocalStore: cannot write \(url.lastPathComponent): \(error)")
            }
        }
    }

    private func notify() {
        NotificationCenter.default.post(name: .airLocalStoreDidChange, object: self)
    }
}

// MARK: - Accepting a request

extension OCTSubmanagerFriends {
    /// Accepts a friend request, and the new contact keeps the time it came (AirLocalStore.askedAt), so
    /// it is never shown as our own request waiting for a reply. Every Accept goes through here.
    func approveKeepingItsTime(_ request: OCTFriendRequest, store: AirLocalStore = .shared) throws {
        // Accepting deletes the request: what is needed of it is read first.
        let publicKey = request.publicKey
        let date = request.date()
        try approve(request)
        store.noteAccepted(publicKey: publicKey, askedAt: date)
    }
}

/// Watches the contacts while a profile is running and marks every hour each one is seen online. A
/// friend who stays online across an hour has no Realm change at the turn of the hour, so a timer
/// marks the current hour as well.
final class AirPresenceRecorder {
    static let tick: TimeInterval = 300

    private let friends: Results<OCTFriend>
    private var token: RLMNotificationToken?
    private var timer: Timer?
    private let store: AirLocalStore

    init(submanagerObjects: OCTSubmanagerObjects, store: AirLocalStore = AirLocalStore.shared) {
        self.friends = submanagerObjects.friends()
        self.store = store
    }

    deinit {
        stop()
    }

    func start() {
        guard token == nil else {
            return
        }
        token = friends.addNotificationBlock { [weak self] _ in
            self?.record()
        }
        timer = Timer.scheduledTimer(withTimeInterval: AirPresenceRecorder.tick, repeats: true) { [weak self] _ in
            self?.record()
        }
        record()
    }

    func stop() {
        token?.invalidate()
        token = nil
        timer?.invalidate()
        timer = nil
    }

    private func record() {
        let now = Date()
        for index in 0..<friends.count {
            guard let friend = friends.object(at: index), !friend.isInvalidated else {
                continue
            }
            if friend.isConnected {
                store.markOnline(publicKey: friend.publicKey, at: now)
                store.noteConnected(publicKey: friend.publicKey, at: now)
            }
            else {
                store.noteDisconnected(publicKey: friend.publicKey)
            }
        }
    }
}

/// Requests from a blocked key are never shown. Tox has no block, so this device drops such a request
/// the moment it lands, the way "Decline" drops one (nothing is sent back), and the list, the badges
/// and the notifications skip blocked keys in the meantime.
final class AirBlockGuard {
    private let requests: Results<OCTFriendRequest>
    private let submanagerFriends: OCTSubmanagerFriends
    private let store: AirLocalStore
    private var token: RLMNotificationToken?
    private var observer: NSObjectProtocol?

    init(submanagerObjects: OCTSubmanagerObjects, submanagerFriends: OCTSubmanagerFriends, store: AirLocalStore = AirLocalStore.shared) {
        self.requests = submanagerObjects.friendRequests()
        self.submanagerFriends = submanagerFriends
        self.store = store
    }

    deinit {
        stop()
    }

    func start() {
        guard token == nil else {
            return
        }
        token = requests.addNotificationBlock { [weak self] _ in
            // Out of the notification itself: dropping a request is a Realm write.
            DispatchQueue.main.async {
                self?.sweep()
            }
        }
        observer = NotificationCenter.default.addObserver(forName: .airLocalStoreDidChange, object: store, queue: .main) { [weak self] _ in
            self?.sweep()
        }
        sweep()
    }

    func stop() {
        token?.invalidate()
        token = nil
        if let observer = observer {
            NotificationCenter.default.removeObserver(observer)
        }
        observer = nil
    }

    /// Which of the waiting requests to drop: the positions of every key on the block list, in order.
    static func blockedPositions(publicKeys: [String], store: AirLocalStore) -> [Int] {
        return publicKeys.indices.filter { store.isBlocked(publicKey: publicKeys[$0]) }
    }

    private func sweep() {
        let waiting = (0..<requests.count).map { requests[$0] }.filter { !$0.isInvalidated }
        let positions = AirBlockGuard.blockedPositions(publicKeys: waiting.map { $0.publicKey }, store: store)
        for position in positions where !waiting[position].isInvalidated {
            submanagerFriends.remove(waiting[position])
        }
    }
}

/// Refused messages by chat, kept in the sealed part of air-local.json until "Retry" sends them or
/// the person drops them; removing the contact drops them too (AirContactRemoval).
final class AirFailedSends {
    static let shared = AirFailedSends(store: AirLocalStore.shared)

    private let store: AirLocalStore

    /// Without a store of the profile the list lives in memory only (the store is never attached).
    init(store: AirLocalStore = AirLocalStore()) {
        self.store = store
    }

    func items(forChat chatId: String) -> [AirFailedSend] {
        return store.failedSends(chatId: chatId)
    }

    @discardableResult
    func add(text: String, date: Date = Date(), toChat chatId: String) -> AirFailedSend {
        let item = AirFailedSend(id: UUID().uuidString, text: text, date: date)
        store.addFailedSend(item, chatId: chatId)
        notify(chatId)
        return item
    }

    func remove(id: String, fromChat chatId: String) {
        if store.removeFailedSend(id: id, chatId: chatId) {
            notify(chatId)
        }
    }

    func removeAll(fromChat chatId: String) {
        if store.removeFailedSends(chatId: chatId) {
            notify(chatId)
        }
    }

    private func notify(_ chatId: String) {
        NotificationCenter.default.post(name: .airFailedSendsDidChange, object: self, userInfo: ["chatId": chatId])
    }

    /// KHANDAQ (QA-Live 2026-11-13): a shared text that toxcore refuses waits in its chat with "Retry",
    /// as a text from the composer does (ChatPrivateController.onSendFailed). The share showed an error
    /// alert instead, and that alert comes while the "Send to" sheet is still going away: when it finds
    /// no screen to stand on, the shared text is gone without a word. True when the text was kept; false
    /// when the chat has no person left to send to again, and the caller shows the error as before.
    @discardableResult
    func keepRefusedShare(_ text: String, toChat chatId: String, hasPerson: Bool) -> Bool {
        guard hasPerson, !text.isEmpty else {
            return false
        }
        add(text: text, toChat: chatId)
        return true
    }
}

/// Invitations to groups waiting in Requests (E12).
final class AirGroupInvites {
    static let shared = AirGroupInvites(store: AirLocalStore.shared)

    private let store: AirLocalStore

    init(store: AirLocalStore = AirLocalStore()) {
        self.store = store
    }

    var items: [AirGroupInvite] {
        return store.groupInvites.sorted { $0.date > $1.date }
    }

    func item(id: String) -> AirGroupInvite? {
        return store.groupInvites.first { $0.id == id }
    }

    @discardableResult
    func add(inviterKey: String, groupName: String, data: Data, date: Date = Date()) -> AirGroupInvite {
        let invite = AirGroupInvite(id: UUID().uuidString, inviterKey: inviterKey, groupName: groupName, data: data, date: date)
        store.addGroupInvite(invite)
        notify()
        return invite
    }

    func remove(id: String) {
        if store.removeGroupInvite(id: id) {
            notify()
        }
    }

    func removeAll(from inviterKey: String) {
        if store.removeGroupInvites(inviterKey: inviterKey) {
            notify()
        }
    }

    private func notify() {
        NotificationCenter.default.post(name: .airGroupInvitesDidChange, object: self)
    }
}

/// Removing a contact, the same from the list, the old contacts screen and the dossier: the contact
/// goes, and with them the conversation and what this device kept about them outside Realm.
enum AirContactRemoval {
    static func remove(_ friend: OCTFriend, submanagerChats: OCTSubmanagerChats, submanagerFriends: OCTSubmanagerFriends) {
        guard !friend.isInvalidated else {
            return
        }
        let publicKey = friend.publicKey
        let chat: OCTChat? = submanagerChats.getOrCreateChat(with: friend)
        let chatId = chat?.uniqueIdentifier
        do {
            try submanagerFriends.remove(friend)
        }
        catch let error as NSError {
            handleErrorWithType(.removeFriend, error: error)
            return
        }
        if let chat = chat, !chat.isInvalidated {
            submanagerChats.removeAllMessages(in: chat, removeChat: true)
        }
        AirLocalStore.shared.forget(publicKey: publicKey)
        AirGroupInvites.shared.removeAll(from: publicKey)
        if let chatId = chatId {
            AirFailedSends.shared.removeAll(fromChat: chatId)
            ChatPinStore.clearPin(forChat: chatId)
        }
    }

    /// Runs `work` once the navigation controller has finished the transition it is in, so the screen
    /// being left no longer shows the objects `work` removes.
    static func afterTransition(of navigation: UINavigationController?, run work: @escaping () -> Void) {
        guard let coordinator = navigation?.transitionCoordinator else {
            DispatchQueue.main.async(execute: work)
            return
        }
        coordinator.animate(alongsideTransition: nil) { _ in
            DispatchQueue.main.async(execute: work)
        }
    }
}
