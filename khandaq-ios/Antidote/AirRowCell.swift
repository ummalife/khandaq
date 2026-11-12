// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

/// A row of the Air list: tile, name with the reachability mark, then time, our delivery state, the
/// last message and the flags ("N in transit", unread). 68 points high on a phone; at the accessibility
/// text sizes each of them takes a line of its own.
final class AirRowCell: UITableViewCell {
    static let reuseIdentifier = "AirRowCell"

    private let tile = AirTileView(side: KQSize.tilePhone)
    private let nameLabel = UILabel()
    private let reachView = AirReachView()
    private let timeLabel = UILabel()
    private let deliveryIcon = UIImageView()
    private let previewLabel = UILabel()
    private let transitFlag = AirTransitFlag()
    private let unreadBadge = AirCountBadge()
    private let top = UIStackView()
    private let last = UIStackView()
    private let spacer = UIView()
    private let bottom = UIStackView()

    override init(style: UITableViewCellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)

        backgroundColor = KQColor.surface
        contentView.backgroundColor = .clear
        tintColor = KQColor.ink

        let highlight = UIView()
        highlight.backgroundColor = KQColor.sunken
        selectedBackgroundView = highlight
        let picked = UIView()
        picked.backgroundColor = KQColor.selected
        multipleSelectionBackgroundView = picked

        nameLabel.font = KQFont.font(.body, weight: 600)
        nameLabel.adjustsFontForContentSizeCategory = true
        nameLabel.textColor = KQColor.ink
        nameLabel.lineBreakMode = .byTruncatingTail
        nameLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        timeLabel.font = KQFont.tabular(KQFont.font(.caption))
        timeLabel.adjustsFontForContentSizeCategory = true
        timeLabel.textColor = KQColor.ink3
        timeLabel.setContentHuggingPriority(.required, for: .horizontal)
        timeLabel.setContentCompressionResistancePriority(.required, for: .horizontal)

        deliveryIcon.contentMode = .scaleAspectFit
        deliveryIcon.setContentHuggingPriority(.required, for: .horizontal)
        deliveryIcon.setContentCompressionResistancePriority(.required, for: .horizontal)

        previewLabel.font = KQFont.font(.label, weight: 400)
        previewLabel.adjustsFontForContentSizeCategory = true
        previewLabel.textColor = KQColor.ink2
        previewLabel.lineBreakMode = .byTruncatingTail
        previewLabel.setContentHuggingPriority(.defaultLow, for: .horizontal)
        previewLabel.setContentCompressionResistancePriority(.defaultLow - 1, for: .horizontal)

        spacer.setContentHuggingPriority(.defaultLow - 2, for: .horizontal)

        top.addArrangedSubview(nameLabel)
        top.addArrangedSubview(reachView)

        let flags = UIStackView(arrangedSubviews: [transitFlag, unreadBadge])
        flags.axis = .horizontal
        flags.alignment = .center
        flags.spacing = KQSpace.x6
        flags.setContentHuggingPriority(.required, for: .horizontal)
        flags.setContentCompressionResistancePriority(.required, for: .horizontal)

        last.addArrangedSubview(deliveryIcon)
        last.addArrangedSubview(previewLabel)
        last.axis = .horizontal
        last.spacing = KQSpace.x4

        [timeLabel, last, spacer, flags].forEach { bottom.addArrangedSubview($0) }
        bottom.axis = .horizontal
        arrangeForTextSize()

        let main = UIStackView(arrangedSubviews: [top, bottom])
        main.axis = .vertical
        main.spacing = KQSpace.x2

        contentView.addSubview(tile)
        contentView.addSubview(main)

        tile.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.centerY.equalToSuperview()
            $0.size.equalTo(KQSize.tilePhone)
        }
        main.snp.makeConstraints {
            $0.leading.equalTo(tile.snp.trailing).offset(KQSpace.x12)
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
            $0.top.greaterThanOrEqualToSuperview().offset(KQSpace.x10)
            $0.bottom.lessThanOrEqualToSuperview().offset(-KQSpace.x10)
            $0.centerY.equalToSuperview()
        }
        deliveryIcon.snp.makeConstraints {
            $0.size.equalTo(KQSize.iconSmall)
        }
        contentView.constrainMinimumRowHeight()

        isAccessibilityElement = true
        accessibilityIdentifier = AirRowCell.identifier(for: .direct)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            arrangeForTextSize()
        }
    }

    /// Name and reach on one line, then time, last message and flags on the next; one above the other
    /// at the accessibility text sizes, where beside each other they left a few letters of the name and
    /// of the last message. The name wraps at its words there, the message keeps two lines (it is an
    /// excerpt either way).
    private func arrangeForTextSize() {
        let stacked = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        top.axis = stacked ? .vertical : .horizontal
        top.alignment = stacked ? .leading : .center
        top.spacing = stacked ? KQSpace.x2 : KQSpace.x8
        nameLabel.numberOfLines = stacked ? 0 : 1
        nameLabel.lineBreakMode = stacked ? .byWordWrapping : .byTruncatingTail
        reachView.wraps = stacked
        bottom.axis = stacked ? .vertical : .horizontal
        bottom.alignment = stacked ? .leading : .center
        bottom.spacing = stacked ? KQSpace.x2 : KQSpace.x8
        spacer.isHidden = stacked
        last.alignment = stacked ? .firstBaseline : .center
        previewLabel.numberOfLines = stacked ? 2 : 1
    }

    func configure(with entry: AirEntry, photo: UIImage?) {
        tile.configure(style: AirRowCell.tileStyle(for: entry), photo: photo)

        nameLabel.text = entry.title

        let reach = AirRowCell.reachText(entry.reach)
        reachView.configure(text: reach, isLive: entry.reach.isLive)

        timeLabel.text = entry.timeText
        timeLabel.isHidden = entry.timeText.isEmpty

        switch entry.lastDelivery {
            case .transit?:
                deliveryIcon.image = AirIcon.image("clock")
                deliveryIcon.tintColor = KQColor.transit
                deliveryIcon.isHidden = false
            case .sent?:
                deliveryIcon.image = AirIcon.image("check")
                deliveryIcon.tintColor = KQColor.ink3
                deliveryIcon.isHidden = false
            case .delivered?:
                deliveryIcon.image = AirIcon.image("check-double")
                deliveryIcon.tintColor = KQColor.ink
                deliveryIcon.isHidden = false
            case nil:
                deliveryIcon.image = nil
                deliveryIcon.isHidden = true
        }

        previewLabel.text = entry.preview
        previewLabel.textColor = entry.previewStyle == .hint ? KQColor.ink3 : KQColor.ink2

        transitFlag.configure(count: entry.transitCount)
        unreadBadge.apply(.unread)
        unreadBadge.setCount(entry.unreadCount, animated: false)

        accessibilityLabel = AirRowCell.accessibilityText(for: entry, reach: reach)
        accessibilityIdentifier = AirRowCell.identifier(for: entry.kind)
    }

    /// "air.row.direct", "air.row.group", "air.row.saved" or "air.row.contact": UI tests pick a row
    /// by what it is, not by its position or its words.
    static func identifier(for kind: AirEntry.Kind) -> String {
        switch kind {
            case .direct:
                return "air.row.direct"
            case .group:
                return "air.row.group"
            case .saved:
                return "air.row.saved"
            case .contact:
                return "air.row.contact"
        }
    }

    static func tileStyle(for entry: AirEntry) -> AirTileView.Style {
        switch entry.kind {
            case .saved:
                return .saved
            case .group:
                return .group(initials: AirInitials.from(entry.title), tone: entry.tone)
            case .direct, .contact:
                return entry.reach == .pending ? .pending : .person(initials: AirInitials.from(entry.title), tone: entry.tone)
        }
    }

    static func reachText(_ reach: AirReach) -> String {
        switch reach {
            case .direct:
                return String(localized: "air_reach_live")
            case .relay:
                return String(localized: "air_reach_relay")
            case .pending:
                return String(localized: "air_reach_pending")
            case .connecting:
                return String(localized: "air_reach_connecting")
            case .group(let online):
                return online > 0 ? String(localized: "air_reach_group_online", online) : String(localized: "air_reach_group_none")
            case .local:
                return ""
            case .away(let seconds):
                if seconds < 60 {
                    return String(localized: "air_reach_just_now")
                }
                if seconds < 3600 {
                    return String(localized: "air_reach_minutes", max(1, Int(seconds / 60)))
                }
                if seconds < 86400 {
                    return String(localized: "air_reach_hours", max(1, Int(seconds / 3600)))
                }
                if seconds < 86400 * 7 {
                    return String(localized: "air_reach_days", max(1, Int(seconds / 86400)))
                }
                return String(localized: "air_reach_long_ago")
        }
    }

    private static func accessibilityText(for entry: AirEntry, reach: String) -> String {
        var parts = [entry.title]
        if !reach.isEmpty {
            parts.append(reach)
        }
        switch entry.lastDelivery {
            case .transit?:
                parts.append(String(localized: "air_delivery_transit"))
            case .sent?:
                parts.append(String(localized: "air_delivery_sent"))
            case .delivered?:
                parts.append(String(localized: "air_delivery_delivered"))
            case nil:
                break
        }
        if !entry.preview.isEmpty {
            parts.append(entry.preview)
        }
        if entry.transitCount > 0 {
            parts.append(String(localized: "air_transit_format", entry.transitCount))
        }
        if entry.unreadCount > 0 {
            parts.append(String(localized: "air_unread_a11y", entry.unreadCount))
        }
        if !entry.timeText.isEmpty {
            parts.append(entry.timeText)
        }
        return parts.joined(separator: ", ")
    }
}

/// An incoming request, above the sections: who is asking (only the key is known until it is
/// accepted), what they wrote, and the two answers. A group invitation uses the same card: the group,
/// the contact who invites, "Join" and "Decline".
final class AirRequestCell: UITableViewCell {
    static let reuseIdentifier = "AirRequestCell"

    var onAccept: (() -> Void)?
    var onDecline: (() -> Void)?

    private let card = UIView()
    private let tagLabel = UILabel()
    private let timeLabel = UILabel()
    private let messageLabel = UILabel()
    private let keyLabel = UILabel()
    private let acceptButton = AirButton(style: .ink, title: String(localized: "contact_request_accept"))
    private let declineButton = AirButton(style: .normal, title: String(localized: "contact_request_decline"))
    private let actions = UIStackView()
    private let actionsTail = UIView()
    private let tagLine = UIStackView()

    override init(style: UITableViewCellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)

        backgroundColor = KQColor.surface
        contentView.backgroundColor = .clear
        selectionStyle = .none
        // The card is read and answered piece by piece: VoiceOver has to reach both buttons.
        isAccessibilityElement = false
        card.accessibilityIdentifier = "air.request"

        card.backgroundColor = KQColor.sunken
        card.layer.cornerRadius = KQRadius.card
        contentView.addSubview(card)

        tagLabel.text = String(localized: "air_request_tag")
        tagLabel.font = KQFont.font(.label, weight: 600)
        tagLabel.adjustsFontForContentSizeCategory = true
        tagLabel.textColor = KQColor.ink
        tagLabel.accessibilityTraits = UIAccessibilityTraitHeader
        // Where the line has no room for the whole tag beside the time (the accessibility text sizes, a
        // long translation), its words wrap whole instead of the tail being cut.
        tagLabel.numberOfLines = 0

        timeLabel.font = KQFont.tabular(KQFont.font(.caption))
        timeLabel.adjustsFontForContentSizeCategory = true
        timeLabel.textColor = KQColor.ink3
        timeLabel.setContentHuggingPriority(.required, for: .horizontal)
        timeLabel.setContentCompressionResistancePriority(.required, for: .horizontal)

        messageLabel.font = KQFont.font(.body)
        messageLabel.adjustsFontForContentSizeCategory = true
        messageLabel.textColor = KQColor.ink
        messageLabel.numberOfLines = 0

        keyLabel.numberOfLines = 0
        keyLabel.adjustsFontForContentSizeCategory = true

        acceptButton.accessibilityIdentifier = "air.request.accept"
        declineButton.accessibilityIdentifier = "air.request.decline"
        acceptButton.addTarget(self, action: #selector(acceptPressed), for: .touchUpInside)
        declineButton.addTarget(self, action: #selector(declinePressed), for: .touchUpInside)

        tagLine.addArrangedSubview(tagLabel)
        tagLine.addArrangedSubview(timeLabel)

        [acceptButton, declineButton, actionsTail].forEach { actions.addArrangedSubview($0) }
        actions.spacing = KQSpace.x8
        arrangeForTextSize()

        let stack = UIStackView(arrangedSubviews: [tagLine, messageLabel, keyLabel, actions])
        stack.axis = .vertical
        stack.spacing = KQSpace.x8
        card.addSubview(stack)

        card.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x8)
            $0.bottom.equalToSuperview().offset(-KQSpace.x4)
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
        }
        stack.snp.makeConstraints {
            $0.edges.equalToSuperview().inset(KQSpace.x16)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        onAccept = nil
        onDecline = nil
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            arrangeForTextSize()
        }
    }

    /// The two answers side by side, one above the other at the accessibility text sizes: beside each
    /// other a one-word answer (Decline in Russian) had no room left and broke inside the word. The time
    /// goes under the tag there for the same reason ("Group invitation" broke beside it at AX5).
    private func arrangeForTextSize() {
        let stacked = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        actions.axis = stacked ? .vertical : .horizontal
        actions.alignment = stacked ? .fill : .center
        actionsTail.isHidden = stacked
        tagLine.axis = stacked ? .vertical : .horizontal
        tagLine.alignment = stacked ? .leading : .firstBaseline
        tagLine.spacing = stacked ? KQSpace.x2 : KQSpace.x8
    }

    func configure(with request: AirRequest) {
        timeLabel.text = request.timeText
        if case let .group(name, inviter, inviterOnline) = request.kind {
            configureInvite(group: name, inviter: inviter, inviterOnline: inviterOnline)
            return
        }
        card.accessibilityIdentifier = "air.request"
        accessibilityIdentifier = card.accessibilityIdentifier
        tagLabel.text = String(localized: "air_request_tag")
        acceptButton.setTitle(String(localized: "contact_request_accept"))
        acceptButton.accessibilityIdentifier = "air.request.accept"
        declineButton.accessibilityIdentifier = "air.request.decline"
        messageLabel.font = KQFont.font(.body)
        messageLabel.text = request.message
        messageLabel.isHidden = request.message.isEmpty

        let key = AirRequestCell.groupedKey(request.publicKey)
        let text = String(localized: "air_request_key_format", key)
        let styled = NSMutableAttributedString(string: text, attributes: [
            NSAttributedStringKey.font: KQFont.font(.caption),
            NSAttributedStringKey.foregroundColor: KQColor.ink3,
        ])
        let range = (text as NSString).range(of: key)
        if range.location != NSNotFound {
            styled.addAttributes([
                NSAttributedStringKey.font: KQFont.font(.mono),
                NSAttributedStringKey.foregroundColor: KQColor.ink2,
            ], range: range)
        }
        keyLabel.attributedText = styled
        keyLabel.accessibilityLabel = String(localized: "air_request_key_format", key.replacingOccurrences(of: " ", with: ""))
    }

    private func configureInvite(group: String, inviter: String, inviterOnline: Bool) {
        card.accessibilityIdentifier = "air.invite"
        accessibilityIdentifier = card.accessibilityIdentifier
        tagLabel.text = String(localized: "air_invite_tag")
        acceptButton.setTitle(String(localized: "air_invite_join"))
        acceptButton.accessibilityIdentifier = "air.invite.join"
        declineButton.accessibilityIdentifier = "air.invite.decline"
        messageLabel.font = KQFont.font(.body, weight: 600)
        messageLabel.text = group
        messageLabel.isHidden = false

        var text = String(localized: "air_invite_from_format", inviter)
        if !inviterOnline {
            text += " " + String(localized: "air_invite_wait_format", inviter)
        }
        let styled = NSMutableAttributedString(string: text, attributes: [
            NSAttributedStringKey.font: KQFont.font(.caption),
            NSAttributedStringKey.foregroundColor: KQColor.ink3,
        ])
        let range = (text as NSString).range(of: inviter)
        if range.location != NSNotFound {
            styled.addAttribute(NSAttributedStringKey.foregroundColor, value: KQColor.ink2, range: range)
        }
        keyLabel.attributedText = styled
        keyLabel.accessibilityLabel = text
    }

    /// The first twelve characters of the key in groups of four: enough to compare with what the
    /// person sent, never mistaken for a whole ID.
    static func groupedKey(_ publicKey: String) -> String {
        let head = Array(publicKey.uppercased().prefix(12))
        return stride(from: 0, to: head.count, by: 4)
            .map { String(head[$0 ..< min($0 + 4, head.count)]) }
            .joined(separator: " ")
    }

    @objc private func acceptPressed() {
        onAccept?()
    }

    @objc private func declinePressed() {
        onDecline?()
    }
}

/// A message that matched the command line: the conversation, the time, and the line with the match
/// set in ink.
final class AirMessageCell: UITableViewCell {
    static let reuseIdentifier = "AirMessageCell"

    private let tile = AirTileView(side: KQSize.tilePhone)
    private let titleLabel = UILabel()
    private let timeLabel = UILabel()
    private let snippetLabel = UILabel()

    override init(style: UITableViewCellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)

        backgroundColor = KQColor.surface
        contentView.backgroundColor = .clear
        let highlight = UIView()
        highlight.backgroundColor = KQColor.sunken
        selectedBackgroundView = highlight

        titleLabel.font = KQFont.font(.body, weight: 600)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = KQColor.ink
        titleLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        timeLabel.font = KQFont.tabular(KQFont.font(.caption))
        timeLabel.adjustsFontForContentSizeCategory = true
        timeLabel.textColor = KQColor.ink3
        timeLabel.setContentHuggingPriority(.required, for: .horizontal)
        timeLabel.setContentCompressionResistancePriority(.required, for: .horizontal)

        snippetLabel.adjustsFontForContentSizeCategory = true
        snippetLabel.lineBreakMode = .byTruncatingTail

        let top = UIStackView(arrangedSubviews: [titleLabel, timeLabel])
        top.axis = .horizontal
        top.alignment = .firstBaseline
        top.spacing = KQSpace.x8

        let main = UIStackView(arrangedSubviews: [top, snippetLabel])
        main.axis = .vertical
        main.spacing = KQSpace.x2

        contentView.addSubview(tile)
        contentView.addSubview(main)
        tile.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.centerY.equalToSuperview()
            $0.size.equalTo(KQSize.tilePhone)
        }
        main.snp.makeConstraints {
            $0.leading.equalTo(tile.snp.trailing).offset(KQSpace.x12)
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
            $0.top.greaterThanOrEqualToSuperview().offset(KQSpace.x10)
            $0.bottom.lessThanOrEqualToSuperview().offset(-KQSpace.x10)
            $0.centerY.equalToSuperview()
        }
        contentView.constrainMinimumRowHeight()

        isAccessibilityElement = true
        accessibilityIdentifier = "air.message"
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(with hit: AirMessageHit, query: String, tile style: AirTileView.Style, photo: UIImage?) {
        tile.configure(style: style, photo: photo)
        titleLabel.text = hit.title
        timeLabel.text = hit.timeText
        snippetLabel.attributedText = AirMessageCell.highlighted(hit.snippet, query: query)
        accessibilityLabel = [hit.title, hit.snippet, hit.timeText].filter { !$0.isEmpty }.joined(separator: ", ")
    }

    static func highlighted(_ snippet: String, query: String) -> NSAttributedString {
        let result = NSMutableAttributedString(string: snippet, attributes: [
            NSAttributedStringKey.font: KQFont.font(.label, weight: 400),
            NSAttributedStringKey.foregroundColor: KQColor.ink2,
        ])
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !needle.isEmpty else {
            return result
        }

        let text = snippet as NSString
        var searchRange = NSRange(location: 0, length: text.length)
        while searchRange.length > 0 {
            let found = text.range(of: needle, options: [.caseInsensitive, .diacriticInsensitive], range: searchRange)
            guard found.location != NSNotFound, found.length > 0 else {
                break
            }
            result.addAttributes([
                NSAttributedStringKey.font: KQFont.font(.label, weight: 600),
                NSAttributedStringKey.foregroundColor: KQColor.ink,
            ], range: found)
            let next = found.location + found.length
            searchRange = NSRange(location: next, length: text.length - next)
        }
        return result
    }
}

/// A section heading with its count: "Online now 3". The count rolls when it changes.
final class AirSectionHeaderView: UITableViewHeaderFooterView {
    static let reuseIdentifier = "AirSectionHeaderView"

    private let titleLabel = UILabel()
    private let countLabel = AirRollingLabel()
    private(set) var kind: AirSectionKind?

    override init(reuseIdentifier: String?) {
        super.init(reuseIdentifier: reuseIdentifier)

        let ground = UIView()
        ground.backgroundColor = KQColor.surface
        backgroundView = ground

        titleLabel.font = KQFont.font(.label)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = KQColor.ink3

        countLabel.font = KQFont.tabular(KQFont.font(.caption, weight: 500))
        countLabel.textColor = KQColor.ink3

        contentView.addSubview(titleLabel)
        contentView.addSubview(countLabel)
        titleLabel.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.top.equalToSuperview().offset(KQSpace.x12)
            $0.bottom.equalToSuperview().offset(-KQSpace.x6)
            $0.trailing.lessThanOrEqualTo(countLabel.snp.leading).offset(-KQSpace.x8)
        }
        countLabel.snp.makeConstraints {
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
            $0.centerY.equalTo(titleLabel)
        }

        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitHeader
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(kind: AirSectionKind, title: String, count: Int, animated: Bool) {
        let previous = Int(countLabel.text) ?? 0
        let sameSection = self.kind == kind
        self.kind = kind
        titleLabel.text = title
        countLabel.setText("\(count)", animated: animated && sameSection, upward: count > previous)
        accessibilityLabel = "\(title), \(count)"
        accessibilityIdentifier = "air.section." + AirSectionHeaderView.name(of: kind)
    }

    static func name(of kind: AirSectionKind) -> String {
        switch kind {
            case .requests:
                return "requests"
            case .live:
                return "live"
            case .away:
                return "away"
            case .saved:
                return "saved"
            case .messages:
                return "messages"
        }
    }
}

private extension UIView {
    /// At least a row high, set on the anchor rather than through SnapKit: SnapKit would turn off the
    /// content view's autoresizing mask, and the row would then grow as wide as its longest name.
    func constrainMinimumRowHeight() {
        let minimumHeight = heightAnchor.constraint(greaterThanOrEqualToConstant: KQSize.rowPhone)
        minimumHeight.priority = .defaultHigh
        minimumHeight.isActive = true
    }
}
