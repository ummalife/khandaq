// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// Building blocks of the Air screen, as the design system draws them (design/system/index.html: tile,
// pill, section head, reach mark, transit flag, unread badge, command line, button, sheet). Every
// colour, size, radius, stroke and duration comes from DesignTokens.swift. Colours are dynamic, so views
// follow the window's interface style; the few that live in Core Animation (borders, the second tile of
// a group) are resolved again whenever the traits change.

enum AirIcon {
    /// An icon from the design-system sprite (Images.xcassets/Air), tinted by the view that shows it.
    static func image(_ name: String) -> UIImage? {
        return UIImage(named: "kq-" + name)?.withRenderingMode(.alwaysTemplate)
    }

    /// The icon drawn at a side of its own, for a button whose image view takes the size of its image. The
    /// sprite is vector, so the glyph stays sharp at any side.
    static func image(_ name: String, side: CGFloat) -> UIImage? {
        guard let icon = image(name) else {
            return nil
        }
        let size = CGSize(width: side, height: side)
        return UIGraphicsImageRenderer(size: size).image { _ in
            icon.draw(in: CGRect(origin: .zero, size: size))
        }.withRenderingMode(.alwaysTemplate)
    }
}

/// The Large Content Viewer of iOS for the controls that keep their size while the text grows: at the
/// accessibility text sizes a long press shows the glyph and the name of the control large in the middle of
/// the screen, and lifting the finger on the control acts on it. The system gives it to its own bar items;
/// the controls drawn here get it from this.
enum AirLargeContent {
    /// The side a glyph of a 44 point target grows to at the accessibility text sizes, where it stands
    /// beside text many times its size: 8 points of the target stay around it (28).
    static let grownGlyph: CGFloat = KQSize.targetIos - 2 * KQSpace.x8

    static func show(on view: UIView, title: String?, image: UIImage?,
                     delegate: UILargeContentViewerInteractionDelegate? = nil) {
        view.showsLargeContentViewer = true
        view.largeContentTitle = title
        view.largeContentImage = image
        view.scalesLargeContentImage = true
        placeInteraction(on: view, delegate: delegate)
    }

    /// A navigation bar shows the viewer for the views of its items itself, from the same properties. With
    /// an interaction of its own there, a view showed nothing on a long press (the title of a conversation
    /// at AX5, stage 5), so a view in a bar keeps the properties only and elsewhere has an interaction of
    /// its own. A view that moves between the two calls this again.
    static func placeInteraction(on view: UIView, delegate: UILargeContentViewerInteractionDelegate? = nil) {
        let own = view.interactions.first { $0 is UILargeContentViewerInteraction }
        if isInBar(view) {
            if let own = own {
                view.removeInteraction(own)
            }
        }
        else if own == nil {
            view.addInteraction(UILargeContentViewerInteraction(delegate: delegate))
        }
    }

    private static func isInBar(_ view: UIView) -> Bool {
        var ancestor = view.superview
        while let current = ancestor {
            if current is UINavigationBar {
                return true
            }
            ancestor = current.superview
        }
        return false
    }
}

enum AirInitials {
    /// The first letters of the first two words, or one letter for a single word.
    static func from(_ title: String) -> String {
        let words = title.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }
        let letters = words.prefix(2).compactMap { $0.first }.map { String($0).uppercased() }
        // A zero-width non-joiner keeps two Arabic letters in their separate forms instead of joining them.
        return letters.joined(separator: "\u{200C}")
    }
}

/// The six identity tones: the initials on a person's or a group's tile, and later an author's name in
/// a group. A tone only tells people apart; it never carries a state.
enum AirTone {
    static let colors: [UIColor] = [KQColor.tone1, KQColor.tone2, KQColor.tone3, KQColor.tone4, KQColor.tone5, KQColor.tone6]

    /// A stable tone for a key (a public key or a chat id): the same person keeps the same colour on
    /// every launch and every device, so the hash is spelled out (FNV-1a) instead of Hasher, whose
    /// seed changes with each process.
    static func index(for key: String) -> Int {
        var hash: UInt32 = 0x811C9DC5
        for byte in key.uppercased().utf8 {
            hash ^= UInt32(byte)
            hash = hash &* 0x01000193
        }
        return Int(hash % UInt32(colors.count))
    }

    static func color(at index: Int) -> UIColor {
        return colors[((index % colors.count) + colors.count) % colors.count]
    }
}

extension UIView {
    /// The colour for Core Animation as it resolves under this view's traits right now.
    func airCGColor(_ color: UIColor) -> CGColor {
        return color.resolvedColor(with: traitCollection).cgColor
    }
}

// MARK: - Tile

/// A rounded square with initials, a photo or an icon: people, groups (with a second tile behind),
/// a request still waiting (dashed), yourself (ink) and saved messages (bookmark).
final class AirTileView: UIView {
    enum Style: Equatable {
        /// `tone` is an AirTone index.
        case person(initials: String, tone: Int)
        case group(initials: String, tone: Int)
        case pending
        /// A group not joined yet (the command line's "Join the group with this key").
        case pendingGroup
        case me(initials: String)
        case saved
    }

    private let side: CGFloat
    private let backTile = CAShapeLayer()
    private let fill = CAShapeLayer()
    private let border = CAShapeLayer()
    private let initialsLabel = UILabel()
    private let iconView = UIImageView()
    private let photoView = UIImageView()
    private var style: Style = .person(initials: "", tone: 0)

    init(side: CGFloat) {
        self.side = side
        super.init(frame: CGRect(x: 0, y: 0, width: side, height: side))

        isAccessibilityElement = false
        isUserInteractionEnabled = false

        backTile.lineWidth = KQStroke.hairline
        layer.addSublayer(backTile)
        layer.addSublayer(fill)

        photoView.contentMode = .scaleAspectFill
        photoView.layer.cornerRadius = KQRadius.control
        photoView.clipsToBounds = true
        addSubview(photoView)

        initialsLabel.font = KQFont.font(.caption, weight: 600)
        initialsLabel.adjustsFontForContentSizeCategory = true
        // The tile keeps its size at every text size, so its letters stop growing where two of them
        // still fit: at the accessibility sizes they were cut to "...". The name beside it grows.
        initialsLabel.maximumContentSizeCategory = .extraExtraExtraLarge
        initialsLabel.textAlignment = .center
        initialsLabel.adjustsFontSizeToFitWidth = true
        initialsLabel.minimumScaleFactor = 0.7
        addSubview(initialsLabel)

        iconView.contentMode = .scaleAspectFit
        addSubview(iconView)

        border.fillColor = UIColor.clear.cgColor
        border.lineWidth = KQStroke.hairline
        border.zPosition = 1
        layer.addSublayer(border)

        photoView.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        initialsLabel.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.leading.greaterThanOrEqualToSuperview().offset(KQSpace.x2)
            $0.trailing.lessThanOrEqualToSuperview().offset(-KQSpace.x2)
        }
        iconView.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.size.equalTo(KQSize.iconSmall)
        }

        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var intrinsicContentSize: CGSize {
        return CGSize(width: side, height: side)
    }

    func configure(style: Style, photo: UIImage?) {
        self.style = style
        // A photo the person chose stays theirs to show; groups, requests and saved messages have none.
        switch style {
            case .person, .me:
                photoView.image = photo
            case .group, .pending, .pendingGroup, .saved:
                photoView.image = nil
        }
        apply()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let inset = KQStroke.hairline / 2
        let rect = bounds.insetBy(dx: inset, dy: inset)
        let path = UIBezierPath(roundedRect: rect, cornerRadius: KQRadius.control).cgPath
        fill.path = path
        border.path = path

        // The second tile peeks out behind a group, towards the trailing edge and up.
        let isRightToLeft = effectiveUserInterfaceLayoutDirection == .rightToLeft
        let offset = CGPoint(x: isRightToLeft ? -KQSpace.x4 : KQSpace.x4, y: -KQSpace.x4)
        backTile.path = UIBezierPath(roundedRect: rect.offsetBy(dx: offset.x, dy: offset.y), cornerRadius: KQRadius.control).cgPath
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        apply()
    }

    private func apply() {
        var fillColor = KQColor.sunken
        var borderColor = KQColor.line
        var textColor = KQColor.ink2
        var dashed = false
        var initials = ""
        var icon: String?
        var iconTint = KQColor.ink3

        switch style {
            case .person(let letters, let tone):
                initials = letters
                textColor = AirTone.color(at: tone)
            case .group(let letters, let tone):
                initials = letters
                textColor = AirTone.color(at: tone)
            case .pending:
                fillColor = .clear
                borderColor = KQColor.lineStrong
                dashed = true
                icon = "user-plus"
            case .pendingGroup:
                fillColor = .clear
                borderColor = KQColor.lineStrong
                dashed = true
                icon = "users"
            case .me(let letters):
                fillColor = KQColor.ink
                borderColor = KQColor.ink
                textColor = KQColor.onInk
                initials = letters
            case .saved:
                icon = "bookmark"
                iconTint = KQColor.ink
        }

        let showsPhoto = photoView.image != nil
        photoView.isHidden = !showsPhoto
        initialsLabel.text = showsPhoto ? nil : initials
        initialsLabel.textColor = textColor
        iconView.image = icon.flatMap { AirIcon.image($0) }
        iconView.tintColor = iconTint
        iconView.isHidden = icon == nil

        fill.fillColor = airCGColor(fillColor)
        border.strokeColor = airCGColor(borderColor)
        border.lineDashPattern = dashed ? [NSNumber(value: Double(KQSpace.x4)), NSNumber(value: Double(KQSpace.x2))] : nil

        if case .group = style {
            backTile.isHidden = false
            backTile.fillColor = airCGColor(KQColor.surface)
            backTile.strokeColor = airCGColor(KQColor.lineStrong)
        }
        else {
            backTile.isHidden = true
        }
    }
}

// MARK: - Rolling counter

/// A short label whose new value rolls in from below (a larger number) or above (a smaller one),
/// so a section count or a pill count changes without a jump; instant when motion is reduced.
final class AirRollingLabel: UIView {
    var font: UIFont = KQFont.tabular(KQFont.font(.caption, weight: 500)) {
        didSet {
            current.font = font
            invalidateIntrinsicContentSize()
        }
    }

    var textColor: UIColor = KQColor.ink3 {
        didSet {
            current.textColor = textColor
        }
    }

    var textAlignment: NSTextAlignment = .natural {
        didSet {
            current.textAlignment = textAlignment
        }
    }

    private(set) var text = ""
    private var current = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        clipsToBounds = true
        isAccessibilityElement = false
        install(current)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var intrinsicContentSize: CGSize {
        return current.intrinsicContentSize
    }

    func setText(_ newText: String, animated: Bool, upward: Bool) {
        guard newText != text else {
            return
        }
        text = newText

        guard animated, window != nil, !UIAccessibilityIsReduceMotionEnabled(), bounds.height > 0 else {
            current.text = newText
            invalidateIntrinsicContentSize()
            return
        }

        let outgoing = current
        let incoming = UILabel()
        install(incoming)
        incoming.text = newText
        current = incoming
        invalidateIntrinsicContentSize()

        let distance = bounds.height
        incoming.transform = CGAffineTransform(translationX: 0, y: upward ? distance : -distance)
        incoming.alpha = 0

        let animator = UIViewPropertyAnimator(duration: KQMotion.duration(KQMotion.state), timingParameters: KQMotion.easeOut)
        animator.addAnimations {
            incoming.transform = .identity
            incoming.alpha = 1
            outgoing.transform = CGAffineTransform(translationX: 0, y: upward ? -distance : distance)
            outgoing.alpha = 0
        }
        animator.addCompletion { _ in
            outgoing.removeFromSuperview()
        }
        animator.startAnimation()
    }

    private func install(_ label: UILabel) {
        label.font = font
        label.textColor = textColor
        label.textAlignment = textAlignment
        label.adjustsFontForContentSizeCategory = true
        label.isAccessibilityElement = false
        addSubview(label)
        label.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
    }
}

// MARK: - Marks and flags

/// The reachability mark next to a row's name: a still green dot with "online", or a quiet grey text.
final class AirReachView: UIView {
    private let dot = UIView()
    private let label = UILabel()
    private let stack = UIStackView()

    /// Whether the words may take more lines: under the name of a stacked row at the accessibility text
    /// sizes, where one line of them could be wider than the row. The dot then stands on the first line.
    var wraps = false {
        didSet {
            label.numberOfLines = wraps ? 0 : 1
            let resistance: UILayoutPriority = wraps ? .defaultHigh : .required
            label.setContentCompressionResistancePriority(resistance, for: .horizontal)
            setContentCompressionResistancePriority(resistance, for: .horizontal)
            stack.alignment = wraps ? .firstBaseline : .center
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = false

        dot.layer.cornerRadius = KQSize.dot / 2
        dot.backgroundColor = KQColor.live
        label.font = KQFont.font(.caption)
        label.adjustsFontForContentSizeCategory = true

        stack.addArrangedSubview(dot)
        stack.addArrangedSubview(label)
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = KQSpace.x6
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        dot.snp.makeConstraints {
            $0.size.equalTo(KQSize.dot)
        }

        setContentHuggingPriority(.required, for: .horizontal)
        setContentCompressionResistancePriority(.required, for: .horizontal)
        label.setContentHuggingPriority(.required, for: .horizontal)
        label.setContentCompressionResistancePriority(.required, for: .horizontal)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(text: String, isLive: Bool) {
        label.text = text
        label.textColor = isLive ? KQColor.live : KQColor.ink3
        dot.isHidden = !isLive
        isHidden = text.isEmpty
    }
}

/// "N in transit": the amber flag of a row whose messages wait for the other side to come online.
final class AirTransitFlag: UIView {
    private let icon = UIImageView(image: AirIcon.image("clock"))
    private let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = false
        backgroundColor = KQColor.transitSoft

        icon.tintColor = KQColor.transit
        icon.contentMode = .scaleAspectFit
        label.font = KQFont.tabular(KQFont.font(.caption, weight: 600))
        label.adjustsFontForContentSizeCategory = true
        label.textColor = KQColor.transit

        let stack = UIStackView(arrangedSubviews: [icon, label])
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = KQSpace.x4
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.top.bottom.equalToSuperview().inset(KQSpace.x2)
            $0.leading.equalToSuperview().offset(KQSpace.x6)
            $0.trailing.equalToSuperview().offset(-KQSpace.x8)
        }
        icon.snp.makeConstraints {
            $0.size.equalTo(KQSize.iconSmall)
        }

        setContentHuggingPriority(.required, for: .horizontal)
        setContentCompressionResistancePriority(.required, for: .horizontal)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = bounds.height / 2
    }

    func configure(count: Int) {
        label.text = String(localized: "air_transit_format", count)
        isHidden = count <= 0
    }
}

/// A count on a round plate: the black unread badge of a row, or the count inside a pill.
final class AirCountBadge: UIView {
    enum Tone {
        case unread
        case pill
        case pillSelected
        case pillTransit
    }

    private let label = AirRollingLabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = false
        label.font = KQFont.tabular(KQFont.font(.caption, weight: 600))
        label.textAlignment = .center
        addSubview(label)
        label.snp.makeConstraints {
            $0.centerY.equalToSuperview()
            $0.leading.equalToSuperview().offset(KQSpace.x6)
            $0.trailing.equalToSuperview().offset(-KQSpace.x6)
        }
        snp.makeConstraints {
            $0.height.equalTo(KQSize.badge)
            $0.width.greaterThanOrEqualTo(KQSize.badge)
        }
        layer.cornerRadius = KQSize.badge / 2
        setContentHuggingPriority(.required, for: .horizontal)
        setContentCompressionResistancePriority(.required, for: .horizontal)
        apply(.unread)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func apply(_ tone: Tone) {
        switch tone {
            case .unread:
                backgroundColor = KQColor.ink
                label.textColor = KQColor.onInk
            case .pill:
                backgroundColor = KQColor.sunken
                label.textColor = KQColor.ink
            case .pillSelected:
                backgroundColor = KQColor.inkBadge
                label.textColor = KQColor.onInk
            case .pillTransit:
                backgroundColor = KQColor.transitSoft
                label.textColor = KQColor.transit
        }
    }

    func setCount(_ count: Int, animated: Bool) {
        let previous = Int(label.text) ?? 0
        label.setText(count > 99 ? "99+" : "\(count)", animated: animated, upward: count > previous)
        isHidden = count <= 0
    }
}

// MARK: - Pills

/// A filter pill with its count. Pressed, it turns ink; the pills never change order.
final class AirPillButton: UIControl {
    let filter: AirFilter

    private let titleLabel = UILabel()
    private let badge = AirCountBadge()
    private let isTransit: Bool
    private var count = 0

    init(filter: AirFilter, title: String) {
        self.filter = filter
        self.isTransit = filter == .transit
        super.init(frame: .zero)

        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityIdentifier = "air.filter.\(filter.accessibilityName)"

        layer.cornerRadius = KQSize.chip / 2
        layer.borderWidth = KQStroke.hairline

        titleLabel.text = title
        titleLabel.font = KQFont.font(.label)
        titleLabel.adjustsFontForContentSizeCategory = true

        let stack = UIStackView(arrangedSubviews: [titleLabel, badge])
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = KQSpace.x6
        stack.isUserInteractionEnabled = false
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.centerY.equalToSuperview()
            $0.top.greaterThanOrEqualToSuperview().offset(KQSpace.x4)
            $0.leading.equalToSuperview().offset(KQSpace.x12)
            $0.trailing.equalToSuperview().offset(-KQSpace.x12)
        }
        // 32 high while its words fit; at the largest text sizes the pill grows with them instead of
        // the words standing out of it.
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.chip)
            $0.height.equalTo(KQSize.chip).priority(.low)
        }

        badge.setCount(0, animated: false)
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
            alpha = isHighlighted ? 0.7 : 1
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = bounds.height / 2
    }

    /// The pill is drawn 32 high; the area that takes a touch is 44, so a thumb never aims at 32.
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        let grow = max(0, (KQSize.targetIos - bounds.height) / 2)
        return bounds.insetBy(dx: 0, dy: -grow).contains(point)
    }

    func setCount(_ value: Int, animated: Bool) {
        count = value
        badge.setCount(value, animated: animated)
        updateAccessibility()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        apply()
    }

    private func apply() {
        backgroundColor = isSelected ? KQColor.ink : KQColor.raised
        layer.borderColor = airCGColor(isSelected ? KQColor.ink : KQColor.lineStrong)
        titleLabel.textColor = isSelected ? KQColor.onInk : KQColor.ink
        if isSelected {
            badge.apply(.pillSelected)
        }
        else {
            badge.apply(isTransit ? .pillTransit : .pill)
        }
        updateAccessibility()
    }

    private func updateAccessibility() {
        accessibilityLabel = titleLabel.text
        accessibilityValue = count > 0 ? "\(count)" : nil
        if isSelected {
            accessibilityTraits = UIAccessibilityTraitButton | UIAccessibilityTraitSelected
        }
        else {
            accessibilityTraits = UIAccessibilityTraitButton
        }
    }
}

// MARK: - Buttons

/// The design-system button: raised with a line, ink for the one main action, or raised with red words
/// for an action that removes something. 44 high on a phone. A disabled button sinks and greys, as
/// .kq-btn:disabled does.
final class AirButton: UIControl {
    enum Style {
        case normal
        case ink
        case danger
    }

    private let style: Style
    private let iconView = UIImageView()
    private let titleLabel = UILabel()
    private let busyDot = UIView()
    private var restingTitle: String
    /// Keeps the busy words clear of the dot; on only while busy, so resting words stay centred.
    private var busyRoom: Constraint?

    /// While true the button says what is happening next to a still dot and takes no taps (busy).
    private(set) var isBusy = false

    init(style: Style, title: String, icon: String? = nil) {
        self.style = style
        self.restingTitle = title
        super.init(frame: .zero)

        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityLabel = title

        layer.cornerRadius = KQRadius.control
        layer.borderWidth = KQStroke.hairline

        titleLabel.text = title
        titleLabel.font = KQFont.font(.label)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 1
        titleLabel.adjustsFontSizeToFitWidth = true
        titleLabel.minimumScaleFactor = 0.8

        iconView.image = icon.flatMap { AirIcon.image($0) }
        iconView.contentMode = .scaleAspectFit
        iconView.isHidden = icon == nil

        let stack = UIStackView(arrangedSubviews: [iconView, titleLabel])
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = KQSpace.x6
        stack.isUserInteractionEnabled = false
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.leading.greaterThanOrEqualToSuperview().offset(KQSpace.x16)
            $0.trailing.lessThanOrEqualToSuperview().offset(-KQSpace.x16)
            // Words taller than the 44 point target (the accessibility text sizes) make the button
            // taller instead of standing over its edges.
            $0.top.greaterThanOrEqualToSuperview().offset(KQSpace.x8).priority(999.0)
            $0.bottom.lessThanOrEqualToSuperview().offset(-KQSpace.x8).priority(999.0)
        }
        iconView.snp.makeConstraints {
            $0.size.equalTo(KQSize.icon)
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
        }
        applyTitleLines()

        busyDot.isUserInteractionEnabled = false
        busyDot.isHidden = true
        busyDot.layer.cornerRadius = KQSize.dot / 2
        addSubview(busyDot)
        busyDot.snp.makeConstraints {
            $0.centerY.equalToSuperview()
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
            $0.size.equalTo(KQSize.dot)
        }
        stack.snp.makeConstraints {
            busyRoom = $0.trailing.lessThanOrEqualTo(busyDot.snp.leading).offset(-KQSpace.x8).constraint
        }
        busyRoom?.deactivate()

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

    override var isEnabled: Bool {
        didSet {
            apply()
        }
    }

    /// For a reused button whose words change (a request card that turns into an invitation).
    func setTitle(_ title: String) {
        restingTitle = title
        guard !isBusy else {
            return
        }
        titleLabel.text = title
        accessibilityLabel = title
    }

    /// Busy (.kq-btn.is-busy): `words` say what is happening ("Creating the profile") and a still dot
    /// stands at the end; nothing moves without end. nil gives the button its own words back.
    func setBusy(_ words: String?) {
        isBusy = words != nil
        titleLabel.text = words ?? restingTitle
        accessibilityLabel = words ?? restingTitle
        busyDot.isHidden = !isBusy
        if isBusy {
            busyRoom?.activate()
        }
        else {
            busyRoom?.deactivate()
        }
        isUserInteractionEnabled = !isBusy
        apply()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            applyTitleLines()
        }
        apply()
    }

    /// One line, a little smaller when it is long; at the accessibility text sizes the words wrap
    /// whole onto more lines instead, so none is cut.
    private func applyTitleLines() {
        let wraps = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        titleLabel.numberOfLines = wraps ? 0 : 1
        titleLabel.lineBreakMode = wraps ? .byWordWrapping : .byTruncatingTail
    }

    private func apply() {
        // The traits were set by hand, so UIKit no longer adds "dimmed" for a disabled control itself.
        accessibilityTraits = isEnabled ? UIAccessibilityTraitButton : UIAccessibilityTraitButton | UIAccessibilityTraitNotEnabled
        guard isEnabled else {
            backgroundColor = KQColor.sunken
            layer.borderColor = airCGColor(KQColor.line)
            titleLabel.textColor = KQColor.ink3
            iconView.tintColor = KQColor.ink3
            return
        }
        switch style {
            case .normal, .danger:
                let words = style == .danger ? KQColor.danger : KQColor.ink
                backgroundColor = isHighlighted ? KQColor.sunken : KQColor.raised
                layer.borderColor = airCGColor(KQColor.lineStrong)
                titleLabel.textColor = words
                iconView.tintColor = words
            case .ink:
                backgroundColor = isHighlighted ? KQColor.inkPressed : KQColor.ink
                layer.borderColor = airCGColor(KQColor.ink)
                titleLabel.textColor = KQColor.onInk
                iconView.tintColor = KQColor.onInk
        }
        busyDot.backgroundColor = titleLabel.textColor
    }
}

/// A bare icon button: a 20 point glyph in a 44 point target, grey until pressed.
final class AirIconButton: UIControl {
    private let iconView = UIImageView()
    private var iconSide: Constraint?

    init(icon: String, label: String) {
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityLabel = label

        layer.cornerRadius = KQRadius.control
        iconView.image = AirIcon.image(icon)
        iconView.contentMode = .scaleAspectFit
        iconView.tintColor = KQColor.ink2
        iconView.isUserInteractionEnabled = false
        addSubview(iconView)
        iconView.snp.makeConstraints {
            $0.center.equalToSuperview()
            iconSide = $0.size.equalTo(KQSize.icon).constraint
        }
        snp.makeConstraints {
            $0.size.equalTo(KQSize.targetIos)
        }
        // At the accessibility text sizes the glyph of 20 points stands beside text many times larger: a
        // long press shows it and its name large.
        AirLargeContent.show(on: self, title: label, image: iconView.image)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// The Large Content Viewer names the button as VoiceOver does, also after the name changes with a state.
    override var accessibilityLabel: String? {
        didSet {
            largeContentTitle = accessibilityLabel
        }
    }

    /// In the header of a conversation the button is a view of a bar item, and the bar shows the viewer.
    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        AirLargeContent.placeInteraction(on: self)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        AirLargeContent.placeInteraction(on: self)
    }

    /// The glyph grows with the text at the accessibility sizes, to 28 points in the 44 point target that
    /// stays; at the other sizes it keeps its 20, so nothing moves. For a button beside text of the body
    /// size: the paperclip in the field.
    var growsWithText = false {
        didSet {
            updateIconSide()
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            updateIconSide()
        }
    }

    private func updateIconSide() {
        let grown = growsWithText && traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        iconSide?.update(offset: grown ? AirLargeContent.grownGlyph : KQSize.icon)
    }

    /// The colour of the glyph at rest: grey, or the colour of a state (live media on, video coming in).
    var restingTint: UIColor = KQColor.ink2 {
        didSet {
            if !isHighlighted {
                iconView.tintColor = restingTint
            }
        }
    }

    /// For a button whose glyph follows its state.
    func setIcon(_ name: String) {
        iconView.image = AirIcon.image(name)
        largeContentImage = iconView.image
    }

    override var isHighlighted: Bool {
        didSet {
            backgroundColor = isHighlighted ? KQColor.selected : .clear
            iconView.tintColor = isHighlighted ? KQColor.ink : restingTint
        }
    }

    /// Disabled like .kq-send:disabled: the same icon at 40 percent, and VoiceOver says so.
    override var isEnabled: Bool {
        didSet {
            alpha = isEnabled ? 1 : 0.4
            accessibilityTraits = isEnabled ? UIAccessibilityTraitButton : UIAccessibilityTraitButton | UIAccessibilityTraitNotEnabled
        }
    }
}

// MARK: - Command line

/// "Find a chat or paste an ID": one field for search and for adding a person.
final class AirCommandField: UIView, UITextFieldDelegate {
    let textField = UITextField()

    var onTextChange: ((String) -> Void)?
    var onReturn: ((String) -> Void)?
    var onFocusChange: ((Bool) -> Void)?

    private let icon = UIImageView(image: AirIcon.image("search"))

    override init(frame: CGRect) {
        super.init(frame: frame)

        layer.cornerRadius = KQRadius.field
        layer.borderWidth = KQStroke.hairline

        icon.contentMode = .scaleAspectFit
        icon.tintColor = KQColor.ink3
        icon.isUserInteractionEnabled = false
        addSubview(icon)

        textField.font = KQFont.font(.body)
        textField.adjustsFontForContentSizeCategory = true
        textField.textColor = KQColor.ink
        textField.tintColor = KQColor.ink
        textField.borderStyle = .none
        textField.clearButtonMode = .whileEditing
        textField.returnKeyType = .search
        textField.autocapitalizationType = .none
        textField.autocorrectionType = .no
        textField.spellCheckingType = .no
        textField.smartDashesType = .no
        textField.smartQuotesType = .no
        textField.smartInsertDeleteType = .no
        textField.enablesReturnKeyAutomatically = true
        textField.delegate = self
        textField.accessibilityIdentifier = "air.command"
        textField.accessibilityLabel = String(localized: "air_command_placeholder")
        textField.attributedPlaceholder = NSAttributedString(
                string: String(localized: "air_command_placeholder"),
                attributes: [NSAttributedStringKey.foregroundColor: KQColor.ink3])
        textField.addTarget(self, action: #selector(textChanged), for: .editingChanged)
        addSubview(textField)

        icon.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x10)
            $0.centerY.equalToSuperview()
            $0.size.equalTo(KQSize.icon)
        }
        textField.snp.makeConstraints {
            $0.leading.equalTo(icon.snp.trailing).offset(KQSpace.x8)
            $0.trailing.equalToSuperview().offset(-KQSpace.x8)
            $0.top.bottom.equalToSuperview()
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
        }

        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// Setting it does not call `onTextChange`: the caller already knows what it wrote.
    var text: String {
        get {
            return textField.text ?? ""
        }
        set {
            textField.text = newValue
            reportedText = newValue
        }
    }

    /// The last text handed to `onTextChange`, so the clear button and `.editingChanged` never
    /// report the same value twice.
    private var reportedText = ""

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        apply()
    }

    @objc private func textChanged() {
        reportChange()
    }

    private func reportChange() {
        let current = text
        guard current != reportedText else {
            return
        }
        reportedText = current
        onTextChange?(current)
    }

    func textFieldShouldClear(_ textField: UITextField) -> Bool {
        // The field is emptied after this returns; report on the next turn, when it really is empty.
        DispatchQueue.main.async { [weak self] in
            self?.reportChange()
        }
        return true
    }

    func textFieldDidBeginEditing(_ textField: UITextField) {
        apply()
        onFocusChange?(true)
    }

    func textFieldDidEndEditing(_ textField: UITextField) {
        apply()
        onFocusChange?(false)
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        onReturn?(text)
        return false
    }

    private func apply() {
        let focused = textField.isFirstResponder
        backgroundColor = focused ? KQColor.raised : KQColor.sunken
        layer.borderColor = airCGColor(focused ? KQColor.ink : KQColor.line)
    }
}

// MARK: - The "you" block

/// Your tile, your name and the state of your node. Tapping the tile or the name opens "You"; tapping
/// the quiet line under the name opens the Network screen (E4), through a target of its own that
/// reaches a little below the block so that it is a full finger high.
final class AirSelfView: UIControl {
    /// The line under the name was tapped.
    var onNetworkTap: (() -> Void)?

    private let tile = AirTileView(side: KQSize.tilePhone)
    private let nameLabel = UILabel()
    private let netDot = UIView()
    private let netLabel = UILabel()
    private let netRow = UIStackView()
    private let textColumn = UIStackView()
    private let row = UIStackView()
    private let netControl = AirSelfLineControl()
    private lazy var youElement = AirSelfYouElement(owner: self)
    private var name = ""
    private var network: AirNetworkState = .connecting

    override init(frame: CGRect) {
        super.init(frame: frame)
        // Two elements for VoiceOver and the tests: "You" (the tile and the name) and the line.
        isAccessibilityElement = false
        youElement.accessibilityTraits = UIAccessibilityTraitButton
        youElement.accessibilityIdentifier = "air.you"
        youElement.accessibilityHint = String(localized: "air_you_hint")
        netControl.isAccessibilityElement = true
        netControl.accessibilityTraits = UIAccessibilityTraitButton
        netControl.accessibilityIdentifier = "air.you.network"
        netControl.accessibilityHint = String(localized: "air_net_hint")
        accessibilityElements = [youElement, netControl]

        nameLabel.font = KQFont.font(.label, weight: 600)
        nameLabel.adjustsFontForContentSizeCategory = true
        nameLabel.textColor = KQColor.ink

        netDot.layer.cornerRadius = KQSize.dot / 2
        netLabel.font = KQFont.font(.caption)
        netLabel.adjustsFontForContentSizeCategory = true
        // At the large text sizes the line wraps rather than losing how the node is connected.
        netLabel.numberOfLines = 0

        netRow.addArrangedSubview(netDot)
        netRow.addArrangedSubview(netLabel)
        netRow.axis = .horizontal
        // The dot sits on the first line's baseline, where the lower-case letters stand.
        netRow.alignment = .firstBaseline
        netRow.spacing = KQSpace.x6

        textColumn.addArrangedSubview(nameLabel)
        textColumn.addArrangedSubview(netRow)
        textColumn.axis = .vertical
        textColumn.alignment = .leading
        textColumn.spacing = KQSpace.x2

        row.addArrangedSubview(tile)
        row.addArrangedSubview(textColumn)
        row.isUserInteractionEnabled = false
        addSubview(row)
        arrangeForTextSize()

        row.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        tile.snp.makeConstraints {
            $0.size.equalTo(KQSize.tilePhone)
        }
        netDot.snp.makeConstraints {
            $0.size.equalTo(KQSize.dot)
        }

        addSubview(netControl)
        netControl.snp.makeConstraints {
            $0.leading.equalTo(textColumn)
            $0.trailing.equalToSuperview()
            $0.top.equalTo(nameLabel.snp.bottom)
            $0.bottom.greaterThanOrEqualToSuperview()
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
            $0.height.equalTo(0).priority(.low)
        }
        netControl.onHighlight = { [weak self] highlighted in
            self?.netRow.alpha = highlighted ? 0.6 : 1
        }
        netControl.addTarget(self, action: #selector(networkTapped), for: .touchUpInside)

        setNetwork(.connecting, animated: false)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let nameFrame = nameLabel.convert(nameLabel.bounds, to: self)
        youElement.accessibilityFrameInContainerSpace = tile.convert(tile.bounds, to: self).union(nameFrame)
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            arrangeForTextSize()
        }
    }

    /// Whether the name and the line stand under the tile, at the full width of the header: at the
    /// accessibility text sizes. Beside the tile and the two buttons they kept a third of the width,
    /// and "through a relay" broke inside its last word.
    static func stacks(_ traits: UITraitCollection) -> Bool {
        return traits.preferredContentSizeCategory.isAccessibilityCategory
    }

    private func arrangeForTextSize() {
        let stacked = AirSelfView.stacks(traitCollection)
        row.axis = stacked ? .vertical : .horizontal
        row.alignment = stacked ? .leading : .center
        row.spacing = stacked ? KQSpace.x6 : KQSpace.x10
        // A long name wraps at its words there rather than losing its end.
        nameLabel.numberOfLines = stacked ? 0 : 1
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        return super.point(inside: point, with: event) || netControl.frame.contains(point)
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard isUserInteractionEnabled, !isHidden, alpha > 0.01, self.point(inside: point, with: event) else {
            return nil
        }
        return netControl.frame.contains(point) ? netControl : self
    }

    @objc private func networkTapped() {
        onNetworkTap?()
    }

    /// Where Voice Control, Switch Control and the UI tests tap "You", in screen coordinates: the middle
    /// of the tile. The middle of the tile and the name together lies on the edge of the line's target.
    /// Converted through the window rather than by the accessibility conversion, which leaves the frame
    /// as it is while no assistive technology runs.
    fileprivate var youActivationPoint: CGPoint? {
        guard let window = tile.window else {
            return nil
        }
        let frame = window.convert(tile.convert(tile.bounds, to: nil), to: window.screen.coordinateSpace)
        return CGPoint(x: frame.midX, y: frame.midY)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            alpha = isHighlighted ? 0.6 : 1
        }
    }

    /// Your tile: "You" grows out of it (AirYouTransition).
    var tileView: UIView {
        return tile
    }

    func configure(name: String, photo: UIImage?) {
        self.name = name.isEmpty ? String(localized: "air_you") : name
        nameLabel.text = self.name
        tile.configure(style: .me(initials: AirInitials.from(self.name)), photo: photo)
        updateAccessibility()
    }

    func setNetwork(_ state: AirNetworkState, animated: Bool) {
        let changed = state != network
        network = state

        let update = {
            switch state {
                case .direct:
                    self.netLabel.text = String(localized: "air_net_direct")
                    self.netDot.backgroundColor = KQColor.live
                    self.netLabel.textColor = KQColor.ink2
                case .relay:
                    self.netLabel.text = String(localized: "air_net_relay")
                    self.netDot.backgroundColor = KQColor.live
                    self.netLabel.textColor = KQColor.ink2
                case .connecting:
                    self.netLabel.text = String(localized: "air_net_connecting")
                    self.netDot.backgroundColor = KQColor.ink3
                    self.netLabel.textColor = KQColor.ink2
                case .offline:
                    self.netLabel.text = String(localized: "air_net_offline")
                    self.netDot.backgroundColor = KQColor.danger
                    self.netLabel.textColor = KQColor.danger
            }
            self.updateAccessibility()
        }

        // The dot stands still; the words cross-fade.
        if animated && changed && window != nil {
            UIView.transition(with: netLabel, duration: KQMotion.duration(KQMotion.state), options: .transitionCrossDissolve, animations: update, completion: nil)
        }
        else {
            update()
        }
    }

    private func updateAccessibility() {
        youElement.accessibilityLabel = String(localized: "air_you") + ", " + name
        netControl.accessibilityLabel = netLabel.text
    }
}

/// The target of the line under the name; it dims the line while pressed.
private final class AirSelfLineControl: UIControl {
    var onHighlight: ((Bool) -> Void)?

    override var isHighlighted: Bool {
        didSet {
            onHighlight?(isHighlighted)
        }
    }
}

/// "You" for VoiceOver and the tests: the tile and the name, acting as the block's tap.
private final class AirSelfYouElement: UIAccessibilityElement {
    private weak var owner: AirSelfView?

    init(owner: AirSelfView) {
        self.owner = owner
        super.init(accessibilityContainer: owner)
    }

    override var accessibilityActivationPoint: CGPoint {
        get {
            return owner?.youActivationPoint ?? super.accessibilityActivationPoint
        }
        set {
            super.accessibilityActivationPoint = newValue
        }
    }

    override func accessibilityActivate() -> Bool {
        owner?.sendActions(for: .touchUpInside)
        return true
    }
}

// MARK: - Empty state

/// What the list says when it has nothing to show, with the one action that helps.
final class AirEmptyView: UIView {
    var onAction: (() -> Void)?

    private let titleLabel = UILabel()
    private let bodyLabel = UILabel()
    private let button: AirButton

    init(title: String, body: String, action: String) {
        button = AirButton(style: .normal, title: action)
        super.init(frame: .zero)

        titleLabel.text = title
        titleLabel.font = KQFont.font(.heading)
        titleLabel.adjustsFontForContentSizeCategory = true
        titleLabel.textColor = KQColor.ink
        titleLabel.textAlignment = .center
        titleLabel.numberOfLines = 0

        bodyLabel.text = body
        bodyLabel.font = KQFont.font(.caption)
        bodyLabel.adjustsFontForContentSizeCategory = true
        bodyLabel.textColor = KQColor.ink2
        bodyLabel.textAlignment = .center
        bodyLabel.numberOfLines = 0

        button.addTarget(self, action: #selector(actionPressed), for: .touchUpInside)
        button.accessibilityIdentifier = "air.empty.action"

        let stack = UIStackView(arrangedSubviews: [titleLabel, bodyLabel, button])
        stack.axis = .vertical
        stack.alignment = .center
        stack.spacing = KQSpace.x8
        stack.setCustomSpacing(KQSpace.x8 + KQSpace.x6, after: bodyLabel)
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x32)
            $0.bottom.equalToSuperview().offset(-KQSpace.x32)
            $0.centerX.equalToSuperview()
            $0.leading.greaterThanOrEqualToSuperview().offset(KQSpace.x20)
            $0.trailing.lessThanOrEqualToSuperview().offset(-KQSpace.x20)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    @objc private func actionPressed() {
        onAction?()
    }
}

// MARK: - "Create" sheet

/// The sheet behind "+": a new contact by ID, a new group, a new private group.
final class AirCreateSheetController: UIViewController {
    struct Action {
        let title: String
        let icon: String
        let identifier: String
        let handler: () -> Void
    }

    private let actions: [Action]
    private let stack = UIStackView()

    init(actions: [Action]) {
        self.actions = actions
        super.init(nibName: nil, bundle: nil)

        modalPresentationStyle = .pageSheet
        if let sheet = sheetPresentationController {
            sheet.prefersGrabberVisible = true
            sheet.preferredCornerRadius = KQRadius.sheet
            if #available(iOS 16.0, *) {
                sheet.detents = [.custom { [weak self] _ in
                    return self?.fittingHeight()
                }]
            }
            else {
                sheet.detents = [.medium()]
            }
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = KQColor.raised

        let title = UILabel()
        title.text = String(localized: "air_create")
        title.font = KQFont.font(.heading)
        title.adjustsFontForContentSizeCategory = true
        title.textColor = KQColor.ink
        title.accessibilityTraits = UIAccessibilityTraitHeader

        stack.axis = .vertical
        stack.spacing = KQSpace.x12
        stack.addArrangedSubview(title)

        for (index, action) in actions.enumerated() {
            let button = AirButton(style: .normal, title: action.title, icon: action.icon)
            button.tag = index
            button.accessibilityIdentifier = action.identifier
            button.addTarget(self, action: #selector(actionPressed(_:)), for: .touchUpInside)
            stack.addArrangedSubview(button)
        }

        view.addSubview(stack)
        stack.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x24)
            $0.leading.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.equalTo(view.safeAreaLayoutGuide).offset(-KQSpace.x16)
        }
    }

    @objc private func actionPressed(_ sender: AirButton) {
        guard sender.tag >= 0, sender.tag < actions.count else {
            return
        }
        let handler = actions[sender.tag].handler
        dismiss(animated: true, completion: handler)
    }

    private func fittingHeight() -> CGFloat {
        loadViewIfNeeded()
        let width = view.bounds.width > 0 ? view.bounds.width : UIScreen.main.bounds.width
        let size = stack.systemLayoutSizeFitting(
                CGSize(width: width - 2 * KQSpace.x16, height: UILayoutFittingCompressedSize.height),
                withHorizontalFittingPriority: .required,
                verticalFittingPriority: .fittingSizeLevel)
        return KQSpace.x24 + size.height + KQSpace.x24
    }
}

// MARK: - Header

/// The top of the Air screen: you and your node, settings and "+", the command line and the pills.
/// While the command line has focus the "you" row folds away and "Cancel" appears next to the field.
final class AirHeaderView: UIView {
    let selfView = AirSelfView()
    let settingsButton = AirIconButton(icon: "settings", label: String(localized: "settings_title"))
    let addButton = AirIconButton(icon: "plus", label: String(localized: "air_create"))
    let commandField = AirCommandField()
    let cancelButton = UIButton(type: .system)
    private(set) var pills: [AirFilter: AirPillButton] = [:]

    private let topRow = UIView()
    private let pillsScroll = UIScrollView()
    private let hairline = UIView()
    private var topRowCollapse: Constraint?
    private var cancelVisible: Constraint?
    private var cancelHidden: Constraint?
    /// Beside the tile and the name, or, at the accessibility text sizes, beside the tile alone while
    /// the name and the line take the full width under it (AirSelfView.stacks).
    private var besideName: [Constraint] = []
    private var besideTile: [Constraint] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.surface

        settingsButton.accessibilityIdentifier = "air.settings"
        addButton.accessibilityIdentifier = "air.create"

        topRow.clipsToBounds = true
        addSubview(topRow)
        topRow.addSubview(selfView)
        topRow.addSubview(settingsButton)
        topRow.addSubview(addButton)

        addSubview(commandField)

        cancelButton.setTitle(String(localized: "alert_cancel"), for: .normal)
        cancelButton.setTitleColor(KQColor.ink, for: .normal)
        cancelButton.titleLabel?.font = KQFont.font(.label)
        cancelButton.titleLabel?.adjustsFontForContentSizeCategory = true
        cancelButton.accessibilityIdentifier = "air.command.cancel"
        cancelButton.alpha = 0
        cancelButton.setContentHuggingPriority(.required, for: .horizontal)
        cancelButton.setContentCompressionResistancePriority(.required, for: .horizontal)
        addSubview(cancelButton)

        pillsScroll.showsHorizontalScrollIndicator = false
        pillsScroll.alwaysBounceHorizontal = false
        addSubview(pillsScroll)

        let pillStack = UIStackView()
        pillStack.axis = .horizontal
        pillStack.alignment = .center
        pillStack.spacing = KQSpace.x6
        pillsScroll.addSubview(pillStack)
        for filter in AirFilter.pills {
            let pill = AirPillButton(filter: filter, title: AirHeaderView.title(for: filter))
            pills[filter] = pill
            pillStack.addArrangedSubview(pill)
        }
        // The stack is never narrower than the strip, and the spacer takes what is left, so the pills
        // start at the leading edge in Arabic as well: a scroll view does not mirror short content.
        let spacer = UIView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        pillStack.addArrangedSubview(spacer)

        hairline.backgroundColor = KQColor.line
        addSubview(hairline)

        topRow.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x4)
            $0.leading.trailing.equalToSuperview()
            topRowCollapse = $0.height.equalTo(0).constraint
        }
        topRowCollapse?.deactivate()
        selfView.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.top.bottom.equalToSuperview().priority(.high)
            $0.centerY.equalToSuperview()
            besideName.append($0.trailing.lessThanOrEqualTo(settingsButton.snp.leading).offset(-KQSpace.x8).constraint)
            besideTile.append($0.trailing.lessThanOrEqualToSuperview().offset(-KQSpace.x16).constraint)
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
        }
        addButton.snp.makeConstraints {
            $0.trailing.equalToSuperview().offset(-KQSpace.x8)
            besideName.append($0.centerY.equalTo(selfView).constraint)
            besideTile.append($0.centerY.equalTo(selfView.tileView).constraint)
        }
        settingsButton.snp.makeConstraints {
            $0.trailing.equalTo(addButton.snp.leading)
            besideName.append($0.centerY.equalTo(selfView).constraint)
            besideTile.append($0.centerY.equalTo(selfView.tileView).constraint)
        }
        arrangeForTextSize()

        commandField.snp.makeConstraints {
            $0.top.equalTo(topRow.snp.bottom).offset(KQSpace.x10)
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            cancelHidden = $0.trailing.equalToSuperview().offset(-KQSpace.x16).constraint
            cancelVisible = $0.trailing.equalTo(cancelButton.snp.leading).offset(-KQSpace.x8).constraint
        }
        cancelVisible?.deactivate()
        cancelButton.snp.makeConstraints {
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
            $0.centerY.equalTo(commandField)
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
        }
        cancelButton.accessibilityElementsHidden = true

        pillsScroll.snp.makeConstraints {
            $0.top.equalTo(commandField.snp.bottom).offset(KQSpace.x4)
            $0.leading.trailing.equalToSuperview()
            // The reach of a thumb, and taller when the pills grow with the largest text sizes.
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
            $0.height.equalTo(KQSize.targetIos).priority(.low)
        }
        pillStack.snp.makeConstraints {
            $0.top.bottom.equalToSuperview()
            $0.height.equalToSuperview()
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
            $0.width.greaterThanOrEqualToSuperview().offset(-2 * KQSpace.x16)
        }

        hairline.snp.makeConstraints {
            $0.top.equalTo(pillsScroll.snp.bottom).offset(KQSpace.x6)
            $0.leading.trailing.bottom.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    static func title(for filter: AirFilter) -> String {
        switch filter {
            case .all:
                return String(localized: "air_filter_all")
            case .requests:
                return String(localized: "air_filter_requests")
            case .transit:
                return String(localized: "air_filter_transit")
            case .favorites:
                return String(localized: "chat_filter_favorites")
        }
    }

    func setFilter(_ filter: AirFilter) {
        for (key, pill) in pills {
            pill.isSelected = key == filter
        }
        if let pill = pills[filter] {
            let frame = pill.convert(pill.bounds, to: pillsScroll)
            pillsScroll.scrollRectToVisible(frame.insetBy(dx: -KQSpace.x16, dy: 0), animated: window != nil)
        }
    }

    func setCounts(_ counts: AirCounts, animated: Bool) {
        pills[.requests]?.setCount(counts.requests, animated: animated)
        pills[.transit]?.setCount(counts.transit, animated: animated)
        pills[.favorites]?.setCount(0, animated: false)
    }

    /// Folds the "you" row away while the command line is in use, and brings "Cancel" in.
    func setSearching(_ searching: Bool, animated: Bool) {
        if searching {
            topRowCollapse?.activate()
            cancelHidden?.deactivate()
            cancelVisible?.activate()
        }
        else {
            topRowCollapse?.deactivate()
            cancelVisible?.deactivate()
            cancelHidden?.activate()
        }

        cancelButton.accessibilityElementsHidden = !searching
        topRow.accessibilityElementsHidden = searching
        let changes = {
            self.topRow.alpha = searching ? 0 : 1
            self.cancelButton.alpha = searching ? 1 : 0
            self.superview?.layoutIfNeeded()
        }

        guard animated, window != nil else {
            changes()
            return
        }
        let animator = UIViewPropertyAnimator(duration: KQMotion.duration(KQMotion.layout), timingParameters: KQMotion.easeOut)
        animator.addAnimations(changes)
        animator.startAnimation()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        cancelButton.setTitleColor(KQColor.ink, for: .normal)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            arrangeForTextSize()
        }
    }

    private func arrangeForTextSize() {
        let stacked = AirSelfView.stacks(traitCollection)
        // What goes is let go first, so the two placements of the buttons never hold at once.
        (stacked ? besideName : besideTile).forEach { $0.deactivate() }
        (stacked ? besideTile : besideName).forEach { $0.activate() }
    }
}
