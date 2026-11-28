// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import MobileCoreServices
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

    /// KHANDAQ (inbox #28, 28.11): what an entry shows in place of words, as the conversation shows it: a photo or a
    /// video by the file the message keeps, or the map of a place it sent. The preview named the file instead
    /// ("Outgoing file: Photo_….jpg") and wrote a place as its wire text ("khandaq-location:…").
    enum Media: Equatable {
        /// pixelSize is the shape of the picture when it is known before the picture is read; nil keeps the
        /// conversation's 4:3.
        case photo(path: String, pixelSize: CGSize?)
        case video(path: String, pixelSize: CGSize?)
        case location(latitude: Double, longitude: Double)
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
    /// KHANDAQ (inbox #28, 28.11): the message the entry answers, quoted over its words as the conversation quotes it.
    let reply: MessageReplyHelper.ReplyMeta?
    let media: Media?

    init(text: String, side: Side, author: String? = nil, authorColor: UIColor? = nil,
         tile: AirTileView.Style? = nil, showsTile: Bool = false,
         reply: MessageReplyHelper.ReplyMeta? = nil, media: Media? = nil) {
        self.text = text
        self.side = side
        self.author = author
        self.authorColor = authorColor
        self.tile = tile
        self.showsTile = showsTile
        self.reply = reply
        self.media = media
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
        /// A photo or a video takes three quarters of the box it has in the conversation: the preview is narrower.
        static let mediaScale: CGFloat = 0.75
    }

    /// The photos the preview has drawn, by their file: a second look at a chat draws them at once.
    static let thumbnails = NSCache<NSString, UIImage>()

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
    /// Marks the reply of a message as the conversation marks it (ChatDeletedReplyTargets): a quote of a message
    /// deleted here says so.
    typealias ReplyMarker = (MessageReplyHelper.ReplyMeta?, OCTMessageAbstract) -> MessageReplyHelper.ReplyMeta?

    /// The entries of messages, in their order: in a group with what its conversation writes over another member's
    /// entries and the tiles beside them, by the same names, tones and runs.
    static func entries(of messages: [OCTMessageAbstract],
                        in chat: OCTChat,
                        groups: OCTSubmanagerGroups,
                        markReply: ReplyMarker = { reply, _ in reply }) -> [ChatPreviewEntry] {
        guard chat.isGroup else {
            return messages.map { message in
                let shown = content(of: message, markReply: markReply)
                return ChatPreviewEntry(text: shown.text,
                                        side: message.isOutgoing() ? .outgoing : .incoming,
                                        reply: shown.reply,
                                        media: shown.media)
            }
        }

        let runs = messages.map {
            AirGroupTranscript.runKey(of: $0, in: chat, groups: groups, isText: $0.messageText != nil && !$0.groupSystemMessage)
        }
        return messages.indices.map { index -> ChatPreviewEntry in
            let message = messages[index]
            if message.groupSystemMessage {
                return ChatPreviewEntry(text: content(of: message).text, side: .notice)
            }
            let shown = content(of: message, markReply: markReply)
            if message.isOutgoing() {
                return ChatPreviewEntry(text: shown.text, side: .outgoing, reply: shown.reply, media: shown.media)
            }
            let author = groupAuthor(of: message, in: chat, groups: groups)
            let previous = index > 0 ? runs[index - 1] : nil
            let next = index + 1 < runs.count ? runs[index + 1] : nil
            let startsRun = AirTranscript.startsRun(runs[index], after: previous)
            return ChatPreviewEntry(text: shown.text,
                                    side: .incoming,
                                    author: startsRun ? author.name : nil,
                                    authorColor: startsRun ? author.color : nil,
                                    tile: author.tile,
                                    showsTile: AirTranscript.endsRun(runs[index], before: next),
                                    reply: shown.reply,
                                    media: shown.media)
        }
    }

    /// Who wrote another member's entry, as the conversation names them (ChatGroupController.configureGroupEntry)
    /// without the role, which the preview has no members for: their name in their tone, the plain tile for an
    /// author whose signing key went stale (KQ-03) or nobody can name.
    private static func groupAuthor(of message: OCTMessageAbstract,
                                    in chat: OCTChat,
                                    groups: OCTSubmanagerGroups) -> (name: String, color: UIColor, tile: AirTileView.Style) {
        guard groups.isGroupMessageAttributable(message, in: chat) else {
            return (String(localized: "air_group_author_unconfirmed"), KQColor.ink2, .unconfirmed)
        }
        let knownName = AirGroupTranscript.peerName(of: message, in: chat, groups: groups)
        let name = knownName ?? String(localized: "air_group_author_unknown")
        let tone = AirTone.index(for: AirGroupTranscript.toneKey(of: message, name: name))
        // KHANDAQ (owner, 28.11.2026): a row the history brought without a signature over it has its author's tile, as
        // in the conversation (ChatGroupController.configureGroupEntry).
        let tile: AirTileView.Style = knownName == nil
            ? .unconfirmed
            : .person(initials: AirInitials.from(name), tone: tone)
        return (name, AirTone.color(at: tone), tile)
    }

    /// What the entry of a message says and shows: its words without the reply and mention markup of the wire, the
    /// quote of the message it answers, and the photo, the video or the place it shows in place of words.
    static func content(of message: OCTMessageAbstract,
                        markReply: ReplyMarker = { reply, _ in reply })
        -> (text: String, reply: MessageReplyHelper.ReplyMeta?, media: ChatPreviewEntry.Media?) {
        if let messageText = message.messageText {
            let reply = markReply(MessageReplyHelper.parse(messageText.text).reply, message)
            // KHANDAQ (#109): clean visible body in the peek bubbles, not the reply/mention wire markup.
            let body = MessageReplyHelper.plainBody(for: message) ?? messageText.text ?? ""
            // A place as the conversation writes it: its coordinates under its map (populateTextModel).
            if let place = LocationMessage.parse(body) {
                return (String(format: "%.5f, %.5f", place.latitude, place.longitude),
                        reply,
                        .location(latitude: place.latitude, longitude: place.longitude))
            }
            return (body, reply, nil)
        }
        if let file = message.messageFile {
            if let media = media(of: file, outgoing: message.isOutgoing()) {
                return ("", nil, media)
            }
            let fileName = file.fileName ?? ""
            let key = message.isOutgoing() ? "chat_outgoing_file" : "chat_incoming_file"
            return (String(localized: key) + " \(fileName)", nil, nil)
        }
        if let call = message.messageCall {
            // As in the list and the transcript; an answered call read "No answer" or "Missed call" here (stage 5).
            return (ChatListContent.callPreview(call, outgoing: message.isOutgoing()).text, nil, nil)
        }
        return ("", nil, nil)
    }

    /// The photo or the video a file message shows in the conversation (maybeLoadImageForCellAtPath): only a file
    /// that is all here, or ours. Any other file, a voice note among them, the conversation shows by its name too, and
    /// so does the preview a file that is gone from the device, rather than an empty box.
    static func media(of file: OCTMessageFile, outgoing: Bool) -> ChatPreviewEntry.Media? {
        guard outgoing || file.fileType == .ready,
              let path = file.filePath(), !path.isEmpty,
              FileManager.default.fileExists(atPath: path) else {
            return nil
        }
        let uti = ChatFileMediaLoader.inferredUTI(for: file)
        let type = (uti ?? "") as CFString
        // Voice before video: an .m4a is an MPEG-4 to UTTypeConformsTo, and isVideoFile takes it (inlineMediaSize).
        if UTTypeConformsTo(type, kUTTypeAudio) || VoiceMessageHelper.isVoiceMessage(fileName: file.fileName, filePath: path) {
            return nil
        }
        if UTTypeConformsTo(type, kUTTypeImage) {
            return .photo(path: path, pixelSize: ChatFileMediaLoader.imagePixelSize(at: path))
        }
        if ChatFileMediaLoader.isVideoFile(uti: uti, fileName: file.fileName) {
            // A video's own size is read from its track, which on the main thread hung the conversation on iOS 26
            // (inlineMediaSize): the frame the conversation has already read gives it, or the box stays 4:3.
            return .video(path: path, pixelSize: ChatFileMediaLoader.cachedPreview(for: path)?.image.size)
        }
        return nil
    }

    /// The box of a photo or a video: its shape as in the conversation (LoadingImageView.previewBox), 4:3 for one of
    /// a shape not known yet, as there.
    static func mediaBox(for pixelSize: CGSize?) -> CGSize {
        let known = pixelSize.flatMap { $0.width > 0 && $0.height > 0 ? $0 : nil }
        let box = LoadingImageView.previewBox(for: known ?? CGSize(width: 4, height: 3))
        return CGSize(width: (box.width * Constants.mediaScale).rounded(), height: (box.height * Constants.mediaScale).rounded())
    }

    /// The box of the map of a place: the conversation's snapshot, as wide as a photo of 4:3 here.
    static var mapBox: CGSize {
        let snapshot = ChatBaseTextCell.mapSnapshotSize
        let width = mediaBox(for: nil).width
        return CGSize(width: width, height: (width * snapshot.height / snapshot.width).rounded())
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
        static let textInsets = UIEdgeInsets(top: 7.0, left: 10.0, bottom: 7.0, right: 10.0)
        static let mediaGap: CGFloat = 4.0
    }

    private let bubble = UIView()
    private let authorLabel = UILabel()
    private let quoteView = ChatReplyQuoteView()
    private let mediaView = UIImageView()
    private let mediaGlyph = UIImageView()
    private let durationBadge = UIView()
    private let durationLabel = UILabel()
    private let label = UILabel()
    private let tileView = AirTileView(side: Constants.tileSide)
    private var leadingToMargin: NSLayoutConstraint?
    private var leadingToTile: NSLayoutConstraint?
    private var trailingConstraint: NSLayoutConstraint?
    private var centerConstraint: NSLayoutConstraint?
    private var stackTop: NSLayoutConstraint?
    private var stackBottom: NSLayoutConstraint?
    private var stackLeading: NSLayoutConstraint?
    private var stackTrailing: NSLayoutConstraint?
    private var mediaWidth: NSLayoutConstraint?
    private var mediaHeight: NSLayoutConstraint?
    /// The picture the cell shows, by its file or its place: one read late goes on only while the cell still shows it.
    private var shownMediaKey: String?

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

        // The quote as the conversation draws it (a transcript quote), in the colours of the bubble it stands on.
        quoteView.useTranscriptStyle()
        quoteView.isUserInteractionEnabled = false

        configureMediaView()

        let stack = UIStackView(arrangedSubviews: [authorLabel, quoteView, mediaView, label])
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.spacing = 2.0
        stack.setCustomSpacing(Constants.mediaGap, after: quoteView)
        stack.setCustomSpacing(Constants.mediaGap, after: mediaView)
        bubble.addSubview(stack)

        leadingToMargin = bubble.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: Constants.margin)
        leadingToTile = bubble.leadingAnchor.constraint(equalTo: tileView.trailingAnchor, constant: Constants.tileGap)
        trailingConstraint = bubble.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -Constants.margin)
        centerConstraint = bubble.centerXAnchor.constraint(equalTo: contentView.centerXAnchor)

        // The tile stands at the foot of the bubble, as at the last entry of a run in the conversation, and the row
        // is never lower than the tile.
        let tileTop = tileView.topAnchor.constraint(greaterThanOrEqualTo: contentView.topAnchor, constant: 4.0)

        // The words keep their margin inside the bubble; a bare photo or video takes the whole of it.
        let insets = Constants.textInsets
        let stackTop = stack.topAnchor.constraint(equalTo: bubble.topAnchor, constant: insets.top)
        let stackBottom = stack.bottomAnchor.constraint(equalTo: bubble.bottomAnchor, constant: -insets.bottom)
        let stackLeading = stack.leadingAnchor.constraint(equalTo: bubble.leadingAnchor, constant: insets.left)
        let stackTrailing = stack.trailingAnchor.constraint(equalTo: bubble.trailingAnchor, constant: -insets.right)
        self.stackTop = stackTop
        self.stackBottom = stackBottom
        self.stackLeading = stackLeading
        self.stackTrailing = stackTrailing

        NSLayoutConstraint.activate([
            tileView.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: Constants.margin),
            tileView.bottomAnchor.constraint(equalTo: bubble.bottomAnchor),
            tileView.widthAnchor.constraint(equalToConstant: Constants.tileSide),
            tileView.heightAnchor.constraint(equalToConstant: Constants.tileSide),
            tileTop,

            bubble.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 4.0),
            bubble.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -4.0),
            bubble.widthAnchor.constraint(lessThanOrEqualTo: contentView.widthAnchor, multiplier: 0.78),

            stackTop,
            stackBottom,
            stackLeading,
            stackTrailing,
        ])
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// The picture of a photo, a video or a place: rounded as in the conversation, with the glyph of a video or of a
    /// place in its middle and the length of a video in its corner.
    private func configureMediaView() {
        mediaView.translatesAutoresizingMaskIntoConstraints = false
        mediaView.contentMode = .scaleAspectFill
        mediaView.clipsToBounds = true
        mediaView.layer.cornerRadius = KQRadius.card
        mediaView.backgroundColor = KQColor.sunken
        mediaView.isHidden = true

        mediaGlyph.translatesAutoresizingMaskIntoConstraints = false
        mediaGlyph.contentMode = .center
        mediaView.addSubview(mediaGlyph)

        durationBadge.translatesAutoresizingMaskIntoConstraints = false
        durationBadge.backgroundColor = KQColor.mediaShade
        durationBadge.layer.cornerRadius = KQRadius.small
        durationBadge.isHidden = true
        mediaView.addSubview(durationBadge)

        durationLabel.translatesAutoresizingMaskIntoConstraints = false
        durationLabel.font = KQFont.tabular(KQFont.font(.caption))
        durationLabel.textColor = KQColor.fixedWhite
        durationBadge.addSubview(durationLabel)

        // Off while the entry has no picture: a hidden view of the stack must not hold a size against it.
        mediaWidth = mediaView.widthAnchor.constraint(equalToConstant: 0.0)
        mediaHeight = mediaView.heightAnchor.constraint(equalToConstant: 0.0)

        NSLayoutConstraint.activate([
            mediaGlyph.centerXAnchor.constraint(equalTo: mediaView.centerXAnchor),
            mediaGlyph.centerYAnchor.constraint(equalTo: mediaView.centerYAnchor),

            durationBadge.leadingAnchor.constraint(equalTo: mediaView.leadingAnchor, constant: KQSpace.x8),
            durationBadge.bottomAnchor.constraint(equalTo: mediaView.bottomAnchor, constant: -KQSpace.x8),

            durationLabel.topAnchor.constraint(equalTo: durationBadge.topAnchor, constant: KQSpace.x2),
            durationLabel.bottomAnchor.constraint(equalTo: durationBadge.bottomAnchor, constant: -KQSpace.x2),
            durationLabel.leadingAnchor.constraint(equalTo: durationBadge.leadingAnchor, constant: KQSpace.x6),
            durationLabel.trailingAnchor.constraint(equalTo: durationBadge.trailingAnchor, constant: -KQSpace.x6),
        ])
    }

    func configure(entry: ChatPreviewEntry, theme: Theme) {
        label.text = entry.text
        // A photo or a video has no words of its own.
        label.isHidden = entry.text.isEmpty
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

        configureQuote(entry.reply, side: entry.side, theme: theme)
        configureMedia(entry.media)

        // A photo or a video stands bare, as in the conversation; a place keeps its words in the bubble under its map.
        var bare = false
        if let media = entry.media {
            switch media {
                case .photo, .video:
                    bare = true
                case .location:
                    bare = false
            }
        }
        if bare {
            bubble.backgroundColor = .clear
        }
        let insets = bare ? .zero : Constants.textInsets
        stackTop?.constant = insets.top
        stackBottom?.constant = -insets.bottom
        stackLeading?.constant = insets.left
        stackTrailing?.constant = -insets.right
    }

    /// The quote of the message the entry answers: the transcript's plate in the colours of the bubble, which is dark
    /// for our own entries.
    private func configureQuote(_ reply: MessageReplyHelper.ReplyMeta?, side: ChatPreviewEntry.Side, theme: Theme) {
        let page = theme.colorForType(.NormalBackground)
        quoteView.bubbleColors = side == .outgoing
            ? (plate: page.withAlphaComponent(0.14), text: page.withAlphaComponent(0.8))
            : (plate: page.withAlphaComponent(0.55), text: KQColor.ink2)
        quoteView.bind(meta: side == .notice ? nil : reply, theme: theme)
    }

    private func configureMedia(_ media: ChatPreviewEntry.Media?) {
        mediaView.image = nil
        mediaGlyph.image = nil
        durationLabel.text = nil
        durationBadge.isHidden = true

        guard let media = media else {
            shownMediaKey = nil
            mediaView.isHidden = true
            mediaWidth?.isActive = false
            mediaHeight?.isActive = false
            return
        }

        let box: CGSize
        switch media {
            case .photo(let path, let pixelSize):
                box = ChatPreviewController.mediaBox(for: pixelSize)
                shownMediaKey = path
                if let image = ChatPreviewController.thumbnails.object(forKey: path as NSString) {
                    mediaView.image = image
                }
                else {
                    loadPhoto(at: path, maxSide: max(box.width, box.height))
                }
            case .video(let path, let pixelSize):
                box = ChatPreviewController.mediaBox(for: pixelSize)
                shownMediaKey = path
                if let preview = ChatFileMediaLoader.cachedPreview(for: path) {
                    showVideoFrame(preview)
                }
                else {
                    showVideoGlyph(onFrame: false)
                    ChatFileMediaLoader.loadPreview(at: path) { [weak self] preview in
                        guard let self = self, let preview = preview, self.shownMediaKey == path else {
                            return
                        }
                        self.showVideoFrame(preview)
                    }
                }
            case .location(let latitude, let longitude):
                box = ChatPreviewController.mapBox
                let key = ChatBaseTextCell.mapSnapshotKey(latitude: latitude, longitude: longitude) as String
                shownMediaKey = key
                if let map = ChatBaseTextCell.cachedMapSnapshot(latitude: latitude, longitude: longitude) {
                    mediaView.image = map
                }
                else {
                    // The glyph of the conversation's map that is still being made (showLocationMapPlaceholder).
                    mediaGlyph.image = UIImage(systemName: "location",
                                               withConfiguration: UIImage.SymbolConfiguration(pointSize: 20.0, weight: .regular))
                    mediaGlyph.tintColor = KQColor.ink3
                    ChatBaseTextCell.makeMapSnapshot(latitude: latitude, longitude: longitude) { [weak self] map in
                        guard let self = self, self.shownMediaKey == key else {
                            return
                        }
                        self.mediaView.image = map
                        self.mediaGlyph.image = nil
                    }
                }
        }

        mediaWidth?.constant = box.width
        mediaHeight?.constant = box.height
        mediaWidth?.isActive = true
        mediaHeight?.isActive = true
        mediaView.isHidden = false
    }

    /// Reads a photo at the size of its box off the main thread, as the conversation does (loadImageForCellAtIndexPath).
    private func loadPhoto(at path: String, maxSide: CGFloat) {
        let maxPixelSide = maxSide * UIScreen.main.scale
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let image = ChatFileMediaLoader.downsampledImage(at: path, maxPixelSide: maxPixelSide) else {
                return
            }
            ChatPreviewController.thumbnails.setObject(image, forKey: path as NSString)

            DispatchQueue.main.async {
                guard let self = self, self.shownMediaKey == path else {
                    return
                }
                self.mediaView.image = image
            }
        }
    }

    private func showVideoFrame(_ preview: ChatFileMediaLoader.Preview) {
        mediaView.image = preview.image
        showVideoGlyph(onFrame: true)
        durationLabel.text = preview.durationText
        durationBadge.isHidden = (preview.durationText ?? "").isEmpty
    }

    /// The play glyph of the conversation's video, white on its frame and grey on the plate that waits for it.
    private func showVideoGlyph(onFrame: Bool) {
        mediaGlyph.image = UIImage.templateNamed("chat-file-play-big")
        mediaGlyph.tintColor = onFrame ? KQColor.fixedWhite : KQColor.ink3
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        label.text = nil
        authorLabel.text = nil
        shownMediaKey = nil
        mediaView.image = nil
    }
}
