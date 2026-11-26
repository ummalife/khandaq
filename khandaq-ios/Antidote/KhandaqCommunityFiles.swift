// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/// KHANDAQ (0.2.57, SPEC-community part B, item 6): strangers write in Khandaq Community, so nothing of theirs
/// downloads or shows by itself there. The group file packets push every file to every member, so its bytes
/// land in the group's folder (files/group/<chat id>) whatever the app does. A file of someone else waits
/// there for Accept: its entry shows the name and the size with Accept alone, no picture, no bar and no
/// sound, and it stays out of the gallery, the attachments and forwarding. Accept marks the file itself (an
/// extended attribute), so the mark goes with the bytes and leaves nothing behind when they go.
///
/// On iOS the bytes stay where toxcore wrote them rather than in memory: the system stops the app in the
/// background at any moment, and a file kept in memory would be lost each time. Held bytes get a cleanup of
/// their own instead (KhandaqCommunityFileKeeper): complete held files take at most `heldLimitBytes` in all,
/// the oldest going first; the partial bytes of one that stopped, and the bytes no entry names any more
/// (a message taken back by its author, a cleared history), go at once; when the group's chat goes (left,
/// removed, or the app stopped between the two) they all go; and while they wait they stay out of the phone's
/// backup. Taken files and a file toxcore is writing are never deleted here, as in every other group.
extension KhandaqCommunity {
    /// What a file entry of the conversation is, for the person.
    enum FileHold: Equatable {
        /// A file like any other: ours, of another group, or taken.
        case none
        /// A file of someone else in the group that waits for Accept.
        case held
        /// One that waited and whose bytes are gone: they went to make room, or never came whole.
        case dropped
    }

    /// What is on the disk for the file of an entry.
    enum FileBytes: Equatable {
        case taken
        case waiting
        case missing
    }

    /// As on desktop: the largest group file (200 MiB) fits alone.
    static let heldLimitBytes: Int64 = 256 * 1024 * 1024

    /// The mark Accept puts on the file.
    static let takenAttribute = "org.khandaq.community.taken"

    /// A file of the group's folder that no entry names is let alone this long: toxcore writes a file a moment
    /// before it makes its entry.
    static let orphanGraceSeconds: TimeInterval = 10 * 60

    // MARK: The entry

    /// `bytes` is asked only for a file of someone else in the group. Whenever it came: one shown in 0.2.56
    /// waits too, until Accept.
    static func fileHold(isCommunity: Bool,
                         isOutgoing: Bool,
                         fileType: OCTMessageFileType,
                         bytes: () -> FileBytes) -> FileHold {
        guard isCommunity, !isOutgoing else {
            return .none
        }
        switch bytes() {
            case .taken:
                return .none
            case .missing:
                return .dropped
            case .waiting:
                return fileType == .canceled ? .dropped : .held
        }
    }

    static func fileHold(of message: OCTMessageAbstract, in chat: OCTChat?) -> FileHold {
        guard !message.isInvalidated, let file = message.messageFile, isCommunity(chat) else {
            return .none
        }
        return fileHold(isCommunity: true,
                        isOutgoing: message.isOutgoing(),
                        fileType: file.fileType,
                        bytes: { fileBytes(atPath: file.filePath()) })
    }

    /// The entries of `chat` whose files wait for Accept or are gone, for the screens that list files.
    static func heldMessageIds(in chat: OCTChat, objects: OCTSubmanagerObjects) -> [String] {
        guard isCommunity(chat) else {
            return []
        }
        let rows = objects.messages(predicate: NSPredicate(format: "chatUniqueIdentifier == %@ AND messageFile != nil",
                                                           chat.uniqueIdentifier))
        var ids: [String] = []
        for index in 0..<rows.count {
            guard let message = rows.object(at: index), fileHold(of: message, in: chat) != .none else {
                continue
            }
            ids.append(message.uniqueIdentifier)
        }
        return ids
    }

    /// The entry of a held file: its name and size with Accept alone, as an incoming file that waits; of
    /// one whose bytes are gone, "The download stopped". No picture, no bar, no voice note to play.
    static func shapeEntry(_ model: ChatGenericFileCellModel, hold: FileHold, accept: @escaping () -> Void) {
        guard hold != .none else {
            return
        }
        model.previewSize = nil
        model.caption = nil
        model.isVoiceMessage = false
        model.isAudioFile = false
        model.voiceDuration = 0
        model.transferProgress = 0
        model.voiceTransferProgress = 0
        model.openHandle = nil
        model.voicePlayToggleHandle = nil
        model.voiceSeekHandle = nil
        model.cancelHandle = nil
        model.retryHandle = nil
        model.pauseOrResumeHandle = nil
        switch hold {
            case .held:
                model.state = .waitingConfirmation
                model.startLoadingHandle = accept
            case .dropped, .none:
                model.state = .cancelled
                model.startLoadingHandle = nil
        }
    }

    // MARK: The mark on the file

    static func fileBytes(atPath path: String?) -> FileBytes {
        guard let path = path, !path.isEmpty else {
            return .missing
        }
        if getxattr(path, takenAttribute, nil, 0, 0, 0) >= 0 {
            return .taken
        }
        let error = errno
        if error == ENOENT || error == ENOTDIR {
            return .missing
        }
        // ENOATTR: the file is here and waits. Any other answer leaves it waiting too: shown by a tap only.
        return .waiting
    }

    /// Accept: the file is the person's from now on, and goes into the backup like any other.
    @discardableResult
    static func take(_ message: OCTMessageAbstract) -> Bool {
        guard !message.isInvalidated, let path = message.messageFile?.filePath() else {
            return false
        }
        return markTaken(atPath: path)
    }

    @discardableResult
    static func markTaken(atPath path: String) -> Bool {
        var mark: UInt8 = 1
        guard setxattr(path, takenAttribute, &mark, 1, 0, 0) == 0 else {
            log("Community: a file could not be marked taken (errno \(errno))")
            return false
        }
        setExcludedFromBackup(false, atPath: path)
        return true
    }

    // MARK: Room

    /// What an entry of the group says of its file in the group's folder.
    struct EntryFile: Equatable {
        enum Kind: Int, Comparable {
            /// Its bytes stop: it waited, and the transfer stopped.
            case stopped
            /// Complete, and waits for Accept.
            case held
            /// Never deleted here: ours, or still under way.
            case kept

            static func < (lhs: Kind, rhs: Kind) -> Bool {
                return lhs.rawValue < rhs.rawValue
            }
        }

        let kind: Kind
        let date: TimeInterval
    }

    /// A file of the group's folder.
    struct FolderFile: Equatable {
        let name: String
        let bytes: Int64
        let modified: TimeInterval
        let taken: Bool
        let excludedFromBackup: Bool
    }

    static func entryKind(isOutgoing: Bool, fileType: OCTMessageFileType) -> EntryFile.Kind {
        guard !isOutgoing else {
            return .kept
        }
        switch fileType {
            case .ready:
                return .held
            case .canceled:
                return .stopped
            default:
                return .kept
        }
    }

    /// The name of the file at `path` when it is right in `folder`, else nil. The folder of a group is named after
    /// its chat id (files/group/<chat id>), so the last two parts of a path and its name say which file it is,
    /// whatever root the app's container had when the path was written.
    static func name(ofPath path: String, inFolder folder: String) -> String? {
        let parent = (path as NSString).deletingLastPathComponent as NSString
        let home = folder as NSString
        guard !home.lastPathComponent.isEmpty,
              parent.lastPathComponent.caseInsensitiveCompare(home.lastPathComponent) == .orderedSame,
              (parent.deletingLastPathComponent as NSString).lastPathComponent == (home.deletingLastPathComponent as NSString).lastPathComponent else {
            return nil
        }
        let name = (path as NSString).lastPathComponent
        return name.isEmpty ? nil : name
    }

    /// The names in `folder` of the files toxcore is writing now.
    static func names(ofPaths paths: Set<String>, inFolder folder: String) -> Set<String> {
        return Set(paths.compactMap { name(ofPath: $0, inFolder: folder) })
    }

    /// The entries of the group that name a file of `folder`, by its name there, read on the main thread for the
    /// keeper. For a file an entry of someone else names, only theirs decide: one of ours never keeps it out of
    /// the room. Of two that name one file, the one that keeps it wins, and of two alike the newest, whose bytes
    /// they are: no file takes the path of an entry any more (OCTFileTools createNewGroupFilePathInDirectory),
    /// but one that came before may have.
    static func folderEntries(of rows: Results<OCTMessageAbstract>, folder: String) -> [String: EntryFile] {
        var theirs: [String: EntryFile] = [:]
        var ours: [String: EntryFile] = [:]
        for index in 0..<rows.count {
            guard let message = rows.object(at: index), !message.isInvalidated,
                  let file = message.messageFile, let path = file.filePath(),
                  let name = name(ofPath: path, inFolder: folder) else {
                continue
            }
            let outgoing = message.isOutgoing()
            let entry = EntryFile(kind: entryKind(isOutgoing: outgoing, fileType: file.fileType), date: message.dateInterval)
            if outgoing {
                ours[name] = entry
            }
            else if let other = theirs[name], other.kind > entry.kind || (other.kind == entry.kind && other.date >= entry.date) {
                continue
            }
            else {
                theirs[name] = entry
            }
        }
        return theirs.merging(ours) { their, _ in their }
    }

    /// What to delete in the group's folder: the bytes of entries that stopped, those no entry has named for
    /// `orphanGraceSeconds`, then the oldest complete held files until the rest fit in `limit`. Taken files, the
    /// files of kept entries and the files toxcore is writing (`busy`, by name) stay.
    static func filesToDelete(in folder: [FolderFile],
                              entries: [String: EntryFile],
                              busy: Set<String> = [],
                              now: TimeInterval,
                              limit: Int64 = heldLimitBytes) -> [String] {
        var drop: [String] = []
        var held: [(name: String, date: TimeInterval, bytes: Int64)] = []
        for file in folder where !file.taken && !busy.contains(file.name) {
            guard let entry = entries[file.name] else {
                if now - file.modified >= orphanGraceSeconds {
                    drop.append(file.name)
                }
                continue
            }
            switch entry.kind {
                case .kept:
                    continue
                case .stopped:
                    drop.append(file.name)
                case .held:
                    held.append((file.name, entry.date, file.bytes))
            }
        }
        held.sort { $0.date != $1.date ? $0.date < $1.date : $0.name < $1.name }
        var total = held.reduce(Int64(0)) { $0 + $1.bytes }
        for file in held where total > limit {
            drop.append(file.name)
            total -= file.bytes
        }
        return drop
    }

    /// The regular files right in `folder`, hidden ones too: a file of someone else may have come with a name that
    /// starts with a dot before 0.2.57 gave it a "_" there.
    static func folderFiles(atPath folder: String) -> [FolderFile] {
        let keys: [URLResourceKey] = [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey, .isExcludedFromBackupKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(at: URL(fileURLWithPath: folder, isDirectory: true),
                                                                      includingPropertiesForKeys: keys,
                                                                      options: []) else {
            return []
        }
        return urls.compactMap { url in
            guard let values = try? url.resourceValues(forKeys: Set(keys)), values.isRegularFile == true else {
                return nil
            }
            return FolderFile(name: url.lastPathComponent,
                              bytes: Int64(values.fileSize ?? 0),
                              modified: values.contentModificationDate?.timeIntervalSince1970 ?? 0,
                              taken: fileBytes(atPath: url.path) == .taken,
                              excludedFromBackup: values.isExcludedFromBackup ?? false)
        }
    }

    /// One pass of the keeper, off the main thread: deletes what `filesToDelete` says and keeps what still
    /// waits out of the backup. Returns the names it deleted.
    @discardableResult
    static func keepRoom(inFolder folder: String,
                         entries: [String: EntryFile],
                         busy: Set<String> = [],
                         now: TimeInterval,
                         limit: Int64 = heldLimitBytes) -> [String] {
        let files = folderFiles(atPath: folder)
        let drop = filesToDelete(in: files, entries: entries, busy: busy, now: now, limit: limit)
        let freed = remove(drop, inFolder: folder, files: files)
        let dropped = Set(drop)
        for file in files where !file.taken && !file.excludedFromBackup && !dropped.contains(file.name) && entries[file.name]?.kind == .held {
            setExcludedFromBackup(true, atPath: (folder as NSString).appendingPathComponent(file.name))
        }
        if !drop.isEmpty {
            log("Community: \(drop.count) waiting files deleted, \(freed) bytes")
        }
        return drop
    }

    /// The group's chat is gone: every file right in its folder that was not taken goes, but one toxcore is
    /// writing (`busy`, by name). Its entries went with its chat, so nothing reaches those bytes any more. Returns
    /// the names it deleted.
    @discardableResult
    static func dropWaitingFiles(inFolder folder: String, busy: Set<String> = []) -> [String] {
        let files = folderFiles(atPath: folder)
        let drop = files.filter { !$0.taken && !busy.contains($0.name) }.map { $0.name }
        let freed = remove(drop, inFolder: folder, files: files)
        if !drop.isEmpty {
            log("Community: no chat; \(drop.count) waiting files deleted, \(freed) bytes")
        }
        return drop
    }

    private static func remove(_ names: [String], inFolder folder: String, files: [FolderFile]) -> Int64 {
        let sizes = Dictionary(files.map { ($0.name, $0.bytes) }, uniquingKeysWith: { first, _ in first })
        var freed: Int64 = 0
        for name in names {
            do {
                try FileManager.default.removeItem(atPath: (folder as NSString).appendingPathComponent(name))
                freed += sizes[name] ?? 0
            }
            catch {
                log("Community: a waiting file could not be deleted: \(error.localizedDescription)")
            }
        }
        return freed
    }

    private static func setExcludedFromBackup(_ excluded: Bool, atPath path: String) {
        var url = URL(fileURLWithPath: path)
        var values = URLResourceValues()
        values.isExcludedFromBackup = excluded
        try? url.setResourceValues(values)
    }
}

/// KHANDAQ (0.2.57, item 6): the room of the files that wait in Khandaq Community, for the session. It follows
/// the group's chat; each time a file of the group completes or stops, or entries go, it runs a pass of
/// KhandaqCommunity.keepRoom on a queue of its own. With no chat of the group, at the start or when it goes, it
/// deletes every file that waited in the group's folder, which it knows from the chat id alone.
final class KhandaqCommunityFileKeeper {
    private let submanagerObjects: OCTSubmanagerObjects
    /// files/group/<chat id> (OCTSubmanagerGroups.groupFilesDirectory(forChatIdHex:)); empty: nothing to keep.
    private let folder: String
    /// The paths of the files toxcore is writing now, asked on the main thread.
    private let pathsInProgress: () -> Set<String>
    private let queue = DispatchQueue(label: "khandaq.community.files", qos: .utility)

    private var chats: Results<OCTChat>?
    private var chatsToken: RLMNotificationToken?
    private var finished: Results<OCTMessageAbstract>?
    private var finishedToken: RLMNotificationToken?
    private var bound = false
    private var boundChatId: String?
    private var passPending = false

    init(submanagerObjects: OCTSubmanagerObjects, folder: String, pathsInProgress: @escaping () -> Set<String>) {
        self.submanagerObjects = submanagerObjects
        self.folder = folder
        self.pathsInProgress = pathsInProgress
    }

    convenience init(submanagerObjects: OCTSubmanagerObjects, submanagerGroups: OCTSubmanagerGroups) {
        self.init(submanagerObjects: submanagerObjects,
                  folder: submanagerGroups.groupFilesDirectory(forChatIdHex: KhandaqCommunity.chatIdHex),
                  pathsInProgress: { [weak submanagerGroups] in
                      submanagerGroups?.incomingGroupFilePathsInProgress() ?? []
                  })
    }

    deinit {
        stop()
    }

    func start() {
        guard chatsToken == nil, !folder.isEmpty else {
            return
        }
        let results = submanagerObjects.chats(predicate: NSPredicate(format: "isGroup == YES AND groupChatIdHex ==[c] %@",
                                                                     KhandaqCommunity.chatIdHex))
        chats = results
        chatsToken = results.addNotificationBlock { [weak self] _ in
            self?.bind()
        }
        bind()
    }

    func stop() {
        chatsToken?.invalidate()
        chatsToken = nil
        chats = nil
        finishedToken?.invalidate()
        finishedToken = nil
        finished = nil
        bound = false
        boundChatId = nil
    }

    private func bind() {
        var chat: OCTChat?
        if let chats = chats {
            for index in 0..<chats.count {
                if let candidate = chats.object(at: index), !candidate.isInvalidated {
                    chat = candidate
                    break
                }
            }
        }
        let chatId = chat?.uniqueIdentifier
        guard !bound || chatId != boundChatId else {
            return
        }
        bound = true
        finishedToken?.invalidate()
        finishedToken = nil
        finished = nil
        boundChatId = chatId

        guard let id = chatId else {
            // No chat of the group, at the start or since it went: the person left it, was removed from it, or the
            // app stopped between the two. Nothing reaches what waited in its folder any more.
            let folder = self.folder
            let busy = KhandaqCommunity.names(ofPaths: pathsInProgress(), inFolder: folder)
            queue.async {
                KhandaqCommunity.dropWaitingFiles(inFolder: folder, busy: busy)
            }
            return
        }
        let predicate = NSPredicate(format: "chatUniqueIdentifier == %@ AND messageFile != nil AND (messageFile.fileType == \(OCTMessageFileType.ready.rawValue) OR messageFile.fileType == \(OCTMessageFileType.canceled.rawValue))",
                                    id)
        let results = submanagerObjects.messages(predicate: predicate)
        finished = results
        finishedToken = results.addNotificationBlock { [weak self] change in
            switch change {
                case .initial:
                    self?.schedulePass()
                case .update(_, let deletions, let insertions, _):
                    if !deletions.isEmpty || !insertions.isEmpty {
                        self?.schedulePass()
                    }
                case .error:
                    break
            }
        }
    }

    /// At most one pass a second while files come.
    private func schedulePass() {
        guard !passPending else {
            return
        }
        passPending = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
            self?.passPending = false
            self?.pass()
        }
    }

    private func pass() {
        guard let chatId = boundChatId else {
            return
        }
        let folder = self.folder
        let rows = submanagerObjects.messages(predicate: NSPredicate(format: "chatUniqueIdentifier == %@ AND messageFile != nil", chatId))
        let entries = KhandaqCommunity.folderEntries(of: rows, folder: folder)
        let busy = KhandaqCommunity.names(ofPaths: pathsInProgress(), inFolder: folder)
        let now = Date().timeIntervalSince1970
        queue.async {
            KhandaqCommunity.keepRoom(inFolder: folder, entries: entries, busy: busy, now: now)
        }
    }
}
