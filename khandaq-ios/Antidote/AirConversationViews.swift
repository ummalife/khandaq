// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// Pieces of the conversation with one person, drawn as design/system/index.html draws them: the link
// mark, the day strip and its axis, the status mark of a message, the presence band under the header,
// the pinned strip, the "On its way" tray, the messages the network refused, the hint under the field,
// the path picture and the contact dossier. Every colour, size, radius, stroke and duration comes from
// DesignTokens.swift; nothing here moves without end, and with Reduce Motion every move becomes a fade
// of at most 120 ms (KQMotion.duration).

private extension UIView {
    var airIsRightToLeft: Bool {
        return effectiveUserInterfaceLayoutDirection == .rightToLeft
    }
}

private func airLabel(_ role: KQFontRole, weight: Int? = nil, color: UIColor, lines: Int = 1) -> UILabel {
    let label = UILabel()
    label.font = weight.map { KQFont.font(role, weight: $0) } ?? KQFont.font(role)
    label.adjustsFontForContentSizeCategory = true
    label.textColor = color
    label.numberOfLines = lines
    return label
}

// MARK: - Link mark

/// The 30 by 10 picture of the link beside the presence line: a solid green line straight to the
/// person, a green line through a small square for a relay, a dashed grey line to an empty circle when
/// there is no link. Your end is the leading one.
final class AirLinkMarkView: UIView {
    private let nearDot = CAShapeLayer()
    private let line = CAShapeLayer()
    private let relayBox = CAShapeLayer()
    private let farDot = CAShapeLayer()
    private(set) var state: AirLinkState = .none

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = false
        isUserInteractionEnabled = false
        for shape in [line, relayBox, nearDot, farDot] {
            shape.lineWidth = KQStroke.mark
            shape.lineCap = kCALineCapRound
            layer.addSublayer(shape)
        }
        snp.makeConstraints {
            $0.width.equalTo(KQSize.linkMark)
            $0.height.equalTo(KQSize.linkMark / 3)
        }
        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(_ state: AirLinkState) {
        self.state = state
        apply()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let scale = bounds.width / 30
        let midY = bounds.midY
        func x(_ value: CGFloat) -> CGFloat {
            let scaled = value * scale
            return airIsRightToLeft ? bounds.width - scaled : scaled
        }
        let radius = 3 * scale
        nearDot.path = UIBezierPath(arcCenter: CGPoint(x: x(4), y: midY), radius: radius, startAngle: 0, endAngle: 2 * .pi, clockwise: true).cgPath
        farDot.path = UIBezierPath(arcCenter: CGPoint(x: x(26), y: midY), radius: radius, startAngle: 0, endAngle: 2 * .pi, clockwise: true).cgPath

        let path = UIBezierPath()
        switch state {
            case .direct:
                path.move(to: CGPoint(x: x(7), y: midY))
                path.addLine(to: CGPoint(x: x(23), y: midY))
                relayBox.path = nil
            case .relay:
                path.move(to: CGPoint(x: x(7), y: midY))
                path.addLine(to: CGPoint(x: x(12.5), y: midY))
                path.move(to: CGPoint(x: x(17.5), y: midY))
                path.addLine(to: CGPoint(x: x(23), y: midY))
                let side = 5 * scale
                let boxX = min(x(12.5), x(17.5))
                relayBox.path = UIBezierPath(roundedRect: CGRect(x: boxX, y: midY - side / 2, width: side, height: side), cornerRadius: scale).cgPath
            case .none:
                path.move(to: CGPoint(x: x(10), y: midY))
                path.addLine(to: CGPoint(x: x(20), y: midY))
                relayBox.path = nil
        }
        line.path = path.cgPath
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        apply()
    }

    private func apply() {
        let live = state != .none
        let color = airCGColor(live ? KQColor.live : KQColor.ink3)
        nearDot.fillColor = color
        nearDot.strokeColor = UIColor.clear.cgColor
        line.strokeColor = color
        line.fillColor = UIColor.clear.cgColor
        line.lineDashPattern = live ? nil : [NSNumber(value: 2), NSNumber(value: 3)]
        relayBox.strokeColor = color
        relayBox.fillColor = UIColor.clear.cgColor
        farDot.strokeColor = color
        farDot.fillColor = live ? color : UIColor.clear.cgColor
    }
}

// MARK: - Day strip

/// 24 cells, one per clock hour: green for an hour the person was online, the current hour outlined
/// (and solid green while they are online). The compact strip sits under the header, the tall one in
/// the dossier with an axis under it.
final class AirDayStripView: UIView {
    enum Style {
        case compact
        case tall
    }

    private let style: Style
    private var cells: [CALayer] = []
    private let nowRing = CAShapeLayer()
    private var strip = AirDayStrip(online: [Bool](repeating: false, count: 24), nowHour: 0)
    private var nowOnline = false

    init(style: Style) {
        self.style = style
        super.init(frame: .zero)
        isAccessibilityElement = false
        isUserInteractionEnabled = false
        for _ in 0..<24 {
            let cell = CALayer()
            cell.cornerRadius = KQRadius.tick
            layer.addSublayer(cell)
            cells.append(cell)
        }
        nowRing.fillColor = UIColor.clear.cgColor
        nowRing.lineWidth = KQStroke.strong
        layer.addSublayer(nowRing)

        snp.makeConstraints {
            $0.height.equalTo(style == .compact ? KQSize.hourCell : KQSize.hourCellTall)
            if style == .compact {
                $0.width.equalTo(KQSize.hours)
            }
        }
        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(strip: AirDayStrip, nowOnline: Bool) {
        self.strip = strip
        self.nowOnline = nowOnline
        apply()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let gap = KQSpace.x2
        let width = max(1, (bounds.width - gap * 23) / 24)
        for hour in 0..<24 {
            let slot = airIsRightToLeft ? 23 - hour : hour
            cells[hour].frame = CGRect(x: CGFloat(slot) * (width + gap), y: 0, width: width, height: bounds.height)
        }
        let nowFrame = cells[min(max(strip.nowHour, 0), 23)].frame
        let ringInset = -(1 + KQStroke.strong / 2)
        nowRing.path = UIBezierPath(roundedRect: nowFrame.insetBy(dx: ringInset, dy: ringInset), cornerRadius: KQRadius.tick + 1).cgPath
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        apply()
    }

    private func apply() {
        for hour in 0..<24 {
            let isNow = hour == strip.nowHour
            let color: UIColor
            if isNow && nowOnline {
                color = KQColor.live
            }
            else if hour < strip.online.count && strip.online[hour] {
                color = KQColor.liveCell
            }
            else {
                color = KQColor.sunken
            }
            cells[hour].backgroundColor = airCGColor(color)
        }
        nowRing.strokeColor = airCGColor(KQColor.ink)
    }
}

/// 0, 6, 12, 18 and 24 under the tall strip, in tabular figures.
final class AirDayAxisView: UIView {
    private var labels: [UILabel] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = false
        for value in stride(from: 0, through: 24, by: 6) {
            let label = airLabel(.caption, color: KQColor.ink3)
            label.font = KQFont.tabular(KQFont.font(.caption))
            label.text = "\(value)"
            addSubview(label)
            labels.append(label)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var intrinsicContentSize: CGSize {
        return CGSize(width: UIViewNoIntrinsicMetric, height: ceil(KQFont.lineHeight(.caption)))
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        for (index, label) in labels.enumerated() {
            let size = label.intrinsicContentSize
            let fraction = CGFloat(index) / CGFloat(labels.count - 1)
            let position = airIsRightToLeft ? 1 - fraction : fraction
            var x = position * bounds.width - size.width / 2
            x = min(max(0, x), bounds.width - size.width)
            label.frame = CGRect(x: x, y: 0, width: size.width, height: bounds.height)
        }
    }
}

// MARK: - Status mark

/// The mark after one of our own messages: an amber clock in the tray, a faint clock while it leaves,
/// one check when sent, two when delivered, a red alert when refused. A change cross-fades.
final class AirStatusMarkView: UIView {
    private let imageView = UIImageView()
    private(set) var status: AirEntryStatus = .none

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = false
        isUserInteractionEnabled = false
        imageView.contentMode = .scaleAspectFit
        addSubview(imageView)
        imageView.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.size.equalTo(KQSize.iconSmall)
        }
        snp.makeConstraints {
            $0.size.equalTo(KQSize.mark)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func set(_ status: AirEntryStatus, animated: Bool) {
        let changed = status != self.status
        self.status = status
        let update = {
            self.imageView.image = status.iconName.flatMap { AirIcon.image($0) }
            self.imageView.alpha = status == .sending ? 0.45 : 1
            self.imageView.tintColor = AirStatusMarkView.color(for: status)
        }
        if animated && changed && window != nil {
            UIView.transition(with: imageView,
                              duration: KQMotion.duration(KQMotion.state),
                              options: [.transitionCrossDissolve, .beginFromCurrentState, .allowUserInteraction],
                              animations: update,
                              completion: nil)
        }
        else {
            update()
        }
        isHidden = status == .none
    }

    static func color(for status: AirEntryStatus) -> UIColor {
        switch status {
            case .transit:
                return KQColor.transit
            case .sending, .none:
                return KQColor.ink3
            case .sent, .delivered:
                return KQColor.ink
            case .failed:
                return KQColor.danger
        }
    }
}

// MARK: - Presence band

/// Under the header: the link mark with "online since 9:40, direct" or "offline for 25 minutes", and
/// the day strip with when the person is usually online. One tap target: it opens the dossier.
final class AirPresenceBandView: UIControl {
    let linkMark = AirLinkMarkView()
    let presenceLabel = airLabel(.caption, color: KQColor.ink2)
    let strip = AirDayStripView(style: .compact)
    let stripHintLabel = airLabel(.caption, color: KQColor.ink3)
    private let rule = UIView()
    private let stripRow = UIStackView()
    private var stripSpoken = ""

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.surface
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityIdentifier = "air.chat.band"

        // Two lines rather than a cut: the hours of the strip are the point of the line, and Arabic
        // ("4:00 to 6:00") or a large text size do not fit one.
        presenceLabel.font = KQFont.tabular(KQFont.font(.caption))
        presenceLabel.numberOfLines = 2
        presenceLabel.lineBreakMode = .byTruncatingTail
        stripHintLabel.font = KQFont.tabular(KQFont.font(.caption))
        stripHintLabel.numberOfLines = 2
        stripHintLabel.lineBreakMode = .byTruncatingTail
        rule.backgroundColor = KQColor.line

        let presenceRow = UIStackView(arrangedSubviews: [linkMark, presenceLabel])
        presenceRow.axis = .horizontal
        presenceRow.alignment = .center
        presenceRow.spacing = KQSpace.x8
        stripRow.addArrangedSubview(strip)
        stripRow.addArrangedSubview(stripHintLabel)
        stripRow.axis = .horizontal
        stripRow.alignment = .center
        stripRow.spacing = KQSpace.x10
        arrangeForTextSize()
        let column = UIStackView(arrangedSubviews: [presenceRow, stripRow])
        column.axis = .vertical
        column.alignment = .leading
        column.spacing = KQSpace.x6
        column.isUserInteractionEnabled = false
        addSubview(column)
        addSubview(rule)

        column.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x6)
            $0.bottom.equalToSuperview().offset(-KQSpace.x10)
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.trailing.lessThanOrEqualToSuperview().offset(-KQSpace.x16)
        }
        rule.snp.makeConstraints {
            $0.leading.trailing.bottom.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }
        presenceLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        stripHintLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        // Above the tray and the field: with a large text size and the keyboard up the log and the tray
        // give way first, so the presence line is never cut in half.
        let keepsHeight = UILayoutPriority(rawValue: 900)
        presenceLabel.setContentCompressionResistancePriority(keepsHeight, for: .vertical)
        stripHintLabel.setContentCompressionResistancePriority(keepsHeight, for: .vertical)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            backgroundColor = isHighlighted ? KQColor.selected : KQColor.surface
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            arrangeForTextSize()
        }
    }

    /// At the accessibility text sizes the band keeps the presence line, whole, and leaves the strip and
    /// its hours to the dossier's Last day, a tap away; VoiceOver still reads them here. Beside the strip
    /// two lines cut "for the day: 8 to 10, 21 to 23". Where the log is short of room the whole band
    /// folds away (AirLogRoom).
    private func arrangeForTextSize() {
        let folded = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        presenceLabel.numberOfLines = folded ? 0 : 2
        stripRow.isHidden = folded
    }

    func configure(link: AirLinkState, presence: String, strip dayStrip: AirDayStrip, nowOnline: Bool, stripHint: String, stripSpoken: String) {
        linkMark.configure(link)
        presenceLabel.text = presence
        strip.configure(strip: dayStrip, nowOnline: nowOnline)
        stripHintLabel.text = stripHint
        self.stripSpoken = stripSpoken
        accessibilityLabel = [presence, link.spokenText, String(localized: "air_day_title") + ": " + stripSpoken]
            .filter { !$0.isEmpty }
            .joined(separator: ". ")
        accessibilityHint = String(localized: "air_chat_dossier_hint")
    }
}

/// At the accessibility text sizes the log of a conversation keeps its share of the screen. At AX5 the
/// band and a refused message had left it 45 points, and the open tray none (stage 5).
enum AirLogRoom {
    /// The share of the screen's height the log keeps before the presence band gives way.
    static let share: CGFloat = 0.25

    /// Whether the presence band folds away: always while the keyboard is up, and where the log would
    /// have less than its share with the band. `log` is the log's height now; `band` is the band's height
    /// on the screen, or the height it would take back while it is folded. A folded band comes back only
    /// where the log keeps its share with it, so it does not come and go from one layout to the next.
    static func bandFolds(accessible: Bool, keyboardUp: Bool, log: CGFloat, band: CGFloat, bandShown: Bool, screen: CGFloat) -> Bool {
        guard accessible else {
            return false
        }
        if keyboardUp {
            return true
        }
        let logWithBand = bandShown ? log : log - band
        return logWithBand < screen * share
    }
}

// MARK: - Pinned strip

/// The pinned message as a quiet strip under the header: a pin, one line of text, a close button.
final class AirPinnedStripView: UIControl {
    let textLabel = airLabel(.label, weight: 400, color: KQColor.ink)
    let closeButton = AirIconButton(icon: "x", label: String(localized: "air_pin_remove"))
    private let titleLabel = airLabel(.caption, weight: 500, color: KQColor.ink3)
    private let icon = UIImageView(image: AirIcon.image("pin"))
    private let rule = UIView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.surface
        isAccessibilityElement = false
        accessibilityIdentifier = "air.chat.pinned"

        icon.tintColor = KQColor.ink3
        icon.contentMode = .scaleAspectFit
        titleLabel.text = String(localized: "chat_pinned_banner_title")
        textLabel.lineBreakMode = .byTruncatingTail
        rule.backgroundColor = KQColor.line

        let texts = UIStackView(arrangedSubviews: [titleLabel, textLabel])
        texts.axis = .vertical
        texts.spacing = 0
        texts.isUserInteractionEnabled = false
        addSubview(icon)
        addSubview(texts)
        addSubview(closeButton)
        addSubview(rule)

        icon.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.centerY.equalTo(texts)
            $0.size.equalTo(KQSize.iconSmall)
        }
        texts.snp.makeConstraints {
            $0.leading.equalTo(icon.snp.trailing).offset(KQSpace.x10)
            $0.top.equalToSuperview().offset(KQSpace.x6)
            $0.bottom.equalToSuperview().offset(-KQSpace.x6)
            $0.trailing.lessThanOrEqualTo(closeButton.snp.leading).offset(-KQSpace.x4)
        }
        closeButton.snp.makeConstraints {
            $0.trailing.equalToSuperview().offset(-KQSpace.x4)
            $0.centerY.equalToSuperview()
        }
        rule.snp.makeConstraints {
            $0.leading.trailing.bottom.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            backgroundColor = isHighlighted ? KQColor.selected : KQColor.surface
        }
    }

    func configure(text: String) {
        textLabel.text = text
    }

    /// The strip reads as one button, the close button stays a second element.
    override var accessibilityElements: [Any]? {
        get {
            let strip = UIAccessibilityElement(accessibilityContainer: self)
            strip.accessibilityLabel = String(localized: "chat_pinned_banner_title") + ": " + (textLabel.text ?? "")
            strip.accessibilityTraits = UIAccessibilityTraitButton
            strip.accessibilityHint = String(localized: "air_pin_jump_hint")
            strip.accessibilityFrameInContainerSpace = bounds
            return [strip, closeButton]
        }
        set {}
    }

    override func accessibilityActivate() -> Bool {
        sendActions(for: .touchUpInside)
        return true
    }
}

// MARK: - Entry rows outside the log

/// One message laid out like a transcript entry: time column, text, status mark. At the accessibility
/// text sizes the time stands above the text, as in the log (AirEntryMetrics.stacksTime).
final class AirEntryRowView: UIControl {
    let timeLabel = airLabel(.caption, color: KQColor.ink3)
    let textLabel = airLabel(.body, color: KQColor.ink, lines: 2)
    let mark = AirStatusMarkView()
    private var timeWidth: Constraint?
    private var textLeadingToTime: Constraint?
    private var textLeadingToEdge: Constraint?
    private var textTopToEdge: Constraint?
    private var textTopToTime: Constraint?

    override init(frame: CGRect) {
        super.init(frame: frame)
        timeLabel.font = KQFont.tabular(KQFont.font(.caption))
        textLabel.lineBreakMode = .byTruncatingTail
        for view in [timeLabel, textLabel, mark] as [UIView] {
            view.isUserInteractionEnabled = false
            addSubview(view)
        }
        timeLabel.snp.makeConstraints {
            $0.leading.equalToSuperview()
            $0.top.equalToSuperview().offset(KQSpace.x4 + KQSpace.x2)
            // The column of the transcript, so the text lines up with the log at every text size.
            timeWidth = $0.width.equalTo(AirEntryMetrics.timeColumnWidth()).constraint
        }
        textLabel.snp.makeConstraints {
            textLeadingToTime = $0.leading.equalTo(timeLabel.snp.trailing).offset(KQSpace.x10).constraint
            textLeadingToEdge = $0.leading.equalToSuperview().constraint
            textTopToEdge = $0.top.equalToSuperview().offset(KQSpace.x4).constraint
            textTopToTime = $0.top.equalTo(timeLabel.snp.bottom).offset(KQSpace.x2).constraint
            $0.bottom.equalToSuperview().offset(-KQSpace.x4)
            $0.trailing.equalTo(mark.snp.leading).offset(-KQSpace.x10)
        }
        mark.snp.makeConstraints {
            $0.trailing.equalToSuperview()
            $0.top.equalToSuperview().offset(KQSpace.x4 + KQSpace.x2)
        }
        timeLabel.setContentHuggingPriority(.required, for: .horizontal)
        timeLabel.adjustsFontSizeToFitWidth = true
        timeLabel.minimumScaleFactor = 0.7
        applyTimePlacement()
    }

    /// The time beside the text, or above it at the accessibility text sizes.
    private func applyTimePlacement() {
        if AirEntryMetrics.stacksTime() {
            textLeadingToTime?.deactivate()
            textTopToEdge?.deactivate()
            textLeadingToEdge?.activate()
            textTopToTime?.activate()
        }
        else {
            textLeadingToEdge?.deactivate()
            textTopToTime?.deactivate()
            textLeadingToTime?.activate()
            textTopToEdge?.activate()
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            alpha = isHighlighted ? 0.6 : 1
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory else {
            return
        }
        // A tabular font has a fixed size, so it is made again for the new text size.
        timeLabel.font = KQFont.tabular(KQFont.font(.caption))
        timeWidth?.update(offset: AirEntryMetrics.timeColumnWidth())
        applyTimePlacement()
    }
}

/// An item of the tray or of the refused list.
struct AirQueueItem: Equatable {
    let id: String
    let text: String
    let time: String
    let status: AirEntryStatus
}

// MARK: - The "On its way" tray

/// Messages waiting for the person, between the log and the field, on the pale amber of transit. On a
/// phone it rests as one line ("3 on their way") and opens with a tap; each message can then be edited
/// or cancelled until it leaves. When the person comes online the messages leave one by one, 90 ms
/// apart, and the tray folds away.
final class AirTrayView: UIView {
    var onToggle: ((Bool) -> Void)?
    var onItemTap: ((String, UIView) -> Void)?
    var onItemAction: ((String, AirTrayAction) -> Void)?
    /// Called inside animations so the screen around the tray moves with it.
    var layoutHost: (() -> Void)?

    private(set) var isExpanded = false
    private(set) var items: [AirQueueItem] = []
    /// While the keyboard is up the hint under the field already says that the messages wait, so the
    /// open tray drops its explanation and leaves the room to the log; VoiceOver still reads it.
    var hidesDetail = false {
        didSet {
            if hidesDetail != oldValue {
                applyExpanded()
            }
        }
    }
    /// At the accessibility text sizes the whole tray folds away while the keyboard is up: the hint under
    /// the field says that the messages wait, and its two lines of title left the log no room at AX5.
    var foldsAway = false {
        didSet {
            if foldsAway != oldValue {
                isHidden = items.isEmpty || foldsAway
            }
        }
    }

    private let box = UIView()
    private let header = UIControl()
    private let clock = UIImageView(image: AirIcon.image("clock"))
    /// Wraps at its words: at the large text sizes "In transit: 5 messages" lost its end on one line.
    private let titleLabel = airLabel(.label, color: KQColor.transit, lines: 0)
    private let chevron = UIImageView(image: AirIcon.image("chevron-down"))
    private let subLabel = airLabel(.caption, color: KQColor.ink2, lines: 0)
    private let itemsScroll = UIScrollView()
    private let itemsStack = UIStackView()
    private let column = UIStackView()
    private var rows: [String: AirEntryRowView] = [:]
    private var collapsedTitle = ""
    private var expandedTitle = ""

    override init(frame: CGRect) {
        super.init(frame: frame)
        accessibilityIdentifier = "air.chat.tray"

        box.backgroundColor = KQColor.transitTray
        box.layer.cornerRadius = KQRadius.card
        box.clipsToBounds = true
        addSubview(box)

        clock.tintColor = KQColor.transit
        clock.contentMode = .scaleAspectFit
        chevron.tintColor = KQColor.ink3
        chevron.contentMode = .scaleAspectFit
        titleLabel.font = KQFont.tabular(KQFont.font(.label))

        let headRow = UIStackView(arrangedSubviews: [clock, titleLabel, UIView(), chevron])
        headRow.axis = .horizontal
        headRow.alignment = .center
        headRow.spacing = KQSpace.x8
        headRow.isUserInteractionEnabled = false
        header.addSubview(headRow)
        header.addTarget(self, action: #selector(headerTapped), for: .touchUpInside)
        header.isAccessibilityElement = true
        header.accessibilityTraits = UIAccessibilityTraitButton
        header.accessibilityIdentifier = "air.chat.tray.header"
        headRow.snp.makeConstraints {
            $0.leading.trailing.equalToSuperview()
            $0.centerY.equalToSuperview()
            $0.top.greaterThanOrEqualToSuperview()
            $0.bottom.lessThanOrEqualToSuperview()
            // The header hugs its row above the minimum height, so the log keeps the rest of the screen.
            $0.top.equalToSuperview().priority(.low)
        }
        header.snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.targetIos - 2 * KQSpace.x6)
        }
        clock.snp.makeConstraints {
            $0.size.equalTo(KQSize.iconSmall)
        }
        chevron.snp.makeConstraints {
            $0.size.equalTo(KQSize.iconSmall)
        }

        itemsStack.axis = .vertical
        itemsStack.spacing = 0
        itemsScroll.addSubview(itemsStack)
        itemsScroll.showsVerticalScrollIndicator = true
        itemsScroll.alwaysBounceVertical = false
        itemsStack.snp.makeConstraints {
            $0.edges.equalToSuperview()
            $0.width.equalToSuperview()
        }
        itemsScroll.snp.makeConstraints {
            $0.height.equalTo(itemsStack).priority(.low)
            $0.height.lessThanOrEqualTo(UIScreen.main.bounds.height * 0.28)
        }

        column.axis = .vertical
        column.spacing = KQSpace.x2
        column.addArrangedSubview(header)
        column.addArrangedSubview(subLabel)
        column.addArrangedSubview(itemsScroll)
        column.setCustomSpacing(KQSpace.x4, after: subLabel)
        box.addSubview(column)

        box.snp.makeConstraints {
            $0.top.equalToSuperview()
            $0.leading.equalToSuperview().offset(KQSpace.x12)
            $0.trailing.equalToSuperview().offset(-KQSpace.x12)
            $0.bottom.equalToSuperview().offset(-KQSpace.x10)
        }
        column.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x8)
            $0.leading.equalToSuperview().offset(KQSpace.x12)
            $0.trailing.equalToSuperview().offset(-KQSpace.x12)
            $0.bottom.equalToSuperview().offset(-KQSpace.x6)
        }

        arrangeForTextSize()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        box.backgroundColor = KQColor.transitTray
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            arrangeForTextSize()
        }
    }

    /// At the accessibility text sizes the explanation scrolls with the messages: open, it took six lines
    /// at AX5, the screen had no room left for the messages and the title lost its end. On a full screen
    /// the scroll gives way first; the title and the words of the field stay whole.
    private func arrangeForTextSize() {
        let scrolls = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        if scrolls, subLabel.superview !== itemsStack {
            itemsStack.insertArrangedSubview(subLabel, at: 0)
            itemsStack.setCustomSpacing(KQSpace.x4, after: subLabel)
        }
        else if !scrolls, subLabel.superview !== column {
            column.insertArrangedSubview(subLabel, at: 1)
            column.setCustomSpacing(KQSpace.x4, after: subLabel)
        }
        applyExpanded()
    }

    /// `collapsed` is the one line at rest, `expanded` the heading when open, `detail` the line under it.
    func configure(items: [AirQueueItem], collapsed: String, expanded: String, detail: String) {
        self.items = items
        collapsedTitle = collapsed
        expandedTitle = expanded
        subLabel.text = detail

        var kept: [String: AirEntryRowView] = [:]
        itemsStack.arrangedSubviews.filter { $0 !== subLabel }.forEach { $0.removeFromSuperview() }
        for item in items {
            let row = rows[item.id] ?? makeRow(id: item.id)
            row.timeLabel.text = item.time
            row.textLabel.text = item.text
            row.mark.set(item.status, animated: rows[item.id] != nil)
            row.alpha = 1
            row.transform = .identity
            row.accessibilityLabel = item.text
            row.accessibilityValue = [item.time, item.status.spokenText].compactMap { $0 }.joined(separator: ", ")
            row.accessibilityHint = String(localized: "air_tray_item_hint")
            row.accessibilityCustomActions = [
                UIAccessibilityCustomAction(name: String(localized: "chat_edit_action"), target: self, selector: #selector(editAction(_:))),
                UIAccessibilityCustomAction(name: String(localized: "air_tray_cancel"), target: self, selector: #selector(cancelAction(_:))),
            ]
            row.accessibilityIdentifier = "air.chat.tray.item"
            kept[item.id] = row
            itemsStack.addArrangedSubview(row)
        }
        rows = kept
        isHidden = items.isEmpty || foldsAway
        applyExpanded()
    }

    func setExpanded(_ expanded: Bool, animated: Bool) {
        guard expanded != isExpanded else {
            return
        }
        isExpanded = expanded
        let changes = {
            self.applyExpanded()
            self.layoutHost?()
        }
        if animated {
            UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), delay: 0, options: [.beginFromCurrentState, .curveEaseOut], animations: changes, completion: nil)
        }
        else {
            changes()
        }
        UIAccessibilityPostNotification(UIAccessibilityLayoutChangedNotification, header)
    }

    /// The messages that just left: each fades up and away, 90 ms after the one before; then the tray
    /// folds. With Reduce Motion they fade together.
    func depart(ids: [String], completion: @escaping () -> Void) {
        let leaving = ids.compactMap { rows[$0] }
        guard !isHidden, !leaving.isEmpty else {
            completion()
            return
        }
        let reduce = UIAccessibilityIsReduceMotionEnabled()
        let step = reduce ? 0 : KQMotion.stagger
        let duration = KQMotion.duration(KQMotion.state)
        for (index, row) in leaving.enumerated() {
            UIView.animate(withDuration: duration, delay: step * Double(index), options: [.curveEaseOut], animations: {
                row.alpha = 0
                if !reduce {
                    row.transform = CGAffineTransform(translationX: 0, y: -KQSpace.x8)
                }
            }, completion: nil)
        }
        let total = step * Double(max(0, leaving.count - 1)) + duration
        DispatchQueue.main.asyncAfter(deadline: .now() + total) {
            completion()
        }
    }

    /// Folds the tray away: its height closes over the layout duration, or it fades with Reduce Motion.
    func fold(completion: (() -> Void)? = nil) {
        guard !isHidden else {
            completion?()
            return
        }
        UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), delay: 0, options: [.curveEaseOut], animations: {
            self.alpha = 0
            self.isHidden = true
            self.layoutHost?()
        }, completion: { _ in
            self.alpha = 1
            completion?()
        })
    }

    private func applyExpanded() {
        let count = items.count
        titleLabel.text = isExpanded ? expandedTitle : collapsedTitle
        clock.isHidden = isExpanded
        subLabel.isHidden = !isExpanded || hidesDetail || (subLabel.text ?? "").isEmpty
        let detailScrolls = subLabel.superview === itemsStack && !subLabel.isHidden
        itemsScroll.isHidden = !isExpanded || (count == 0 && !detailScrolls)
        chevron.transform = isExpanded ? CGAffineTransform(rotationAngle: .pi) : .identity
        header.accessibilityLabel = titleLabel.text
        header.accessibilityValue = isExpanded ? subLabel.text : nil
        header.accessibilityHint = String(localized: isExpanded ? "air_tray_collapse_hint" : "air_tray_expand_hint")
    }

    private func makeRow(id: String) -> AirEntryRowView {
        let row = AirEntryRowView()
        row.accessibilityIdentifier = id
        row.isAccessibilityElement = true
        row.accessibilityTraits = UIAccessibilityTraitButton
        row.addTarget(self, action: #selector(rowTapped(_:)), for: .touchUpInside)
        row.snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.targetIos - KQSpace.x12)
        }
        return row
    }

    private func id(of row: UIView) -> String? {
        return rows.first(where: { $0.value === row })?.key
    }

    @objc private func headerTapped() {
        setExpanded(!isExpanded, animated: true)
        onToggle?(isExpanded)
    }

    @objc private func rowTapped(_ sender: AirEntryRowView) {
        guard let id = id(of: sender) else {
            return
        }
        onItemTap?(id, sender)
    }

    @objc private func editAction(_ action: UIAccessibilityCustomAction) -> Bool {
        return performAction(.edit)
    }

    @objc private func cancelAction(_ action: UIAccessibilityCustomAction) -> Bool {
        return performAction(.cancel)
    }

    private func performAction(_ action: AirTrayAction) -> Bool {
        guard let focused = UIAccessibilityFocusedElement(UIAccessibilityNotificationVoiceOverIdentifier) as? UIView,
              let id = id(of: focused) else {
            return false
        }
        onItemAction?(id, action)
        return true
    }
}

enum AirTrayAction {
    case edit
    case cancel
}

// MARK: - Refused messages

/// Messages the network refused, at the foot of the log: each with its red mark and "Not sent. Retry"
/// under the text. They take their own height; where the screen gives them less (at the accessibility
/// text sizes the log keeps half the room, ChatPrivateController), they scroll.
final class AirFailedListView: UIView {
    var onRetry: ((String) -> Void)?
    var onItemTap: ((String, UIView) -> Void)?

    private let scroll = UIScrollView()
    private let stack = UIStackView()
    private var rowIds: [ObjectIdentifier: String] = [:]

    override init(frame: CGRect) {
        super.init(frame: frame)
        accessibilityIdentifier = "air.chat.failed"
        backgroundColor = KQColor.surface
        stack.axis = .vertical
        stack.spacing = 0
        scroll.showsVerticalScrollIndicator = true
        scroll.alwaysBounceVertical = false
        scroll.addSubview(stack)
        addSubview(scroll)
        stack.snp.makeConstraints {
            $0.edges.equalToSuperview()
            $0.width.equalToSuperview()
        }
        scroll.snp.makeConstraints {
            $0.top.equalToSuperview()
            $0.bottom.equalToSuperview().offset(-KQSpace.x6)
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
            $0.height.equalTo(stack).priority(.low)
        }
        isHidden = true
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(items: [AirQueueItem]) {
        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        rowIds = [:]
        for item in items {
            let row = AirEntryRowView()
            row.timeLabel.text = item.time
            row.textLabel.text = item.text
            row.mark.set(.failed, animated: false)
            row.isAccessibilityElement = true
            row.accessibilityTraits = UIAccessibilityTraitButton
            row.accessibilityLabel = item.text
            row.accessibilityValue = [item.time, AirEntryStatus.failed.spokenText].compactMap { $0 }.joined(separator: ", ")
            row.accessibilityHint = String(localized: "air_failed_item_hint")
            row.accessibilityIdentifier = "air.chat.failed.item"
            row.addTarget(self, action: #selector(rowTapped(_:)), for: .touchUpInside)
            rowIds[ObjectIdentifier(row)] = item.id

            let retry = AirQuietButton(title: String(localized: "air_failed_retry"), color: KQColor.danger)
            retry.accessibilityHint = String(localized: "air_failed_retry_hint")
            retry.accessibilityIdentifier = "air.chat.failed.retry"
            retry.addTarget(self, action: #selector(retryTapped(_:)), for: .touchUpInside)
            rowIds[ObjectIdentifier(retry)] = item.id
            let retryRow = UIView()
            retryRow.addSubview(retry)
            retry.snp.makeConstraints {
                $0.top.bottom.equalToSuperview()
                $0.leading.equalToSuperview().offset(max(0, AirEntryMetrics.textStart() - KQSpace.x16 - KQSpace.x4))
                $0.trailing.lessThanOrEqualToSuperview()
            }

            stack.addArrangedSubview(row)
            stack.addArrangedSubview(retryRow)
        }
        isHidden = items.isEmpty
    }

    @objc private func rowTapped(_ sender: UIView) {
        guard let id = rowIds[ObjectIdentifier(sender)] else {
            return
        }
        onItemTap?(id, sender)
    }

    @objc private func retryTapped(_ sender: UIView) {
        guard let id = rowIds[ObjectIdentifier(sender)] else {
            return
        }
        onRetry?(id)
    }
}

/// The quiet button of the design system (.kq-btn-quiet): underlined words without a plate, drawn 28
/// high and taking a touch 44 high. Grey words darken to ink when pressed; red words stay red.
final class AirQuietButton: UIControl {
    private let titleLabel = UILabel()
    private let color: UIColor
    private var title: String

    init(title: String, color: UIColor = KQColor.ink2) {
        self.color = color
        self.title = title
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityLabel = title
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.numberOfLines = 0
        titleLabel.isUserInteractionEnabled = false
        addSubview(titleLabel)
        titleLabel.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x4)
            $0.trailing.equalToSuperview().offset(-KQSpace.x4)
            $0.top.greaterThanOrEqualToSuperview()
            $0.bottom.lessThanOrEqualToSuperview()
            $0.centerY.equalToSuperview()
            // Hugs the words above the minimum height instead of taking whatever room is left.
            $0.top.equalToSuperview().priority(.low)
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.controlSmall)
        }
        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            apply()
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        apply()
    }

    func setTitle(_ title: String) {
        self.title = title
        accessibilityLabel = title
        apply()
    }

    private func apply() {
        let words = isHighlighted && color == KQColor.ink2 ? KQColor.ink : color
        titleLabel.attributedText = NSAttributedString(string: title, attributes: [
            NSAttributedStringKey.font: KQFont.font(.label),
            NSAttributedStringKey.foregroundColor: words,
            NSAttributedStringKey.underlineStyle: NSUnderlineStyle.styleSingle.rawValue,
            NSAttributedStringKey.underlineColor: words,
        ])
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        let grow = max(0, (KQSize.targetIos - bounds.height) / 2)
        return bounds.insetBy(dx: 0, dy: -grow).contains(point)
    }
}

// MARK: - Hint under the field

/// One line under the message field about what happens to a message sent now, with a green or amber
/// dot, and the length counter at the end once only a tenth of the room is left. A new hint
/// cross-fades in place: the line keeps its height. At the accessibility text sizes the counter takes
/// a line of its own under the words, so a long name keeps the width of the line.
final class AirComposeHintView: UIView {
    private let dot = UIView()
    private let label = airLabel(.caption, color: KQColor.ink2)
    private let counter = airLabel(.caption, color: KQColor.ink3)
    private let line = UIStackView()
    private let row = UIStackView()
    private var labelHeight: Constraint?
    private var lineFillsRow: Constraint?
    private(set) var hint = AirComposeHint(text: "", dot: .none)

    override init(frame: CGRect) {
        super.init(frame: frame)
        accessibilityIdentifier = "air.chat.hint"
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitStaticText

        dot.layer.cornerRadius = KQSize.dot / 2
        // A hint longer than the line wraps rather than being cut (a long translation, a large text size).
        label.numberOfLines = 0
        label.lineBreakMode = .byWordWrapping
        counter.font = KQFont.tabular(KQFont.font(.caption))
        counter.isHidden = true

        line.addArrangedSubview(dot)
        line.addArrangedSubview(label)
        line.axis = .horizontal
        line.alignment = .center
        line.spacing = KQSpace.x8
        row.addArrangedSubview(line)
        row.addArrangedSubview(counter)
        row.spacing = KQSpace.x8
        addSubview(row)
        row.snp.makeConstraints {
            $0.edges.equalToSuperview()
            $0.height.greaterThanOrEqualTo(ceil(KQFont.lineHeight(.caption)))
        }
        line.snp.makeConstraints {
            lineFillsRow = $0.width.equalTo(row).constraint
        }
        dot.snp.makeConstraints {
            $0.size.equalTo(KQSize.dot)
        }
        label.snp.makeConstraints {
            labelHeight = $0.height.greaterThanOrEqualTo(0).constraint
        }
        counter.setContentCompressionResistancePriority(.required, for: .horizontal)
        counter.setContentHuggingPriority(.required, for: .horizontal)
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        // The words keep their height above the default: anything hugging the composer at the default
        // priority (the selection toolbar lies over it, edge to edge) pressed a long hint to its kept
        // room, one line of three at AX5. The tray (750) still gives way first on a full screen.
        label.setContentCompressionResistancePriority(UILayoutPriority(rawValue: 850), for: .vertical)
        updateLineCount()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        updateLineCount()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        updateLineCount()
    }

    func setHint(_ newHint: AirComposeHint, animated: Bool) {
        guard newHint != hint else {
            return
        }
        hint = newHint
        let apply = {
            self.label.text = newHint.text
            switch newHint.dot {
                case .live:
                    self.dot.backgroundColor = KQColor.live
                    self.dot.isHidden = false
                case .transit:
                    self.dot.backgroundColor = KQColor.transit
                    self.dot.isHidden = false
                case .none:
                    self.dot.isHidden = true
            }
        }
        if animated && window != nil {
            UIView.transition(with: self, duration: KQMotion.duration(KQMotion.state), options: [.transitionCrossDissolve, .allowUserInteraction], animations: apply, completion: nil)
        }
        else {
            apply()
        }
        updateAccessibility()
    }

    /// `remaining` from AirComposeLimit; nil hides the counter.
    func setCounter(remaining: Int?) {
        guard let remaining = remaining else {
            counter.isHidden = true
            updateAccessibility()
            return
        }
        counter.text = AirComposeLimit.counterText(remaining: remaining)
        counter.textColor = remaining < 0 ? KQColor.danger : KQColor.ink3
        counter.isHidden = false
        updateAccessibility()
    }

    /// The room of one line, two at the accessibility text sizes, is kept whatever the hint says, so a
    /// shorter hint does not move the field; only a hint longer than that room moves it up, whole.
    private func updateLineCount() {
        let stacked = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        row.axis = stacked ? .vertical : .horizontal
        row.alignment = stacked ? .trailing : .center
        if stacked {
            lineFillsRow?.activate()
        }
        else {
            lineFillsRow?.deactivate()
        }
        labelHeight?.update(offset: CGFloat(stacked ? 2 : 1) * ceil(KQFont.lineHeight(.caption)))
    }

    private func updateAccessibility() {
        var parts = [hint.text]
        if !counter.isHidden, let text = counter.text {
            parts.append(text)
        }
        accessibilityLabel = parts.filter { !$0.isEmpty }.joined(separator: ". ")
    }
}

// MARK: - Path picture

/// "You, relay, person": two circles with the link between them, solid and green when they are
/// online, through a small square for a relay, dashed and grey when there is no link.
final class AirPathView: UIView {
    private let line = CAShapeLayer()
    private let ends = [CAShapeLayer(), CAShapeLayer()]
    private let relayNode = CAShapeLayer()
    private let youLabel = airLabel(.caption, weight: 600, color: KQColor.ink2)
    private let initialsLabel = airLabel(.caption, weight: 600, color: KQColor.ink2)
    private let nameLabel = airLabel(.caption, color: KQColor.ink2)
    private let relayLabel = airLabel(.caption, color: KQColor.ink3)
    private var state: AirLinkState = .none
    private var heightConstraint: Constraint!
    /// The two ends are circles the size of a control; the link runs between them at their centre.
    private static let radius: CGFloat = KQSize.control / 2
    private static let centerY: CGFloat = KQSpace.x12 + radius
    private static let endInset: CGFloat = radius + KQSpace.x8
    /// The relay is a small square in the middle of the link.
    private static let relayHalf: CGFloat = KQSpace.x6
    /// One caption line under a circle; the name under the far end is at most four tiles wide.
    private static let labelHeight: CGFloat = KQSpace.x16
    private static let nameMaxWidth: CGFloat = 4 * KQSize.tilePhone
    /// "You" and the initials keep this far inside their circle and shrink to fit at large text sizes.
    private static let glyphInset: CGFloat = KQSpace.x6

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = false
        line.lineWidth = KQStroke.strong
        line.fillColor = UIColor.clear.cgColor
        layer.addSublayer(line)
        for end in ends {
            end.lineWidth = KQStroke.hairline
            layer.addSublayer(end)
        }
        relayNode.lineWidth = KQStroke.strong
        layer.addSublayer(relayNode)
        youLabel.text = String(localized: "air_you")
        youLabel.textAlignment = .center
        initialsLabel.textAlignment = .center
        nameLabel.textAlignment = .center
        relayLabel.textAlignment = .center
        relayLabel.text = String(localized: "air_path_relay")
        for label in [youLabel, initialsLabel] {
            label.adjustsFontSizeToFitWidth = true
            label.minimumScaleFactor = 0.5
        }
        // The words under the link and the far end wrap at their words rather than being cut: at the
        // large text sizes "relay" and the name lost their ends on one line.
        for label in [nameLabel, relayLabel] {
            label.numberOfLines = 0
            label.lineBreakMode = .byWordWrapping
        }
        for label in [youLabel, initialsLabel, nameLabel, relayLabel] {
            addSubview(label)
        }
        snp.makeConstraints {
            heightConstraint = $0.height.equalTo(AirPathView.centerY + AirPathView.radius + KQSpace.x4 + AirPathView.labelHeight + KQSpace.x8).constraint
        }
        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(state: AirLinkState, initials: String, name: String) {
        self.state = state
        initialsLabel.text = initials
        nameLabel.text = name
        relayLabel.isHidden = state != .relay
        apply()
        setNeedsLayout()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let r = AirPathView.radius
        let y = AirPathView.centerY
        let inset = AirPathView.endInset
        let near = airIsRightToLeft ? bounds.width - inset : inset
        let far = airIsRightToLeft ? inset : bounds.width - inset
        ends[0].path = UIBezierPath(arcCenter: CGPoint(x: near, y: y), radius: r, startAngle: 0, endAngle: 2 * .pi, clockwise: true).cgPath
        ends[1].path = UIBezierPath(arcCenter: CGPoint(x: far, y: y), radius: r, startAngle: 0, endAngle: 2 * .pi, clockwise: true).cgPath
        let start = min(near, far) + r + KQSpace.x2
        let end = max(near, far) - r - KQSpace.x2
        let path = UIBezierPath()
        let mid = (start + end) / 2
        if state == .relay {
            let half = AirPathView.relayHalf
            path.move(to: CGPoint(x: start, y: y))
            path.addLine(to: CGPoint(x: mid - half - KQSpace.x4, y: y))
            path.move(to: CGPoint(x: mid + half + KQSpace.x4, y: y))
            path.addLine(to: CGPoint(x: end, y: y))
            relayNode.path = UIBezierPath(roundedRect: CGRect(x: mid - half, y: y - half, width: 2 * half, height: 2 * half), cornerRadius: KQRadius.tick).cgPath
        }
        else {
            path.move(to: CGPoint(x: start, y: y))
            path.addLine(to: CGPoint(x: end, y: y))
            relayNode.path = nil
        }
        line.path = path.cgPath
        let glyph = AirPathView.glyphInset
        youLabel.frame = CGRect(x: near - r + glyph, y: y - r, width: 2 * (r - glyph), height: 2 * r)
        initialsLabel.frame = CGRect(x: far - r + glyph, y: y - r, width: 2 * (r - glyph), height: 2 * r)

        let captionLine = max(AirPathView.labelHeight, ceil(KQFont.lineHeight(.caption)))
        // Half the link, or as much of the width as the word needs (a large text size), kept on screen.
        let relayWidth = min(max((end - start) / 2, ceil(relayLabel.intrinsicContentSize.width)), bounds.width)
        let relayX = min(max(0, mid - relayWidth / 2), bounds.width - relayWidth)
        relayLabel.frame = CGRect(x: relayX, y: y + KQSpace.x12, width: relayWidth,
                                  height: AirPathView.height(of: relayLabel, width: relayWidth, line: captionLine))

        let nameWidth = min(bounds.width / 2, AirPathView.nameMaxWidth)
        let nameX = min(max(0, far - nameWidth / 2), bounds.width - nameWidth)
        var nameTop = y + r + KQSpace.x4
        if state == .relay {
            // The relay word under the middle and the name under the far end share a line while their
            // texts keep apart; a large text size moves the name to the line below.
            let relayHalfText = min(relayLabel.intrinsicContentSize.width, relayWidth) / 2
            let nameHalfText = min(nameLabel.intrinsicContentSize.width, nameWidth) / 2
            let apart = abs(nameX + nameWidth / 2 - mid) - relayHalfText - nameHalfText
            if apart < KQSpace.x8 {
                nameTop = max(nameTop, relayLabel.frame.maxY + KQSpace.x2)
            }
        }
        nameLabel.frame = CGRect(x: nameX, y: nameTop, width: nameWidth,
                                 height: AirPathView.height(of: nameLabel, width: nameWidth, line: captionLine))

        let height = max(nameLabel.frame.maxY, relayLabel.isHidden ? 0 : relayLabel.frame.maxY) + KQSpace.x8
        if abs(height - bounds.height) > 0.5 {
            heightConstraint.update(offset: height)
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        apply()
        setNeedsLayout()
    }

    /// The height of a label's wrapped words at `width`, at least one caption line.
    private static func height(of label: UILabel, width: CGFloat, line: CGFloat) -> CGFloat {
        let needed = label.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude)).height
        return max(line, ceil(needed))
    }

    /// While a request waits and its screen is open, the dashes run from you towards the other end;
    /// they stand still with reduced motion and once the link is there.
    func setRunning(_ running: Bool) {
        line.removeAnimation(forKey: "run")
        guard running, state == .none, !UIAccessibilityIsReduceMotionEnabled() else {
            return
        }
        let step = 2 * KQSpace.x4
        let run = CABasicAnimation(keyPath: "lineDashPhase")
        run.fromValue = 0
        run.toValue = airIsRightToLeft ? step : -step
        run.duration = KQMotion.reveal * 2
        run.repeatCount = .infinity
        line.add(run, forKey: "run")
    }

    private func apply() {
        let live = state != .none
        line.strokeColor = airCGColor(live ? KQColor.live : KQColor.ink3)
        line.lineDashPattern = live ? nil : [NSNumber(value: Double(KQSpace.x4)), NSNumber(value: Double(KQSpace.x4))]
        for end in ends {
            end.fillColor = airCGColor(KQColor.sunken)
            end.strokeColor = airCGColor(KQColor.lineStrong)
        }
        relayNode.strokeColor = airCGColor(KQColor.live)
        relayNode.fillColor = airCGColor(KQColor.surface)
    }
}

// MARK: - Dossier

/// Everything the dossier shows, prepared by the conversation screen.
struct AirDossierContent {
    let name: String
    let statusMessage: String
    let tile: AirTileView.Style
    let photo: UIImage?
    let initials: String
    let link: AirLinkState
    let linkText: String
    let strip: AirDayStrip
    let nowOnline: Bool
    let stripSpoken: String
    let bestHours: String
    let notifications: String
    let muted: Bool
    let verification: String
    let isVerified: Bool
    let key: String
}

/// The contact dossier, unfolded from the header over the conversation: the link, the last day, the
/// notifications, the key check and the key itself, then "Block" and "Remove". Sections run in one
/// column; the panel scrolls when it is taller than the screen.
final class AirDossierView: UIView, UIGestureRecognizerDelegate {
    var onClose: (() -> Void)?
    var onMuteChange: ((Bool) -> Void)?
    var onVerify: (() -> Void)?
    var onUnverify: (() -> Void)?
    var onCopyKey: (() -> Void)?
    var onShowQR: (() -> Void)?
    var onRename: (() -> Void)?
    var onAttachments: (() -> Void)?
    var onRemove: (() -> Void)?
    var onBlock: (() -> Void)?

    let scrim = UIView()
    let panel = UIView()
    private let scroll = UIScrollView()
    private let content = UIStackView()
    private let grip = UIView()
    private let reveal = CAShapeLayer()
    private var panelHeight: Constraint?
    private let muteSwitch = UISwitch()
    private var isOpen = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        accessibilityIdentifier = "air.chat.dossier"

        scrim.backgroundColor = KQColor.scrim
        scrim.isAccessibilityElement = false
        scrim.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(closeTapped)))
        addSubview(scrim)

        panel.backgroundColor = KQColor.surface
        panel.accessibilityViewIsModal = true
        panel.accessibilityIdentifier = "air.chat.dossier.panel"
        addSubview(panel)

        scroll.alwaysBounceVertical = false
        scroll.showsVerticalScrollIndicator = true
        panel.addSubview(scroll)

        content.axis = .vertical
        content.spacing = KQSpace.x24
        content.isLayoutMarginsRelativeArrangement = true
        content.layoutMargins = UIEdgeInsets(top: KQSpace.x16, left: KQSpace.x16, bottom: KQSpace.x16, right: KQSpace.x16)
        scroll.addSubview(content)

        grip.backgroundColor = KQColor.lineStrong
        grip.layer.cornerRadius = KQSize.gripHeight / 2
        grip.isAccessibilityElement = false
        let gripArea = UIView()
        gripArea.addSubview(grip)
        panel.addSubview(gripArea)

        let rule = UIView()
        rule.backgroundColor = KQColor.line
        panel.addSubview(rule)

        scrim.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        panel.snp.makeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            $0.height.lessThanOrEqualToSuperview().offset(-KQSpace.x64)
            panelHeight = $0.height.equalTo(0).priority(.high).constraint
        }
        scroll.snp.makeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            $0.bottom.equalTo(gripArea.snp.top)
        }
        content.snp.makeConstraints {
            $0.edges.equalToSuperview()
            $0.width.equalToSuperview()
        }
        gripArea.snp.makeConstraints {
            $0.leading.trailing.equalToSuperview()
            $0.bottom.equalTo(rule.snp.top)
            $0.height.equalTo(KQSpace.x24)
        }
        grip.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.width.equalTo(KQSize.gripWidth)
            $0.height.equalTo(KQSize.gripHeight)
        }
        rule.snp.makeConstraints {
            $0.leading.trailing.bottom.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }

        let swipe = UISwipeGestureRecognizer(target: self, action: #selector(swipedUp(_:)))
        swipe.direction = .up
        swipe.delegate = self
        panel.addGestureRecognizer(swipe)
        let gripPan = UIPanGestureRecognizer(target: self, action: #selector(gripPanned(_:)))
        gripArea.addGestureRecognizer(gripPan)

        muteSwitch.onTintColor = KQColor.switchOn
        muteSwitch.accessibilityIdentifier = "air.chat.dossier.mute"
        muteSwitch.addTarget(self, action: #selector(muteChanged), for: .valueChanged)

        panel.layer.mask = reveal
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        if !isAnimatingReveal {
            reveal.path = UIBezierPath(rect: CGRect(x: 0, y: 0, width: panel.bounds.width, height: isOpen ? panel.bounds.height : 0)).cgPath
        }
    }

    private var isAnimatingReveal = false

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        scrim.backgroundColor = KQColor.scrim
    }

    override func accessibilityPerformEscape() -> Bool {
        onClose?()
        return true
    }

    // MARK: Content

    func configure(_ model: AirDossierContent) {
        content.arrangedSubviews.forEach { $0.removeFromSuperview() }

        content.addArrangedSubview(headBlock(model))
        content.addArrangedSubview(section(title: String(localized: "air_dossier_link_title"), views: [
            pathView(model),
            bodyLabel(model.linkText),
        ]))

        let tall = AirDayStripView(style: .tall)
        tall.configure(strip: model.strip, nowOnline: model.nowOnline)
        let axis = AirDayAxisView()
        let stripBlock = UIStackView(arrangedSubviews: [tall, axis])
        stripBlock.axis = .vertical
        stripBlock.spacing = KQSpace.x4
        stripBlock.isAccessibilityElement = true
        stripBlock.accessibilityLabel = String(localized: "air_day_title") + ": " + model.stripSpoken
        stripBlock.accessibilityTraits = UIAccessibilityTraitImage
        var dayViews: [UIView] = [stripBlock]
        if !model.bestHours.isEmpty {
            dayViews.append(bodyLabel(model.bestHours))
        }
        dayViews.append(noteLabel(String(localized: "air_day_note")))
        content.addArrangedSubview(section(title: String(localized: "air_dossier_day_title"), views: dayViews))

        muteSwitch.isOn = model.muted
        let muteLabel = airLabel(.body, color: KQColor.ink)
        muteLabel.text = String(localized: "air_dossier_mute")
        muteLabel.isAccessibilityElement = false
        muteSwitch.accessibilityLabel = muteLabel.text
        let muteRow = UIStackView(arrangedSubviews: [muteLabel, muteSwitch])
        muteRow.axis = .horizontal
        muteRow.alignment = .center
        muteRow.spacing = KQSpace.x12
        muteRow.snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
        }
        content.addArrangedSubview(section(title: String(localized: "air_dossier_notifications_title"), views: [
            bodyLabel(model.notifications),
            muteRow,
        ]))

        let verifyButton: UIView
        if model.isVerified {
            let button = AirQuietButton(title: String(localized: "air_dossier_unverify"), color: KQColor.ink2)
            button.accessibilityIdentifier = "air.chat.dossier.unverify"
            button.addTarget(self, action: #selector(unverifyTapped), for: .touchUpInside)
            verifyButton = leadingAligned(button)
        }
        else {
            let button = AirButton(style: .normal, title: String(localized: "air_dossier_verify"), icon: "eye")
            button.accessibilityIdentifier = "air.chat.dossier.verify"
            button.addTarget(self, action: #selector(verifyTapped), for: .touchUpInside)
            verifyButton = leadingAligned(button)
        }
        content.addArrangedSubview(section(title: String(localized: "air_dossier_verify_title"), views: [
            bodyLabel(model.verification),
            verifyButton,
        ]))

        let keyLabel = airLabel(.mono, color: KQColor.ink, lines: 0)
        keyLabel.text = AirKeyFormat.lines(model.key)
        keyLabel.accessibilityLabel = String(localized: "air_dossier_key_title")
        keyLabel.accessibilityValue = AirKeyFormat.groups(model.key).joined(separator: " ")
        keyLabel.accessibilityIdentifier = "air.chat.dossier.key"
        let copy = AirButton(style: .normal, title: String(localized: "air_dossier_copy_key"), icon: "copy")
        copy.accessibilityIdentifier = "air.chat.dossier.copy"
        copy.addTarget(self, action: #selector(copyTapped), for: .touchUpInside)
        let qr = AirButton(style: .normal, title: String(localized: "air_dossier_qr"), icon: "scan")
        qr.accessibilityIdentifier = "air.chat.dossier.qr"
        qr.addTarget(self, action: #selector(qrTapped), for: .touchUpInside)
        content.addArrangedSubview(section(title: String(localized: "air_dossier_key_title"), views: [
            keyLabel,
            buttonRow([copy, qr]),
        ]))

        let attachments = AirButton(style: .normal, title: String(localized: "attachments_title"), icon: "paperclip")
        attachments.accessibilityIdentifier = "air.chat.dossier.attachments"
        attachments.addTarget(self, action: #selector(attachmentsTapped), for: .touchUpInside)
        content.addArrangedSubview(section(title: String(localized: "air_dossier_files_title"), views: [leadingAligned(attachments)]))

        // Block is this device's own: the contact goes the way "Remove" takes it and requests from the key
        // stop showing (AirBlockGuard); nothing is sent to the other side.
        let block = AirButton(style: .danger, title: String(localized: "air_dossier_block"))
        block.accessibilityIdentifier = "air.chat.dossier.block"
        block.accessibilityHint = String(localized: "air_dossier_block_hint")
        block.addTarget(self, action: #selector(blockTapped), for: .touchUpInside)
        let remove = AirButton(style: .danger, title: String(localized: "air_dossier_remove"), icon: "trash")
        remove.accessibilityIdentifier = "air.chat.dossier.remove"
        remove.addTarget(self, action: #selector(removeTapped), for: .touchUpInside)
        content.addArrangedSubview(buttonRow([block, remove]))

        setNeedsLayout()
    }

    func setMuted(_ muted: Bool) {
        muteSwitch.setOn(muted, animated: true)
    }

    private func headBlock(_ model: AirDossierContent) -> UIView {
        let tile = AirTileView(side: KQSize.tilePhone)
        tile.configure(style: model.tile, photo: model.photo)
        let name = airLabel(.heading, color: KQColor.ink, lines: 2)
        name.text = model.name
        name.accessibilityTraits = UIAccessibilityTraitHeader
        let status = airLabel(.caption, color: KQColor.ink2, lines: 2)
        status.text = model.statusMessage
        status.isHidden = model.statusMessage.isEmpty
        let names = UIStackView(arrangedSubviews: [name, status])
        names.axis = .vertical
        names.spacing = KQSpace.x2
        let rename = AirQuietButton(title: String(localized: "air_dossier_rename"), color: KQColor.ink)
        rename.accessibilityIdentifier = "air.chat.dossier.rename"
        rename.addTarget(self, action: #selector(renameTapped), for: .touchUpInside)
        // At the accessibility text sizes "Rename" goes under the name, which then keeps the width.
        let stacked = UIApplication.shared.preferredContentSizeCategory.isAccessibilityCategory
        if stacked {
            names.addArrangedSubview(leadingAligned(rename))
        }
        let row = UIStackView(arrangedSubviews: stacked ? [tile, names] : [tile, names, rename])
        row.axis = .horizontal
        row.alignment = .center
        row.spacing = KQSpace.x12
        tile.snp.makeConstraints {
            $0.size.equalTo(KQSize.tilePhone)
        }
        rename.setContentCompressionResistancePriority(.required, for: .horizontal)
        rename.setContentHuggingPriority(.required, for: .horizontal)
        return row
    }

    private func pathView(_ model: AirDossierContent) -> UIView {
        let path = AirPathView()
        path.configure(state: model.link, initials: model.initials, name: model.name)
        return path
    }

    private func section(title: String, views: [UIView]) -> UIView {
        let heading = airLabel(.label, color: KQColor.ink3)
        heading.text = title
        heading.accessibilityTraits = UIAccessibilityTraitHeader
        let stack = UIStackView(arrangedSubviews: [heading] + views)
        stack.axis = .vertical
        stack.spacing = KQSpace.x8
        return stack
    }

    private func bodyLabel(_ text: String) -> UILabel {
        let label = airLabel(.label, weight: 400, color: KQColor.ink2, lines: 0)
        label.text = text
        return label
    }

    private func noteLabel(_ text: String) -> UILabel {
        let label = airLabel(.caption, color: KQColor.ink3, lines: 0)
        label.text = text
        return label
    }

    private func leadingAligned(_ view: UIView) -> UIView {
        let wrapper = UIView()
        wrapper.addSubview(view)
        view.snp.makeConstraints {
            $0.top.bottom.leading.equalToSuperview()
            $0.trailing.lessThanOrEqualToSuperview()
        }
        return wrapper
    }

    private func buttonRow(_ buttons: [UIView]) -> UIView {
        let row = UIStackView(arrangedSubviews: buttons)
        row.axis = traitCollection.preferredContentSizeCategory.isAccessibilityCategory ? .vertical : .horizontal
        row.distribution = .fillEqually
        row.spacing = KQSpace.x8
        return row
    }

    // MARK: Opening and closing

    /// New content while the dossier is open (a key just verified, a new name): the panel stays where it
    /// is, keeps its scroll position and takes the new height without unfolding again.
    func update(_ model: AirDossierContent) {
        let offset = scroll.contentOffset
        configure(model)
        guard isOpen else {
            return
        }
        layoutIfNeeded()
        panelHeight?.update(offset: fittingPanelHeight())
        layoutIfNeeded()
        let maxOffset = max(0, scroll.contentSize.height - scroll.bounds.height)
        scroll.contentOffset = CGPoint(x: 0, y: min(offset.y, maxOffset))
        reveal.path = UIBezierPath(rect: panel.bounds).cgPath
    }

    private func fittingPanelHeight() -> CGFloat {
        let fitting = content.systemLayoutSizeFitting(CGSize(width: bounds.width, height: 0),
                                                      withHorizontalFittingPriority: .required,
                                                      verticalFittingPriority: .fittingSizeLevel).height
        return min(fitting + KQSpace.x24 + KQStroke.hairline, bounds.height - KQSpace.x64)
    }

    /// Unfolds from the top over 440 ms while the conversation dims; a fade with Reduce Motion.
    func open() {
        isOpen = true
        layoutIfNeeded()
        panelHeight?.update(offset: fittingPanelHeight())
        layoutIfNeeded()

        if UIAccessibilityIsReduceMotionEnabled() {
            reveal.path = UIBezierPath(rect: panel.bounds).cgPath
            scrim.alpha = 0
            panel.alpha = 0
            UIView.animate(withDuration: KQMotion.duration(KQMotion.reveal)) {
                self.scrim.alpha = 1
                self.panel.alpha = 1
            }
        }
        else {
            animateReveal(to: panel.bounds.height, from: 0)
            scrim.alpha = 0
            UIView.animate(withDuration: KQMotion.reveal, delay: 0, options: [.curveEaseOut], animations: {
                self.scrim.alpha = 1
            }, completion: nil)
        }
        UIAccessibilityPostNotification(UIAccessibilityScreenChangedNotification, content.arrangedSubviews.first)
    }

    /// Folds back up the same way, then calls `completion` to remove the view.
    func close(completion: @escaping () -> Void) {
        isOpen = false
        if UIAccessibilityIsReduceMotionEnabled() {
            UIView.animate(withDuration: KQMotion.duration(KQMotion.reveal), animations: {
                self.scrim.alpha = 0
                self.panel.alpha = 0
            }, completion: { _ in
                completion()
            })
            return
        }
        animateReveal(to: 0, from: panel.bounds.height)
        UIView.animate(withDuration: KQMotion.layout, delay: 0, options: [.curveEaseOut], animations: {
            self.scrim.alpha = 0
        }, completion: { _ in
            completion()
        })
    }

    private func animateReveal(to height: CGFloat, from start: CGFloat) {
        let width = panel.bounds.width
        let from = UIBezierPath(rect: CGRect(x: 0, y: 0, width: width, height: start)).cgPath
        let to = UIBezierPath(rect: CGRect(x: 0, y: 0, width: width, height: height)).cgPath
        let animation = CABasicAnimation(keyPath: "path")
        animation.fromValue = from
        animation.toValue = to
        animation.duration = height > start ? KQMotion.reveal : KQMotion.layout
        animation.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.8, 0.2, 1)
        isAnimatingReveal = true
        CATransaction.begin()
        CATransaction.setCompletionBlock { [weak self] in
            self?.isAnimatingReveal = false
        }
        reveal.path = to
        reveal.add(animation, forKey: "reveal")
        CATransaction.commit()
    }

    // MARK: Actions

    @objc private func closeTapped() {
        onClose?()
    }

    @objc private func swipedUp(_ gesture: UISwipeGestureRecognizer) {
        let atEnd = scroll.contentOffset.y >= scroll.contentSize.height - scroll.bounds.height - 1
        if atEnd {
            onClose?()
        }
    }

    @objc private func gripPanned(_ gesture: UIPanGestureRecognizer) {
        guard gesture.state == .ended else {
            return
        }
        let movement = gesture.translation(in: self).y
        let speed = gesture.velocity(in: self).y
        if movement < -KQSpace.x16 || speed < -300 {
            onClose?()
        }
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        return true
    }

    @objc private func muteChanged() {
        onMuteChange?(muteSwitch.isOn)
    }

    @objc private func verifyTapped() {
        onVerify?()
    }

    @objc private func unverifyTapped() {
        onUnverify?()
    }

    @objc private func copyTapped() {
        onCopyKey?()
    }

    @objc private func qrTapped() {
        onShowQR?()
    }

    @objc private func renameTapped() {
        onRename?()
    }

    @objc private func attachmentsTapped() {
        onAttachments?()
    }

    @objc private func removeTapped() {
        onRemove?()
    }

    @objc private func blockTapped() {
        onBlock?()
    }
}

// MARK: - Jump to the latest messages

/// The button over the foot of the log that brings back the newest messages: a plate of the design
/// system's normal button with the chevron, 44 by 44.
final class AirJumpButton: UIControl {
    private let icon = UIImageView(image: AirIcon.image("chevron-down"))

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityLabel = String(localized: "air_chat_jump_latest")
        accessibilityIdentifier = "air.chat.jump"

        backgroundColor = KQColor.raised
        layer.cornerRadius = KQRadius.control
        layer.borderWidth = KQStroke.hairline
        icon.tintColor = KQColor.ink2
        icon.contentMode = .scaleAspectFit
        icon.isUserInteractionEnabled = false
        addSubview(icon)
        icon.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.size.equalTo(KQSize.icon)
        }
        snp.makeConstraints {
            $0.size.equalTo(KQSize.targetIos)
        }
        applyColors()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            applyColors()
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyColors()
    }

    private func applyColors() {
        backgroundColor = isHighlighted ? KQColor.selected : KQColor.raised
        icon.tintColor = isHighlighted ? KQColor.ink : KQColor.ink2
        layer.borderColor = airCGColor(KQColor.lineStrong)
    }
}
