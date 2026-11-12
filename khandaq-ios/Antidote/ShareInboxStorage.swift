// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import UIKit
import UniformTypeIdentifiers

enum ShareInboxItemType: String, Codable {
    case text
    case file
}

struct ShareInboxItem: Codable {
    let type: ShareInboxItemType
    var text: String?
    var fileName: String?
    var relativePath: String?
}

struct ShareInboxPayload: Codable {
    let version: Int
    let createdAt: TimeInterval
    var items: [ShareInboxItem]
}

enum ShareInboxStorage {
    static let appGroupIdentifier = "group.org.khandaq.messenger"
    static let manifestFileName = "share_manifest.json"
    static let inboxDirectoryName = "inbox"

    /// KHANDAQ (redesign stage 5): the language the app speaks (AppLanguage keeps it here), so the share
    /// extension says its few words in it too; the app's own settings are out of the extension's reach.
    static var appLanguage: String? {
        get {
            return UserDefaults(suiteName: appGroupIdentifier)?.string(forKey: "khandaq_app_language")
        }
        set {
            UserDefaults(suiteName: appGroupIdentifier)?.set(newValue, forKey: "khandaq_app_language")
        }
    }

    /// What a share holds, for the question where it goes: its texts and the names of its files, one a
    /// line, the first four. A long text gives its first 300 characters, ended at a word where it can be.
    static func summary(of payload: ShareInboxPayload) -> String {
        let lines = payload.items.compactMap { item -> String? in
            switch item.type {
                case .text:
                    return item.text.map { shortened($0, to: 300) }
                case .file:
                    return item.fileName
            }
        }
        return lines.prefix(4).joined(separator: "\n")
    }

    static func shortened(_ text: String, to limit: Int) -> String {
        guard text.count > limit else {
            return text
        }
        let head = String(text.prefix(limit))
        if let space = head.rangeOfCharacter(from: .whitespacesAndNewlines, options: .backwards),
           head.distance(from: head.startIndex, to: space.lowerBound) > limit / 2 {
            return String(head[..<space.lowerBound])
        }
        return head
    }

    static var hasPendingShare: Bool {
        return loadPendingShare() != nil
    }

    static func loadPendingShare() -> ShareInboxPayload? {
        guard let manifestURL = manifestURL(),
              FileManager.default.fileExists(atPath: manifestURL.path),
              let data = try? Data(contentsOf: manifestURL) else {
            return nil
        }

        let decoder = JSONDecoder()
        guard let payload = try? decoder.decode(ShareInboxPayload.self, from: data),
              !payload.items.isEmpty else {
            return nil
        }

        return payload
    }

    @discardableResult
    static func savePendingShare(_ payload: ShareInboxPayload) -> Bool {
        guard let manifestURL = manifestURL() else {
            return false
        }

        do {
            try ensureInboxDirectoryExists()
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(payload)
            try data.write(to: manifestURL, options: [.atomic])
            return true
        } catch {
            return false
        }
    }

    /// KHANDAQ (redesign stage 5): clears what was taken, `payload`. While it is still the share that
    /// waits, the whole inbox goes with it, files an earlier share left behind included; when a newer
    /// share came in meanwhile (another app shared again before the question was answered), only the
    /// files of `payload` go and the newer share waits for its own question.
    static func clearPendingShare(_ payload: ShareInboxPayload) {
        guard let waiting = loadPendingShare(), waiting.createdAt != payload.createdAt else {
            clearPendingShare()
            return
        }
        for item in payload.items {
            if let path = absolutePath(for: item) {
                _ = try? FileManager.default.removeItem(atPath: path)
            }
        }
    }

    static func clearPendingShare() {
        guard let manifestURL = manifestURL() else {
            return
        }

        _ = try? FileManager.default.removeItem(at: manifestURL)

        guard let inboxURL = inboxDirectoryURL() else {
            return
        }

        if let files = try? FileManager.default.contentsOfDirectory(at: inboxURL, includingPropertiesForKeys: nil) {
            for file in files {
                _ = try? FileManager.default.removeItem(at: file)
            }
        }
    }

    static func storeFile(from sourceURL: URL, suggestedName: String) -> ShareInboxItem? {
        guard let inboxURL = inboxDirectoryURL() else {
            return nil
        }

        do {
            try ensureInboxDirectoryExists()

            let sanitizedName = sanitizedFileName(suggestedName)
            let destinationURL = uniqueFileURL(in: inboxURL, fileName: sanitizedName)

            // A file of another app (Files) is reachable only while its access is held.
            let scoped = sourceURL.isFileURL && sourceURL.startAccessingSecurityScopedResource()
            defer {
                if scoped {
                    sourceURL.stopAccessingSecurityScopedResource()
                }
            }

            if sourceURL.isFileURL {
                if FileManager.default.fileExists(atPath: destinationURL.path) {
                    _ = try? FileManager.default.removeItem(at: destinationURL)
                }
                try FileManager.default.copyItem(at: sourceURL, to: destinationURL)
            } else if let data = try? Data(contentsOf: sourceURL) {
                try data.write(to: destinationURL, options: [.atomic])
            } else {
                return nil
            }

            let relativePath = "\(inboxDirectoryName)/\(destinationURL.lastPathComponent)"
            return ShareInboxItem(type: .file, text: nil, fileName: destinationURL.lastPathComponent, relativePath: relativePath)
        } catch {
            return nil
        }
    }

    static func storeFileData(_ data: Data, suggestedName: String) -> ShareInboxItem? {
        guard let inboxURL = inboxDirectoryURL() else {
            return nil
        }

        do {
            try ensureInboxDirectoryExists()
            let destinationURL = uniqueFileURL(in: inboxURL, fileName: sanitizedFileName(suggestedName))
            try data.write(to: destinationURL, options: [.atomic])
            let relativePath = "\(inboxDirectoryName)/\(destinationURL.lastPathComponent)"
            return ShareInboxItem(type: .file, text: nil, fileName: destinationURL.lastPathComponent, relativePath: relativePath)
        } catch {
            return nil
        }
    }

    static func absolutePath(for item: ShareInboxItem) -> String? {
        guard item.type == .file,
              let relativePath = item.relativePath,
              let containerURL = containerURL() else {
            return nil
        }

        return containerURL.appendingPathComponent(relativePath).path
    }

    static func containerURL() -> URL? {
        return FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroupIdentifier)
    }

    static func manifestURL() -> URL? {
        return containerURL()?.appendingPathComponent(manifestFileName)
    }

    static func inboxDirectoryURL() -> URL? {
        return containerURL()?.appendingPathComponent(inboxDirectoryName, isDirectory: true)
    }

    static func ensureInboxDirectoryExists() throws {
        guard let inboxURL = inboxDirectoryURL() else {
            throw NSError(domain: "ShareInboxStorage", code: 1, userInfo: nil)
        }

        try FileManager.default.createDirectory(at: inboxURL, withIntermediateDirectories: true, attributes: nil)
    }

    static func sanitizedFileName(_ name: String) -> String {
        let invalid = CharacterSet(charactersIn: "/\\:")
        let components = name.components(separatedBy: invalid).filter { !$0.isEmpty }
        let joined = components.joined(separator: "_")
        return joined.isEmpty ? "shared_file" : joined
    }

    static func uniqueFileURL(in directory: URL, fileName: String) -> URL {
        var candidate = directory.appendingPathComponent(fileName)
        guard FileManager.default.fileExists(atPath: candidate.path) else {
            return candidate
        }

        let baseName = (fileName as NSString).deletingPathExtension
        let ext = (fileName as NSString).pathExtension
        var index = 1

        while true {
            let nextName: String
            if ext.isEmpty {
                nextName = "\(baseName)-\(index)"
            } else {
                nextName = "\(baseName)-\(index).\(ext)"
            }

            candidate = directory.appendingPathComponent(nextName)
            if !FileManager.default.fileExists(atPath: candidate.path) {
                return candidate
            }
            index += 1
        }
    }
}

/// KHANDAQ (redesign stage 5): what another app hands the share extension, read into inbox items in the
/// order it was given. Every provider answers on a queue of its own, often at the same moment, so each
/// answer gets a place of its own and they are put together once all have come: appending them to one
/// array from those queues could lose items or crash. A file can come under a text type (a .txt from
/// Files answers with its URL) and a picture as plain data: both are kept as files. Texts that follow one
/// another become one text, a line each: a page's title and its link are one share, not two messages.
enum ShareInboxCollector {
    /// The types asked for, in this order: a provider is read as the first of them it has. A file comes
    /// first and a link before plain text, so a file is never read as its text and a page as its title.
    static let readOrder: [UTType] = [.fileURL, .url, .plainText, .image, .movie, .data]

    /// The items of `extensionItems` in the order they were given, without repeats, on the main queue.
    static func collect(_ extensionItems: [NSExtensionItem], completion: @escaping ([ShareInboxItem]) -> Void) {
        var texts = [(Int, String)]()
        var providers = [(Int, NSItemProvider)]()
        var count = 0
        for extensionItem in extensionItems {
            if let text = extensionItem.attributedContentText?.string {
                texts.append((count, text))
                count += 1
            }
            for case let provider as NSItemProvider in extensionItem.attachments ?? [] {
                providers.append((count, provider))
                count += 1
            }
        }

        var slots = [[ShareInboxItem]](repeating: [], count: count)
        for (index, text) in texts {
            slots[index] = textItems(text)
        }
        let lock = NSLock()
        let group = DispatchGroup()
        for (index, provider) in providers {
            group.enter()
            read(provider) { items in
                lock.lock()
                slots[index] = items
                lock.unlock()
                group.leave()
            }
        }
        group.notify(queue: .main) {
            lock.lock()
            let items = slots.flatMap { $0 }
            lock.unlock()
            completion(joinedTexts(deduplicated(items)))
        }
    }

    /// One provider, read as the first type of `readOrder` it has; nothing when it has none of them.
    static func read(_ provider: NSItemProvider, completion: @escaping ([ShareInboxItem]) -> Void) {
        guard let type = readOrder.first(where: { provider.hasItemConformingToTypeIdentifier($0.identifier) }) else {
            completion([])
            return
        }
        guard type == .fileURL || type == .url else {
            readContent(of: provider, as: type, completion: completion)
            return
        }
        // A link is read as an object: loadItem hands a file link over as the property list it travels
        // in on iOS, not as a link.
        _ = provider.loadObject(ofClass: URL.self) { url, _ in
            guard let url = url else {
                let content = readOrder.first {
                    $0 != .fileURL && $0 != .url && provider.hasItemConformingToTypeIdentifier($0.identifier)
                }
                if let content = content {
                    readContent(of: provider, as: content, completion: completion)
                }
                else {
                    completion([])
                }
                return
            }
            guard url.isFileURL else {
                completion(textItems(url.absoluteString))
                return
            }
            if let item = ShareInboxStorage.storeFile(from: url, suggestedName: url.lastPathComponent) {
                completion([item])
                return
            }
            // Out of the extension's reach: the system's own copy of the file, under the file's name.
            readFileCopy(of: provider, named: url.lastPathComponent, completion: completion)
        }
    }

    /// A provider read as `type`, whatever form the answer takes.
    private static func readContent(of provider: NSItemProvider, as type: UTType,
                                    completion: @escaping ([ShareInboxItem]) -> Void) {
        provider.loadItem(forTypeIdentifier: type.identifier, options: nil) { item, _ in
            completion(items(from: item, readAs: type, provider: provider))
        }
    }

    /// The system's copy of a provider's file, kept in the inbox under `name`. The copy lasts only while
    /// its handler runs.
    private static func readFileCopy(of provider: NSItemProvider, named name: String,
                                     completion: @escaping ([ShareInboxItem]) -> Void) {
        let content = provider.registeredTypeIdentifiers.first { identifier in
            guard let type = UTType(identifier) else {
                return false
            }
            return !type.conforms(to: .url) && type.conforms(to: .data)
        }
        guard let identifier = content else {
            completion([])
            return
        }
        provider.loadFileRepresentation(forTypeIdentifier: identifier) { copy, _ in
            guard let copy = copy else {
                completion([])
                return
            }
            completion(stored(ShareInboxStorage.storeFile(from: copy, suggestedName: name)))
        }
    }

    /// What one answer of a provider holds, in whatever form it came. A file is copied into the inbox
    /// while the answer lasts: the system may remove its copy afterwards.
    static func items(from item: NSSecureCoding?, readAs type: UTType, provider: NSItemProvider) -> [ShareInboxItem] {
        if let url = item as? URL {
            guard url.isFileURL else {
                return textItems(url.absoluteString)
            }
            return stored(ShareInboxStorage.storeFile(from: url, suggestedName: url.lastPathComponent))
        }
        if let text = item as? String {
            return textItems(text)
        }
        if let text = item as? NSAttributedString {
            return textItems(text.string)
        }
        if let image = item as? UIImage {
            guard let data = UIImageJPEGRepresentation(image, 0.9) else {
                return []
            }
            let name = fileName(provider.suggestedName, extension: "jpg", fallback: "photo")
            return stored(ShareInboxStorage.storeFileData(data, suggestedName: name))
        }
        if let data = item as? Data {
            if type == .plainText, let text = String(data: data, encoding: .utf8) {
                return textItems(text)
            }
            let name = fileName(provider.suggestedName,
                                extension: registeredExtension(of: provider, conformingTo: type),
                                fallback: type == .image ? "photo" : (type == .movie ? "video" : "shared_file"))
            return stored(ShareInboxStorage.storeFileData(data, suggestedName: name))
        }
        return []
    }

    /// A text as one item, without the blank space around it; nothing for a blank one.
    static func textItems(_ text: String) -> [ShareInboxItem] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return []
        }
        return [ShareInboxItem(type: .text, text: trimmed, fileName: nil, relativePath: nil)]
    }

    /// The same text or the same inbox file once, the first time it comes.
    static func deduplicated(_ items: [ShareInboxItem]) -> [ShareInboxItem] {
        var seen = Set<String>()
        return items.filter { item in
            switch item.type {
                case .text:
                    return seen.insert("text:" + (item.text ?? "")).inserted
                case .file:
                    return seen.insert("file:" + (item.relativePath ?? item.fileName ?? "")).inserted
            }
        }
    }

    /// Texts that come one after another as one text, a line each.
    static func joinedTexts(_ items: [ShareInboxItem]) -> [ShareInboxItem] {
        var result = [ShareInboxItem]()
        for item in items {
            if item.type == .text, let last = result.last, last.type == .text {
                result[result.count - 1].text = [last.text, item.text].compactMap { $0 }.joined(separator: "\n")
            }
            else {
                result.append(item)
            }
        }
        return result
    }

    private static func stored(_ item: ShareInboxItem?) -> [ShareInboxItem] {
        return item.map { [$0] } ?? []
    }

    /// The name a file of plain data is kept under: the one the provider suggests, or `fallback`, with
    /// the extension of its type when the name has none.
    private static func fileName(_ suggested: String?, extension ext: String?, fallback: String) -> String {
        let base = (suggested?.isEmpty == false ? suggested : nil) ?? fallback
        guard let ext = ext, !ext.isEmpty, (base as NSString).pathExtension.isEmpty else {
            return base
        }
        return base + "." + ext
    }

    /// The file extension of the first type the provider has that is a kind of `type`.
    private static func registeredExtension(of provider: NSItemProvider, conformingTo type: UTType) -> String? {
        for identifier in provider.registeredTypeIdentifiers {
            if let registered = UTType(identifier), registered.conforms(to: type),
               let ext = registered.preferredFilenameExtension {
                return ext
            }
        }
        return nil
    }
}
