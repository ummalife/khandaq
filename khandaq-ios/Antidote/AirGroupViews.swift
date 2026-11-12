// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// The parts of the group screens (design/redesign/02-plan.md, E13 to E15): the band under the header
// with the people who get a message right now, the bar that replaces the field when you may only read,
// a member row of the group dossier and a contact row of an invitation list.

// MARK: - Band under the header

/// "9 members, 4 online now", then "Get it now" with the tiles of the people online. The tiles scroll
/// sideways when there are many; a tap anywhere opens the group dossier.
final class AirGroupBandView: UIView {
    var onOpen: (() -> Void)?

    private let countsLabel = AirKit.label(.caption, color: KQColor.ink2, lines: 2)
    private let nowLabel = AirKit.label(.caption, color: KQColor.ink3)
    private let tilesScroll = UIScrollView()
    private let tiles = UIStackView()
    private let reachRow = UIStackView()
    private let rule = UIView()
    private var spokenNames: [String] = []
    private var shownKeys = Set<String>()
    private var tilesHeight: Constraint!
    private var shown: (reach: AirGroupReach, reachable: [AirGroupMember], showsReach: Bool)?
    private var tileCategory = UIContentSizeCategory.unspecified

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.surface
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityIdentifier = "air.group.band"
        accessibilityHint = String(localized: "air_group_dossier_hint")

        countsLabel.font = KQFont.tabular(KQFont.font(.caption))
        countsLabel.setContentCompressionResistancePriority(UILayoutPriority(rawValue: 900), for: .vertical)
        nowLabel.text = String(localized: "air_group_get_now")
        nowLabel.setContentHuggingPriority(.required, for: .horizontal)
        nowLabel.setContentCompressionResistancePriority(.required, for: .horizontal)

        tiles.axis = .horizontal
        tiles.spacing = KQSpace.x4
        tiles.alignment = .center
        tilesScroll.showsHorizontalScrollIndicator = false
        tilesScroll.alwaysBounceHorizontal = false
        tilesScroll.addSubview(tiles)

        reachRow.addArrangedSubview(nowLabel)
        reachRow.addArrangedSubview(tilesScroll)
        reachRow.axis = .horizontal
        reachRow.alignment = .center
        reachRow.spacing = KQSpace.x8

        let column = UIStackView(arrangedSubviews: [countsLabel, reachRow])
        column.axis = .vertical
        column.alignment = .fill
        column.spacing = KQSpace.x6
        addSubview(column)
        rule.backgroundColor = KQColor.line
        addSubview(rule)

        column.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x6)
            $0.bottom.equalToSuperview().offset(-KQSpace.x8)
            $0.leading.equalTo(safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.equalTo(safeAreaLayoutGuide).offset(-KQSpace.x16)
        }
        tiles.snp.makeConstraints {
            $0.edges.equalToSuperview()
            $0.height.equalToSuperview()
        }
        tilesScroll.snp.makeConstraints {
            tilesHeight = $0.height.equalTo(KQSize.tileDesk).constraint
        }
        rule.snp.makeConstraints {
            $0.leading.trailing.bottom.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }

        // A tap rather than a control: the tiles have to scroll under the finger.
        let tap = UITapGestureRecognizer(target: self, action: #selector(tapped))
        addGestureRecognizer(tap)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// `reachable` is the people who get a message now; `showsReach` is false where you cannot write.
    /// With `animated`, the tile of someone who has just come online fades in (at once with Reduce Motion).
    func configure(reach: AirGroupReach, reachable: [AirGroupMember], showsReach: Bool, animated: Bool = false) {
        shown = (reach, reachable, showsReach)
        countsLabel.text = reach.headerText
        // The tiles grow with the words beside them at the accessibility sizes, so the initials fit.
        tileCategory = traitCollection.preferredContentSizeCategory
        let side = tileCategory.isAccessibilityCategory ? KQSize.tilePhone : KQSize.tileDesk
        tilesHeight.update(offset: side)
        tiles.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let before = shownKeys
        shownKeys = Set(reachable.map { $0.toneKey })
        let fadeIn = animated && !before.isEmpty && !UIAccessibilityIsReduceMotionEnabled()
        for member in reachable {
            let tile = AirTileView(side: side)
            tile.configure(style: .person(initials: AirInitials.from(member.name), tone: AirTone.index(for: member.toneKey)), photo: nil)
            tile.snp.makeConstraints {
                $0.size.equalTo(side)
            }
            tiles.addArrangedSubview(tile)
            if fadeIn && !before.contains(member.toneKey) {
                tile.alpha = 0
                tile.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
                UIView.animate(withDuration: KQMotion.duration(KQMotion.state), delay: 0, options: [.curveEaseOut, .allowUserInteraction], animations: {
                    tile.alpha = 1
                    tile.transform = .identity
                }, completion: nil)
            }
        }
        reachRow.isHidden = !showsReach || reachable.isEmpty
        spokenNames = reachable.map { $0.name }
        var spoken = [reach.headerText]
        if !reachRow.isHidden {
            spoken.append(String(localized: "air_group_get_now_spoken_format", spokenNames.joined(separator: ", ")))
        }
        accessibilityLabel = spoken.joined(separator: ". ")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if let shown = shown, traitCollection.preferredContentSizeCategory != tileCategory {
            configure(reach: shown.reach, reachable: shown.reachable, showsReach: shown.showsReach)
        }
    }

    override func accessibilityActivate() -> Bool {
        onOpen?()
        return true
    }

    @objc private func tapped() {
        onOpen?()
    }
}

// MARK: - Read only

/// In place of the field when you may not write: why, and whom to ask; an observer, who cannot ask
/// inside the group, gets a line saying so instead of the button. The bar is as tall as its text up to
/// `screenShare` of the conversation under the header (ChatGroupController); past that, at the largest
/// text sizes, the text scrolls inside it and the log keeps its room.
final class AirGroupReadOnlyBar: UIView {
    /// The most of the conversation under the header the bar takes. At AX5 the two lines of an observer
    /// had left the log 5 points in Russian; held to this share, the log keeps a third of the screen.
    static let screenShare: CGFloat = 0.4

    var onAsk: (() -> Void)?

    private let icon = UIImageView(image: AirIcon.image("eye"))
    private let textLabel = AirKit.label(.label, weight: 400, color: KQColor.ink2, lines: 0)
    private let noteLabel = AirKit.label(.label, weight: 400, color: KQColor.ink2, lines: 0)
    private let askButton = AirButton(style: .normal, title: "")
    private let rule = UIView()
    /// Holds the text and the button: as tall as they are, until the share of the screen stops it.
    private let scroller = UIScrollView()
    /// Set when the text changes or the bar comes back; the next layout that finds the text taller than
    /// the bar flashes the scroll indicator once, so the rest below is not missed.
    private var flashWhenCut = false

    override var isHidden: Bool {
        didSet {
            if oldValue && !isHidden {
                flashWhenCut = true
                setNeedsLayout()
            }
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.surface
        accessibilityIdentifier = "air.group.readOnly"
        icon.tintColor = KQColor.ink3
        icon.contentMode = .scaleAspectFit
        icon.isAccessibilityElement = false
        textLabel.accessibilityIdentifier = "air.group.readOnly.text"
        noteLabel.accessibilityIdentifier = "air.group.readOnly.note"
        noteLabel.isHidden = true
        askButton.accessibilityIdentifier = "air.group.readOnly.ask"
        askButton.addTarget(self, action: #selector(askTapped), for: .touchUpInside)
        rule.backgroundColor = KQColor.line
        scroller.contentInsetAdjustmentBehavior = .never
        scroller.showsHorizontalScrollIndicator = false

        let texts = UIStackView(arrangedSubviews: [textLabel, noteLabel])
        texts.axis = .vertical
        texts.spacing = KQSpace.x6
        let line = UIStackView(arrangedSubviews: [icon, texts])
        line.axis = .horizontal
        line.alignment = .top
        line.spacing = KQSpace.x8
        let column = UIStackView(arrangedSubviews: [line, askButton])
        column.axis = .vertical
        column.spacing = KQSpace.x10
        addSubview(scroller)
        scroller.addSubview(column)
        addSubview(rule)

        rule.snp.makeConstraints {
            $0.leading.trailing.top.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }
        scroller.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        column.snp.makeConstraints {
            $0.top.equalTo(scroller.contentLayoutGuide).offset(KQSpace.x12)
            $0.bottom.equalTo(scroller.contentLayoutGuide).offset(-KQSpace.x12)
            $0.leading.equalTo(safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.equalTo(safeAreaLayoutGuide).offset(-KQSpace.x16)
        }
        // Nothing scrolls sideways. In height the bar takes what it holds, unless its share of the
        // screen, a required cap, stops it first. Just under the text's resistance to being squeezed
        // (750): where the cap stops the bar, the bar gives way and the text scrolls; at 750 the engine
        // squeezed the lines of an observer at AX5 instead.
        scroller.contentLayoutGuide.widthAnchor.constraint(equalTo: scroller.frameLayoutGuide.widthAnchor).isActive = true
        let fit = scroller.frameLayoutGuide.heightAnchor.constraint(equalTo: scroller.contentLayoutGuide.heightAnchor)
        fit.priority = UILayoutPriority(UILayoutPriority.defaultHigh.rawValue - 1)
        fit.isActive = true
        icon.snp.makeConstraints {
            $0.size.equalTo(KQSize.icon)
        }
        // The button has no height of its own above its minimum.
        askButton.snp.makeConstraints {
            $0.height.equalTo(KQSize.targetIos).priority(.medium)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard flashWhenCut, !isHidden, window != nil else {
            return
        }
        scroller.layoutIfNeeded()
        guard scroller.bounds.height > 0 else {
            return
        }
        flashWhenCut = false
        if scroller.contentSize.height > scroller.bounds.height + 1 {
            scroller.flashScrollIndicators()
        }
    }

    /// `note` says why there is nobody to ask inside the group; it stands in place of the button.
    func configure(text: String, askTitle: String?, canAsk: Bool, note: String? = nil) {
        if textLabel.text != text || noteLabel.text != note {
            flashWhenCut = true
            setNeedsLayout()
        }
        textLabel.text = text
        noteLabel.text = note
        noteLabel.isHidden = note == nil
        askButton.setTitle(askTitle ?? "")
        askButton.isHidden = askTitle == nil || note != nil
        askButton.isEnabled = canAsk
        askButton.accessibilityHint = canAsk ? nil : String(localized: "air_group_ask_nobody")
    }

    @objc private func askTapped() {
        onAsk?()
    }
}

// MARK: - Member row

/// A member of the group dossier: the tile with a dot while online, the name, the role and when this
/// phone last saw them. The whole row opens the member's actions; so does a long press.
final class AirGroupMemberRow: UIControl {
    var onLongPress: (() -> Void)?

    let member: AirGroupMember
    private let tile = AirTileView(side: KQSize.tilePhone)
    private let dot = UIView()
    private let nameLabel = AirKit.label(.body, weight: 500, color: KQColor.ink, lines: 2)
    private let statusLabel = AirKit.label(.caption, color: KQColor.ink3, lines: 2)

    init(member: AirGroupMember, status: String, opens: Bool) {
        self.member = member
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityTraits = opens ? UIAccessibilityTraitButton : UIAccessibilityTraitStaticText
        accessibilityIdentifier = member.isSelf ? "air.group.member.self" : "air.group.member"
        isUserInteractionEnabled = opens

        tile.configure(style: member.isSelf
                           ? .me(initials: AirInitials.from(member.name))
                           : .person(initials: AirInitials.from(member.name), tone: AirTone.index(for: member.toneKey)),
                       photo: nil)
        dot.backgroundColor = KQColor.live
        dot.layer.cornerRadius = (KQSize.dot + 2 * KQStroke.hairline) / 2
        dot.layer.borderWidth = 2 * KQStroke.hairline
        dot.isHidden = !member.isOnline
        nameLabel.text = member.isSelf ? String(localized: "air_group_member_you_format", member.name) : member.name
        statusLabel.text = status
        statusLabel.font = KQFont.tabular(KQFont.font(.caption))

        let texts = AirKit.column([nameLabel, statusLabel], spacing: KQSpace.x2)
        let row = UIStackView(arrangedSubviews: [tile, texts])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = KQSpace.x12
        row.isUserInteractionEnabled = false
        addSubview(row)
        addSubview(dot)

        row.snp.makeConstraints {
            $0.top.bottom.equalToSuperview().inset(KQSpace.x10)
            $0.leading.trailing.equalToSuperview().inset(KQSpace.x16)
        }
        tile.snp.makeConstraints {
            $0.size.equalTo(KQSize.tilePhone)
        }
        dot.snp.makeConstraints {
            $0.size.equalTo(KQSize.dot + 2 * KQStroke.hairline)
            $0.trailing.equalTo(tile).offset(KQStroke.hairline)
            $0.bottom.equalTo(tile).offset(KQStroke.hairline)
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.rowDesk)
        }
        applyDotBorder()

        accessibilityLabel = nameLabel.text
        accessibilityValue = status
        if opens {
            accessibilityHint = String(localized: "air_group_member_hint")
            let press = UILongPressGestureRecognizer(target: self, action: #selector(longPressed(_:)))
            addGestureRecognizer(press)
        }
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

    /// The dot is cut out of the tile by a ring of the row's colour.
    private func applyDotBorder() {
        dot.layer.borderColor = airCGColor(KQColor.surface)
    }

    @objc private func longPressed(_ recognizer: UILongPressGestureRecognizer) {
        if recognizer.state == .began {
            onLongPress?()
        }
    }
}

// MARK: - Invitation row

/// A contact on an invitation list: the tile, the name, why they cannot be invited now if so, and a
/// round mark that fills when chosen.
final class AirInviteRow: UIControl {
    let candidate: AirInviteCandidate
    private let tile = AirTileView(side: KQSize.tilePhone)
    private let nameLabel = AirKit.label(.body, color: KQColor.ink, lines: 2)
    private let noteLabel = AirKit.label(.caption, color: KQColor.ink3, lines: 2)
    private let mark = UIView()
    private let check = UIImageView(image: AirIcon.image("check"))

    init(candidate: AirInviteCandidate) {
        self.candidate = candidate
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityIdentifier = "air.invite.contact"
        isEnabled = candidate.canInvite

        tile.configure(style: .person(initials: AirInitials.from(candidate.name), tone: AirTone.index(for: candidate.publicKey)), photo: nil)
        nameLabel.text = candidate.name
        let note = AirInviteList.note(for: candidate) ?? (candidate.isOnline ? String(localized: "air_reach_live") : nil)
        noteLabel.text = note
        noteLabel.isHidden = (note ?? "").isEmpty
        mark.layer.cornerRadius = KQSize.icon / 2
        mark.layer.borderWidth = KQStroke.strong
        mark.isUserInteractionEnabled = false
        check.tintColor = KQColor.onInk
        check.contentMode = .scaleAspectFit
        mark.addSubview(check)

        let texts = AirKit.column([nameLabel, noteLabel], spacing: KQSpace.x2)
        let row = UIStackView(arrangedSubviews: [tile, texts, mark])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = KQSpace.x12
        row.isUserInteractionEnabled = false
        addSubview(row)

        row.snp.makeConstraints {
            $0.top.bottom.equalToSuperview().inset(KQSpace.x10)
            $0.leading.trailing.equalToSuperview().inset(KQSpace.x16)
        }
        tile.snp.makeConstraints {
            $0.size.equalTo(KQSize.tilePhone)
        }
        mark.snp.makeConstraints {
            $0.size.equalTo(KQSize.icon)
        }
        check.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.size.equalTo(KQSize.iconSmall)
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.rowDesk)
        }
        accessibilityLabel = candidate.name
        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isSelected: Bool {
        didSet {
            apply()
        }
    }

    override var isHighlighted: Bool {
        didSet {
            backgroundColor = isHighlighted ? KQColor.selected : .clear
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        apply()
    }

    private func apply() {
        let enabled = candidate.canInvite
        mark.isHidden = !enabled
        mark.backgroundColor = isSelected ? KQColor.ink : .clear
        mark.layer.borderColor = airCGColor(isSelected ? KQColor.ink : KQColor.lineStrong)
        check.isHidden = !isSelected
        nameLabel.textColor = enabled ? KQColor.ink : KQColor.ink3
        var traits = UIAccessibilityTraitButton
        if isSelected {
            traits |= UIAccessibilityTraitSelected
        }
        if !enabled {
            traits |= UIAccessibilityTraitNotEnabled
        }
        accessibilityTraits = traits
        accessibilityValue = noteLabel.isHidden ? nil : noteLabel.text
    }
}

// MARK: - Search field

/// A search field on the page colour: the glyph, the text, a clear button while there is text.
final class AirSearchField: UIView, UITextFieldDelegate {
    var onChange: ((String) -> Void)?

    let textField = UITextField()
    private let glyph = UIImageView(image: AirIcon.image("search"))

    init(placeholder: String) {
        super.init(frame: .zero)
        backgroundColor = KQColor.sunken
        layer.cornerRadius = KQRadius.field
        glyph.tintColor = KQColor.ink3
        glyph.contentMode = .scaleAspectFit
        glyph.isAccessibilityElement = false
        textField.font = KQFont.font(.body)
        textField.adjustsFontForContentSizeCategory = true
        textField.textColor = KQColor.ink
        textField.tintColor = KQColor.ink
        textField.attributedPlaceholder = NSAttributedString(string: placeholder, attributes: [.foregroundColor: KQColor.ink3])
        textField.accessibilityLabel = placeholder
        textField.clearButtonMode = .whileEditing
        textField.returnKeyType = .search
        textField.autocorrectionType = .no
        textField.delegate = self
        textField.addTarget(self, action: #selector(changed), for: .editingChanged)
        addSubview(glyph)
        addSubview(textField)
        glyph.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x10)
            $0.centerY.equalToSuperview()
            $0.size.equalTo(KQSize.icon)
        }
        textField.snp.makeConstraints {
            $0.leading.equalTo(glyph.snp.trailing).offset(KQSpace.x8)
            $0.trailing.equalToSuperview().offset(-KQSpace.x8)
            $0.top.bottom.equalToSuperview().inset(KQSpace.x4)
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    var text: String {
        return textField.text ?? ""
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }

    @objc private func changed() {
        onChange?(text)
    }
}

// MARK: - Invitation list

/// The contacts to invite, online first, with a search from eight contacts on. Used by "New group" and
/// by "Invite" in the group dossier.
final class AirInviteListView: UIView {
    var onChange: (() -> Void)?

    /// The chosen contacts by key: a friend number is only a slot of this session.
    private(set) var selected = Set<String>()
    private var candidates: [AirInviteCandidate] = []
    private let search = AirSearchField(placeholder: String(localized: "air_invite_search"))
    private let list = UIStackView()
    private let plate = UIView()
    private let emptyLabel = AirKit.note("")
    private static let searchFrom = 8

    override init(frame: CGRect) {
        super.init(frame: frame)
        accessibilityIdentifier = "air.invite.list"
        list.axis = .vertical
        plate.backgroundColor = KQColor.surface
        plate.layer.cornerRadius = KQRadius.card
        plate.clipsToBounds = true
        plate.addSubview(list)
        list.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        search.onChange = { [weak self] _ in
            self?.reload()
        }
        let column = AirKit.column([search, plate, emptyLabel], spacing: KQSpace.x8)
        addSubview(column)
        column.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(candidates: [AirInviteCandidate]) {
        self.candidates = candidates
        let invitable = Set(candidates.filter { $0.canInvite }.map { $0.publicKey })
        selected = selected.intersection(invitable)
        search.isHidden = candidates.count < AirInviteListView.searchFrom
        reload()
    }

    var chosen: [AirInviteCandidate] {
        return candidates.filter { selected.contains($0.publicKey) }
    }

    private func reload() {
        list.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let shown = AirInviteList.arranged(candidates, query: search.text)
        for (index, candidate) in shown.enumerated() {
            if index > 0 {
                list.addArrangedSubview(AirInviteListView.rule())
            }
            let row = AirInviteRow(candidate: candidate)
            row.isSelected = selected.contains(candidate.publicKey)
            row.addTarget(self, action: #selector(rowTapped(_:)), for: .touchUpInside)
            list.addArrangedSubview(row)
        }
        plate.isHidden = shown.isEmpty
        if candidates.isEmpty {
            emptyLabel.text = String(localized: "air_invite_no_contacts")
        }
        else if shown.isEmpty {
            emptyLabel.text = String(localized: "air_invite_nobody_found")
        }
        else {
            emptyLabel.text = nil
        }
        emptyLabel.isHidden = emptyLabel.text == nil
    }

    private static func rule() -> UIView {
        let rule = UIView()
        rule.backgroundColor = KQColor.line
        rule.snp.makeConstraints {
            $0.height.equalTo(KQStroke.hairline)
        }
        let inset = UIView()
        inset.addSubview(rule)
        rule.snp.makeConstraints {
            $0.top.bottom.trailing.equalToSuperview()
            $0.leading.equalToSuperview().offset(KQSpace.x16 + KQSize.tilePhone + KQSpace.x12)
        }
        return inset
    }

    @objc private func rowTapped(_ row: AirInviteRow) {
        let key = row.candidate.publicKey
        if selected.contains(key) {
            selected.remove(key)
        }
        else {
            selected.insert(key)
        }
        row.isSelected = selected.contains(key)
        onChange?()
    }
}
