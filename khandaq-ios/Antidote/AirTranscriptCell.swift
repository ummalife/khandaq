// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// A text message of the conversation with one person (and of "For yourself") as an entry of the
// transcript in design/system/index.html: the author over the first entry of a run, the text, and at the
// end of its last line the time and the status mark of our own messages (AirEntryMeta). No bubbles and no
// wallpaper; the day heads its messages where their text starts (ChatMovableDateCell).

enum AirEntryMetrics {
    /// The 38 point time column of the rows of the In transit tray (AirConversationViews), widened with the
    /// person's text size so "12:22" stays whole. The log has none since 28.11.2026 (logStart, AirEntryMeta).
    static func timeColumnWidth(_ category: UIContentSizeCategory = UIApplication.shared.preferredContentSizeCategory) -> CGFloat {
        let traits = UITraitCollection(preferredContentSizeCategory: category)
        let scaled = UIFontMetrics(forTextStyle: .caption1).scaledValue(for: KQSize.timeColumnPhone, compatibleWith: traits)
        return min(max(KQSize.timeColumnPhone, ceil(scaled)), 2 * KQSize.timeColumnPhone)
    }

    /// At the accessibility text sizes a row of the tray puts its time on the line above its text, and the
    /// text runs from the leading margin: beside a 76 point column a word of ten letters no longer fits the
    /// line and broke inside itself.
    static func stacksTime(_ category: UIContentSizeCategory = UIApplication.shared.preferredContentSizeCategory) -> Bool {
        return category.isAccessibilityCategory
    }

    /// Where the text of a row of the tray starts from the leading edge of the row, after its time column.
    static func textStart(_ category: UIContentSizeCategory = UIApplication.shared.preferredContentSizeCategory) -> CGFloat {
        return stacksTime(category) ? KQSpace.x16 : KQSpace.x16 + timeColumnWidth(category) + KQSpace.x10
    }

    /// KHANDAQ (owner, 28.11.2026): where an entry of the log and the day mark over it start, the margin. The owner
    /// found the time column took far too much of the row on the left: the time went to the end of the entry, as
    /// Telegram has it (AirEntryMeta).
    static let logStart: CGFloat = KQSpace.x16

    /// The room between the end of an entry's text, or the side of a file or a call, and its time.
    static let metaGap: CGFloat = KQSpace.x8

    /// Top and bottom room of an entry: 4 of its own and half of the 2 between entries, as .kq-log.
    static let entryInset: CGFloat = KQSpace.x4 + KQSpace.x2 / 2

    /// KHANDAQ (tester 21.11, 8255): the room before the text and the name of an entry that has a column for its
    /// author's tile (AirAuthorTile): the tile and a gap after it. None at the accessibility text sizes, where the
    /// text takes the whole width and the name over the first entry of the run says who wrote it.
    static func authorTileColumn(_ tile: AirAuthorTile?,
                                 category: UIContentSizeCategory = UIApplication.shared.preferredContentSizeCategory) -> CGFloat {
        return tile != nil && !category.isAccessibilityCategory ? KQSize.tileCompact + KQSpace.x8 : 0
    }

    /// Puts the author on its label: two lines at most, as a role in a group can make it long; at the
    /// accessibility text sizes as many as its words need, so a long name goes whole to the next line
    /// instead of breaking inside itself.
    static func show(author: String, color: UIColor,
                     category: UIContentSizeCategory = UIApplication.shared.preferredContentSizeCategory,
                     in label: UILabel) {
        label.attributedText = nil
        label.font = KQFont.font(.label, weight: 600)
        label.textColor = color
        label.text = author
        label.numberOfLines = category.isAccessibilityCategory ? 0 : 2
    }
}

/// KHANDAQ (owner, 28.11.2026): the end of an entry, as Telegram ends a message: "edited" when it was, the time, and
/// the mark of our own entries, in the caption type and the third ink with figures of one width. It stands at the
/// trailing edge of the text's last line, or on a line of its own under the text (BubbleView.showTrailingMeta), and
/// beside the bottom of a file or a call (AirEntryFrame).
final class AirEntryMeta: UIStackView {
    let timeLabel = UILabel()
    let mark = AirStatusMarkView()
    private var fontCategory: UIContentSizeCategory?

    init() {
        super.init(frame: .zero)
        axis = .horizontal
        alignment = .center
        spacing = KQSpace.x4
        isAccessibilityElement = false
        isUserInteractionEnabled = false
        timeLabel.textColor = KQColor.ink3
        timeLabel.isAccessibilityElement = false
        timeLabel.setContentCompressionResistancePriority(.required, for: .horizontal)
        timeLabel.setContentHuggingPriority(.required, for: .horizontal)
        addArrangedSubview(timeLabel)
        addArrangedSubview(mark)
        mark.isHidden = true
    }

    required init(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// "edited 9:41", or "9:41". A tabular font has a fixed size: it is made again when the text size changed.
    func show(time: String, edited: Bool) {
        let category = UIApplication.shared.preferredContentSizeCategory
        if category != fontCategory {
            fontCategory = category
            timeLabel.font = KQFont.tabular(KQFont.font(.caption))
        }
        timeLabel.textColor = KQColor.ink3
        timeLabel.text = edited ? String(localized: "message_edited_marker") + " " + time : time
    }

    /// From the top of the meta to the baseline of its words, which stand on the baseline of the text's last line:
    /// the words are centred in the meta's height, which the mark can make taller than their line.
    var firstBaselineOffset: CGFloat {
        let font = timeLabel.font ?? KQFont.font(.caption)
        let height = bounds.height > 0 ? bounds.height : systemLayoutSizeFitting(UILayoutFittingCompressedSize).height
        return (height - font.lineHeight) / 2 + font.ascender
    }
}

/// KHANDAQ (0.2.58): the author over the first entry of a run. Where the model gives it a tap
/// (ChatMovableDateCellModel.onAuthorTap: the other members of a group), the name is a button: a tap on it,
/// or a double tap with VoiceOver, opens the member. Anywhere else it is a plain label, as before.
///
/// The touch takes the words of the name, a margin to each side, at least a touch target's width, and the
/// room of the entry above the name, so the layout stays as it is. It never reaches down into the message
/// under the name, whose quote, links, mentions and media keep their taps; a long press, the swipe to reply
/// and the scroll of the log win over it as over any tap. While the log selects messages it does nothing,
/// and a tap on it selects the row.
final class AirAuthorLabel: UILabel {
    /// The margin to each side of the words.
    static let touchMargin: CGFloat = KQSpace.x8

    private let tap = UITapGestureRecognizer()
    private var onTap: ((UIView) -> Void)?
    private var spokenName = ""

    override init(frame: CGRect) {
        super.init(frame: frame)
        tap.addTarget(self, action: #selector(tapped))
        addGestureRecognizer(tap)
        opens(nil, name: "")
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// Gives the name the tap of the entry it shows, or none; `name` is what VoiceOver says for the button,
    /// the author alone.
    func opens(_ handler: ((UIView) -> Void)?, name: String) {
        onTap = handler
        spokenName = name
        isUserInteractionEnabled = handler != nil
        accessibilityTraits = handler != nil ? UIAccessibilityTraitButton : UIAccessibilityTraitNone
        accessibilityHint = handler != nil ? String(localized: "air_group_member_hint") : nil
    }

    /// Whether the name opens the member now: it has a tap, it shows, and the log is not selecting.
    var opensMember: Bool {
        return onTap != nil && !isHidden && airEnclosingCell?.isEditing != true
    }

    /// Where the words of the name stand, in the label's coordinates: the label runs to the far end of the
    /// row, the name only as far as it is long.
    var nameRect: CGRect {
        let words = sizeThatFits(CGSize(width: bounds.width, height: CGFloat.greatestFiniteMagnitude))
        let width = min(ceil(words.width), bounds.width)
        let x: CGFloat
        switch resolvedAlignment {
            case .right:
                x = bounds.maxX - width
            case .center:
                x = bounds.midX - width / 2
            default:
                x = bounds.minX
        }
        return CGRect(x: x, y: bounds.minY, width: width, height: bounds.height)
    }

    /// Where a finger opens the member, in the label's coordinates.
    var touchRect: CGRect {
        var rect = nameRect.insetBy(dx: -AirAuthorLabel.touchMargin, dy: 0)
        if rect.width < KQSize.targetIos {
            rect = rect.insetBy(dx: -(KQSize.targetIos - rect.width) / 2, dy: 0)
        }
        rect.origin.y -= AirEntryMetrics.entryInset
        rect.size.height += AirEntryMetrics.entryInset
        return rect
    }

    /// Where the words stand: `.natural` puts them at the start of the layout's direction.
    private var resolvedAlignment: NSTextAlignment {
        guard textAlignment == .natural else {
            return textAlignment
        }
        return effectiveUserInterfaceLayoutDirection == .rightToLeft ? .right : .left
    }

    /// Not the member's button now, the name lets every touch through, as a label does. Nor while VoiceOver
    /// runs: it opens the member from the button's own element (accessibilityActivate), and the taps it makes
    /// in the middle of an entry, for the entry, must not land on the name.
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        return opensMember && !UIAccessibilityIsVoiceOverRunning() && touchRect.contains(point)
    }

    @objc private func tapped() {
        guard opensMember else {
            return
        }
        onTap?(self)
    }

    // MARK: Accessibility

    /// An element of its own only while it opens the member: a plain name is said with its entry.
    override var isAccessibilityElement: Bool {
        get {
            return opensMember
        }
        set {}
    }

    override var accessibilityLabel: String? {
        get {
            return spokenName.isEmpty ? super.accessibilityLabel : spokenName
        }
        set {
            super.accessibilityLabel = newValue
        }
    }

    /// The button is where a finger opens the member, not the whole width of the row.
    override var accessibilityFrame: CGRect {
        get {
            guard let window = window else {
                return super.accessibilityFrame
            }
            return window.convert(convert(touchRect, to: nil), to: window.screen.coordinateSpace)
        }
        set {
            super.accessibilityFrame = newValue
        }
    }

    override func accessibilityActivate() -> Bool {
        guard opensMember, let onTap = onTap else {
            return false
        }
        onTap(self)
        return true
    }
}

private extension UIView {
    /// The row of the log the view is drawn in.
    var airEnclosingCell: UITableViewCell? {
        var view = superview
        while let current = view {
            if let cell = current as? UITableViewCell {
                return cell
            }
            view = current.superview
        }
        return nil
    }
}

/// KHANDAQ (tester 21.11, 8255): the tile of the member who wrote an entry of a group, as Telegram has it: every
/// entry of another member keeps a column for it before its text and its name, and the last entry of a run shows
/// it, level with the bottom of the entry, while the name stays over the first one. It is the member's tile of
/// the band and the dossier (initials in their tone), or the plain tile of an author nobody confirmed.
struct AirAuthorTile {
    let style: AirTileView.Style
    /// Whether this entry shows the tile: the last entry of its run does, the others keep its column empty.
    let shows: Bool
    /// What a tap on the tile does; nil leaves it a picture.
    let onTap: ((UIView) -> Void)?
}

/// KHANDAQ (tester 21.11, 8255): the author's tile beside a group entry (AirAuthorTile). A tap on the tile, or
/// around it to a touch target's size, does what the model says. Without a tap, while the log selects messages
/// and while VoiceOver runs, it lets every touch through, as the author's name does (AirAuthorLabel). VoiceOver
/// passes it by: every entry says who wrote it, and the name over the first entry of the run is the member's
/// button.
final class AirAuthorTileView: UIView {
    private let tile = AirTileView(side: KQSize.tileCompact)
    private let tap = UITapGestureRecognizer()
    private var onTap: ((UIView) -> Void)?
    /// What the tile shows while it shows.
    private(set) var style: AirTileView.Style?

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = false
        addSubview(tile)
        tile.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        tap.addTarget(self, action: #selector(tapped))
        addGestureRecognizer(tap)
        show(nil)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// Shows the tile of `model`, or hides it: nil, or an entry that keeps the column empty.
    func show(_ model: AirAuthorTile?) {
        guard let model = model, model.shows else {
            style = nil
            onTap = nil
            isHidden = true
            isUserInteractionEnabled = false
            return
        }
        style = model.style
        tile.configure(style: model.style, photo: nil)
        onTap = model.onTap
        isHidden = false
        isUserInteractionEnabled = model.onTap != nil
    }

    /// Whether a tap on the tile acts now: it has a tap, it shows, and the log is not selecting.
    var acts: Bool {
        return onTap != nil && !isHidden && airEnclosingCell?.isEditing != true
    }

    /// Where a finger takes the tile, in its own coordinates: the tile and the room around it to a touch target.
    var touchRect: CGRect {
        let dx = max(0, (KQSize.targetIos - bounds.width) / 2)
        let dy = max(0, (KQSize.targetIos - bounds.height) / 2)
        return bounds.insetBy(dx: -dx, dy: -dy)
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        return acts && !UIAccessibilityIsVoiceOverRunning() && touchRect.contains(point)
    }

    @objc private func tapped() {
        guard acts else {
            return
        }
        onTap?(self)
    }
}

/// The frame a file or a call entry shares with a text (AirEntryTextCell): the author over the first entry of a
/// run, the author's tile of another member's entry in a group, and the meta (AirEntryMeta) at the trailing edge,
/// level with the bottom of the body the cell puts in, which never runs under it.
final class AirEntryFrame {
    let authorLabel = AirAuthorLabel()
    /// KHANDAQ (owner, 28.11.2026): the time and the status mark of our own entries, beside the end of the body.
    let meta = AirEntryMeta()
    /// KHANDAQ (tester 21.11, 8255): the author's tile of another member's entry in a group (AirAuthorTile).
    let authorTile = AirAuthorTileView()
    private weak var body: UIView?
    private var authorTileRoom: Constraint!
    private var bodyTopToAuthor: Constraint!
    private var bodyTopToContent: Constraint!
    private var bodyLeading: Constraint!
    private var authorLeading: Constraint!
    private var metaBottom: Constraint?

    /// The status mark of our own entries, in the meta.
    var mark: AirStatusMarkView {
        return meta.mark
    }

    /// The time, in the meta.
    var timeLabel: UILabel {
        return meta.timeLabel
    }

    init() {
        authorLabel.font = KQFont.font(.label, weight: 600)
        authorLabel.adjustsFontForContentSizeCategory = true
        authorLabel.numberOfLines = 2
        authorLabel.lineBreakMode = .byTruncatingTail
    }

    /// Puts the frame around `body`, which the cell has added to its movable content view.
    func install(in cell: ChatMovableDateCell, body: UIView) {
        self.body = body
        let content: UIView = cell.movableContentView
        content.addSubview(authorLabel)
        content.addSubview(meta)
        content.addSubview(authorTile)

        authorLabel.snp.makeConstraints {
            $0.top.equalTo(content).offset(AirEntryMetrics.entryInset)
            authorLeading = $0.leading.equalTo(cell.contentView).offset(AirEntryMetrics.logStart).constraint
            $0.trailing.equalTo(cell.contentView).offset(-KQSpace.x16)
        }
        body.snp.makeConstraints {
            bodyTopToAuthor = $0.top.equalTo(authorLabel.snp.bottom).offset(KQSpace.x2).constraint
            bodyTopToContent = $0.top.equalTo(content).offset(AirEntryMetrics.entryInset).constraint
            $0.bottom.equalTo(content).offset(-AirEntryMetrics.entryInset)
            bodyLeading = $0.leading.equalTo(cell.contentView).offset(AirEntryMetrics.logStart).constraint
            $0.trailing.equalTo(meta.snp.leading).offset(-AirEntryMetrics.metaGap)
        }
        bodyTopToAuthor.deactivate()
        meta.snp.makeConstraints {
            $0.trailing.equalTo(cell.contentView).offset(-KQSpace.x16)
        }
        meta.setContentHuggingPriority(.required, for: .horizontal)
        meta.setContentCompressionResistancePriority(.required, for: .horizontal)
        alignMeta(withBottomOf: nil)
        authorTile.snp.makeConstraints {
            $0.leading.equalTo(cell.contentView).offset(AirEntryMetrics.logStart)
            $0.bottom.equalTo(content).offset(-AirEntryMetrics.entryInset)
            $0.size.equalTo(KQSize.tileCompact)
            // An entry that shows the tile is at least as tall as the tile.
            authorTileRoom = $0.top.greaterThanOrEqualTo(content).offset(AirEntryMetrics.entryInset).constraint
        }
        authorTileRoom.deactivate()
    }

    /// Puts the bottom of the meta level with the bottom of `part`, the last part of the body over the rows that
    /// follow its content (the reactions), or with the bottom of the body when nil.
    func alignMeta(withBottomOf part: UIView?) {
        guard let anchor = part ?? body else {
            return
        }
        metaBottom?.deactivate()
        meta.snp.makeConstraints {
            metaBottom = $0.bottom.equalTo(anchor).constraint
        }
    }

    /// The author, its tile and the time of `model`; the cell sets the mark (mark).
    func configure(_ model: ChatMovableDateCellModel) {
        meta.show(time: model.timeText.isEmpty ? model.dateString : model.timeText, edited: false)

        let tileColumn = AirEntryMetrics.authorTileColumn(model.authorTile)
        bodyLeading.update(offset: AirEntryMetrics.logStart + tileColumn)
        authorLeading.update(offset: AirEntryMetrics.logStart + tileColumn)
        authorTile.show(tileColumn > 0 ? model.authorTile : nil)
        if authorTile.isHidden {
            authorTileRoom.deactivate()
        }
        else {
            authorTileRoom.activate()
        }
        if let author = model.authorText, !author.isEmpty {
            AirEntryMetrics.show(author: author, color: model.authorColor, in: authorLabel)
            authorLabel.isHidden = false
            authorLabel.opens(model.onAuthorTap, name: author)
            bodyTopToContent.deactivate()
            bodyTopToAuthor.activate()
        }
        else {
            authorLabel.text = nil
            authorLabel.isHidden = true
            authorLabel.opens(nil, name: "")
            bodyTopToAuthor.deactivate()
            bodyTopToContent.activate()
        }
    }

    /// Who wrote the entry and when, for the start of what VoiceOver says.
    func spokenLabel(_ model: ChatMovableDateCellModel) -> String {
        let time = model.timeText.isEmpty ? model.dateString : model.timeText
        return [model.spokenAuthor, time].filter { !$0.isEmpty }.joined(separator: ", ")
    }
}

final class AirEntryTextCellModel: ChatBaseTextCellModel {
    var isOutgoing = false
    // The time, the author and who wrote it for VoiceOver are ChatMovableDateCellModel's: every entry has them.
    var status: AirEntryStatus = .none
    /// What the mark showed the last time this entry was drawn, so a change cross-fades.
    var previousStatus: AirEntryStatus?
    /// KHANDAQ (0.2.57): an entry of a 1:1 conversation with a friend (AirStatusMarkView.takesReadReceipts).
    /// The group controllers and the Saved chat leave it false and keep the marks of 0.2.56.
    var takesReadReceipts = false
    /// KHANDAQ (26.11.2026, DESIGN-group-contacts-private-mentions.md): the members a group message names, drawn in
    /// ink and bold, and what a tap on one of them opens (the member's key, and the view tapped).
    var mentions: [GroupMentionHelper.MentionEntry] = []
    var onMentionTap: ((String, UIView) -> Void)?
    /// Who sees a private message of the group feed, over its text behind a lock; nil for any other entry.
    var privateNote: String?
}

final class AirEntryTextCell: ChatBaseTextCell {
    private let authorLabel = AirAuthorLabel()
    /// KHANDAQ (owner, 28.11.2026): "edited", the time and the status mark of our own entries, at the end of the
    /// text's last line (BubbleView.showTrailingMeta).
    let meta = AirEntryMeta()
    /// KHANDAQ (tester 21.11, 8255): the author's tile of another member's entry in a group (AirAuthorTile).
    let authorTile = AirAuthorTileView()
    private var authorTileRoom: Constraint!
    private var bubbleTopToAuthor: Constraint!
    private var bubbleTopToContent: Constraint!
    private var bubbleLeading: Constraint!
    private var authorLeading: Constraint!
    private var pendingStatus: AirEntryStatus?
    private var spokenLabel = ""
    private var spokenValue = ""
    /// KHANDAQ (0.2.58): the entry for VoiceOver while the author is a button of its own.
    private lazy var entryElement = UIAccessibilityElement(accessibilityContainer: self)

    /// The status mark of our own entries, in the meta.
    var mark: AirStatusMarkView {
        return meta.mark
    }

    override func createViews() {
        revealsTime = false
        super.createViews()

        bubbleView.useTranscriptStyle()
        bubbleView.font = KQFont.font(.body)

        authorLabel.font = KQFont.font(.label, weight: 600)
        authorLabel.adjustsFontForContentSizeCategory = true
        // Two lines: in a group the name comes with the role, which large text would otherwise cut.
        authorLabel.numberOfLines = 2
        authorLabel.lineBreakMode = .byTruncatingTail
        contentView.addSubview(authorLabel)

        contentView.addSubview(authorTile)
    }

    override func installConstraints() {
        super.installConstraints()

        // KHANDAQ (owner, 28.11.2026): no time column: the name and the text start at the margin, after the column of
        // the author's tile in a group, and run to the margin at the far end, where the meta ends the text.
        authorLabel.snp.makeConstraints {
            $0.top.equalTo(movableContentView).offset(AirEntryMetrics.entryInset)
            authorLeading = $0.leading.equalTo(contentView).offset(AirEntryMetrics.logStart).constraint
            $0.trailing.equalTo(contentView).offset(-KQSpace.x16)
        }

        bubbleView.snp.makeConstraints {
            // KHANDAQ (0.2.60): the name of the author stands apart from the text under it, as over a file or a call:
            // users read the two as one block.
            bubbleTopToAuthor = $0.top.equalTo(authorLabel.snp.bottom).offset(KQSpace.x2).constraint
            bubbleTopToContent = $0.top.equalTo(movableContentView).offset(AirEntryMetrics.entryInset).constraint
            $0.bottom.equalTo(movableContentView).offset(-AirEntryMetrics.entryInset)
            bubbleLeading = $0.leading.equalTo(contentView).offset(AirEntryMetrics.logStart).constraint
            $0.trailing.equalTo(contentView).offset(-KQSpace.x16)
        }
        bubbleTopToAuthor.deactivate()

        authorTile.snp.makeConstraints {
            $0.leading.equalTo(contentView).offset(AirEntryMetrics.logStart)
            $0.bottom.equalTo(movableContentView).offset(-AirEntryMetrics.entryInset)
            $0.size.equalTo(KQSize.tileCompact)
            // An entry that shows the tile is at least as tall as the tile: a one-line text grows to it.
            authorTileRoom = $0.top.greaterThanOrEqualTo(movableContentView).offset(AirEntryMetrics.entryInset).constraint
        }
        authorTileRoom.deactivate()
    }

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let entry = model as? AirEntryTextCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        // After the base setup: it assigns the theme's text colour and the system font.
        bubbleView.font = KQFont.font(.body)
        bubbleView.textColor = KQColor.ink
        bubbleView.tintColor = KQColor.ink
        bubbleNormalBackground = nil
        bubbleView.backgroundColor = .clear
        // KHANDAQ (owner, 28.11.2026): "edited" goes into the meta, before the time, as Telegram has it.
        bubbleView.bindEdited(false)
        bubbleView.bindPrivateNote(entry.privateNote)
        // KHANDAQ (26.11.2026): the mentions after the font and the colour of the whole text, which would undo them.
        let mentionRanges = entry.hasLocation ? [] : GroupMentionHelper.mentionRanges(in: entry.message, mentions: entry.mentions)
        bubbleView.styleMentions(mentionRanges, color: KQColor.ink, font: KQFont.font(.body, weight: 600))
        if let open = entry.onMentionTap, !mentionRanges.isEmpty {
            bubbleView.onMentionTap = { [weak self] key in
                guard let self = self else {
                    return
                }
                open(key, self.bubbleView)
            }
        }
        else {
            bubbleView.onMentionTap = nil
        }

        // KHANDAQ (tester 21.11, 8255): the text and the name of another member's entry in a group stand after the
        // column of the author's tile, which the last entry of a run shows.
        let tileColumn = AirEntryMetrics.authorTileColumn(entry.authorTile)
        bubbleLeading.update(offset: AirEntryMetrics.logStart + tileColumn)
        authorLeading.update(offset: AirEntryMetrics.logStart + tileColumn)
        authorTile.show(tileColumn > 0 ? entry.authorTile : nil)
        if authorTile.isHidden {
            authorTileRoom.deactivate()
        }
        else {
            authorTileRoom.activate()
        }
        if let author = entry.authorText, !author.isEmpty {
            AirEntryMetrics.show(author: author, color: entry.authorColor, in: authorLabel)
            authorLabel.isHidden = false
            authorLabel.opens(entry.onAuthorTap, name: author)
            bubbleTopToContent.deactivate()
            bubbleTopToAuthor.activate()
        }
        else {
            authorLabel.text = nil
            authorLabel.isHidden = true
            authorLabel.opens(nil, name: "")
            bubbleTopToAuthor.deactivate()
            bubbleTopToContent.activate()
        }

        let shown = entry.previousStatus ?? entry.status
        mark.takesReadReceipts = entry.takesReadReceipts
        mark.set(shown, animated: false)
        mark.isHidden = !entry.isOutgoing || entry.status == .none
        pendingStatus = shown != entry.status ? entry.status : nil
        if window != nil {
            applyPendingStatus()
        }
        // Last: the meta keeps its room after the text as it stands now, with every style above.
        meta.show(time: entry.timeText, edited: entry.edited && !entry.hasLocation)
        bubbleView.showTrailingMeta(meta, gap: AirEntryMetrics.metaGap)

        spokenLabel = [entry.spokenAuthor, entry.timeText].filter { !$0.isEmpty }.joined(separator: ", ")
        var value = [String]()
        if let marks = transcriptMarksSpoken {
            value.append(marks)
        }
        if let note = entry.privateNote {
            value.append(note)
        }
        // A reply says whom and what it answers before its own words, as the desktop reads it.
        if let quote = MessageReplyHelper.spokenQuote(entry.replyMeta) {
            value.append(quote)
        }
        // KHANDAQ (owner, 28.11): a place is said as what it is and where: its title and coordinates (LocationMessage.caption).
        if let latitude = entry.locationLatitude, let longitude = entry.locationLongitude {
            value.append(LocationMessage.caption(latitude: latitude, longitude: longitude))
        }
        else {
            value.append(entry.message)
        }
        // KHANDAQ (0.2.61): the reactions, as the entry of a file says them.
        if let reactions = entry.reactionsDisplay {
            value.append(reactions)
        }
        if entry.edited {
            value.append(String(localized: "message_edited_marker"))
        }
        if let spoken = entry.status.spokenText, entry.isOutgoing {
            value.append(spoken)
        }
        spokenValue = value.joined(separator: ", ")
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        contentView.layer.removeAllAnimations()
        contentView.alpha = 1
        contentView.transform = .identity
        pendingStatus = nil
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window != nil {
            applyPendingStatus()
        }
    }

    private func applyPendingStatus() {
        guard let status = pendingStatus else {
            return
        }
        pendingStatus = nil
        mark.set(status, animated: true)
    }

    /// A message that has just arrived, or just left the tray, rises 8 points into place and fades in.
    /// With Reduce Motion it only fades, in at most 120 ms. The flipped table flips each cell back, so
    /// the cell's own coordinates run as on screen. The animation starts on the next turn of the run
    /// loop: the log inserts its rows inside performWithoutAnimation, which would swallow it.
    func animateArrival(delay: TimeInterval) {
        let reduce = UIAccessibilityIsReduceMotionEnabled()
        contentView.alpha = 0
        contentView.transform = reduce ? .identity : CGAffineTransform(translationX: 0, y: KQSpace.x8)
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                return
            }
            UIView.animate(withDuration: KQMotion.duration(KQMotion.state),
                           delay: reduce ? 0 : delay,
                           options: [.curveEaseOut, .allowUserInteraction, .beginFromCurrentState],
                           animations: {
                               self.contentView.alpha = 1
                               self.contentView.transform = .identity
                           },
                           completion: nil)
        }
    }

    override func menuTargetRect() -> CGRect {
        return bubbleView.frame.union(authorLabel.isHidden ? bubbleView.frame : authorLabel.frame)
    }
}

// Accessibility: one entry; while the author opens the member (AirAuthorLabel), the entry, then the
// author's button.
extension AirEntryTextCell {
    override var accessibilityLabel: String? {
        get {
            return spokenLabel
        }
        set {}
    }

    override var accessibilityValue: String? {
        get {
            return spokenValue
        }
        set {}
    }

    override var isAccessibilityElement: Bool {
        get {
            return !authorLabel.opensMember
        }
        set {}
    }

    override var accessibilityElements: [Any]? {
        get {
            guard authorLabel.opensMember else {
                return nil
            }
            // Says what the row said as one element; a double tap taps its middle as it did (AirAuthorLabel
            // takes no touch while VoiceOver runs).
            entryElement.accessibilityLabel = spokenLabel
            entryElement.accessibilityValue = spokenValue
            entryElement.accessibilityHint = accessibilityHint
            entryElement.accessibilityTraits = accessibilityTraits
            entryElement.accessibilityFrameInContainerSpace = bounds
            return [entryElement, authorLabel]
        }
        set {}
    }

}
