// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

protocol ChatMovableDateCellDelegate: class {
    func chatMovableDateCellCopyPressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellDeletePressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellMorePressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellReplyPressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellForwardPressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellReactPressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellEditPressed(_ cell: ChatMovableDateCell)
    func chatMovableDateCellPinPressed(_ cell: ChatMovableDateCell)
}

extension ChatMovableDateCellDelegate {
    func chatMovableDateCellForwardPressed(_ cell: ChatMovableDateCell) {}
    // KHANDAQ (#192): default no-op so delegates without reactions keep compiling
    func chatMovableDateCellReactPressed(_ cell: ChatMovableDateCell) {}
    // KHANDAQ (#208): default no-op so delegates without edit keep compiling
    func chatMovableDateCellEditPressed(_ cell: ChatMovableDateCell) {}
    // KHANDAQ (#G5): default no-op so delegates without pin keep compiling
    func chatMovableDateCellPinPressed(_ cell: ChatMovableDateCell) {}
}

class ChatMovableDateCell: BaseCell {
    private static var __once: () = {
            var items = UIMenuController.shared.menuItems ?? [UIMenuItem]()
            items += [
                // KHANDAQ (#192): "Реакция" opens the Telegram-style horizontal reaction bar.
                UIMenuItem(title: String(localized: "chat_react_action"), action: #selector(reactAction)),
                // KHANDAQ (#208): "Изменить" — own text messages only (gated by canBeEdited).
                UIMenuItem(title: String(localized: "chat_edit_action"), action: #selector(editMessageAction)),
                UIMenuItem(title: String(localized: "chat_reply_action"), action: #selector(replyAction)),
                UIMenuItem(title: String(localized: "chat_forward_action"), action: #selector(forwardAction)),
                // KHANDAQ (#G5): "Закрепить" — pin a message; a banner atop the chat shows it.
                UIMenuItem(title: String(localized: "chat_pin_action"), action: #selector(pinAction)),
                UIMenuItem(title: String(localized: "chat_more_menu_item"), action: #selector(moreAction))
            ]

            UIMenuController.shared.menuItems = items
        }()
    weak var delegate: ChatMovableDateCellDelegate?
    weak var replySwipeDelegate: ChatReplySwipeDelegate?

    var canBeCopied = false
    // KHANDAQ (#192): gates the "Реакция" menu item; text cells set it in setupWithTheme
    var canBeReacted = false
    // KHANDAQ (#208): gates the "Изменить" menu item; set for own outgoing text messages only
    var canBeEdited = false

    /// KHANDAQ (redesign stage 2): false for a transcript entry, which shows its time in its own column,
    /// so the pan that reveals the time and the permanent shift of "time on every message" do nothing.
    /// Set it in createViews of the subclass, before the constraints are installed.
    var revealsTime = true

    /// KHANDAQ (0.2.56): views of the row that take a sideways drag themselves, which the swipe to reply
    /// leaves to them: the wave of a voice note scrubs (ChatGenericFileCell).
    var replySwipeExcludedViews: [UIView] {
        return []
    }

    /// KHANDAQ (0.2.56): the swipe toward the start edge that answers this message.
    fileprivate var replySwipe: ChatReplySwipe!

    /**
        Superview for content that should move while panning table to the left.
     */
    var movableContentView: UIView!

    var movableOffset: CGFloat = 0 {
        didSet {
            guard revealsTime else {
                return
            }
            var offset = movableOffset

            if (UserDefaultsManager().DateonmessageMode == true) {
                offset = 39
            }

            if #available(iOS 9.0, *) {
                if UIView.userInterfaceLayoutDirection(for: self.semanticContentAttribute) == .rightToLeft {
                    offset = -offset
                }
            }

            if offset > 0.0 {
                offset = 0.0
            }

            let minOffset = -dateLabel.frame.size.width - 5.0

            if offset < minOffset {
                offset = minOffset
            }

            movableContentViewLeftConstraint.update(offset: offset)
            layoutIfNeeded()
        }
    }

    fileprivate var movableContentViewLeftConstraint: Constraint!
    fileprivate var dateLabel: UILabel!

    // KHANDAQ (#99): Telegram-style day separator pinned at the cell's coordinate-top (= visual top,
    // since the table's y-flip is cancelled by the per-cell y-flip — content is upright). Added to
    // contentView (NOT movableContentView) so it stays put during the swipe-to-reveal-time pan. When
    // the model carries no dateSeparator, the pill is hidden and movableContentView fills the cell as
    // before (top offset 0) — zero layout impact on the vast majority of rows.
    static let daySeparatorReservedHeight: CGFloat = 34.0
    // KHANDAQ (Figma): reserved band height for the "Непрочитанные сообщения" divider.
    static let unreadSeparatorReservedHeight: CGFloat = 32.0
    fileprivate var movableContentViewTopConstraint: Constraint!
    fileprivate var daySeparatorContainer: UIView!
    fileprivate var daySeparatorLabel: UILabel!
    fileprivate var unreadSeparatorContainer: UIView!
    fileprivate var unreadSeparatorLabel: UILabel!
    fileprivate var unreadSeparatorTopConstraint: Constraint!
    // KHANDAQ (redesign stage 2): the transcript marks, created on first use.
    fileprivate var transcriptDayLabel: UILabel?
    fileprivate var transcriptUnreadLabel: UILabel?
    fileprivate var transcriptUnreadTopConstraint: Constraint?
    fileprivate var transcriptLeadingConstraints: [Constraint] = []

    /// Room above the entry for the transcript day mark: 10 above the words, 4 below, as .kq-day.
    static func transcriptDayReservedHeight() -> CGFloat {
        return 14.0 + ceil(KQFont.lineHeight(.caption))
    }

    /// Room above the entry for the transcript unread mark.
    static func transcriptUnreadReservedHeight() -> CGFloat {
        return 12.0 + ceil(KQFont.lineHeight(.caption))
    }

    /// The room a row needs above its content for the marks it carries.
    static func reservedMarksHeight(day: Bool, unread: Bool, transcript: Bool) -> CGFloat {
        if transcript {
            return (day ? transcriptDayReservedHeight() : 0) + (unread ? transcriptUnreadReservedHeight() : 0)
        }
        return (day ? daySeparatorReservedHeight : 0) + (unread ? unreadSeparatorReservedHeight : 0)
    }

    fileprivate var isShowingMenu: Bool = false
    fileprivate static var setupOnceToken: Int = 0

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let movableModel = model as? ChatMovableDateCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        _ = ChatMovableDateCell.__once

        // KHANDAQ design (Figma): chat cells are transparent so the pale-green chat background
        // (tableView background) shows through — otherwise the opaque white cell fill covers it and
        // the white incoming bubble becomes invisible. On iOS 14+ the cell uses a background
        // configuration that overrides backgroundColor, so clear that too.
        backgroundColor = .clear
        contentView.backgroundColor = .clear
        if #available(iOS 14.0, *) {
            automaticallyUpdatesBackgroundConfiguration = false
            backgroundConfiguration = UIBackgroundConfiguration.clear()
        }

        dateLabel.text = movableModel.dateString
        dateLabel.numberOfLines = 0 // --> multiline label
        dateLabel.textColor = theme.colorForType(.ChatListCellMessage)
        dateLabel.isHidden = !revealsTime

        if movableModel.transcriptMarks {
            setupTranscriptMarks(movableModel)
            return
        }
        transcriptDayLabel?.isHidden = true
        transcriptUnreadLabel?.isHidden = true

        // KHANDAQ (#99): show/hide the day separator and reserve top space only when present. The
        // reserved offset is reset on every reuse, so a recycled separator cell lays out cleanly.
        // KHANDAQ (Figma): the "Непрочитанные сообщения" band stacks BELOW the day pill when both
        // land on the same row; offsets are additive and reset on reuse.
        let dayVisible: Bool
        if let separator = movableModel.dateSeparator, !separator.isEmpty {
            daySeparatorLabel.text = separator
            daySeparatorContainer.isHidden = false
            daySeparatorContainer.backgroundColor = theme.colorForType(.ChatIncomingBubble)
            daySeparatorLabel.textColor = theme.colorForType(.ChatListCellMessage)
            dayVisible = true
        } else {
            daySeparatorContainer.isHidden = true
            dayVisible = false
        }

        let unreadVisible = movableModel.unreadSeparator
        if unreadVisible {
            unreadSeparatorLabel.text = String(localized: "unread_messages_separator")
            unreadSeparatorContainer.isHidden = false
            unreadSeparatorContainer.backgroundColor = theme.colorForType(.ChatIncomingBubble).withAlphaComponent(0.92)
            unreadSeparatorLabel.textColor = theme.colorForType(.ChatListCellMessage)
        } else {
            unreadSeparatorContainer.isHidden = true
        }

        let dayOffset = dayVisible ? ChatMovableDateCell.daySeparatorReservedHeight : 0.0
        unreadSeparatorTopConstraint.update(offset: dayOffset + 3.0)
        movableContentViewTopConstraint.update(offset: dayOffset + (unreadVisible ? ChatMovableDateCell.unreadSeparatorReservedHeight : 0.0))
    }

    override func createViews() {
        super.createViews()

        movableContentView = UIView()
        movableContentView.backgroundColor = .clear
        contentView.addSubview(movableContentView)

        dateLabel = UILabel()
        dateLabel.font = UIFont.khandaqFontWithSize(11.0, weight: .medium)
        movableContentView.addSubview(dateLabel)

        // KHANDAQ (#99): day separator pill (hidden unless the model sets dateSeparator).
        daySeparatorContainer = UIView()
        daySeparatorContainer.layer.cornerRadius = 13.0
        daySeparatorContainer.layer.masksToBounds = true
        daySeparatorContainer.isUserInteractionEnabled = false
        daySeparatorContainer.isHidden = true
        contentView.addSubview(daySeparatorContainer)

        daySeparatorLabel = UILabel()
        daySeparatorLabel.font = UIFont.systemFont(ofSize: 12.0, weight: .semibold)
        daySeparatorLabel.textAlignment = .center
        daySeparatorContainer.addSubview(daySeparatorLabel)

        // KHANDAQ (Figma): full-width "Непрочитанные сообщения" band (hidden unless the model sets it).
        unreadSeparatorContainer = UIView()
        unreadSeparatorContainer.isUserInteractionEnabled = false
        unreadSeparatorContainer.isHidden = true
        contentView.addSubview(unreadSeparatorContainer)

        unreadSeparatorLabel = UILabel()
        unreadSeparatorLabel.font = UIFont.systemFont(ofSize: 12.0, weight: .semibold)
        unreadSeparatorLabel.textAlignment = .center
        unreadSeparatorContainer.addSubview(unreadSeparatorLabel)

        // KHANDAQ (redesign stage 2): the transcript marks, hidden until a model asks for them.
        let dayLabel = UILabel()
        dayLabel.font = KQFont.font(.caption, weight: 500)
        dayLabel.adjustsFontForContentSizeCategory = true
        dayLabel.isHidden = true
        dayLabel.isAccessibilityElement = false
        contentView.addSubview(dayLabel)
        transcriptDayLabel = dayLabel

        let unreadLabel = UILabel()
        unreadLabel.font = KQFont.font(.caption, weight: 600)
        unreadLabel.adjustsFontForContentSizeCategory = true
        unreadLabel.isHidden = true
        unreadLabel.isAccessibilityElement = false
        contentView.addSubview(unreadLabel)
        transcriptUnreadLabel = unreadLabel

        // KHANDAQ (0.2.56): swipe toward the start edge to answer, as in Telegram. It replaces the swipe to
        // the right that answered from the other edge, so no drag answers twice.
        replySwipe = ChatReplySwipe(cell: self)

        // Using empty view for multiple selection background.
        multipleSelectionBackgroundView = UIView()
    }

    override func installConstraints() {
        super.installConstraints()

        movableContentView.snp.makeConstraints {
            // KHANDAQ (#99): split the old `size.equalTo(contentView)` into width + bottom so the TOP can
            // be pushed down by the day-separator's reserved height (0 by default → identical layout).
            movableContentViewTopConstraint = $0.top.equalTo(contentView).constraint
            if revealsTime && UserDefaultsManager().DateonmessageMode == true {
                movableContentViewLeftConstraint = $0.leading.equalTo(contentView).constraint.update(offset: -39)
            } else {
                movableContentViewLeftConstraint = $0.leading.equalTo(contentView).constraint
            }
            $0.width.equalTo(contentView)
            $0.bottom.equalTo(contentView)
        }

        dateLabel.snp.makeConstraints {
            $0.centerY.equalTo(movableContentView)
            $0.leading.equalTo(movableContentView.snp.trailing)
        }

        // Pill centred at the coordinate-top (visual top) of the cell, within the reserved band.
        daySeparatorContainer.snp.makeConstraints {
            $0.top.equalTo(contentView).offset(4.0)
            $0.centerX.equalTo(contentView)
            $0.height.equalTo(26.0)
        }
        daySeparatorLabel.snp.makeConstraints {
            $0.top.bottom.equalTo(daySeparatorContainer)
            $0.leading.equalTo(daySeparatorContainer).offset(12.0)
            $0.trailing.equalTo(daySeparatorContainer).offset(-12.0)
        }

        // Full-width band sits under the day pill's reserved area (offset updated in setup).
        unreadSeparatorContainer.snp.makeConstraints {
            unreadSeparatorTopConstraint = $0.top.equalTo(contentView).offset(3.0).constraint
            $0.leading.trailing.equalTo(contentView)
            $0.height.equalTo(26.0)
        }
        unreadSeparatorLabel.snp.makeConstraints {
            $0.edges.equalTo(unreadSeparatorContainer)
        }

        if let dayLabel = transcriptDayLabel, let unreadLabel = transcriptUnreadLabel {
            dayLabel.snp.makeConstraints {
                $0.top.equalTo(contentView).offset(10.0)
                transcriptLeadingConstraints.append($0.leading.equalTo(contentView).constraint)
                $0.trailing.lessThanOrEqualTo(contentView).offset(-KQSpace.x16)
            }
            unreadLabel.snp.makeConstraints {
                transcriptUnreadTopConstraint = $0.top.equalTo(contentView).offset(8.0).constraint
                transcriptLeadingConstraints.append($0.leading.equalTo(contentView).constraint)
                $0.trailing.lessThanOrEqualTo(contentView).offset(-KQSpace.x16)
            }
        }
    }

    /// The day ("Today", "Yesterday", "12 March") heads its messages where their text starts, in grey
    /// words without a plate or rules; the unread mark sits the same way, in ink.
    fileprivate func setupTranscriptMarks(_ model: ChatMovableDateCellModel) {
        daySeparatorContainer.isHidden = true
        unreadSeparatorContainer.isHidden = true

        let textStart = AirEntryMetrics.textStart()
        transcriptLeadingConstraints.forEach { $0.update(offset: textStart) }

        let dayVisible: Bool
        if let separator = model.dateSeparator, !separator.isEmpty {
            transcriptDayLabel?.text = separator
            transcriptDayLabel?.textColor = KQColor.ink3
            transcriptDayLabel?.isHidden = false
            dayVisible = true
        }
        else {
            transcriptDayLabel?.isHidden = true
            dayVisible = false
        }

        if model.unreadSeparator {
            transcriptUnreadLabel?.text = String(localized: "unread_messages_separator")
            transcriptUnreadLabel?.textColor = KQColor.ink
            transcriptUnreadLabel?.isHidden = false
        }
        else {
            transcriptUnreadLabel?.isHidden = true
        }

        let dayHeight = dayVisible ? ChatMovableDateCell.transcriptDayReservedHeight() : 0.0
        transcriptUnreadTopConstraint?.update(offset: dayHeight + 8.0)
        movableContentViewTopConstraint.update(offset: ChatMovableDateCell.reservedMarksHeight(day: dayVisible,
                                                                                            unread: model.unreadSeparator,
                                                                                            transcript: true))
    }

    /// What the marks above the entry say, for VoiceOver.
    var transcriptMarksSpoken: String? {
        let parts = [transcriptDayLabel, transcriptUnreadLabel]
            .compactMap { $0 }
            .filter { !$0.isHidden }
            .compactMap { $0.text }
        return parts.isEmpty ? nil : parts.joined(separator: ", ")
    }

    override func setSelected(_ selected: Bool, animated: Bool) {
        if !isEditing {
            // don't call super in case of editing to avoid background change
            return
        }

        super.setSelected(selected, animated: animated)
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        // A swipe under way belongs to the message the row showed, not to the one it shows next.
        replySwipe.reset()
    }

    /// The day and unread marks above the message, which stay where they are while the swipe to reply
    /// moves the row.
    fileprivate var replySwipeStillViews: [UIView] {
        let marks: [UIView?] = [daySeparatorContainer, unreadSeparatorContainer, transcriptDayLabel, transcriptUnreadLabel]
        return marks.compactMap { $0 }
    }

    override func layoutSubviews() {
        super.layoutSubviews()

        // KHANDAQ (#iOS multi-select): the day-separator reserved band pushes the bubble
        // (movableContentView) DOWN inside the cell, but UIKit centers the multi-select checkmark on the
        // whole cell — so the FIRST message of a day rendered its checkmark too high, floating up into the
        // date-pill band, while every other row was fine (tester: «первая отметка высоко находится, а
        // последующие норм»). Re-center the system edit control on movableContentView so it lines up with
        // the bubble. For rows WITHOUT a separator movableContentView.frame == contentView.frame, so the
        // target equals the current (correct) position — a provable no-op, no regression. It also no-ops
        // safely if UIKit ever renames the control (nothing matches → nothing moves).
        guard isEditing, movableContentView != nil else {
            return
        }
        let targetY = movableContentView.frame.midY
        for sub in subviews where NSStringFromClass(type(of: sub)).contains("EditControl") {
            if abs(sub.center.y - targetY) > 0.5 {
                sub.center = CGPoint(x: sub.center.x, y: targetY)
            }
        }
    }
}

// Accessibility
extension ChatMovableDateCell {
    override var isAccessibilityElement: Bool {
        get {
            return true
        }
        set {}
    }

    override var accessibilityValue: String? {
        get {
            return dateLabel.text!
        }
        set {}
    }
}

extension ChatMovableDateCell: ChatEditable {
    // Override in subclass to enable menu
    @objc func shouldShowMenu() -> Bool {
        return false
    }

    // Override in subclass to enable menu
    @objc func menuTargetRect() -> CGRect {
        return CGRect.zero
    }

    @objc func willShowMenu() {
        isShowingMenu = true
    }

    @objc func willHideMenu() {
        isShowingMenu = false
    }
}

// Methods to make UIMenuController work.
extension ChatMovableDateCell {
    func isMenuActionSupportedByCell(_ action: Selector) -> Bool {
        switch action {
            case #selector(copy(_:)):
                return canBeCopied
            case #selector(delete(_:)):
                return true
            case #selector(replyAction):
                return true
            case #selector(reactAction):
                return canBeReacted
            case #selector(editMessageAction):
                return canBeEdited
            case #selector(forwardAction):
                return true
            case #selector(pinAction):
                return true
            case #selector(moreAction):
                return true
            default:
                return false
        }
    }

    override func copy(_ sender: Any?) {
        delegate?.chatMovableDateCellCopyPressed(self)
    }

    override func delete(_ sender: Any?) {
        delegate?.chatMovableDateCellDeletePressed(self)
    }

    @objc func moreAction() {
        delegate?.chatMovableDateCellMorePressed(self)
    }

    @objc func replyAction() {
        delegate?.chatMovableDateCellReplyPressed(self)
    }

    @objc func reactAction() {
        delegate?.chatMovableDateCellReactPressed(self)
    }

    @objc func editMessageAction() {
        delegate?.chatMovableDateCellEditPressed(self)
    }

    @objc func forwardAction() {
        delegate?.chatMovableDateCellForwardPressed(self)
    }

    @objc func pinAction() {
        delegate?.chatMovableDateCellPinPressed(self)
    }
}

/// KHANDAQ (0.2.56, owner decision 3): swipe a message toward the start edge to answer it, as in Telegram
/// and as ReplySwipeCallback does on Android: left, or right in a right-to-left layout; the other way does
/// nothing. The row follows the finger up to ChatReplySwipeGeometry.maxOffset while a reply badge in the
/// colours of the day pill grows in the strip it leaves; past the reply point the finger feels one light
/// tick and a release answers. The row springs back either way; the day and unread marks above the message
/// stay where they are.
///
/// Which sideways drag is which: a drag toward the start edge that starts on a message that can be
/// answered (ChatReplySwipeDelegate.chatCellCanReply) is this swipe; a drag on a view that takes drags
/// itself (replySwipeExcludedViews, the wave of a voice note) stays that view's; any other drag goes on to
/// the table. In the conversation with one person the table has a pan of its own that slides the rows
/// aside to show their time: it begins only on a row that still hides its time, which no message that
/// can be answered does (ChatPrivateController.gestureRecognizerShouldBegin), so the two never meet.
///
/// The row is moved by a transform on each view of its content rather than on the content view, whose
/// frame the cell sets on every layout and whose transform the arrival of an entry animates
/// (AirEntryTextCell): Auto Layout keeps a transform it did not set, so the row stays with the finger
/// through a layout pass.
fileprivate final class ChatReplySwipe: NSObject, UIGestureRecognizerDelegate {
    private weak var cell: ChatMovableDateCell?
    private let pan = UIPanGestureRecognizer()
    private let badge = UIView()
    private var feedback: UIImpactFeedbackGenerator?
    private var isRightToLeft = false
    private var isPastTrigger = false
    private var isSpringingBack = false
    /// Where the finger came down on the row, in the cell. The recognizer's own translation is still zero
    /// when it asks whether to begin (it starts counting there), so the drag is measured from here.
    private var touchDown: CGPoint?

    init(cell: ChatMovableDateCell) {
        self.cell = cell
        super.init()

        pan.addTarget(self, action: #selector(panned(_:)))
        pan.delegate = self
        pan.maximumNumberOfTouches = 1
        cell.contentView.addGestureRecognizer(pan)

        let diameter = ChatReplySwipeGeometry.badgeDiameter
        let iconSize = ChatReplySwipeGeometry.iconSize
        badge.bounds = CGRect(x: 0, y: 0, width: diameter, height: diameter)
        badge.layer.cornerRadius = diameter / 2
        // The colours of the day pill: the theme's chat-incoming-bubble and chat-list-cell-message are
        // these tokens, which follow the light and the dark theme by themselves.
        badge.backgroundColor = KQColor.sunken
        badge.isUserInteractionEnabled = false
        badge.isAccessibilityElement = false
        badge.alpha = 0
        badge.isHidden = true

        // The reply arrow of the message menu, the glyph Android draws too.
        let icon = UIImageView(image: ChatReactionPopup.sharedGlyph(for: "arrowshape.turn.up.left")
                                      ?? UIImage(systemName: "arrowshape.turn.up.left.fill")?.withRenderingMode(.alwaysTemplate))
        icon.tintColor = KQColor.ink2
        icon.contentMode = .scaleAspectFit
        icon.frame = CGRect(x: (diameter - iconSize) / 2, y: (diameter - iconSize) / 2, width: iconSize, height: iconSize)
        badge.addSubview(icon)
        cell.contentView.addSubview(badge)
    }

    /// Puts the row back at rest at once and ends a swipe under way without answering.
    func reset() {
        if pan.state == .began || pan.state == .changed {
            pan.isEnabled = false
            pan.isEnabled = true
        }
        stopSpringBack()
        feedback = nil
        isPastTrigger = false
        touchDown = nil
        apply(offset: 0)
        badge.isHidden = true
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard let cell = cell else {
            return false
        }
        if let view = touch.view, cell.replySwipeExcludedViews.contains(where: { view.isDescendant(of: $0) }) {
            return false
        }
        // The day and unread marks above the message are not the message.
        guard cell.movableContentView.bounds.contains(touch.location(in: cell.movableContentView)) else {
            return false
        }
        if pan.numberOfTouches == 0 {
            touchDown = touch.location(in: cell)
        }
        return true
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let cell = cell, !cell.isEditing, let delegate = cell.replySwipeDelegate, let touchDown = touchDown else {
            return false
        }
        let location = pan.location(in: cell)
        let movement = CGPoint(x: location.x - touchDown.x, y: location.y - touchDown.y)
        let isRightToLeft = cell.effectiveUserInterfaceLayoutDirection == .rightToLeft
        guard ChatReplySwipeGeometry.beginsSwipe(translation: movement, isRightToLeft: isRightToLeft) else {
            return false
        }
        return delegate.chatCellCanReply(cell)
    }

    @objc private func panned(_ recognizer: UIPanGestureRecognizer) {
        guard let cell = cell else {
            return
        }

        switch recognizer.state {
            case .began:
                // Caught again while it springs back: it follows the finger from rest at once.
                stopSpringBack()
                isRightToLeft = cell.effectiveUserInterfaceLayoutDirection == .rightToLeft
                isPastTrigger = false
                badge.isHidden = false
                feedback = UIImpactFeedbackGenerator(style: .light)
                feedback?.prepare()
                follow(recognizer, in: cell)
            case .changed:
                follow(recognizer, in: cell)
            case .ended:
                // What the finger felt decides, as on Android; the row must still be one to answer.
                let answers = isPastTrigger && cell.replySwipeDelegate?.chatCellCanReply(cell) == true
                springBack()
                if answers {
                    cell.replySwipeDelegate?.chatCellDidRequestReply(cell)
                }
            default:
                // Cancelled or failed: back to rest without answering.
                springBack()
        }
    }

    private func follow(_ recognizer: UIPanGestureRecognizer, in cell: ChatMovableDateCell) {
        let offset = ChatReplySwipeGeometry.offset(forTranslation: recognizer.translation(in: cell).x,
                                                    isRightToLeft: isRightToLeft)
        apply(offset: offset)
        let past = ChatReplySwipeGeometry.isPastTrigger(offset)
        if past && !isPastTrigger {
            // One light tick each time the finger goes past the reply point.
            feedback?.impactOccurred()
            feedback?.prepare()
        }
        isPastTrigger = past
    }

    /// The views of the row's content: all of the content view but the marks above the message and the badge.
    private func movingViews(of cell: ChatMovableDateCell) -> [UIView] {
        let still = cell.replySwipeStillViews + [badge]
        return cell.contentView.subviews.filter { view in !still.contains(where: { $0 === view }) }
    }

    private func apply(offset: CGFloat) {
        guard let cell = cell else {
            return
        }

        let moved = offset == 0 ? CGAffineTransform.identity : CGAffineTransform(translationX: offset, y: 0)
        movingViews(of: cell).forEach { $0.transform = moved }

        let progress = ChatReplySwipeGeometry.badgeProgress(forOffset: offset)
        let scale = max(progress, 0.01)
        badge.alpha = progress
        // Mirrored in a right-to-left layout, where the arrow points the other way.
        badge.transform = CGAffineTransform(scaleX: isRightToLeft ? -scale : scale, y: scale)
        // Level with the message, below the marks; the centre is the same whatever the transform.
        badge.center = CGPoint(x: ChatReplySwipeGeometry.badgeCenterX(rowWidth: cell.contentView.bounds.width,
                                                                      offset: offset,
                                                                      isRightToLeft: isRightToLeft),
                               y: cell.movableContentView.center.y)
    }

    private func stopSpringBack() {
        guard isSpringingBack, let cell = cell else {
            return
        }
        isSpringingBack = false
        (movingViews(of: cell) + [badge]).forEach { $0.layer.removeAllAnimations() }
    }

    private func springBack() {
        feedback = nil
        isPastTrigger = false
        isSpringingBack = true
        let back = {
            self.apply(offset: 0)
        }
        let done: (Bool) -> Void = { [weak self] finished in
            guard let self = self, finished, self.isSpringingBack else {
                return
            }
            self.isSpringingBack = false
            self.badge.isHidden = true
        }
        if UIAccessibilityIsReduceMotionEnabled() {
            UIView.animate(withDuration: KQMotion.duration(KQMotion.state), delay: 0,
                           options: [.curveEaseOut, .allowUserInteraction, .beginFromCurrentState],
                           animations: back, completion: done)
        }
        else {
            UIView.animate(withDuration: KQMotion.layout, delay: 0, usingSpringWithDamping: 0.8,
                           initialSpringVelocity: 0, options: [.allowUserInteraction, .beginFromCurrentState],
                           animations: back, completion: done)
        }
    }
}

// KHANDAQ: forward a message to any chat (friend or group). Resolves the full OCTManager via the app
// coordinator so it works from both 1:1 and group controllers (which each hold only a subset of
// submanagers). Wire format unchanged, so forwarded content stays cross-platform.
enum MessageForwarder {
    private static var toxManager: OCTManager? {
        guard let appDelegate = UIApplication.shared.delegate as? AppDelegate,
              let running = appDelegate.coordinator?.activeCoordinator as? RunningCoordinator else {
            return nil
        }
        return running.activeSessionCoordinator?.toxManager
    }

    static func displayName(for chat: OCTChat) -> String {
        if chat.isSavedMessages {
            return String(localized: "saved_messages_title")
        }
        if chat.isGroup {
            let name = KhandaqCommunity.displayName(for: chat) ?? chat.groupName ?? ""
            return name.isEmpty ? "—" : name
        }
        if let friend = chat.friends.lastObject() as? OCTFriend {
            return friend.nickname.isEmpty ? friend.publicKey : friend.nickname
        }
        return "?"
    }

    static func presentForwardPicker(for message: OCTMessageAbstract, from controller: UIViewController, sourceView: UIView) {
        guard let manager = toxManager else {
            return
        }

        let chats = manager.objects.chats().sortedResultsUsingProperty("lastActivityDateInterval", ascending: false)
        let alert = UIAlertController(title: String(localized: "chat_forward_action"), message: nil, preferredStyle: .actionSheet)
        alert.popoverPresentationController?.sourceView = sourceView
        alert.popoverPresentationController?.sourceRect = sourceView.bounds

        // Saved Messages always available as first option (created on demand).
        alert.addAction(UIAlertAction(title: String(localized: "saved_messages_title"), style: .default) { _ in
            guard let saved = manager.objects.getOrCreateSavedMessagesChat() else { return }
            forward(message, to: saved, manager: manager)
        })

        let limit = min(chats.count, 25)
        for index in 0..<limit {
            let chat = chats[index]
            if chat.isSavedMessages {
                continue
            }
            alert.addAction(UIAlertAction(title: displayName(for: chat), style: .default) { _ in
                forward(message, to: chat, manager: manager)
            })
        }
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        controller.present(alert, animated: true, completion: nil)
    }

    /// KHANDAQ (#G3): bulk forward — pick one destination, forward every selected message into it
    /// (caller passes them in chronological order). Same chat picker as the single-message path.
    static func presentForwardPicker(forMessages messages: [OCTMessageAbstract], from controller: UIViewController, sourceView: UIView) {
        guard let manager = toxManager, !messages.isEmpty else {
            return
        }
        let chats = manager.objects.chats().sortedResultsUsingProperty("lastActivityDateInterval", ascending: false)
        let alert = UIAlertController(title: String(localized: "chat_forward_action"), message: nil, preferredStyle: .actionSheet)
        alert.popoverPresentationController?.sourceView = sourceView
        alert.popoverPresentationController?.sourceRect = sourceView.bounds

        let send: (OCTChat) -> Void = { chat in messages.forEach { forward($0, to: chat, manager: manager) } }

        alert.addAction(UIAlertAction(title: String(localized: "saved_messages_title"), style: .default) { _ in
            guard let saved = manager.objects.getOrCreateSavedMessagesChat() else { return }
            send(saved)
        })

        let limit = min(chats.count, 25)
        for index in 0..<limit {
            let chat = chats[index]
            if chat.isSavedMessages {
                continue
            }
            alert.addAction(UIAlertAction(title: displayName(for: chat), style: .default) { _ in send(chat) })
        }
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        controller.present(alert, animated: true, completion: nil)
    }

    /// KHANDAQ (#39): the original author's display name for a "Forwarded from …" header — own
    /// messages → "You"; group messages → the frozen group peer name; 1:1 → the friend's nickname.
    private static func forwardSourceName(for message: OCTMessageAbstract, manager: OCTManager) -> String {
        // KHANDAQ (#G2): sanitize — a stale/cross-platform peer name can arrive as the literal
        // string "(null)"/"null" (or empty), which non-empty checks let through and produced
        // "↪ Переслано от (null)". Never surface those; fall back to "Неизвестно".
        func clean(_ raw: String) -> String? {
            let t = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            if t.isEmpty || t == "(null)" || t.lowercased() == "null" { return nil }
            return t
        }

        if message.isOutgoing() {
            return String(localized: "chat_reply_self_name")
        }
        if let peerName = message.messageText?.groupPeerName, let name = clean(peerName) {
            return name
        }
        if let senderId = message.senderUniqueIdentifier,
           let friend = manager.objects.object(withUniqueIdentifier: senderId, for: .friend) as? OCTFriend {
            if let nick = clean(friend.nickname) { return nick }
            if let pk = clean(friend.publicKey) { return pk }
        }
        return String(localized: "chat_reply_unknown_sender")
    }

    private static func forward(_ message: OCTMessageAbstract, to chat: OCTChat, manager: OCTManager) {
        // KHANDAQ (0.2.57, SPEC-community item 6): a file of Khandaq Community that waits for Accept stays
        // where it is, in a selection too.
        if message.messageFile != nil,
           let source = manager.objects.object(withUniqueIdentifier: message.chatUniqueIdentifier, for: .chat) as? OCTChat,
           KhandaqCommunity.fileHold(of: message, in: source) != .none {
            return
        }

        if chat.isSavedMessages {
            saveLocally(message, to: chat, manager: manager)
            return
        }

        if let raw = message.messageText?.text, !raw.isEmpty {
            // KHANDAQ (#39/#32): forward the clean visible body, NOT the original reply/mention wire
            // markup. Re-sending the raw text made the forward masquerade as a reply (leaked quote) and
            // left a tappable quote that could fuzzy-jump to an unrelated local message on the receiver.
            // KHANDAQ (#iOS forward): strip any existing "↪ Переслано от …" header so re-forwards don't
            // stack headers (and don't carry a stale "(null)" from a cross-platform/legacy sender).
            let body = MessageReplyHelper.stripForwardedHeader(MessageReplyHelper.plainBody(for: message) ?? raw)
            // KHANDAQ (#39): Telegram-style "Forwarded from <name>" attribution line above the body.
            // String(localized:) formats by itself; formatting its result again sent "(null)" for the name.
            let header = MessageReplyHelper.forwardMark + String(localized: "chat_forwarded_from_format",
                                                                 forwardSourceName(for: message, manager: manager) as NSString)
            let maxBytes = chat.isGroup ? AirGroupComposeLimit.bytes : AirComposeLimit.bytes
            let parts = forwardTextParts(header: header, body: body, maxBytes: maxBytes)
            if chat.isGroup {
                // One after another, in order, as a shared text goes to a group.
                for part in parts {
                    manager.groups.sendMessage(to: chat, text: part, type: .normal, successBlock: { _ in }, failureBlock: { _ in })
                }
            }
            else {
                manager.chats.sendMessageParts(to: chat, parts: parts, type: .normal, successBlock: nil, failureBlock: nil)
            }
            return
        }

        guard let file = message.messageFile,
              let path = file.filePath(),
              FileManager.default.fileExists(atPath: path) else {
            return
        }

        // Copy to a temp path so sendFile(moveToUploads:) doesn't move the source message's file away.
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(forwardFileName(for: file, diskPath: path))
        try? FileManager.default.removeItem(at: tempURL)
        guard (try? FileManager.default.copyItem(atPath: path, toPath: tempURL.path)) != nil else {
            return
        }

        if chat.isGroup {
            manager.groups.sendFile(atPath: tempURL.path, to: chat, moveToUploads: true, successBlock: { _ in }, failureBlock: { _ in })
        }
        else {
            manager.files.sendFile(atPath: tempURL.path, moveToUploads: true, to: chat) { _ in }
        }
    }

    /// The messages a forwarded text goes as: the header line, then the body, in parts that each fit one
    /// message of `maxBytes`, the header riding on the first. KHANDAQ (review 0.2.56): it went as one, and
    /// the forward of a part of a long text, with the header added, no longer fit: a group refused it, a
    /// chat with a person cut its end off.
    static func forwardTextParts(header: String, body: String, maxBytes: Int) -> [String] {
        let text = header + "\n" + body
        guard text.utf8.count > maxBytes else {
            return [text]
        }
        return AirComposeLimit.parts(of: body, head: header + "\n", maxBytes: maxBytes)
    }

    /// The name a forwarded file goes out under: the one it was sent or received with, not the on-disk
    /// one. The file store appends " 2" to keep disk names unique, so a voice note stored as
    /// "voice.file 2.m4a" lost its ".file.m4a" marker and arrived as a plain file. That name came from
    /// a peer, so one that could step out of the temp directory falls back to the disk name.
    static func forwardFileName(for file: OCTMessageFile, diskPath: String) -> String {
        guard let name = file.fileName, !name.isEmpty, name != ".",
              !name.contains("/"), !name.contains("\\"), !name.contains("..") else {
            return (diskPath as NSString).lastPathComponent
        }
        return name
    }

    /// Persistent directory for files stored in Saved Messages (Tox never transfers these).
    private static func savedFilesDirectory() -> URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = base.appendingPathComponent("KhandaqSaved", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: nil)
        return dir
    }

    private static func saveLocally(_ message: OCTMessageAbstract, to chat: OCTChat, manager: OCTManager) {
        if let raw = message.messageText?.text, !raw.isEmpty {
            // KHANDAQ (#39): store the clean body, not the reply/mention wire markup.
            // KHANDAQ (#iOS forward): also strip any "↪ Переслано от …" header so Saved copies aren't doubled.
            manager.objects.addSavedTextMessage(
                MessageReplyHelper.stripForwardedHeader(MessageReplyHelper.plainBody(for: message) ?? raw), to: chat)
            return
        }

        guard let file = message.messageFile,
              let path = file.filePath(),
              FileManager.default.fileExists(atPath: path),
              let dir = savedFilesDirectory() else {
            return
        }

        let originalName = (file.fileName?.isEmpty == false) ? file.fileName! : (path as NSString).lastPathComponent
        let dest = dir.appendingPathComponent("\(UUID().uuidString)_\(originalName)")
        guard (try? FileManager.default.copyItem(atPath: path, toPath: dest.path)) != nil else {
            return
        }

        manager.objects.addSavedFileMessage(withPath: dest.path,
                                            fileName: originalName,
                                            fileSize: file.fileSize,
                                            fileUTI: file.fileUTI,
                                            to: chat)
    }
}
