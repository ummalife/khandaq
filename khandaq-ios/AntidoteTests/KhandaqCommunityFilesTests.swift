// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Khandaq Community, item 6: files of others never download or show by themselves. Their bytes, pushed by the
 group file packets, wait in the group's folder for Accept, within a room of their own (256 MiB of complete
 files, the oldest going first), and go when the person leaves the group.
 */
class KhandaqCommunityFilesTests: XCTestCase {
    private typealias Entry = KhandaqCommunity.EntryFile
    private let mebibyte: Int64 = 1024 * 1024
    private var directories: [URL] = []
    private var kept: [AnyObject] = []

    override func tearDown() {
        directories.forEach { try? FileManager.default.removeItem(at: $0) }
        directories = []
        kept = []
        super.tearDown()
    }

    // MARK: Which entries wait

    func testEveryFileOfSomeoneElseInTheCommunityWaitsUntilAccept() {
        XCTAssertEqual(hold(), .held)
        XCTAssertEqual(hold(type: .loading), .held, "under way: Accept, and no bar")
        XCTAssertEqual(hold(type: .canceled), .dropped, "it stopped before it was whole")
        XCTAssertEqual(hold(bytes: .missing), .dropped, "its bytes went to make room")
        XCTAssertEqual(hold(bytes: .taken), .none, "Accept was tapped")

        XCTAssertEqual(hold(community: false), .none, "every other group is as before")
        XCTAssertEqual(hold(outgoing: true), .none, "our own files")
    }

    func testTheDiskIsAskedOnlyAboutAFileThatMayWait() {
        var asked = 0
        let bytes: () -> KhandaqCommunity.FileBytes = {
            asked += 1
            return .waiting
        }
        _ = KhandaqCommunity.fileHold(isCommunity: false, isOutgoing: false, fileType: .ready, bytes: bytes)
        _ = KhandaqCommunity.fileHold(isCommunity: true, isOutgoing: true, fileType: .ready, bytes: bytes)
        XCTAssertEqual(asked, 0)
        _ = KhandaqCommunity.fileHold(isCommunity: true, isOutgoing: false, fileType: .ready, bytes: bytes)
        XCTAssertEqual(asked, 1)
    }

    /// The group's history comes to a new member with its old dates: a file from before the join, or from
    /// before 0.2.57, waits like one that came now.
    func testAFileFromTheHistoryDatedBeforeTheJoinWaits() throws {
        let world = try communityWorld()
        let synced = try world.incoming("from-history.jpg", mebibytes: 1, date: 1)
        let realm: RLMRealm = world.realmManager.realm
        realm.beginWriteTransaction()
        synced.groupHistorySync = true
        synced.groupSenderPeerId = 0
        try realm.commitWriteTransaction()

        XCTAssertFalse(synced.isOutgoing())
        XCTAssertEqual(KhandaqCommunity.fileHold(of: synced, in: world.chat), .held)
        XCTAssertEqual(KhandaqCommunity.heldMessageIds(in: world.chat, objects: world.objects), [synced.uniqueIdentifier])
        let read = KhandaqCommunity.folderEntries(of: world.objects.messages(predicate: NSPredicate(format: "chatUniqueIdentifier == %@", world.chat.uniqueIdentifier)))
        XCTAssertEqual(read.entries["from-history.jpg"], Entry(kind: .held, date: 1), "it counts in the room of the files that wait")
    }

    func testAWaitingEntryShowsItsNameAndSizeWithAcceptAlone() {
        let model = ChatIncomingFileCellModel()
        model.fileName = "photo.jpg"
        model.fileSize = "1.2 MB"
        model.previewSize = CGSize(width: 4, height: 3)
        model.caption = "a caption"
        model.isVoiceMessage = true
        model.transferProgress = 0.5
        model.state = .loading
        model.openHandle = {}
        model.voicePlayToggleHandle = {}
        var accepted = 0

        KhandaqCommunity.shapeEntry(model, hold: .held) {
            accepted += 1
        }

        XCTAssertEqual(model.state, .waitingConfirmation)
        XCTAssertEqual(model.fileName, "photo.jpg")
        XCTAssertEqual(model.fileSize, "1.2 MB")
        XCTAssertNil(model.previewSize, "no picture")
        XCTAssertNil(model.caption)
        XCTAssertFalse(model.isVoiceMessage, "nothing to play")
        XCTAssertEqual(model.transferProgress, 0, "no bar")
        XCTAssertNil(model.openHandle)
        XCTAssertNil(model.voicePlayToggleHandle)
        XCTAssertNil(model.cancelHandle)
        model.startLoadingHandle?()
        XCTAssertEqual(accepted, 1)
        // The cell offers what it has a handle for: Accept alone.
        let text = AirFileEntryText.make(state: model.state, incoming: true, bytesTotal: 1_200_000, progress: 0,
                                         available: [.accept], personName: nil, personOnline: true)
        XCTAssertEqual(text.actions, [.accept])
        XCTAssertNil(text.progress)

        let gone = ChatIncomingFileCellModel()
        gone.state = .done
        KhandaqCommunity.shapeEntry(gone, hold: .dropped) {}
        XCTAssertEqual(gone.state, .cancelled)
        XCTAssertNil(gone.startLoadingHandle)
        let stopped = AirFileEntryText.make(state: gone.state, incoming: true, bytesTotal: 1, progress: 0,
                                            available: [], personName: nil, personOnline: true)
        XCTAssertEqual(stopped.note, String(localized: "air_file_failed_incoming"))
        XCTAssertEqual(stopped.actions, [])

        let shown = ChatIncomingFileCellModel()
        shown.state = .done
        shown.previewSize = CGSize(width: 4, height: 3)
        KhandaqCommunity.shapeEntry(shown, hold: .none) {}
        XCTAssertEqual(shown.state, .done)
        XCTAssertNotNil(shown.previewSize)
    }

    // MARK: The mark on the file

    func testAcceptMarksTheFileItselfAndPutsItBackIntoTheBackup() throws {
        let folder = makeFolder()
        let path = folder.appendingPathComponent("photo.jpg").path
        XCTAssertEqual(KhandaqCommunity.fileBytes(atPath: nil), .missing)
        XCTAssertEqual(KhandaqCommunity.fileBytes(atPath: path), .missing)
        try Data([1, 2, 3]).write(to: URL(fileURLWithPath: path))
        XCTAssertEqual(KhandaqCommunity.fileBytes(atPath: path), .waiting)

        // While it waits it stays out of the backup...
        KhandaqCommunity.keepRoom(inFolder: folder.path, entries: ["photo.jpg": Entry(kind: .held, date: 1)], now: 2)
        XCTAssertEqual(try excludedFromBackup(path), true)
        XCTAssertEqual(KhandaqCommunity.fileBytes(atPath: path), .waiting)

        // ...Accept marks it, and it goes into the backup like any file.
        XCTAssertTrue(KhandaqCommunity.markTaken(atPath: path))
        XCTAssertEqual(KhandaqCommunity.fileBytes(atPath: path), .taken)
        XCTAssertEqual(try excludedFromBackup(path), false)
        XCTAssertFalse(KhandaqCommunity.markTaken(atPath: folder.appendingPathComponent("gone.jpg").path))
    }

    // MARK: The room

    func testWhatAnEntrySaysOfItsFile() {
        XCTAssertEqual(KhandaqCommunity.entryKind(isOutgoing: false, fileType: .ready), .held)
        XCTAssertEqual(KhandaqCommunity.entryKind(isOutgoing: false, fileType: .canceled), .stopped)
        XCTAssertEqual(KhandaqCommunity.entryKind(isOutgoing: false, fileType: .loading), .kept, "under way")
        XCTAssertEqual(KhandaqCommunity.entryKind(isOutgoing: true, fileType: .canceled), .kept, "ours")
        XCTAssertEqual(KhandaqCommunity.entryKind(isOutgoing: true, fileType: .ready), .kept, "ours")
    }

    func testTheOldestCompleteWaitingFilesGoUntilTheRestFit() {
        let three = [file("b", 100), file("a", 100), file("c", 100)]
        let ages = ["a": Entry(kind: .held, date: 10), "b": Entry(kind: .held, date: 30), "c": Entry(kind: .held, date: 20)]
        XCTAssertEqual(KhandaqCommunity.filesToDelete(in: three, entries: ages, now: 100), ["a"], "300 MiB: the oldest goes")

        let full = [file("a", 128), file("b", 128)]
        XCTAssertEqual(KhandaqCommunity.filesToDelete(in: full, entries: ["a": Entry(kind: .held, date: 1), "b": Entry(kind: .held, date: 2)],
                                                      now: 100),
                       [], "256 MiB fit")

        // The largest group file fits alone; the next one pushes it out.
        let large = [file("large", 200), file("next", 60)]
        XCTAssertEqual(KhandaqCommunity.filesToDelete(in: large, entries: ["large": Entry(kind: .held, date: 1), "next": Entry(kind: .held, date: 2)],
                                                      now: 100),
                       ["large"])
        XCTAssertEqual(KhandaqCommunity.filesToDelete(in: [file("large", 200)], entries: ["large": Entry(kind: .held, date: 1)], now: 100), [])
    }

    func testTakenFilesKeptEntriesAndNewFilesNoEntryNamesYetStay() {
        let now: TimeInterval = 10_000
        let folder = [
            file("taken", 300, modified: 2_000, taken: true),
            file("ours", 300, modified: 2_000),
            file("underway", 200, modified: 2_000),
            file("stopped", 1, modified: 2_000),
            file("old-stranger", 1, modified: now - KhandaqCommunity.orphanGraceSeconds),
            file("new-stranger", 1, modified: now - 5),
            file("from-056-no-entry", 1, modified: 999),
        ]
        let entries = [
            "ours": Entry(kind: .kept, date: 1),
            "underway": Entry(kind: .kept, date: 2),
            "stopped": Entry(kind: .stopped, date: 3),
        ]
        // A file of 0.2.56 whose entry went (its history was cleared) waits for no one: it goes too.
        XCTAssertEqual(Set(KhandaqCommunity.filesToDelete(in: folder, entries: entries, now: now)),
                       ["stopped", "old-stranger", "from-056-no-entry"])
    }

    func testOnlyTheFolderNamedAfterTheGroupIsEverCleaned() {
        let community = "/profile/files/group/" + KhandaqCommunity.chatIdHex.lowercased()
        XCTAssertEqual(KhandaqCommunity.communityFolder(fromPaths: [community + "/a.jpg", "/profile/files/group/abc/b.jpg", community + "/c.jpg"]),
                       community)
        XCTAssertNil(KhandaqCommunity.communityFolder(fromPaths: ["/profile/files/group/a.jpg", "/profile/files/b.jpg"]),
                     "the folder of every group's uploads is never the Community's")
        XCTAssertNil(KhandaqCommunity.communityFolder(fromPaths: []))
    }

    func testAPassDeletesOnTheDiskAndCountsATransferBySize() throws {
        let folder = makeFolder()
        // Sparse files: their size without the room.
        try sparse(folder, "1-oldest.bin", mebibytes: 100)
        try sparse(folder, "2.bin", mebibytes: 100)
        try sparse(folder, "3-newest.bin", mebibytes: 100)
        try sparse(folder, "stopped.bin", mebibytes: 1)
        let entries = [
            "1-oldest.bin": Entry(kind: .held, date: 1),
            "2.bin": Entry(kind: .held, date: 2),
            "3-newest.bin": Entry(kind: .held, date: 3),
            "stopped.bin": Entry(kind: .stopped, date: 4),
        ]

        let deleted = KhandaqCommunity.keepRoom(inFolder: folder.path, entries: entries, now: Date().timeIntervalSince1970)

        XCTAssertEqual(Set(deleted), ["1-oldest.bin", "stopped.bin"])
        XCTAssertEqual(Set(names(in: folder)), ["2.bin", "3-newest.bin"])
        XCTAssertEqual(try excludedFromBackup(folder.appendingPathComponent("2.bin").path), true)
    }

    func testLeavingDeletesTheFilesThatWaited() throws {
        let folder = makeFolder()
        try sparse(folder, "waiting.bin", mebibytes: 1)
        try sparse(folder, "taken.bin", mebibytes: 1)
        try sparse(folder, "stopped.bin", mebibytes: 1)
        // One that came long ago, in 0.2.56, and was never taken, goes as well.
        let old = try sparse(folder, "from-056.bin", mebibytes: 1)
        try FileManager.default.setAttributes([.modificationDate: Date(timeIntervalSinceNow: -86_400 * 30)], ofItemAtPath: old.path)
        XCTAssertTrue(KhandaqCommunity.markTaken(atPath: folder.appendingPathComponent("taken.bin").path))

        XCTAssertEqual(Set(KhandaqCommunity.dropWaitingFiles(inFolder: folder.path)), ["waiting.bin", "stopped.bin", "from-056.bin"])
        XCTAssertEqual(names(in: folder), ["taken.bin"])
    }

    // MARK: With the profile's Realm

    func testTheGalleryAndTheListsPassOverFilesThatWait() throws {
        let world = try communityWorld()
        let waiting = try world.incoming("waiting.jpg", mebibytes: 1, date: 10)
        let taken = try world.incoming("taken.jpg", mebibytes: 1, date: 11)
        XCTAssertTrue(KhandaqCommunity.take(taken))
        let ours = try world.ours("ours.jpg", date: 12)

        XCTAssertEqual(KhandaqCommunity.fileHold(of: waiting, in: world.chat), .held)
        XCTAssertEqual(KhandaqCommunity.fileHold(of: taken, in: world.chat), .none)
        XCTAssertEqual(KhandaqCommunity.fileHold(of: ours, in: world.chat), .none)
        XCTAssertEqual(KhandaqCommunity.heldMessageIds(in: world.chat, objects: world.objects), [waiting.uniqueIdentifier])

        let preview = FilePreviewControllerDataSource(chat: world.chat,
                                                      submanagerObjects: world.objects,
                                                      excludingMessageIds: KhandaqCommunity.heldMessageIds(in: world.chat, objects: world.objects))
        XCTAssertNil(preview.indexOfMessage(waiting), "not in the gallery nor in QuickLook")
        XCTAssertNotNil(preview.indexOfMessage(taken))
        XCTAssertNotNil(preview.indexOfMessage(ours))

        let read = KhandaqCommunity.folderEntries(of: world.objects.messages(predicate: NSPredicate(format: "chatUniqueIdentifier == %@", world.chat.uniqueIdentifier)))
        XCTAssertEqual(read.folder.map { URL(fileURLWithPath: $0).standardizedFileURL.path },
                       world.folder.standardizedFileURL.path)
        XCTAssertEqual(read.entries["waiting.jpg"]?.kind, .held)
        XCTAssertEqual(read.entries["ours.jpg"]?.kind, .kept)
    }

    func testTheKeeperMakesRoomAndDeletesWhatWaitedWhenThePersonLeaves() throws {
        let world = try communityWorld()
        _ = try world.incoming("1-oldest.bin", mebibytes: 100, date: 1)
        _ = try world.incoming("2.bin", mebibytes: 100, date: 2)
        _ = try world.incoming("3-newest.bin", mebibytes: 100, date: 3)
        let taken = try world.incoming("taken.bin", mebibytes: 100, date: 0.5)
        XCTAssertTrue(KhandaqCommunity.take(taken))
        var left = false
        let keeper = KhandaqCommunityFileKeeper(submanagerObjects: world.objects, left: { left })
        kept.append(keeper)

        keeper.start()
        wait(until: { !self.names(in: world.folder).contains("1-oldest.bin") }, "the oldest went to make room")
        XCTAssertEqual(Set(names(in: world.folder)), ["2.bin", "3-newest.bin", "taken.bin"])

        // The person leaves: the group's chat goes, and every file that waited with it.
        left = true
        let realm: RLMRealm = world.realmManager.realm
        realm.beginWriteTransaction()
        realm.delete(world.chat)
        try realm.commitWriteTransaction()
        wait(until: { self.names(in: world.folder) == ["taken.bin"] }, "what waited went with the group")
        keeper.stop()
    }

    // MARK: Helpers

    private func hold(community: Bool = true,
                      outgoing: Bool = false,
                      type: OCTMessageFileType = .ready,
                      bytes: KhandaqCommunity.FileBytes = .waiting) -> KhandaqCommunity.FileHold {
        return KhandaqCommunity.fileHold(isCommunity: community, isOutgoing: outgoing, fileType: type, bytes: { bytes })
    }

    private func file(_ name: String, _ mebibytes: Int64, modified: TimeInterval = 0, taken: Bool = false) -> KhandaqCommunity.FolderFile {
        return KhandaqCommunity.FolderFile(name: name, bytes: mebibytes * mebibyte, modified: modified, taken: taken, excludedFromBackup: false)
    }

    private func makeFolder() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("community-files-" + UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        directories.append(url)
        return url
    }

    @discardableResult
    private func sparse(_ folder: URL, _ name: String, mebibytes: Int64) throws -> URL {
        let url = folder.appendingPathComponent(name)
        XCTAssertTrue(FileManager.default.createFile(atPath: url.path, contents: nil))
        let handle = try FileHandle(forWritingTo: url)
        try handle.truncate(atOffset: UInt64(mebibytes * mebibyte))
        try handle.close()
        return url
    }

    private func names(in folder: URL) -> [String] {
        return ((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []).sorted()
    }

    private func excludedFromBackup(_ path: String) throws -> Bool? {
        return try URL(fileURLWithPath: path).resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup
    }

    private func wait(until condition: @escaping () -> Bool, _ description: String) {
        let done = expectation(description: description)
        func poll() {
            if condition() {
                done.fulfill()
                return
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05, execute: poll)
        }
        poll()
        wait(for: [done], timeout: 10)
    }

    /// A profile with Khandaq Community in its Realm, and the group's folder as objcTox names it.
    private func communityWorld() throws -> CommunityWorld {
        let directory = makeFolder()
        let folder = directory.appendingPathComponent("files/group/" + KhandaqCommunity.chatIdHex.lowercased(), isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let realmManagerClass = try appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
        let realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("profile.realm"),
                                                                encryptionKey: nil))
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        chat.isGroup = true
        chat.groupNumber = 3
        chat.groupName = "Khandaq Community"
        chat.groupChatIdHex = KhandaqCommunity.chatIdHex
        realmManager.add(chat)
        let objects = OCTSubmanagerObjectsMock(realm: realmManager.realm)
        kept += [realmManager, objects]
        return CommunityWorld(test: self, realmManager: realmManager, objects: objects, chat: chat, folder: folder)
    }

    private func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }

    private struct CommunityWorld {
        let test: KhandaqCommunityFilesTests
        let realmManager: OCTRealmManager
        let objects: OCTSubmanagerObjectsMock
        let chat: OCTChat
        let folder: URL

        /// A complete file of another member, as objcTox stores one.
        func incoming(_ name: String, mebibytes: Int64, date: TimeInterval) throws -> OCTMessageAbstract {
            let url = try test.sparse(folder, name, mebibytes: mebibytes)
            let message = try add(name, path: url.path, bytes: mebibytes * test.mebibyte, date: date)
            let realm: RLMRealm = realmManager.realm
            realm.beginWriteTransaction()
            message.groupSenderPeerId = 7
            try realm.commitWriteTransaction()
            return message
        }

        /// One of ours, which objcTox keeps among the uploads.
        func ours(_ name: String, date: TimeInterval) throws -> OCTMessageAbstract {
            let uploads = folder.deletingLastPathComponent()
            let url = try test.sparse(uploads, name, mebibytes: 1)
            return try add(name, path: url.path, bytes: test.mebibyte, date: date)
        }

        private func add(_ name: String, path: String, bytes: Int64, date: TimeInterval) throws -> OCTMessageAbstract {
            let message = try XCTUnwrap(realmManager.addMessage(withFileNumber: 0, fileType: .ready, fileSize: bytes, fileName: name,
                                                                filePath: path, fileUTI: nil, chat: chat, sender: nil))
            let realm: RLMRealm = realmManager.realm
            realm.beginWriteTransaction()
            message.dateInterval = date
            try realm.commitWriteTransaction()
            return message
        }
    }
}
