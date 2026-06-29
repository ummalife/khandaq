// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import MobileCoreServices

class ShareViewController: UIViewController {
    private let statusLabel = UILabel()
    private var didFinish = false

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = UIColor(white: 0.97, alpha: 1.0)

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.font = UIFont.systemFont(ofSize: 16)
        statusLabel.text = "Preparing share…"
        view.addSubview(statusLabel)

        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            statusLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])

        processSharedItems()
    }

    private func processSharedItems() {
        guard let extensionItems = extensionContext?.inputItems as? [NSExtensionItem] else {
            finishWithFailure()
            return
        }

        var collectedItems = [ShareInboxItem]()
        let group = DispatchGroup()

        for extensionItem in extensionItems {
            if let attributedText = extensionItem.attributedContentText?.string {
                let trimmed = attributedText.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty {
                    collectedItems.append(ShareInboxItem(type: .text, text: trimmed, fileName: nil, relativePath: nil))
                }
            }

            guard let attachments = extensionItem.attachments as? [NSItemProvider] else {
                continue
            }

            for provider in attachments {
                if provider.hasItemConformingToTypeIdentifier(kUTTypePlainText as String) {
                    group.enter()
                    provider.loadItem(forTypeIdentifier: kUTTypePlainText as String, options: nil) { (item: NSSecureCoding?, error: Error?) in
                        defer { group.leave() }
                        if let text = item as? String {
                            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                            if !trimmed.isEmpty {
                                collectedItems.append(ShareInboxItem(type: .text, text: trimmed, fileName: nil, relativePath: nil))
                            }
                        } else if let data = item as? Data, let text = String(data: data, encoding: .utf8) {
                            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                            if !trimmed.isEmpty {
                                collectedItems.append(ShareInboxItem(type: .text, text: trimmed, fileName: nil, relativePath: nil))
                            }
                        }
                    }
                    continue
                }

                if provider.hasItemConformingToTypeIdentifier(kUTTypeURL as String) {
                    group.enter()
                    provider.loadItem(forTypeIdentifier: kUTTypeURL as String, options: nil) { (item: NSSecureCoding?, error: Error?) in
                        defer { group.leave() }
                        guard let url = item as? URL else {
                            return
                        }

                        if url.isFileURL {
                            if let stored = ShareInboxStorage.storeFile(from: url, suggestedName: url.lastPathComponent) {
                                collectedItems.append(stored)
                            }
                        } else {
                            collectedItems.append(ShareInboxItem(type: .text, text: url.absoluteString, fileName: nil, relativePath: nil))
                        }
                    }
                    continue
                }

                if provider.hasItemConformingToTypeIdentifier(kUTTypeImage as String) {
                    group.enter()
                    provider.loadItem(forTypeIdentifier: kUTTypeImage as String, options: nil) { (item: NSSecureCoding?, error: Error?) in
                        defer { group.leave() }
                        if let url = item as? URL, let stored = ShareInboxStorage.storeFile(from: url, suggestedName: url.lastPathComponent) {
                            collectedItems.append(stored)
                            return
                        }

                        if let image = item as? UIImage, let data = UIImageJPEGRepresentation(image, 0.9),
                           let stored = ShareInboxStorage.storeFileData(data, suggestedName: "photo.jpg") {
                            collectedItems.append(stored)
                        }
                    }
                    continue
                }

                // KHANDAQ (#3): handle shared VIDEO explicitly. A movie file (from Photos/Files)
                // conforms to public.data and would be caught by the generic kUTTypeData branch
                // below, but the explicit handler gives a correct file name/extension and matches
                // the NSExtensionActivationSupportsMovieWithMaxCount rule. (A YouTube "share" is a
                // web URL, handled by the kUTTypeURL branch above as text — not a movie file.)
                if provider.hasItemConformingToTypeIdentifier(kUTTypeMovie as String) {
                    group.enter()
                    provider.loadItem(forTypeIdentifier: kUTTypeMovie as String, options: nil) { (item: NSSecureCoding?, error: Error?) in
                        defer { group.leave() }
                        if let url = item as? URL, let stored = ShareInboxStorage.storeFile(from: url, suggestedName: url.lastPathComponent) {
                            collectedItems.append(stored)
                            return
                        }

                        if let data = item as? Data {
                            let fileName = provider.suggestedName ?? "video.mp4"
                            if let stored = ShareInboxStorage.storeFileData(data, suggestedName: fileName) {
                                collectedItems.append(stored)
                            }
                        }
                    }
                    continue
                }

                if provider.hasItemConformingToTypeIdentifier(kUTTypeData as String) {
                    group.enter()
                    provider.loadItem(forTypeIdentifier: kUTTypeData as String, options: nil) { (item: NSSecureCoding?, error: Error?) in
                        defer { group.leave() }
                        if let url = item as? URL, let stored = ShareInboxStorage.storeFile(from: url, suggestedName: url.lastPathComponent) {
                            collectedItems.append(stored)
                            return
                        }

                        if let data = item as? Data {
                            let fileName = provider.suggestedName ?? "shared_file"
                            if let stored = ShareInboxStorage.storeFileData(data, suggestedName: fileName) {
                                collectedItems.append(stored)
                            }
                        }
                    }
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            self?.finalizeShare(with: collectedItems)
        }
    }

    private func finalizeShare(with items: [ShareInboxItem]) {
        let uniqueItems = deduplicatedItems(items)
        guard !uniqueItems.isEmpty else {
            finishWithFailure()
            return
        }

        let payload = ShareInboxPayload(version: 1, createdAt: Date().timeIntervalSince1970, items: uniqueItems)
        guard ShareInboxStorage.savePendingShare(payload) else {
            finishWithFailure()
            return
        }

        statusLabel.text = "Opening Khandaq…"

        guard let url = URL(string: "khandaq://share") else {
            finishWithFailure()
            return
        }

        extensionContext?.open(url, completionHandler: { [weak self] _ in
            self?.finishSuccessfully()
        })
    }

    private func deduplicatedItems(_ items: [ShareInboxItem]) -> [ShareInboxItem] {
        var result = [ShareInboxItem]()
        var seen = Set<String>()

        for item in items {
            let key: String
            switch item.type {
                case .text:
                    key = "text:" + (item.text ?? "")
                case .file:
                    key = "file:" + (item.relativePath ?? item.fileName ?? "")
            }

            if seen.insert(key).inserted {
                result.append(item)
            }
        }

        return result
    }

    private func finishSuccessfully() {
        guard !didFinish else {
            return
        }
        didFinish = true
        extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
    }

    private func finishWithFailure() {
        guard !didFinish else {
            return
        }
        didFinish = true
        let error = NSError(domain: "ShareExtension", code: 1, userInfo: [
            NSLocalizedDescriptionKey: "Could not share to Khandaq",
        ])
        extensionContext?.cancelRequest(withError: error)
    }
}
