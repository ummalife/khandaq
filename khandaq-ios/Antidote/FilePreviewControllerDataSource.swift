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
        return messages.indexOfObject(message)
    }

    // KHANDAQ (Figma): items for the custom media gallery.
    func galleryItems(myName: String) -> [GalleryItem] {
        let friendName = (chat.friends.lastObject() as? OCTFriend)?.nickname ?? ""
        var items: [GalleryItem] = []
        for i in 0..<messages.count {
            let message = messages[i]
            guard let file = message.messageFile, let path = file.filePath() else {
                continue
            }
            // Cleanup/reinstall can leave DB rows whose file is gone — a black page in the gallery.
            guard FileManager.default.fileExists(atPath: path) else {
                continue
            }
            let url = URL(fileURLWithPath: path)
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
