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

/// KHANDAQ (0.2.60): an invitation of ours to a group for a contact who was offline. toxcore sends one only
/// over the link with the contact, so it waits here, in the sealed part of air-local.json, and goes once both
/// are online (AirOutgoingInviteSender). It goes away once sent or refused, with the contact or the group, and
/// AirLocalStore.outgoingInviteKeepSeconds after it was asked for.
struct AirOutgoingInvite: Equatable {
    /// 64 hexadecimal characters in upper case: the contact, and the chat id of the group.
    let friendKey: String
    let groupKey: String
    /// When it was asked for.
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
    /// KHANDAQ (0.2.60): thirty days: a contact away for longer is invited again by hand.
    static let outgoingInviteKeepSeconds: TimeInterval = 30 * 86400
    /// The most of our invitations kept; the oldest make room.
    static let outgoingInviteLimit = 256

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

    private struct OutgoingInviteRecord: Codable {
        let friendKey: String
        let groupKey: String
        let date: Double
    }

    /// The sealed part. Blocked keys are upper-case, 64 hex characters, in the order they were blocked.
    private struct Secrets: Codable {
        var failed: [String: [FailedRecord]] = [:]
        var blocked: [String] = []
        var invites: [InviteRecord] = []
        /// KHANDAQ (0.2.60): ours, waiting for their contacts, oldest first.
        var outgoingInvites: [OutgoingInviteRecord] = []

        init() {
        }

        /// A part sealed before a field existed still opens: what it lacks starts empty.
        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            failed = try container.decodeIfPresent([String: [FailedRecord]].self, forKey: .failed) ?? [:]
            blocked = try container.decodeIfPresent([String].self, forKey: .blocked) ?? []
            invites = try container.decodeIfPresent([InviteRecord].self, forKey: .invites) ?? []
            outgoingInvites = try container.decodeIfPresent([OutgoingInviteRecord].self, forKey: .outgoingInvites) ?? []
        }

        var isEmpty: Bool {
            return failed.isEmpty && blocked.isEmpty && invites.isEmpty && outgoingInvites.isEmpty
        }
    }

    /// KHANDAQ (0.2.57): Khandaq Community (SPEC-community, part B), outside the sealed part: the marks say
    /// nothing about anybody, and a moment the keychain is locked must not lose them. They only become true.
    private struct CommunityMarks: Codable {
        var autoJoinDone = false
        var left = false
        /// KHANDAQ (0.2.60): KhandaqCommunity.restoreSound is done.
        var soundRestored = false

        init() {
        }

        /// Marks written by a later version with fields this one lacks, or the other way round, still open; the
        /// "holdSince" of a build of 0.2.57 before its release is let go.
        init(from decoder: Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            autoJoinDone = try container.decodeIfPresent(Bool.self, forKey: .autoJoinDone) ?? false
            left = try container.decodeIfPresent(Bool.self, forKey: .left) ?? false
            soundRestored = try container.decodeIfPresent(Bool.self, forKey: .soundRestored) ?? false
        }

        var isEmpty: Bool {
            return !autoJoinDone && !left && !soundRestored
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
        /// Absent in a file of 0.2.56 and older, whose profile then joins Khandaq Community once like a new one.
        var community: CommunityMarks?
    }

    private var contacts: [String: Contact] = [:]
    private var community = CommunityMarks()
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
        community = CommunityMarks()
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
        community = CommunityMarks()
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
    /// the key, not the contact. KHANDAQ (0.2.60): our invitations waiting for the contact go too.
    func forget(publicKey: String) {
        let key = AirLocalStore.hashedKey(publicKey)
        onlineSince.removeValue(forKey: key)
        let friend = AirLocalStore.normalizedKey(publicKey)
        let invitesBefore = secrets.outgoingInvites.count
        secrets.outgoingInvites.removeAll { $0.friendKey == friend }
        let droppedInvites = secrets.outgoingInvites.count != invitesBefore
        let hadMarks = contacts.removeValue(forKey: key) != nil
        guard hadMarks || droppedInvites else {
            return
        }
        save()
        if hadMarks {
            notify()
        }
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

    // MARK: Our invitations waiting for a contact (KHANDAQ 0.2.60)

    /// Oldest first.
    var outgoingInvites: [AirOutgoingInvite] {
        return secrets.outgoingInvites.map {
            AirOutgoingInvite(friendKey: $0.friendKey, groupKey: $0.groupKey, date: Date(timeIntervalSince1970: $0.date))
        }
    }

    /// Keeps an invitation of ours until it can go; false for keys that are not keys, an invitation kept
    /// already (it keeps its date) or one asked for more than outgoingInviteKeepSeconds before `now`.
    @discardableResult
    func keepOutgoingInvite(friendKey: String, groupKey: String, at date: Date, now: Date = Date()) -> Bool {
        let friend = AirLocalStore.normalizedKey(friendKey)
        let group = AirLocalStore.normalizedKey(groupKey)
        guard AirLocalStore.isPublicKey(friend), AirLocalStore.isPublicKey(group),
              now.timeIntervalSince(date) <= AirLocalStore.outgoingInviteKeepSeconds,
              !secrets.outgoingInvites.contains(where: { $0.friendKey == friend && $0.groupKey == group }) else {
            return false
        }
        secrets.outgoingInvites.append(OutgoingInviteRecord(friendKey: friend, groupKey: group, date: date.timeIntervalSince1970))
        let excess = secrets.outgoingInvites.count - AirLocalStore.outgoingInviteLimit
        if excess > 0 {
            secrets.outgoingInvites.removeFirst(excess)
        }
        save()
        return true
    }

    func hasOutgoingInvite(friendKey: String, groupKey: String) -> Bool {
        let friend = AirLocalStore.normalizedKey(friendKey)
        let group = AirLocalStore.normalizedKey(groupKey)
        return secrets.outgoingInvites.contains { $0.friendKey == friend && $0.groupKey == group }
    }

    /// Sent or refused: it goes.
    @discardableResult
    func dropOutgoingInvite(friendKey: String, groupKey: String) -> Bool {
        let friend = AirLocalStore.normalizedKey(friendKey)
        let group = AirLocalStore.normalizedKey(groupKey)
        return dropOutgoingInvites { $0.friendKey == friend && $0.groupKey == group }
    }

    /// The group was left: our invitations to it go.
    @discardableResult
    func dropOutgoingInvites(groupKey: String) -> Bool {
        let group = AirLocalStore.normalizedKey(groupKey)
        return dropOutgoingInvites { $0.groupKey == group }
    }

    /// Drops the invitations asked for more than outgoingInviteKeepSeconds before `now`.
    @discardableResult
    func pruneOutgoingInvites(now: Date = Date()) -> Bool {
        let oldest = now.timeIntervalSince1970 - AirLocalStore.outgoingInviteKeepSeconds
        return dropOutgoingInvites { $0.date < oldest }
    }

    private func dropOutgoingInvites(where shouldDrop: (OutgoingInviteRecord) -> Bool) -> Bool {
        let before = secrets.outgoingInvites.count
        secrets.outgoingInvites.removeAll(where: shouldDrop)
        guard secrets.outgoingInvites.count != before else {
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

    // MARK: Khandaq Community

    /// The profile joined Khandaq Community by itself once (SPEC-community, item 1).
    var communityAutoJoinDone: Bool {
        return community.autoJoinDone
    }

    /// The person left Khandaq Community: it is never joined by itself again (item 3).
    var communityLeft: Bool {
        return community.left
    }

    /// KHANDAQ (0.2.60): the sound the auto-join of 0.2.57 turned off is on again (KhandaqCommunity.restoreSound).
    var communitySoundRestored: Bool {
        return community.soundRestored
    }

    func setCommunityAutoJoinDone() {
        guard !community.autoJoinDone else {
            return
        }
        community.autoJoinDone = true
        save()
    }

    func setCommunitySoundRestored() {
        guard !community.soundRestored else {
            return
        }
        community.soundRestored = true
        save()
    }

    @discardableResult
    func setCommunityLeft() -> Bool {
        guard !community.left else {
            return false
        }
        community.left = true
        save()
        return true
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
        community = file.community ?? CommunityMarks()
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
        var file = FileContents(version: 2, contacts: contacts, keyId: keyId, sealed: nil,
                                community: community.isEmpty ? nil : community)
        let secretsSnapshot = secrets
        let keep = unreadableSealed
        let sealing = self.sealing
        writeQueue.async {
            if let keep = keep {
                file.sealed = keep
            }
            else if !secretsSnapshot.isEmpty {
                if let keyId = file.keyId,
                   let plain = try? JSONEncoder().encode(secretsSnapshot),
                   let box = sealing.seal(plain, keyId: keyId) {
                    file.sealed = box.base64EncodedString()
                }
                else {
                    // Writing without the sealed part would lose it: the file keeps the one it has, under its
                    // key, and the rest is written, the marks of Khandaq Community among them (a join or a leave
                    // not kept would be made again at the next start). What this run added to the sealed part
                    // goes with the next write that seals.
                    let onDisk = (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode(FileContents.self, from: $0) }
                    file.keyId = onDisk?.keyId
                    file.sealed = onDisk?.sealed
                    log("AirLocalStore: cannot seal, \(url.lastPathComponent) keeps its sealed part as it was")
                }
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

extension AirLocalStore: KhandaqCommunityMarks {
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

/// KHANDAQ (0.2.60): our invitations to groups (owner, 2026-11-18: a person is added to a group even when they
/// are offline). tox_group_invite_friend sends one at once over the link with the contact and toxcore keeps
/// none, so the invitation a contact offline now cannot take waits in the store and goes once both are online
/// (AirOutgoingInviteSender).
enum AirOutgoingInvites {
    /// A contact as an invitation that waits sees them.
    enum Contact: Equatable {
        /// Removed: their invitations go.
        case gone
        case offline
        case online(OCTToxFriendNumber)
    }

    /// A group as an invitation that waits sees it.
    enum Group: Equatable {
        /// Left: its invitations go.
        case gone
        /// Not with toxcore yet (no group number): its invitations wait.
        case waiting
        case ready(OCTToxGroupNumber)
    }

    /// "Invite" in the group dossier: the contacts chosen, to the group of `chat`.
    static func invite(_ candidates: [AirInviteCandidate],
                       to chat: OCTChat,
                       submanagerGroups: OCTSubmanagerGroups,
                       store: AirLocalStore = AirLocalStore.shared) -> AirInviteTally {
        let friends = candidates.filter { $0.canInvite }.map { (number: OCTToxFriendNumber($0.friendNumber), key: $0.publicKey) }
        return invite(friends: friends, to: chat, submanagerGroups: submanagerGroups, store: store)
    }

    /// "New group": the contacts chosen on its sheet, to the group just made.
    static func invite(_ friends: [OCTFriend],
                       to chat: OCTChat,
                       submanagerGroups: OCTSubmanagerGroups,
                       store: AirLocalStore = AirLocalStore.shared) -> AirInviteTally {
        let numbers = friends.filter { !$0.isInvalidated }.map { (number: $0.friendNumber, key: $0.publicKey) }
        return invite(friends: numbers, to: chat, submanagerGroups: submanagerGroups, store: store)
    }

    /// Invites `friends` (friend number and key) to the group `groupKey`, `groupNumber` in this session,
    /// through `send`; the invitation of one offline now is kept in `store`, one sent or refused goes from it.
    static func invite(friends: [(number: OCTToxFriendNumber, key: String)],
                       groupNumber: OCTToxGroupNumber,
                       groupKey: String,
                       store: AirLocalStore,
                       now: Date = Date(),
                       send: (OCTToxFriendNumber, OCTToxGroupNumber) throws -> Void) -> AirInviteTally {
        var tally = AirInviteTally()
        for friend in friends {
            guard friend.number >= 0 else {
                tally.refused += 1
                continue
            }
            var failure: NSError?
            do {
                try send(friend.number, groupNumber)
            }
            catch let error as NSError {
                failure = error
            }
            switch AirInviteOutcome(error: failure) {
                case .sent:
                    tally.sent += 1
                    // One kept from before goes too: it has just been sent.
                    store.dropOutgoingInvite(friendKey: friend.key, groupKey: groupKey)
                case .later:
                    store.keepOutgoingInvite(friendKey: friend.key, groupKey: groupKey, at: now, now: now)
                    if store.hasOutgoingInvite(friendKey: friend.key, groupKey: groupKey) {
                        tally.later += 1
                    }
                    else {
                        // Keys that are not keys cannot be kept: nothing would ever send it.
                        tally.refused += 1
                        tally.firstRefusal = tally.firstRefusal ?? failure
                    }
                case .refused:
                    tally.refused += 1
                    tally.firstRefusal = tally.firstRefusal ?? failure
                    store.dropOutgoingInvite(friendKey: friend.key, groupKey: groupKey)
            }
        }
        return tally
    }

    /// Sends the invitations kept in `store` whose contact is online now and whose group is with toxcore.
    /// Those sent and those refused go, as do those of a contact or a group gone and those older than
    /// AirLocalStore.outgoingInviteKeepSeconds; the others wait. Returns how many were sent.
    @discardableResult
    static func sendWaiting(store: AirLocalStore,
                            now: Date = Date(),
                            contact: (String) -> Contact,
                            group: (String) -> Group,
                            send: (OCTToxFriendNumber, OCTToxGroupNumber) throws -> Void) -> Int {
        store.pruneOutgoingInvites(now: now)
        var sent = 0
        for invite in store.outgoingInvites {
            let groupState = group(invite.groupKey)
            let contactState = contact(invite.friendKey)
            if groupState == .gone || contactState == .gone {
                store.dropOutgoingInvite(friendKey: invite.friendKey, groupKey: invite.groupKey)
                continue
            }
            guard case .ready(let groupNumber) = groupState, case .online(let friendNumber) = contactState else {
                continue
            }
            var failure: Error?
            do {
                try send(friendNumber, groupNumber)
            }
            catch {
                failure = error
            }
            switch AirInviteOutcome(error: failure) {
                case .sent:
                    sent += 1
                    store.dropOutgoingInvite(friendKey: invite.friendKey, groupKey: invite.groupKey)
                case .later:
                    break
                case .refused:
                    log("AirOutgoingInvites: a kept group invitation was refused (\((failure as NSError?)?.code ?? -1)) and dropped")
                    store.dropOutgoingInvite(friendKey: invite.friendKey, groupKey: invite.groupKey)
            }
        }
        return sent
    }

    private static func invite(friends: [(number: OCTToxFriendNumber, key: String)],
                               to chat: OCTChat,
                               submanagerGroups: OCTSubmanagerGroups,
                               store: AirLocalStore) -> AirInviteTally {
        guard !chat.isInvalidated, chat.groupNumber >= 0 else {
            var tally = AirInviteTally()
            tally.refused = friends.count
            return tally
        }
        return invite(friends: friends,
                      groupNumber: OCTToxGroupNumber(chat.groupNumber),
                      groupKey: chat.groupChatIdHex ?? "",
                      store: store) { friendNumber, groupNumber in
            try submanagerGroups.inviteFriend(withNumber: friendNumber, toGroupNumber: groupNumber)
        }
    }
}

/// KHANDAQ (0.2.60): sends our invitations kept for contacts who were offline (AirOutgoingInvites) while a
/// profile runs: a little after the contacts change (one came online), and every retryInterval while any
/// wait, as the group may have been disconnected when the contact came.
final class AirOutgoingInviteSender {
    static let retryInterval: TimeInterval = 60
    /// After a change of the contacts: the link with one who has just come online settles first.
    static let settleDelay: TimeInterval = 3

    private let submanagerObjects: OCTSubmanagerObjects
    private let submanagerGroups: OCTSubmanagerGroups
    private let store: AirLocalStore
    private let friends: Results<OCTFriend>
    private var token: RLMNotificationToken?
    private var timer: Timer?
    private var scheduled = false

    init(submanagerObjects: OCTSubmanagerObjects, submanagerGroups: OCTSubmanagerGroups, store: AirLocalStore = AirLocalStore.shared) {
        self.submanagerObjects = submanagerObjects
        self.submanagerGroups = submanagerGroups
        self.store = store
        self.friends = submanagerObjects.friends()
    }

    deinit {
        stop()
    }

    func start() {
        guard token == nil else {
            return
        }
        token = friends.addNotificationBlock { [weak self] _ in
            self?.schedule()
        }
        timer = Timer.scheduledTimer(withTimeInterval: AirOutgoingInviteSender.retryInterval, repeats: true) { [weak self] _ in
            self?.sendWaiting()
        }
        schedule()
    }

    func stop() {
        token?.invalidate()
        token = nil
        timer?.invalidate()
        timer = nil
    }

    /// Out of the notification itself: sending an invitation may write to Realm (objcTox prepares the group,
    /// joining it again if need be).
    private func schedule() {
        guard !scheduled, !store.outgoingInvites.isEmpty else {
            return
        }
        scheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + AirOutgoingInviteSender.settleDelay) { [weak self] in
            self?.scheduled = false
            self?.sendWaiting()
        }
    }

    private func sendWaiting() {
        guard token != nil, !store.outgoingInvites.isEmpty else {
            return
        }
        let objects = submanagerObjects
        let groups = submanagerGroups
        let sent = AirOutgoingInvites.sendWaiting(store: store,
                                                  contact: { key in
                                                      let found = objects.friends(predicate: NSPredicate(format: "publicKey ==[c] %@", key))
                                                      guard let friend = found.object(at: 0), !friend.isInvalidated else {
                                                          return .gone
                                                      }
                                                      return friend.isConnected ? .online(friend.friendNumber) : .offline
                                                  },
                                                  group: { key in
                                                      let chats = objects.chats(predicate: NSPredicate(format: "isGroup == YES AND groupChatIdHex ==[c] %@", key))
                                                      var waiting = false
                                                      for index in 0..<chats.count {
                                                          guard let chat = chats.object(at: index), !chat.isInvalidated else {
                                                              continue
                                                          }
                                                          if chat.groupNumber >= 0 {
                                                              return .ready(OCTToxGroupNumber(chat.groupNumber))
                                                          }
                                                          waiting = true
                                                      }
                                                      return waiting ? .waiting : .gone
                                                  },
                                                  send: { friendNumber, groupNumber in
                                                      try groups.inviteFriend(withNumber: friendNumber, toGroupNumber: groupNumber)
                                                  })
        if sent > 0 {
            log("AirOutgoingInvites: \(sent) group invitation(s) kept for offline contacts sent")
        }
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
