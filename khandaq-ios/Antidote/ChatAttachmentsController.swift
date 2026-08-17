// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import UIKit
import SnapKit
import QuickLook
import MobileCoreServices

/**
 Everything a chat has ever received or sent as a file, in one place, split into documents, audio
 and voice notes (KHANDAQ, user request 17.08 — Android got the same three folders).

 Voice notes are deliberately excluded from the audio list: a voice note is an .m4a too, so putting
 it in both would make "voice" a duplicate of "audio" instead of a separate place to look.
 */
final class ChatAttachmentsController: UIViewController {
    enum Kind: Int, CaseIterable {
        case documents
        case audio
        case voice

        var title: String {
            switch self {
                case .documents: return String(localized: "attachments_documents")
                case .audio: return String(localized: "attachments_audio")
                case .voice: return String(localized: "attachments_voice")
            }
        }
    }

    fileprivate let theme: Theme
    fileprivate let chat: OCTChat
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    fileprivate var messages: Results<OCTMessageAbstract>!
    fileprivate var messagesToken: RLMNotificationToken?

    fileprivate var kind: Kind = .documents
    fileprivate var rows: [OCTMessageAbstract] = []

    fileprivate var segmentedControl: UISegmentedControl!
    fileprivate var tableView: UITableView!
    fileprivate var placeholderLabel: UILabel!

    fileprivate var previewController: QuickLookPreviewController?
    fileprivate var previewDataSource: AttachmentPreviewDataSource?

    init(theme: Theme, chat: OCTChat, submanagerObjects: OCTSubmanagerObjects) {
        self.theme = theme
        self.chat = chat
        self.submanagerObjects = submanagerObjects

        super.init(nibName: nil, bundle: nil)

        title = String(localized: "attachments_title")
        edgesForExtendedLayout = UIRectEdge()

        // Only transfers that finished have a file on disk to show; an outgoing cancelled one still
        // has its local copy, which is why the preview data source accepts it too.
        let predicate = NSPredicate(format: "chatUniqueIdentifier == %@ AND messageFile != nil",
                                    chat.uniqueIdentifier)
        messages = submanagerObjects.messages(predicate: predicate)
                .sortedResultsUsingProperty("sortTimestamp", ascending: false)
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        messagesToken?.invalidate()
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = theme.colorForType(.NormalBackground)

        createSegmentedControl()
        createTableView()
        createPlaceholder()
        installConstraints()

        reload()

        messagesToken = messages.addNotificationBlock { [weak self] _ in
            self?.reload()
        }
    }
}

// MARK: - Views

private extension ChatAttachmentsController {
    func createSegmentedControl() {
        segmentedControl = UISegmentedControl(items: Kind.allCases.map { $0.title })
        segmentedControl.selectedSegmentIndex = kind.rawValue
        segmentedControl.tintColor = theme.colorForType(.LinkText)
        segmentedControl.addTarget(self,
                                   action: #selector(ChatAttachmentsController.kindChanged),
                                   for: .valueChanged)
        view.addSubview(segmentedControl)
    }

    func createTableView() {
        tableView = UITableView()
        tableView.dataSource = self
        tableView.delegate = self
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.rowHeight = 60.0
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "attachment")
        view.addSubview(tableView)
    }

    func createPlaceholder() {
        placeholderLabel = UILabel()
        placeholderLabel.text = String(localized: "attachments_empty")
        placeholderLabel.textColor = theme.colorForType(.EmptyScreenPlaceholderText)
        placeholderLabel.textAlignment = .center
        placeholderLabel.numberOfLines = 0
        view.addSubview(placeholderLabel)
    }

    func installConstraints() {
        segmentedControl.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide).offset(12.0)
            $0.leading.equalTo(view).offset(16.0)
            $0.trailing.equalTo(view).offset(-16.0)
        }

        tableView.snp.makeConstraints {
            $0.top.equalTo(segmentedControl.snp.bottom).offset(12.0)
            $0.leading.trailing.bottom.equalTo(view)
        }

        placeholderLabel.snp.makeConstraints {
            $0.center.equalTo(tableView)
            $0.leading.greaterThanOrEqualTo(view).offset(24.0)
            $0.trailing.lessThanOrEqualTo(view).offset(-24.0)
        }
    }
}

// MARK: - Data

private extension ChatAttachmentsController {
    @objc func kindChanged() {
        kind = Kind(rawValue: segmentedControl.selectedSegmentIndex) ?? .documents
        reload()
    }

    func reload() {
        rows = (0..<messages.count).compactMap { messages[$0] }.filter { matches($0) }
        tableView.reloadData()
        placeholderLabel.isHidden = !rows.isEmpty
    }

    func matches(_ message: OCTMessageAbstract) -> Bool {
        guard let file = message.messageFile else {
            return false
        }

        let isVoice = VoiceMessageHelper.isVoiceMessage(fileName: file.fileName, filePath: file.filePath())
        switch kind {
            case .voice:
                return isVoice
            case .audio:
                return !isVoice && isAudio(file)
            case .documents:
                return !isVoice && !isAudio(file)
        }
    }

    func isAudio(_ file: OCTMessageFile) -> Bool {
        guard let uti = fileUTI(for: file) else {
            return false
        }
        return UTTypeConformsTo(uti as CFString, kUTTypeAudio)
    }

    func fileUTI(for file: OCTMessageFile) -> String? {
        if let uti = file.fileUTI, !uti.isEmpty {
            return uti
        }
        guard let name = file.fileName else {
            return nil
        }
        let ext = (name as NSString).pathExtension
        guard !ext.isEmpty,
              let uti = UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension,
                                                              ext as CFString, nil)?
                      .takeRetainedValue() as String? else {
            return nil
        }
        return uti
    }

    func subtitle(for message: OCTMessageAbstract) -> String {
        guard let file = message.messageFile else {
            return ""
        }
        let size = ByteCountFormatter.string(fromByteCount: Int64(file.fileSize), countStyle: .file)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return "\(size) · \(formatter.string(from: message.date()))"
    }

    func displayName(for message: OCTMessageAbstract) -> String {
        guard let file = message.messageFile else {
            return ""
        }
        return VoiceMessageHelper.displayFileName(for: file.fileName)
    }
}

// MARK: - Table

extension ChatAttachmentsController: UITableViewDataSource, UITableViewDelegate {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return rows.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "attachment", for: indexPath)
        let message = rows[indexPath.row]

        cell.backgroundColor = theme.colorForType(.NormalBackground)
        cell.textLabel?.text = displayName(for: message)
        cell.textLabel?.textColor = theme.colorForType(.NormalText)
        cell.detailTextLabel?.text = subtitle(for: message)
        cell.detailTextLabel?.textColor = theme.colorForType(.ChatListCellMessage)
        cell.accessoryType = .disclosureIndicator
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)

        let message = rows[indexPath.row]
        guard let file = message.messageFile,
              let path = file.filePath(),
              FileManager.default.fileExists(atPath: path) else {
            // A transfer that never finished has nothing on disk yet — say so rather than opening an
            // empty preview.
            UIAlertController.showWithTitle("", message: String(localized: "attachments_not_downloaded"),
                                            retryBlock: nil)
            return
        }

        let dataSource = AttachmentPreviewDataSource(url: URL(fileURLWithPath: path),
                                                    title: displayName(for: message))
        let controller = QuickLookPreviewController()
        controller.dataSourceStorage = dataSource
        controller.dataSource = dataSource

        previewDataSource = dataSource
        previewController = controller
        present(controller, animated: true, completion: nil)
    }
}

/// A one-item QuickLook source: the attachments list opens exactly what was tapped.
private final class AttachmentPreviewDataSource: NSObject, QuickLookPreviewControllerDataSource {
    // Required by the protocol so the controller can be reached back from its source.
    weak var previewController: QuickLookPreviewController?

    private let item: AttachmentPreviewItem

    init(url: URL, title: String) {
        item = AttachmentPreviewItem(url: url, title: title)
        super.init()
    }

    func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
        return 1
    }

    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
        return item
    }
}

private final class AttachmentPreviewItem: NSObject, QLPreviewItem {
    @objc var previewItemURL: URL?
    @objc var previewItemTitle: String?

    init(url: URL, title: String?) {
        previewItemURL = url
        previewItemTitle = title
    }
}
