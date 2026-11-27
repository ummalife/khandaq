// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// KHANDAQ (inbox #24, 27.11): one entry of the preview of a conversation. In a group another member's entries carry
/// the author over the first entry of their run and the member's tile beside its last, as the conversation has them:
/// without them the preview of a group read as the talk of two. A notice of the group stands in the middle.
struct ChatPreviewEntry {
    enum Side {
        case outgoing
        case incoming
        case notice
    }

    let text: String
    let side: Side
    /// The author written over the entry; only the first entry of another member's run in a group has one.
    let author: String?
    let authorColor: UIColor?
    /// The tile of another member's entry in a group, nil elsewhere. Every entry of the run keeps its column, and
    /// only the last shows the tile (showsTile).
    let tile: AirTileView.Style?
    let showsTile: Bool

    init(text: String, side: Side, author: String? = nil, authorColor: UIColor? = nil,
         tile: AirTileView.Style? = nil, showsTile: Bool = false) {
        self.text = text
        self.side = side
        self.author = author
        self.authorColor = authorColor
        self.tile = tile
        self.showsTile = showsTile
    }
}

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
    private let tile: AirTileView.Style
    private let photo: UIImage?
    private let entries: [ChatPreviewEntry]

    private let tableView = UITableView(frame: .zero, style: .plain)

    /// tile and photo are those of the chat's row in the list (ChatListContent.tileStyle).
    init(theme: Theme, title: String, tile: AirTileView.Style, photo: UIImage?, entries: [ChatPreviewEntry]) {
        self.theme = theme
        self.titleText = title
        self.tile = tile
        self.photo = photo
        self.entries = entries

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
        guard entries.count > 0 else {
            return
        }
        let lastRow = IndexPath(row: entries.count - 1, section: 0)
        tableView.scrollToRow(at: lastRow, at: .bottom, animated: false)
    }

    private func makeHeaderView() -> UIView {
        let container = UIView()
        container.backgroundColor = theme.colorForType(.NormalBackground)

        let tileView = AirTileView(side: Constants.avatarSize)
        tileView.translatesAutoresizingMaskIntoConstraints = false
        tileView.configure(style: tile, photo: photo)
        container.addSubview(tileView)

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
            tileView.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 12.0),
            tileView.centerYAnchor.constraint(equalTo: container.centerYAnchor),
            tileView.widthAnchor.constraint(equalToConstant: Constants.avatarSize),
            tileView.heightAnchor.constraint(equalToConstant: Constants.avatarSize),

            titleLabel.leadingAnchor.constraint(equalTo: tileView.trailingAnchor, constant: 10.0),
            titleLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -12.0),
            titleLabel.centerYAnchor.constraint(equalTo: container.centerYAnchor),

            separator.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            separator.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            separator.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            separator.heightAnchor.constraint(equalToConstant: 0.5),
        ])

        return container
    }
}

extension ChatPreviewController {
    /// The entries of messages, in their order: in a group with what its conversation writes over another member's
    /// entries and the tiles beside them, by the same names, tones and runs.
    static func entries(of messages: [OCTMessageAbstract], in chat: OCTChat, groups: OCTSubmanagerGroups) -> [ChatPreviewEntry] {
        guard chat.isGroup else {
            return messages.map { ChatPreviewEntry(text: text(for: $0), side: $0.isOutgoing() ? .outgoing : .incoming) }
        }

        let runs = messages.map {
            AirGroupTranscript.runKey(of: $0, in: chat, groups: groups, isText: $0.messageText != nil && !$0.groupSystemMessage)
        }
        return messages.indices.map { index -> ChatPreviewEntry in
            let message = messages[index]
            if message.groupSystemMessage {
                return ChatPreviewEntry(text: text(for: message), side: .notice)
            }
            if message.isOutgoing() {
                return ChatPreviewEntry(text: text(for: message), side: .outgoing)
            }
            let author = groupAuthor(of: message, in: chat, groups: groups)
            let previous = index > 0 ? runs[index - 1] : nil
            let next = index + 1 < runs.count ? runs[index + 1] : nil
            let startsRun = AirTranscript.startsRun(runs[index], after: previous)
            return ChatPreviewEntry(text: text(for: message),
                                    side: .incoming,
                                    author: startsRun ? author.name : nil,
                                    authorColor: startsRun ? author.color : nil,
                                    tile: author.tile,
                                    showsTile: AirTranscript.endsRun(runs[index], before: next))
        }
    }

    /// Who wrote another member's entry, as the conversation names them (ChatGroupController.configureGroupEntry)
    /// without the role, which the preview has no members for: their name in their tone, the plain tile for an
    /// author nobody confirmed or nobody can name.
    private static func groupAuthor(of message: OCTMessageAbstract,
                                    in chat: OCTChat,
                                    groups: OCTSubmanagerGroups) -> (name: String, color: UIColor, tile: AirTileView.Style) {
        guard groups.isGroupMessageAttributable(message, in: chat) else {
            return (String(localized: "air_group_author_unconfirmed"), KQColor.ink2, .unconfirmed)
        }
        let knownName = AirGroupTranscript.peerName(of: message, in: chat, groups: groups)
        let name = knownName ?? String(localized: "air_group_author_unknown")
        let tone = AirTone.index(for: AirGroupTranscript.toneKey(of: message, name: name))
        let unconfirmed = message.groupHistorySync && !groups.isGroupMessageAuthorVerified(message, in: chat)
        let tile: AirTileView.Style = unconfirmed || knownName == nil
            ? .unconfirmed
            : .person(initials: AirInitials.from(name), tone: tone)
        return (name, AirTone.color(at: tone), tile)
    }

    static func text(for message: OCTMessageAbstract) -> String {
        if let messageText = message.messageText {
            // KHANDAQ (#109): clean visible body in the peek bubbles, not the reply/mention wire markup.
            return MessageReplyHelper.plainBody(for: message) ?? messageText.text ?? ""
        }
        if let file = message.messageFile {
            let fileName = file.fileName ?? ""
            let key = message.isOutgoing() ? "chat_outgoing_file" : "chat_incoming_file"
            return String(localized: key) + " \(fileName)"
        }
        if let call = message.messageCall {
            // As in the list and the transcript; an answered call read "No answer" or "Missed call" here (stage 5).
            return ChatListContent.callPreview(call, outgoing: message.isOutgoing()).text
        }
        return ""
    }
}

extension ChatPreviewController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return entries.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: BubbleCell.reuseId, for: indexPath) as! BubbleCell
        cell.configure(entry: entries[indexPath.row], theme: theme)
        return cell
    }
}

/// Minimal chat-bubble cell used only by the read-only preview.
private final class BubbleCell: UITableViewCell {
    static let reuseId = "ChatPreviewBubbleCell"

    private struct Constants {
        static let margin: CGFloat = 12.0
        static let tileSide: CGFloat = 28.0
        static let tileGap: CGFloat = 6.0
    }

    private let bubble = UIView()
    private let authorLabel = UILabel()
    private let label = UILabel()
    private let tileView = AirTileView(side: Constants.tileSide)
    private var leadingToMargin: NSLayoutConstraint?
    private var leadingToTile: NSLayoutConstraint?
    private var trailingConstraint: NSLayoutConstraint?
    private var centerConstraint: NSLayoutConstraint?

    override init(style: UITableViewCellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)

        selectionStyle = .none
        backgroundColor = .clear
        contentView.backgroundColor = .clear

        tileView.translatesAutoresizingMaskIntoConstraints = false
        contentView.addSubview(tileView)

        bubble.translatesAutoresizingMaskIntoConstraints = false
        bubble.layer.cornerRadius = 12.0
        bubble.layer.masksToBounds = true
        contentView.addSubview(bubble)

        authorLabel.font = KQFont.font(.label, weight: 600)
        authorLabel.numberOfLines = 1
        label.numberOfLines = 0

        let stack = UIStackView(arrangedSubviews: [authorLabel, label])
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.spacing = 2.0
        bubble.addSubview(stack)

        leadingToMargin = bubble.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: Constants.margin)
        leadingToTile = bubble.leadingAnchor.constraint(equalTo: tileView.trailingAnchor, constant: Constants.tileGap)
        trailingConstraint = bubble.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -Constants.margin)
        centerConstraint = bubble.centerXAnchor.constraint(equalTo: contentView.centerXAnchor)

        // The tile stands at the foot of the bubble, as at the last entry of a run in the conversation, and the row
        // is never lower than the tile.
        let tileTop = tileView.topAnchor.constraint(greaterThanOrEqualTo: contentView.topAnchor, constant: 4.0)

        NSLayoutConstraint.activate([
            tileView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: Constants.margin),
            tileView.bottomAnchor.constraint(equalTo: bubble.bottomAnchor),
            tileView.widthAnchor.constraint(equalToConstant: Constants.tileSide),
            tileView.heightAnchor.constraint(equalToConstant: Constants.tileSide),
            tileTop,

            bubble.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 4.0),
            bubble.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -4.0),
            bubble.widthAnchor.constraint(lessThanOrEqualTo: contentView.widthAnchor, multiplier: 0.78),

            stack.topAnchor.constraint(equalTo: bubble.topAnchor, constant: 7.0),
            stack.bottomAnchor.constraint(equalTo: bubble.bottomAnchor, constant: -7.0),
            stack.leadingAnchor.constraint(equalTo: bubble.leadingAnchor, constant: 10.0),
            stack.trailingAnchor.constraint(equalTo: bubble.trailingAnchor, constant: -10.0),
        ])
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(entry: ChatPreviewEntry, theme: Theme) {
        label.text = entry.text
        authorLabel.text = entry.author
        authorLabel.textColor = entry.authorColor
        authorLabel.isHidden = entry.author == nil

        if let style = entry.tile {
            tileView.configure(style: style, photo: nil)
            tileView.isHidden = !entry.showsTile
        }
        else {
            tileView.isHidden = true
        }

        // Every alignment constraint off first, so that none of them ever pulls against another.
        leadingToMargin?.isActive = false
        leadingToTile?.isActive = false
        trailingConstraint?.isActive = false
        centerConstraint?.isActive = false

        switch entry.side {
            case .outgoing:
                bubble.backgroundColor = theme.colorForType(.LinkText)
                label.textColor = theme.colorForType(.NormalBackground)
                label.font = UIFont.systemFont(ofSize: 15.0)
                label.textAlignment = .natural
                trailingConstraint?.isActive = true
            case .incoming:
                bubble.backgroundColor = theme.colorForType(.SeparatorsAndBorders)
                label.textColor = theme.colorForType(.NormalText)
                label.font = UIFont.systemFont(ofSize: 15.0)
                label.textAlignment = .natural
                (entry.tile == nil ? leadingToMargin : leadingToTile)?.isActive = true
            case .notice:
                bubble.backgroundColor = .clear
                label.textColor = KQColor.ink2
                label.font = UIFont.systemFont(ofSize: 13.0)
                label.textAlignment = .center
                centerConstraint?.isActive = true
        }
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        label.text = nil
        authorLabel.text = nil
    }
}
