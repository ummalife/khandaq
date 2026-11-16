// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import QuickLook

private class FilePreviewItem: NSObject, QLPreviewItem {
    @objc var previewItemURL: URL?
    @objc var previewItemTitle: String?

    init(url: URL?, title: String?) {
        self.previewItemURL = url
        self.previewItemTitle = title
    }
}

/// The files of a conversation for QuickLook and the media gallery.
///
/// KHANDAQ (0.2.57, SPEC-community item 6): they are taken once, when the preview opens, and never read again
/// from the conversation. QuickLook asked the live entries for an item by its number while it was open: a file
/// that came meanwhile, one of Khandaq Community waiting for Accept among them, moved every number by one, and a
/// swipe showed it, or the file next to the one tapped; an entry deleted meanwhile crashed the app.
class FilePreviewControllerDataSource: NSObject , QuickLookPreviewControllerDataSource {
    weak var previewController: QuickLookPreviewController?

    /// A file of the conversation as it was when the preview opened.
    private struct Entry {
        let messageId: String
        let url: URL
        let title: String?
        let isVideo: Bool
        /// On the disk, and an image or a video: the gallery shows it.
        let isMedia: Bool
        let isOutgoing: Bool
        let date: TimeInterval
    }

    private let entries: [Entry]
    private let friendName: String

    /// `excludingMessageIds`: entries whose files are not to be shown, as those of Khandaq Community that wait
    /// for Accept (KhandaqCommunity.heldMessageIds).
    init(chat: OCTChat, submanagerObjects: OCTSubmanagerObjects, excludingMessageIds: [String] = []) {
        var subpredicates: [NSPredicate] = [
            NSPredicate(format: "chatUniqueIdentifier == %@ AND messageFile != nil", chat.uniqueIdentifier),

            NSCompoundPredicate(orPredicateWithSubpredicates: [
                NSPredicate(format: "messageFile.fileType == \(OCTMessageFileType.ready.rawValue)"),
                NSPredicate(format: "senderUniqueIdentifier == nil AND messageFile.fileType == \(OCTMessageFileType.canceled.rawValue)"),
            ]),
        ]
        if !excludingMessageIds.isEmpty {
            subpredicates.append(NSPredicate(format: "NOT (uniqueIdentifier IN %@)", excludingMessageIds))
        }
        let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: subpredicates)

        // KHANDAQ (0.2.56): in the conversation's order (OCTMessageAbstract.chatOrderSortDescriptors).
        let messages = submanagerObjects.messages(predicate: predicate)
            .sortedResultsUsingDescriptors(OCTMessageAbstract.chatOrderSortDescriptors(ascending: true))

        var entries: [Entry] = []
        for index in 0..<messages.count {
            // An entry without a path has nothing to show.
            guard let message = messages.object(at: index), !message.isInvalidated,
                  let messageId = message.uniqueIdentifier,
                  let file = message.messageFile,
                  let path = file.filePath(), !path.isEmpty else {
                continue
            }
            let url = URL(fileURLWithPath: path)
            // KHANDAQ (#204-C): the custom gallery can only render image/video. A non-media file (PDF, doc,
            // archive…) landed in it as a black page and was un-openable. Classify by extension so galleryItems
            // and the tapped-index mapping stay consistent.
            let name = file.fileName ?? url.lastPathComponent
            let isVideo = MediaGalleryViewController.isVideoFile(name)
            let isMedia = (isVideo || FilePreviewControllerDataSource.isImageFile(name)) && FileManager.default.fileExists(atPath: path)
            entries.append(Entry(messageId: messageId,
                                 url: url,
                                 title: file.fileName,
                                 isVideo: isVideo,
                                 isMedia: isMedia,
                                 isOutgoing: message.isOutgoing(),
                                 date: message.dateInterval))
        }
        self.entries = entries
        self.friendName = (chat.friends.lastObject() as? OCTFriend)?.nickname ?? ""

        super.init()
    }

    /// The number of the entry's file among the files, or nil when it is not one of them.
    func indexOfMessage(_ message: OCTMessageAbstract) -> Int? {
        guard !message.isInvalidated, let messageId = message.uniqueIdentifier else {
            return nil
        }
        return entries.firstIndex { $0.messageId == messageId }
    }

    static func isImageFile(_ name: String) -> Bool {
        let ext = (name as NSString).pathExtension.lowercased()
        return ["jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tiff", "tif"].contains(ext)
    }

    // Map a tapped index (into the files, as indexOfMessage gives it) to its position inside the media-only
    // gallery list. Returns nil when the tapped message is NOT media → caller routes it to QuickLook (which
    // previews everything, PDFs included) instead of the gallery.
    func galleryStartIndex(forMessageIndex index: Int) -> Int? {
        guard entries.indices.contains(index), entries[index].isMedia else {
            return nil
        }
        return entries[..<index].filter { $0.isMedia }.count
    }

    // KHANDAQ (Figma): items for the custom media gallery (image/video only).
    func galleryItems(myName: String) -> [GalleryItem] {
        return entries.filter { $0.isMedia }.map { entry in
            GalleryItem(url: entry.url,
                        isVideo: entry.isVideo,
                        senderName: entry.isOutgoing ? myName : friendName,
                        date: Date(timeIntervalSince1970: entry.date))
        }
    }
}

extension FilePreviewControllerDataSource: QLPreviewControllerDataSource {
    func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
        return entries.count
    }

    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
        guard entries.indices.contains(index) else {
            return FilePreviewItem(url: nil, title: nil)
        }
        let entry = entries[index]
        return FilePreviewItem(url: entry.url, title: entry.title)
    }
}
