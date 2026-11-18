// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// A text message of the conversation with one person (and of "For yourself") as an entry of the
// transcript in design/system/index.html: the time in a 38 point column of tabular figures, the author
// over the first entry of a run, the text, and the status mark of our own messages at the far end. No
// bubbles and no wallpaper; the day heads its messages where their text starts (ChatMovableDateCell).

enum AirEntryMetrics {
    /// The 38 point time column, widened with the person's text size so "12:22" stays whole.
    static func timeColumnWidth(_ category: UIContentSizeCategory = UIApplication.shared.preferredContentSizeCategory) -> CGFloat {
        let traits = UITraitCollection(preferredContentSizeCategory: category)
        let scaled = UIFontMetrics(forTextStyle: .caption1).scaledValue(for: KQSize.timeColumnPhone, compatibleWith: traits)
        return min(max(KQSize.timeColumnPhone, ceil(scaled)), 2 * KQSize.timeColumnPhone)
    }

    /// At the accessibility text sizes the time leaves its column for the line above the text (with the
    /// author, when the author shows) and the text runs from the leading margin: beside a 76 point
    /// column a word of ten letters no longer fits the line and broke inside itself.
    static func stacksTime(_ category: UIContentSizeCategory = UIApplication.shared.preferredContentSizeCategory) -> Bool {
        return category.isAccessibilityCategory
    }

    /// Where the text of an entry starts from the leading edge of the row; the day mark and the rows
    /// outside the log line up with it.
    static func textStart(_ category: UIContentSizeCategory = UIApplication.shared.preferredContentSizeCategory) -> CGFloat {
        return stacksTime(category) ? KQSpace.x16 : KQSpace.x16 + timeColumnWidth(category) + KQSpace.x10
    }

    /// Top and bottom room of an entry: 4 of its own and half of the 2 between entries, as .kq-log.
    static let entryInset: CGFloat = KQSpace.x4 + KQSpace.x2 / 2

    /// The line over the first entry of a run at the accessibility text sizes: the time, then the author,
    /// as one text from the leading margin that wraps at its words. Beside the time column a name of
    /// eleven letters broke inside itself at AX5; here it goes whole to the next line instead.
    static func stackedHead(time: String, author: String, color: UIColor) -> NSAttributedString {
        let head = NSMutableAttributedString(string: time + " ", attributes: [
            NSAttributedStringKey.font: KQFont.tabular(KQFont.font(.caption)),
            NSAttributedStringKey.foregroundColor: KQColor.ink3,
        ])
        head.append(NSAttributedString(string: author, attributes: [
            NSAttributedStringKey.font: KQFont.font(.label, weight: 600),
            NSAttributedStringKey.foregroundColor: color,
        ]))
        return head
    }

    /// Puts the author on its label: beside the time at the usual sizes (two lines at most, as a role
    /// in a group can make it long), or after the time on a line of its own (AirEntryMetrics.stackedHead).
    static func show(author: String, color: UIColor, time: String, stacked: Bool, in label: UILabel) {
        if stacked {
            label.attributedText = stackedHead(time: time, author: author, color: color)
            label.numberOfLines = 0
        }
        else {
            // A stacked head left its first font and colour on the label: they are set again.
            label.attributedText = nil
            label.font = KQFont.font(.label, weight: 600)
            label.textColor = color
            label.text = author
            label.numberOfLines = 2
        }
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
    /// the author alone (a stacked head starts with the time).
    func opens(_ handler: ((UIView) -> Void)?, name: String) {
        onTap = handler
        spokenName = name
        isUserInteractionEnabled = handler != nil
        accessibilityTraits = handler != nil ? UIAccessibilityTraitButton : UIAccessibilityTraitNone
        accessibilityHint = handler != nil ? String(localized: "air_group_member_hint") : nil
    }

    /// Whether the name opens the member now: it has a tap, it shows, and the log is not selecting.
    var opensMember: Bool {
        return onTap != nil && !isHidden && enclosingCell?.isEditing != true
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

    private var enclosingCell: UITableViewCell? {
        var view = superview
        while let current = view {
            if let cell = current as? UITableViewCell {
                return cell
            }
            view = current.superview
        }
        return nil
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

/// The frame a file or a call entry shares with a text (AirEntryTextCell): the time in the time column,
/// the author over the first entry of a run, and the status mark of our own entries at the far end,
/// around the body the cell puts between them.
final class AirEntryFrame {
    let timeLabel = UILabel()
    let authorLabel = AirAuthorLabel()
    let mark = AirStatusMarkView()
    private var timeWidthConstraint: Constraint!
    private var timeTopConstraint: Constraint!
    private var markTopConstraint: Constraint!
    private var bodyTopToAuthor: Constraint!
    private var bodyTopToContent: Constraint!
    private var bodyTopToTime: Constraint!
    private var bodyLeadingToTime: Constraint!
    private var bodyLeadingToMargin: Constraint!
    private var authorLeadingToTime: Constraint!
    private var authorLeadingToMargin: Constraint!
    private var timeFontCategory = UIApplication.shared.preferredContentSizeCategory

    init() {
        timeLabel.font = KQFont.tabular(KQFont.font(.caption))
        timeLabel.textColor = KQColor.ink3
        timeLabel.adjustsFontSizeToFitWidth = true
        timeLabel.minimumScaleFactor = 0.7
        timeLabel.isAccessibilityElement = false

        authorLabel.font = KQFont.font(.label, weight: 600)
        authorLabel.adjustsFontForContentSizeCategory = true
        authorLabel.numberOfLines = 2
        authorLabel.lineBreakMode = .byTruncatingTail
    }

    /// Puts the frame around `body`, which the cell has added to its movable content view.
    func install(in cell: ChatMovableDateCell, body: UIView) {
        let content: UIView = cell.movableContentView
        content.addSubview(timeLabel)
        content.addSubview(authorLabel)
        content.addSubview(mark)

        timeLabel.snp.makeConstraints {
            $0.leading.equalTo(cell.contentView).offset(KQSpace.x16)
            timeTopConstraint = $0.top.equalTo(content).offset(AirEntryMetrics.entryInset).constraint
            timeWidthConstraint = $0.width.equalTo(AirEntryMetrics.timeColumnWidth()).constraint
        }
        authorLabel.snp.makeConstraints {
            $0.top.equalTo(content).offset(AirEntryMetrics.entryInset)
            authorLeadingToTime = $0.leading.equalTo(timeLabel.snp.trailing).offset(KQSpace.x10).constraint
            authorLeadingToMargin = $0.leading.equalTo(cell.contentView).offset(KQSpace.x16).constraint
            $0.trailing.equalTo(mark.snp.leading).offset(-KQSpace.x10)
        }
        authorLeadingToMargin.deactivate()
        body.snp.makeConstraints {
            bodyTopToAuthor = $0.top.equalTo(authorLabel.snp.bottom).offset(KQSpace.x2).constraint
            bodyTopToContent = $0.top.equalTo(content).offset(AirEntryMetrics.entryInset).constraint
            bodyTopToTime = $0.top.equalTo(timeLabel.snp.bottom).offset(KQSpace.x2).constraint
            $0.bottom.equalTo(content).offset(-AirEntryMetrics.entryInset)
            bodyLeadingToTime = $0.leading.equalTo(timeLabel.snp.trailing).offset(KQSpace.x10).constraint
            bodyLeadingToMargin = $0.leading.equalTo(cell.contentView).offset(KQSpace.x16).constraint
            $0.trailing.equalTo(mark.snp.leading).offset(-KQSpace.x10)
        }
        bodyTopToAuthor.deactivate()
        bodyTopToTime.deactivate()
        bodyLeadingToMargin.deactivate()
        mark.snp.makeConstraints {
            $0.trailing.equalTo(cell.contentView).offset(-KQSpace.x16)
            markTopConstraint = $0.top.equalTo(content).offset(AirEntryMetrics.entryInset).constraint
        }
    }

    /// `firstLine` is the height of the body's first line: the time and the mark sit on it, or on the
    /// author's line while the author shows. At the accessibility text sizes they sit on a line of their
    /// own above the body, with the author when it shows (AirEntryMetrics.stacksTime).
    func configure(_ model: ChatMovableDateCellModel, firstLine: CGFloat) {
        let category = UIApplication.shared.preferredContentSizeCategory
        if category != timeFontCategory {
            timeFontCategory = category
            timeLabel.font = KQFont.tabular(KQFont.font(.caption))
        }
        timeWidthConstraint.update(offset: AirEntryMetrics.timeColumnWidth(category))
        timeLabel.text = model.timeText.isEmpty ? model.dateString : model.timeText
        timeLabel.textColor = KQColor.ink3

        let stacked = AirEntryMetrics.stacksTime(category)
        if stacked {
            bodyLeadingToTime.deactivate()
            bodyLeadingToMargin.activate()
        }
        else {
            bodyLeadingToMargin.deactivate()
            bodyLeadingToTime.activate()
        }
        let captionLine = ceil(KQFont.lineHeight(.caption))
        let line: CGFloat
        let headStacked = stacked && !(model.authorText ?? "").isEmpty
        timeLabel.isHidden = headStacked
        (headStacked ? authorLeadingToTime : authorLeadingToMargin).deactivate()
        (headStacked ? authorLeadingToMargin : authorLeadingToTime).activate()
        if let author = model.authorText, !author.isEmpty {
            AirEntryMetrics.show(author: author, color: model.authorColor, time: timeLabel.text ?? "", stacked: stacked, in: authorLabel)
            authorLabel.isHidden = false
            authorLabel.opens(model.onAuthorTap, name: author)
            bodyTopToContent.deactivate()
            bodyTopToTime.deactivate()
            bodyTopToAuthor.activate()
            line = ceil(KQFont.lineHeight(.label))
        }
        else {
            authorLabel.text = nil
            authorLabel.isHidden = true
            authorLabel.opens(nil, name: "")
            bodyTopToAuthor.deactivate()
            if stacked {
                bodyTopToContent.deactivate()
                bodyTopToTime.activate()
            }
            else {
                bodyTopToTime.deactivate()
                bodyTopToContent.activate()
            }
            line = stacked ? captionLine : firstLine
        }
        timeTopConstraint.update(offset: AirEntryMetrics.entryInset + max(0, (line - captionLine) / 2))
        markTopConstraint.update(offset: AirEntryMetrics.entryInset + max(0, (line - KQSize.mark) / 2))
    }

    /// Who wrote the entry and when, for the start of what VoiceOver says.
    func spokenLabel(_ model: ChatMovableDateCellModel) -> String {
        return [model.spokenAuthor, timeLabel.text ?? ""].filter { !$0.isEmpty }.joined(separator: ", ")
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
}

final class AirEntryTextCell: ChatBaseTextCell {
    private let timeLabel = UILabel()
    private let authorLabel = AirAuthorLabel()
    private let mark = AirStatusMarkView()
    private var timeWidthConstraint: Constraint!
    private var timeTopConstraint: Constraint!
    private var markTopConstraint: Constraint!
    private var bubbleTopToAuthor: Constraint!
    private var bubbleTopToContent: Constraint!
    private var bubbleTopToTime: Constraint!
    private var bubbleLeadingToTime: Constraint!
    private var bubbleLeadingToMargin: Constraint!
    private var authorLeadingToTime: Constraint!
    private var authorLeadingToMargin: Constraint!
    private var pendingStatus: AirEntryStatus?
    private var timeFontCategory = UIApplication.shared.preferredContentSizeCategory
    private var spokenLabel = ""
    private var spokenValue = ""
    /// KHANDAQ (0.2.58): the entry for VoiceOver while the author is a button of its own.
    private lazy var entryElement = UIAccessibilityElement(accessibilityContainer: self)

    override func createViews() {
        revealsTime = false
        super.createViews()

        bubbleView.useTranscriptStyle()
        bubbleView.font = KQFont.font(.body)

        timeLabel.font = KQFont.tabular(KQFont.font(.caption))
        timeLabel.textColor = KQColor.ink3
        timeLabel.adjustsFontSizeToFitWidth = true
        timeLabel.minimumScaleFactor = 0.7
        timeLabel.isAccessibilityElement = false
        contentView.addSubview(timeLabel)

        authorLabel.font = KQFont.font(.label, weight: 600)
        authorLabel.adjustsFontForContentSizeCategory = true
        // Two lines: in a group the name comes with the role, which large text would otherwise cut.
        authorLabel.numberOfLines = 2
        authorLabel.lineBreakMode = .byTruncatingTail
        contentView.addSubview(authorLabel)

        contentView.addSubview(mark)
    }

    override func installConstraints() {
        super.installConstraints()

        timeLabel.snp.makeConstraints {
            $0.leading.equalTo(contentView).offset(KQSpace.x16)
            timeTopConstraint = $0.top.equalTo(movableContentView).offset(AirEntryMetrics.entryInset).constraint
            timeWidthConstraint = $0.width.equalTo(AirEntryMetrics.timeColumnWidth()).constraint
        }

        authorLabel.snp.makeConstraints {
            $0.top.equalTo(movableContentView).offset(AirEntryMetrics.entryInset)
            authorLeadingToTime = $0.leading.equalTo(timeLabel.snp.trailing).offset(KQSpace.x10).constraint
            authorLeadingToMargin = $0.leading.equalTo(contentView).offset(KQSpace.x16).constraint
            $0.trailing.equalTo(mark.snp.leading).offset(-KQSpace.x10)
        }
        authorLeadingToMargin.deactivate()

        bubbleView.snp.makeConstraints {
            bubbleTopToAuthor = $0.top.equalTo(authorLabel.snp.bottom).constraint
            bubbleTopToContent = $0.top.equalTo(movableContentView).offset(AirEntryMetrics.entryInset).constraint
            bubbleTopToTime = $0.top.equalTo(timeLabel.snp.bottom).constraint
            $0.bottom.equalTo(movableContentView).offset(-AirEntryMetrics.entryInset)
            bubbleLeadingToTime = $0.leading.equalTo(timeLabel.snp.trailing).offset(KQSpace.x10).constraint
            bubbleLeadingToMargin = $0.leading.equalTo(contentView).offset(KQSpace.x16).constraint
            $0.trailing.equalTo(mark.snp.leading).offset(-KQSpace.x10)
        }
        bubbleTopToAuthor.deactivate()
        bubbleTopToTime.deactivate()
        bubbleLeadingToMargin.deactivate()

        mark.snp.makeConstraints {
            $0.trailing.equalTo(contentView).offset(-KQSpace.x16)
            markTopConstraint = $0.top.equalTo(movableContentView).offset(AirEntryMetrics.entryInset).constraint
        }
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

        // A tabular font has a fixed size: made again when the text size has changed since.
        let category = UIApplication.shared.preferredContentSizeCategory
        if category != timeFontCategory {
            timeFontCategory = category
            timeLabel.font = KQFont.tabular(KQFont.font(.caption))
        }
        timeWidthConstraint.update(offset: AirEntryMetrics.timeColumnWidth(category))
        timeLabel.text = entry.timeText

        // At the accessibility text sizes the time and the author stand on a line above the text, which
        // takes the whole width (AirEntryMetrics.stacksTime).
        let stacked = AirEntryMetrics.stacksTime(category)
        if stacked {
            bubbleLeadingToTime.deactivate()
            bubbleLeadingToMargin.activate()
        }
        else {
            bubbleLeadingToMargin.deactivate()
            bubbleLeadingToTime.activate()
        }
        let bodyLine = ceil(KQFont.lineHeight(.body))
        let captionLine = ceil(KQFont.lineHeight(.caption))
        let headStacked = stacked && !(entry.authorText ?? "").isEmpty
        timeLabel.isHidden = headStacked
        (headStacked ? authorLeadingToTime : authorLeadingToMargin).deactivate()
        (headStacked ? authorLeadingToMargin : authorLeadingToTime).activate()
        if let author = entry.authorText, !author.isEmpty {
            AirEntryMetrics.show(author: author, color: entry.authorColor, time: entry.timeText, stacked: stacked, in: authorLabel)
            authorLabel.isHidden = false
            authorLabel.opens(entry.onAuthorTap, name: author)
            bubbleTopToContent.deactivate()
            bubbleTopToTime.deactivate()
            bubbleTopToAuthor.activate()
            let authorLine = ceil(KQFont.lineHeight(.label))
            timeTopConstraint.update(offset: AirEntryMetrics.entryInset + max(0, (authorLine - captionLine) / 2))
            markTopConstraint.update(offset: AirEntryMetrics.entryInset + max(0, (authorLine - KQSize.mark) / 2))
        }
        else if stacked {
            authorLabel.text = nil
            authorLabel.isHidden = true
            authorLabel.opens(nil, name: "")
            bubbleTopToAuthor.deactivate()
            bubbleTopToContent.deactivate()
            bubbleTopToTime.activate()
            timeTopConstraint.update(offset: AirEntryMetrics.entryInset)
            markTopConstraint.update(offset: AirEntryMetrics.entryInset + max(0, (captionLine - KQSize.mark) / 2))
        }
        else {
            authorLabel.text = nil
            authorLabel.isHidden = true
            authorLabel.opens(nil, name: "")
            bubbleTopToAuthor.deactivate()
            bubbleTopToTime.deactivate()
            bubbleTopToContent.activate()
            timeTopConstraint.update(offset: AirEntryMetrics.entryInset + max(0, (bodyLine - captionLine) / 2))
            markTopConstraint.update(offset: AirEntryMetrics.entryInset + max(0, (bodyLine - KQSize.mark) / 2))
        }

        let shown = entry.previousStatus ?? entry.status
        mark.takesReadReceipts = entry.takesReadReceipts
        mark.set(shown, animated: false)
        mark.isHidden = !entry.isOutgoing || entry.status == .none
        pendingStatus = shown != entry.status ? entry.status : nil
        if window != nil {
            applyPendingStatus()
        }

        spokenLabel = [entry.spokenAuthor, entry.timeText].filter { !$0.isEmpty }.joined(separator: ", ")
        var value = [String]()
        if let marks = transcriptMarksSpoken {
            value.append(marks)
        }
        value.append(entry.message)
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
