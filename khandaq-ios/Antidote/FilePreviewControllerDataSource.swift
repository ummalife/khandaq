// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import QuickLook

private class FilePreviewItem: NSObject, QLPreviewItem {
    @objc var previewItemURL: URL?
    @objc var previewItemTitle: String?

    init(url: URL, title: String?) {
        self.previewItemURL = url
            self.previewItemTitle = title
    }
}

class FilePreviewControllerDataSource: NSObject , QuickLookPreviewControllerDataSource {
    weak var previewController: QuickLookPreviewController?

    let messages: Results<OCTMessageAbstract>
    var messagesToken: RLMNotificationToken?
    private let chat: OCTChat

    init(chat: OCTChat, submanagerObjects: OCTSubmanagerObjects) {
        self.chat = chat
        let predicate = NSCompoundPredicate(andPredicateWithSubpredicates: [
            NSPredicate(format: "chatUniqueIdentifier == %@ AND messageFile != nil", chat.uniqueIdentifier),

            NSCompoundPredicate(orPredicateWithSubpredicates: [
                NSPredicate(format: "messageFile.fileType == \(OCTMessageFileType.ready.rawValue)"),
                NSPredicate(format: "senderUniqueIdentifier == nil AND messageFile.fileType == \(OCTMessageFileType.canceled.rawValue)"),
            ]),
        ])

        self.messages = submanagerObjects.messages(predicate: predicate).sortedResultsUsingProperty("dateInterval", ascending: true)

        super.init()

        messagesToken = messages.addNotificationBlock { [unowned self] change in
            switch change {
                case .initial:
                    break
                case .update:
                    self.previewController?.reloadData()
                case .error(let error):
                fatalError("\(error)")
            }
        }
    }

    deinit {
        messagesToken?.invalidate()
    }

    func indexOfMessage(_ message: OCTMessageAbstract) -> Int? {
        // KHANDAQ (audit): indexOfObject returns -1 (never nil) for not-found, so the Optional was
        // always .some and callers' `guard let` never caught the missing case — a -1 then flowed into
        // gallery/QuickLook indexing. Return a true nil when the message isn't in the current Results.
        let idx = messages.indexOfObject(message)
        return idx >= 0 ? idx : nil
    }

    // KHANDAQ (#204-C): the custom gallery can only render image/video. A non-media file
    // (PDF, doc, archive…) landed in it as a black page and was un-openable. Classify by
    // extension so galleryItems and the tapped-index mapping stay consistent.
    static func isImageFile(_ name: String) -> Bool {
        let ext = (name as NSString).pathExtension.lowercased()
        return ["jpg", "jpeg", "png", "gif", "heic", "heif", "webp", "bmp", "tiff", "tif"].contains(ext)
    }

    // A message qualifies for the media gallery only if its file exists AND is image/video.
    private func isMediaMessage(at index: Int) -> Bool {
        guard index >= 0, index < messages.count,
              let file = messages[index].messageFile,
              let path = file.filePath(),
              FileManager.default.fileExists(atPath: path) else {
            return false
        }
        let name = file.fileName ?? URL(fileURLWithPath: path).lastPathComponent
        return MediaGalleryViewController.isVideoFile(name) || FilePreviewControllerDataSource.isImageFile(name)
    }

    // Map a tapped index (into the full file `messages` Results) to its position inside the
    // media-only gallery list. Returns nil when the tapped message is NOT media → caller routes
    // it to QuickLook (which previews everything, PDFs included) instead of the gallery.
    func galleryStartIndex(forMessageIndex index: Int) -> Int? {
        guard isMediaMessage(at: index) else { return nil }
        var count = 0
        for i in 0..<index where isMediaMessage(at: i) {
            count += 1
        }
        return count
    }

    // KHANDAQ (Figma): items for the custom media gallery (image/video only — see isMediaMessage).
    func galleryItems(myName: String) -> [GalleryItem] {
        let friendName = (chat.friends.lastObject() as? OCTFriend)?.nickname ?? ""
        var items: [GalleryItem] = []
        for i in 0..<messages.count {
            guard isMediaMessage(at: i) else { continue }
            let message = messages[i]
            let file = message.messageFile!
            let url = URL(fileURLWithPath: file.filePath()!)
            let isVideo = MediaGalleryViewController.isVideoFile(file.fileName ?? url.lastPathComponent)
            let sender = message.isOutgoing() ? myName : friendName
            items.append(GalleryItem(url: url,
                                     isVideo: isVideo,
                                     senderName: sender,
                                     date: Date(timeIntervalSince1970: message.dateInterval)))
        }
        return items
    }
}

extension FilePreviewControllerDataSource: QLPreviewControllerDataSource {
    func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
        return messages.count
    }

    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
        let message = messages[index]

        let url = URL(fileURLWithPath: message.messageFile!.filePath()!)

        return FilePreviewItem(url: url, title: message.messageFile!.fileName)
    }
}
