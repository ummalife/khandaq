// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

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
