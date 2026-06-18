// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// KHANDAQ (#13): a lightweight, READ-ONLY preview of a conversation, shown as the peek/context-menu
/// preview when long-pressing a chat row in the list. It is deliberately self-contained — it reads
/// only the OCTChat + its recent messages and renders them as simple bubbles. Unlike the live
/// ChatPrivateController/ChatGroupController it has NO side effects (it does not mark the chat read,
/// register notifications or drive submanagers), so peeking can never mutate session state.
final class ChatPreviewController: UIViewController {
    private struct Constants {
        static let maxMessages = 24
        static let avatarSize: CGFloat = 32.0
        static let preferredWidth: CGFloat = 320.0
        static let preferredHeight: CGFloat = 380.0
    }

    private let theme: Theme
    private let titleText: String
    private let avatar: UIImage?
    private let messages: [OCTMessageAbstract]

    private let tableView = UITableView(frame: .zero, style: .plain)

    init(theme: Theme, title: String, avatar: UIImage?, messages: [OCTMessageAbstract]) {
        self.theme = theme
        self.titleText = title
        self.avatar = avatar
        self.messages = messages

        super.init(nibName: nil, bundle: nil)

        preferredContentSize = CGSize(width: Constants.preferredWidth, height: Constants.preferredHeight)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = theme.colorForType(.NormalBackground)

        let header = makeHeaderView()
        header.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(header)

        tableView.translatesAutoresizingMaskIntoConstraints = false
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.separatorStyle = .none
        tableView.allowsSelection = false
        tableView.dataSource = self
        tableView.estimatedRowHeight = 44.0
        tableView.rowHeight = UITableViewAutomaticDimension
        tableView.register(BubbleCell.self, forCellReuseIdentifier: BubbleCell.reuseId)
        view.addSubview(tableView)

        NSLayoutConstraint.activate([
            header.topAnchor.constraint(equalTo: view.topAnchor),
            header.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            header.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            header.heightAnchor.constraint(equalToConstant: 52.0),

            tableView.topAnchor.constraint(equalTo: header.bottomAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        scrollToBottom()
    }

    private func scrollToBottom() {
        guard messages.count > 0 else {
            return
        }
        let lastRow = IndexPath(row: messages.count - 1, section: 0)
        tableView.scrollToRow(at: lastRow, at: .bottom, animated: false)
    }

    private func makeHeaderView() -> UIView {
        let container = UIView()
        container.backgroundColor = theme.colorForType(.NormalBackground)

        let avatarView = UIImageView(image: avatar)
        avatarView.translatesAutoresizingMaskIntoConstraints = false
        avatarView.contentMode = .scaleAspectFill
        avatarView.clipsToBounds = true
        avatarView.layer.cornerRadius = Constants.avatarSize / 2.0
        container.addSubview(avatarView)

        let titleLabel = UILabel()
        titleLabel.translatesAutoresizingMaskIntoConstraints = false
        titleLabel.text = titleText
        titleLabel.textColor = theme.colorForType(.NormalText)
        titleLabel.font = UIFont.systemFont(ofSize: 17.0, weight: .semibold)
        container.addSubview(titleLabel)

        let separator = UIView()
        separator.translatesAutoresizingMaskIntoConstraints = false
        separator.backgroundColor = theme.colorForType(.SeparatorsAndBorders)
        container.addSubview(separator)

        NSLayoutConstraint.activate([
            avatarView.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 12.0),
            avatarView.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            avatarView.widthAnchor.constraint(equalToConstant: Constants.avatarSize),
            avatarView.heightAnchor.constraint(equalToConstant: Constants.avatarSize),

            titleLabel.leadingAnchor.constraint(equalTo: avatarView.trailingAnchor, constant: 10.0),
            titleLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -12.0),
            titleLabel.centerYAnchor.constraint(equalTo: container.centerYAnchor),

            separator.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            separator.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            separator.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            separator.heightAnchor.constraint(equalToConstant: 0.5),
        ])

        return container
    }

    fileprivate func text(for message: OCTMessageAbstract) -> String {
        if let messageText = message.messageText {
            return messageText.text ?? ""
        }
        if let file = message.messageFile {
            let fileName = file.fileName ?? ""
            let key = message.isOutgoing() ? "chat_outgoing_file" : "chat_incoming_file"
            return String(localized: key) + " \(fileName)"
        }
        if message.messageCall != nil {
            return String(localized: message.isOutgoing() ? "chat_unanwered_call" : "chat_missed_call_message")
        }
        return ""
    }
}

extension ChatPreviewController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return messages.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: BubbleCell.reuseId, for: indexPath) as! BubbleCell
        let message = messages[indexPath.row]
        cell.configure(text: text(for: message), outgoing: message.isOutgoing(), theme: theme)
        return cell
    }
}

/// Minimal chat-bubble cell used only by the read-only preview.
private final class BubbleCell: UITableViewCell {
    static let reuseId = "ChatPreviewBubbleCell"

    private let bubble = UIView()
    private let label = UILabel()
    private var leadingConstraint: NSLayoutConstraint?
    private var trailingConstraint: NSLayoutConstraint?

    override init(style: UITableViewCellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)

        selectionStyle = .none
        backgroundColor = .clear
        contentView.backgroundColor = .clear

        bubble.translatesAutoresizingMaskIntoConstraints = false
        bubble.layer.cornerRadius = 12.0
        bubble.layer.masksToBounds = true
        contentView.addSubview(bubble)

        label.translatesAutoresizingMaskIntoConstraints = false
        label.numberOfLines = 0
        label.font = UIFont.systemFont(ofSize: 15.0)
        bubble.addSubview(label)

        let leading = bubble.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 12.0)
        let trailing = bubble.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -12.0)
        leadingConstraint = leading
        trailingConstraint = trailing

        NSLayoutConstraint.activate([
            bubble.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 4.0),
            bubble.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -4.0),
            bubble.widthAnchor.constraint(lessThanOrEqualTo: contentView.widthAnchor, multiplier: 0.78),

            label.topAnchor.constraint(equalTo: bubble.topAnchor, constant: 7.0),
            label.bottomAnchor.constraint(equalTo: bubble.bottomAnchor, constant: -7.0),
            label.leadingAnchor.constraint(equalTo: bubble.leadingAnchor, constant: 10.0),
            label.trailingAnchor.constraint(equalTo: bubble.trailingAnchor, constant: -10.0),
        ])
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(text: String, outgoing: Bool, theme: Theme) {
        label.text = text

        if outgoing {
            bubble.backgroundColor = theme.colorForType(.LinkText)
            label.textColor = theme.colorForType(.NormalBackground)
            leadingConstraint?.isActive = false
            trailingConstraint?.isActive = true
        }
        else {
            bubble.backgroundColor = theme.colorForType(.SeparatorsAndBorders)
            label.textColor = theme.colorForType(.NormalText)
            trailingConstraint?.isActive = false
            leadingConstraint?.isActive = true
        }
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        label.text = nil
    }
}
