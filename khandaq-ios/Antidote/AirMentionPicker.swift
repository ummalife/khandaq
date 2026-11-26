// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

/// KHANDAQ (26.11.2026, DESIGN-group-contacts-private-mentions.md): the members "@" offers above the field of a group,
/// as Telegram does: the tile, the name and the @handle of each, online first, then by name. A tap puts "@handle " in
/// place of what was typed; a space, or the caret leaving the word, closes it.
final class AirMentionPickerView: UIView {
    /// The member picked.
    var onPick: ((GroupMentionHelper.Candidate) -> Void)?

    private let scroll = UIScrollView()
    private let stack = UIStackView()
    private let rule = UIView()
    private var heightConstraint: Constraint?
    private(set) var candidates: [GroupMentionHelper.Candidate] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.surface
        accessibilityIdentifier = "air.group.mentions"
        isHidden = true
        rule.backgroundColor = KQColor.line
        stack.axis = .vertical
        scroll.alwaysBounceVertical = false
        scroll.addSubview(stack)
        addSubview(scroll)
        addSubview(rule)

        rule.snp.makeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }
        scroll.snp.makeConstraints {
            $0.top.equalTo(rule.snp.bottom)
            $0.leading.trailing.bottom.equalToSuperview()
        }
        stack.snp.makeConstraints {
            $0.edges.equalTo(scroll.contentLayoutGuide)
            $0.width.equalTo(scroll.frameLayoutGuide)
        }
        snp.makeConstraints {
            heightConstraint = $0.height.equalTo(0).constraint
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// Shows `candidates`, or hides the list when there are none.
    func show(_ candidates: [GroupMentionHelper.Candidate]) {
        guard !candidates.isEmpty else {
            hide()
            return
        }
        if candidates != self.candidates || isHidden {
            self.candidates = candidates
            stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
            for candidate in candidates {
                let row = AirMentionRow(candidate: candidate)
                row.addTarget(self, action: #selector(rowPressed(_:)), for: .touchUpInside)
                stack.addArrangedSubview(row)
            }
            scroll.contentOffset = .zero
        }
        isHidden = false
        updateHeight()
    }

    func hide() {
        guard !isHidden || !candidates.isEmpty else {
            return
        }
        isHidden = true
        candidates = []
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        updateHeight()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        setNeedsLayout()
    }

    /// As tall as its rows, at most four and a half of them (the half says there are more), and never more than
    /// half of the screen above the field.
    private func updateHeight() {
        let width = superview?.bounds.width ?? bounds.width
        guard !isHidden, width > 0 else {
            return
        }
        let content = stack.systemLayoutSizeFitting(CGSize(width: width, height: UILayoutFittingCompressedSize.height),
                                                    withHorizontalFittingPriority: .required,
                                                    verticalFittingPriority: .fittingSizeLevel).height
        let rowHeight = content / CGFloat(max(1, candidates.count))
        var limit = rowHeight * 4.5
        if let host = superview {
            limit = min(limit, host.bounds.height * 0.5)
        }
        let height = ceil(min(content, limit)) + KQStroke.hairline
        if abs((heightConstraint?.layoutConstraints.first?.constant ?? 0) - height) > 0.5 {
            heightConstraint?.update(offset: height)
        }
    }

    @objc private func rowPressed(_ row: AirMentionRow) {
        onPick?(row.candidate)
    }
}

/// A member in the list of "@": the tile with the dot of one online now, the name and the @handle.
final class AirMentionRow: UIControl {
    let candidate: GroupMentionHelper.Candidate
    private let tile = AirTileView(side: KQSize.tileDesk)
    private let dot = UIView()
    private let nameLabel = AirKit.label(.body, weight: 500, color: KQColor.ink, lines: 1)
    private let handleLabel = AirKit.label(.caption, color: KQColor.ink3, lines: 1)

    init(candidate: GroupMentionHelper.Candidate) {
        self.candidate = candidate
        super.init(frame: .zero)
        let member = candidate.member
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityIdentifier = "air.group.mention"
        accessibilityLabel = member.name
        accessibilityValue = "@" + candidate.handle
        accessibilityHint = String(localized: "air_group_mention_hint")

        tile.configure(style: .person(initials: AirInitials.from(member.name), tone: AirTone.index(for: member.toneKey)), photo: nil)
        dot.backgroundColor = KQColor.live
        dot.layer.cornerRadius = (KQSize.dot + 2 * KQStroke.hairline) / 2
        dot.layer.borderWidth = 2 * KQStroke.hairline
        dot.isHidden = !member.isOnline
        nameLabel.text = member.name
        nameLabel.lineBreakMode = .byTruncatingTail
        handleLabel.text = "@" + candidate.handle
        handleLabel.lineBreakMode = .byTruncatingTail

        let texts = AirKit.column([nameLabel, handleLabel], spacing: 0)
        let row = UIStackView(arrangedSubviews: [tile, texts])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = KQSpace.x12
        row.isUserInteractionEnabled = false
        addSubview(row)
        addSubview(dot)

        row.snp.makeConstraints {
            $0.top.bottom.equalToSuperview().inset(KQSpace.x6)
            $0.leading.trailing.equalToSuperview().inset(KQSpace.x16)
        }
        tile.snp.makeConstraints {
            $0.size.equalTo(KQSize.tileDesk)
        }
        dot.snp.makeConstraints {
            $0.size.equalTo(KQSize.dot + 2 * KQStroke.hairline)
            $0.trailing.equalTo(tile).offset(KQStroke.hairline)
            $0.bottom.equalTo(tile).offset(KQStroke.hairline)
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.rowDeskCompact)
        }
        applyDotBorder()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            backgroundColor = isHighlighted ? KQColor.selected : .clear
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyDotBorder()
    }

    /// The dot is cut out of the tile by a ring of the list's colour.
    private func applyDotBorder() {
        dot.layer.borderColor = airCGColor(KQColor.surface)
    }
}

// MARK: - The field of a group

extension ChatGroupController {
    /// The list above the field, laid out over the foot of the transcript, above the reply being written.
    func installMentionPicker() {
        view.addSubview(mentionPicker)
        mentionPicker.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            $0.bottom.equalTo(replyController.previewView.snp.top)
        }
        mentionPicker.onPick = { [weak self] candidate in
            self?.insertMention(candidate)
        }
        chatInputView.onSelectionChanged = { [weak self] in
            self?.updateMentionPicker()
        }
    }

    /// The handle of every member of the group now, by key.
    var groupMentionHandles: [String: String] {
        return GroupMentionHelper.handles(for: groupMembers)
    }

    /// The block of the members `text` mentions (DESIGN-group-contacts-private-mentions.md): every "@handle" of a
    /// member now, with the member's key; empty when it mentions nobody.
    func mentionBlock(for text: String) -> String {
        return GroupMentionHelper.block(for: GroupMentionHelper.mentions(in: text, handles: groupMentionHandles))
    }

    /// `text` with the block of the members it mentions in front of it.
    func textWithMentionBlock(_ text: String) -> String {
        return mentionBlock(for: text) + text
    }

    /// Opens, filters or closes the list for the word at the caret.
    func updateMentionPicker() {
        guard groupComposeRight == .write, chatInputView.isEditingText, chatInputView.selectedRange.length == 0,
              let typed = GroupMentionHelper.typedMention(in: chatInputView.text, caret: chatInputView.selectedRange.location) else {
            mentionPicker.hide()
            return
        }
        mentionPicker.show(GroupMentionHelper.candidates(from: groupMembers, handles: groupMentionHandles, query: typed.query))
    }

    /// "@handle " in place of the word being typed.
    func insertMention(_ candidate: GroupMentionHelper.Candidate) {
        guard let typed = GroupMentionHelper.typedMention(in: chatInputView.text, caret: chatInputView.selectedRange.location) else {
            mentionPicker.hide()
            return
        }
        chatInputView.replaceText(in: typed.range, with: "@" + candidate.handle + " ")
        mentionPicker.hide()
    }
}
